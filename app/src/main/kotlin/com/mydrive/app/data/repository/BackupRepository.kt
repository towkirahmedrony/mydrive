package com.mydrive.app.data.repository

import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.local.CloudBackedUpIdentity
import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.local.UploadQueueEntity
import com.mydrive.app.data.media.CloudBackupCandidate
import com.mydrive.app.data.media.CloudBackupLookup
import com.mydrive.app.data.media.MediaSourceKind
import com.mydrive.app.data.media.MediaUriProbe
import com.mydrive.app.data.media.classifyMediaSource
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.isRetryable
import com.mydrive.app.data.remote.dto.MediaAssetRow
import com.mydrive.app.data.remote.CloudinaryAuthResult
import com.mydrive.app.data.remote.CloudinaryUploadResult
import com.mydrive.app.data.remote.CloudinaryUploadPlan
import com.mydrive.app.data.remote.CloudinaryUploadService
import com.mydrive.app.data.remote.FinalizeResult
import com.mydrive.app.data.remote.MediaFinalizeRequest
import com.mydrive.app.data.remote.MediaFinalizeService
import com.mydrive.app.data.remote.NetworkMonitor
import com.mydrive.app.data.remote.UploadLog
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.OperationTrace
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Backup gate — checks only network + Cloudinary readiness.
 * Telegram configuration is preserved for future server-side replication
 * but does NOT gate the primary Cloudinary backup path.
 */
sealed interface BackupGate {
    data object Ready : BackupGate
    data object Offline : BackupGate

    fun message(): String = when (this) {
        Ready -> ""
        Offline -> "You're offline. Connect to the internet to back up photos."
    }
}

class BackupRepository(
    private val syncRepository: SyncRepository,
    private val cloudinaryService: CloudinaryUploadService,
    private val mediaFinalizeService: MediaFinalizeService,
    private val network: NetworkMonitor,
    private val sessionProvider: AuthenticatedSessionProvider,
    private val deviceIdProvider: suspend () -> String?,
    /**
     * The locally-sourceable representation of a media id, for upload only.
     *
     * Deliberately not "whatever the gallery shows for this id": the composed
     * library also carries cloud-only representations whose `uri` is a Cloudinary
     * delivery URL, and those must never become an upload source.
     */
    private val localUploadSource: (String) -> MediaItem?,
    private val queueLookup: suspend (String) -> UploadQueueEntity?,
    private val queuedMediaResolver: suspend (UploadQueueEntity) -> MediaItem?,
    private val uriProbe: (String) -> MediaUriProbe,
    /**
     * The authoritative answer to "does the cloud already hold this media?".
     *
     * The upload gate reads it before every Cloudinary upload, so an item is never
     * sent again merely because the local view of the cloud catalog was incomplete
     * (a single catalog page used to be the whole of that view).
     */
    private val verifyCloudBackedUp: suspend (List<CloudBackupCandidate>) -> CloudBackupLookup,
    private val scheduleUploadWork: () -> Unit
) {

    private val workerMutex = Mutex()

    /**
     * Check if the Cloudinary backup pipeline is ready.
     * Only requires network connectivity.
     * Auth check happens at upload-auth request time.
     */
    fun gate(): BackupGate {
        if (!network.isOnline()) return BackupGate.Offline
        return BackupGate.Ready
    }

    fun startBackup(ids: Collection<String>, resumeIfPaused: Boolean = true) {
        if (gate() != BackupGate.Ready) {
            DeveloperLogger.warn(
                category = LogCategory.SYSTEM,
                event = "BACKUP_BLOCKED",
                message = "Backup start blocked: ${gate().message()}"
            )
            return
        }
        val ownerUserId = sessionProvider.currentUserIdOrNull() ?: run {
            DeveloperLogger.warn(
                category = LogCategory.AUTH,
                event = "BACKUP_BLOCKED",
                message = "Backup start blocked: no authenticated user"
            )
            return
        }
        ids.forEach { OperationTrace.idFor(it) }
        if (resumeIfPaused) {
            syncRepository.setPaused(false)
        }
        syncRepository.enqueue(ids, ownerUserId)
        if (!syncRepository.paused.value) {
            scheduleUploadWork()
        }
    }

    fun retry(id: String) = retryAll(listOf(id))

    fun retryAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        if (gate() != BackupGate.Ready) return
        val ownerUserId = sessionProvider.currentUserIdOrNull() ?: return
        val owned = ids.filter { id ->
            val record = syncRepository.records.value[id] ?: return@filter false
            syncRepository.belongsTo(record, ownerUserId)
        }
        if (owned.isEmpty()) return
        syncRepository.setPaused(false)
        syncRepository.retryAll(owned)
        scheduleUploadWork()
    }

    fun pause() {
        syncRepository.setPaused(true)
    }

    fun resume() {
        if (gate() != BackupGate.Ready) return
        syncRepository.setPaused(false)
        scheduleUploadWork()
    }

    fun cancel(id: String) {
        syncRepository.cancel(id)
    }

    suspend fun processPendingQueue(): QueueDrain = workerMutex.withLock { processQueue() }

    private suspend fun processQueue(): QueueDrain {
        while (true) {
            if (syncRepository.paused.value) return QueueDrain.Idle
            if (gate() != BackupGate.Ready) {
                DeveloperLogger.warn(
                    category = LogCategory.NETWORK,
                    event = "QUEUE_HALTED",
                    message = "Upload queue halted: network unavailable"
                )
                return QueueDrain.NetworkUnavailable
            }
            val prepared = sessionProvider.prepare()
            val userId = when (prepared) {
                is PreparedAuth.Available -> {
                    DeveloperLogger.info(
                        category = LogCategory.AUTH,
                        event = "SESSION_CHECKED",
                        message = "Auth session available",
                        metadata = mapOf("error_source" to "local_session")
                    )
                    prepared.userId
                }
                is PreparedAuth.NetworkError -> {
                    DeveloperLogger.warn(
                        category = LogCategory.AUTH,
                        event = "QUEUE_HALTED",
                        message = "Upload queue halted: session network error",
                        metadata = mapOf("error_source" to "supabase_client")
                    )
                    return QueueDrain.NetworkUnavailable
                }
                is PreparedAuth.SignedOut -> {
                    DeveloperLogger.warn(
                        category = LogCategory.AUTH,
                        event = "QUEUE_HALTED",
                        message = "Upload queue halted: signed out",
                        metadata = mapOf("error_source" to "local_session")
                    )
                    return QueueDrain.AwaitingSession
                }
            }
            if (com.mydrive.app.data.session.AccountSession.userId != userId) {
                return QueueDrain.AwaitingSession
            }
            syncRepository.bindOwner(userId)
            val next = nextWaiting(userId) ?: return QueueDrain.Idle
            when (processOne(next, userId)) {
                ItemOutcome.HaltQueue -> {
                    return when (sessionProvider.prepare()) {
                        is PreparedAuth.Available -> QueueDrain.Idle
                        is PreparedAuth.NetworkError -> QueueDrain.NetworkUnavailable
                        is PreparedAuth.SignedOut -> QueueDrain.AwaitingSession
                    }
                }
                ItemOutcome.Continue, ItemOutcome.Failed -> Unit
            }
        }
    }

    private fun nextWaiting(userId: String): String? = syncRepository.records.value
        .asSequence()
        .filter { syncRepository.belongsTo(it.value, userId) }
        .filter { it.value.state.toBackupState().resumeLocally() == BackupState.WAITING }
        .minByOrNull { it.value.queuedAtMillis }
        ?.key

    private suspend fun processOne(id: String, userId: String): ItemOutcome {
        val operationId = OperationTrace.idFor(id)
        UploadLog.itemClaimed(id)
        if (com.mydrive.app.data.session.AccountSession.userId != userId) return ItemOutcome.HaltQueue
        val record = syncRepository.records.value[id] ?: return ItemOutcome.Continue
        if (!syncRepository.belongsTo(record, userId)) return ItemOutcome.Continue
        if (record.state.toBackupState().resumeLocally() != BackupState.WAITING) return ItemOutcome.Continue

        val queueEntity = queueLookup(id)
        // A persisted row is only probed when its own URI is a local source. The
        // probe opens the URI through ContentResolver, so probing a stored
        // Cloudinary URL was itself one of the ways the "No content provider"
        // failure was produced — and it must never be produced by the diagnostic.
        val storedSourceKind = classifyMediaSource(queueEntity?.contentUri, originLocal = true)
        val storedProbe = if (queueEntity != null && storedSourceKind.isLocalUploadSource) {
            uriProbe(queueEntity.contentUri)
        } else {
            null
        }
        if (queueEntity != null) {
            DeveloperLogger.info(
                category = LogCategory.MEDIASTORE,
                event = "MEDIA_URI_PROBE",
                message = if (storedSourceKind.isLocalUploadSource) {
                    "Checked persisted media URI before upload"
                } else {
                    "Persisted queue URI is not a local upload source; not probed"
                },
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                metadata = uriMetadata(queueEntity, storedProbe, storedSourceKind, record)
            )
        }
        // The gallery StateFlow can be stale or temporarily partial. Room's
        // persisted URI is the queue source of truth; re-scan MediaStore only
        // when the in-memory lookup cannot find the item. Either way the result is
        // accepted only when it is genuinely locally sourceable, so a cloud-only
        // tile whose `uri` is the Cloudinary delivery URL can never be picked up as
        // the upload body.
        val alreadyUploaded = record.cloudinaryAssetId != null && record.cloudinaryPublicId != null
        val item = (localUploadSource(id) ?: queueEntity?.let { queuedMediaResolver(it) })
            ?.takeIf { candidate -> classifyMediaSource(candidate.uri, candidate.originLocal).isLocalUploadSource }
        if (item == null && alreadyUploaded) {
            DeveloperLogger.info(
                category = LogCategory.MEDIASTORE,
                event = "MEDIASTORE_ITEM_NOT_FOUND",
                message = "Local MediaStore item missing after Cloudinary upload; finalizing cloud asset",
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId
            )
            val synthetic = MediaItem(
                id = id,
                filename = queueEntity?.fileName.orEmpty().ifBlank { "media" },
                type = if (queueEntity?.mimeType?.startsWith("video/") == true) MediaType.VIDEO else MediaType.PHOTO,
                fileSizeBytes = queueEntity?.fileSize ?: 0L,
                capturedAtMillis = queueEntity?.createdAt ?: 0L,
                device = "My Drive",
                resolution = "Unknown",
                thumbnailSeed = id.hashCode(),
                mediaStoreId = queueEntity?.localMediaId ?: 0L,
                uri = queueEntity?.contentUri.orEmpty(),
                mimeType = queueEntity?.mimeType.orEmpty(),
                originLocal = false
            )
            return finalizeOnSupabase(id, synthetic)
        }
        if (item != null) {
            DeveloperLogger.info(
                category = LogCategory.MEDIASTORE,
                event = "MEDIA_DETECTED",
                message = "Media available for upload",
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                metadata = mapOf(
                    "file_name" to item.filename,
                    "mime_type" to item.mimeType,
                    "file_size" to item.fileSizeBytes.toString()
                )
            )
        }
        if (item == null) {
            // No local upload source. Whether that means "nothing to do" or "the
            // media is genuinely gone" depends on the cloud, never on the local
            // view alone — and a Cloudinary URL is never substituted for the
            // missing local URI.
            return reconcileWithoutLocalSource(
                id = id,
                operationId = operationId,
                record = record,
                queueEntity = queueEntity,
                storedKind = storedSourceKind,
                storedProbe = storedProbe
            )
        }

        val itemKind = classifyMediaSource(item.uri, item.originLocal)
        if (queueEntity != null && queueEntity.contentUri != item.uri) {
            syncRepository.updateQueueMedia(id, item)
            DeveloperLogger.info(
                category = LogCategory.ROOM,
                event = "QUEUE_MEDIA_URI_REFRESHED",
                message = "Updated queued media metadata after MediaStore re-resolution",
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                metadata = uriMetadata(item, uriProbe(item.uri), itemKind, record)
            )
        }

        val uploadProbe = uriProbe(item.uri)
        if (!uploadProbe.inputStreamOpened || !uploadProbe.fileDescriptorOpened) {
            DeveloperLogger.error(
                category = LogCategory.MEDIASTORE,
                event = "MEDIA_OPEN_FAILED",
                message = "Media item was found but its local upload URI could not be opened",
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                throwable = uploadProbe.errorType?.let { IllegalStateException(uploadProbe.errorMessage) },
                metadata = uriMetadata(item, uploadProbe, itemKind, record)
            )
            UploadLog.uploadFailed(id, "media_open_failed")
            syncRepository.updateState(id, BackupState.FAILED, "This media could not be opened for upload.")
            return ItemOutcome.Continue
        }

        val uploaded = record.cloudinaryAssetId != null && record.cloudinaryPublicId != null
        if (uploaded) return finalizeOnSupabase(id, item)

        // Never hand bytes to Cloudinary before the cloud has been asked whether it
        // already holds this media. The local queue is install-local and the loaded
        // catalog is one page of many, so neither can prove the absence of a cloud
        // copy — only the server can. Until it answers, the item waits: an upload
        // that cannot be justified is exactly the duplicate this fix removes.
        val candidate = cloudCandidate(item, record)
        when (val lookup = verifyCloudBackedUp(listOf(candidate))) {
            CloudBackupLookup.Unavailable -> return deferUntilCloudVerified(
                id = id,
                operationId = operationId,
                item = item,
                sourceKind = itemKind
            )
            is CloudBackupLookup.Checked -> {
                lookup.rowFor(candidate)?.let { row ->
                    adoptCloudBackedUp(
                        id = id,
                        item = item,
                        row = row,
                        lookup = lookup,
                        candidate = candidate,
                        operationId = operationId,
                        sourceKind = itemKind,
                        sourceAvailable = true
                    )
                    return ItemOutcome.Continue
                }
            }
        }

        return when (val upload = uploadToCloudinary(id, item)) {
            ItemOutcome.Continue -> finalizeOnSupabase(id, item)
            else -> upload
        }
    }

    /**
     * Handles a queued item that has no locally-sourceable media behind it.
     *
     * Three genuinely different situations, which the previous single "failed"
     * branch collapsed into one:
     *
     * - the cloud already holds the media — a Cloudinary URL stored where a local
     *   URI belongs, or a local copy removed after a successful backup. This is the
     *   valid "cloud READY + local missing" state: reconcile the metadata, never
     *   upload, and never open the cloud URL;
     * - the cloud question could not be answered — wait, do not guess;
     * - the cloud has nothing and there is no local source — the item is genuinely
     *   unavailable, so it is marked retryable instead of being fed to
     *   `ContentResolver` (the cause of the reported `No content provider`).
     */
    private suspend fun reconcileWithoutLocalSource(
        id: String,
        operationId: String,
        record: SyncRecord,
        queueEntity: UploadQueueEntity?,
        storedKind: MediaSourceKind,
        storedProbe: MediaUriProbe?
    ): ItemOutcome {
        val candidate = cloudCandidate(id, queueEntity, record)
        when (val lookup = verifyCloudBackedUp(listOf(candidate))) {
            CloudBackupLookup.Unavailable -> return deferUntilCloudVerified(
                id = id,
                operationId = operationId,
                item = null,
                sourceKind = storedKind
            )
            is CloudBackupLookup.Checked -> {
                lookup.rowFor(candidate)?.let { row ->
                    adoptCloudBackedUp(
                        id = id,
                        item = null,
                        row = row,
                        lookup = lookup,
                        candidate = candidate,
                        operationId = operationId,
                        sourceKind = storedKind,
                        sourceAvailable = false
                    )
                    return ItemOutcome.Continue
                }
            }
        }

        val event = when {
            // The specific failure this fix removes: a Cloudinary URL stored where a
            // local upload source belongs. It is no longer fed to ContentResolver, and
            // it is now reported for what it is.
            storedKind == MediaSourceKind.REMOTE -> "MEDIA_SOURCE_NOT_LOCAL"
            queueEntity == null || storedProbe?.queryFound != true -> "MEDIASTORE_ITEM_NOT_FOUND"
            else -> "MEDIA_OPEN_FAILED"
        }
        DeveloperLogger.error(
            category = LogCategory.MEDIASTORE,
            event = event,
            message = if (storedKind == MediaSourceKind.REMOTE) {
                "Queued item carries a cloud URL where a local upload source is required"
            } else {
                "No local upload source is available for queued media"
            },
            operationId = operationId,
            localMediaId = id,
            clientUploadId = record.clientUploadId,
            throwable = storedProbe?.errorType?.let { IllegalStateException(storedProbe.errorMessage) },
            metadata = uriMetadata(queueEntity, storedProbe, storedKind, record) +
                mapOf(
                    "source_available" to "false",
                    "cloud_state" to "MISSING",
                    "reason" to "local_source_unavailable"
                )
        )
        UploadLog.uploadFailed(id, "media_unavailable")
        syncRepository.updateState(
            id = id,
            state = BackupState.FAILED,
            errorMessage = "This photo is no longer available on this device."
        )
        return ItemOutcome.Continue
    }

    /**
     * Stops the drain because the cloud could not be asked whether it already holds
     * the item.
     *
     * The item stays WAITING — not FAILED, because nothing about it is known to be
     * wrong — and the queue halts so a whole backup run is not attempted against an
     * unanswered catalog. WorkManager reschedules the work.
     */
    private suspend fun deferUntilCloudVerified(
        id: String,
        operationId: String,
        item: MediaItem?,
        sourceKind: MediaSourceKind
    ): ItemOutcome {
        DeveloperLogger.warn(
            category = LogCategory.DATABASE,
            event = "CLOUD_BACKUP_VERIFY_UNAVAILABLE",
            message = "Upload deferred: the cloud could not confirm whether this media is already backed up",
            operationId = operationId,
            localMediaId = id,
            clientUploadId = syncRepository.records.value[id]?.clientUploadId,
            metadata = mapOf(
                "source_type" to sourceKind.label,
                "source_available" to (item != null).toString(),
                "cloud_state" to "UNKNOWN",
                "reason" to "verify_unavailable",
                "retryable" to "true"
            )
        )
        syncRepository.updateState(
            id = id,
            state = BackupState.WAITING,
            errorMessage = "Waiting to confirm this item's backup state. Will retry."
        )
        return ItemOutcome.HaltQueue
    }

    /**
     * Records an authoritative cloud row for media that needs no upload at all.
     */
    private suspend fun adoptCloudBackedUp(
        id: String,
        item: MediaItem?,
        row: MediaAssetRow,
        lookup: CloudBackupLookup.Checked,
        candidate: CloudBackupCandidate,
        operationId: String,
        sourceKind: MediaSourceKind,
        sourceAvailable: Boolean
    ) {
        syncRepository.adoptCloudBackedUp(
            mapOf(
                id to CloudBackedUpIdentity(
                    remoteMediaId = row.id,
                    clientUploadId = row.clientUploadId
                )
            )
        )
        DeveloperLogger.info(
            category = LogCategory.REPLICATION,
            event = "MEDIA_ALREADY_IN_CLOUD",
            message = "Media is already in the cloud; reconciled without uploading",
            operationId = operationId,
            localMediaId = id,
            clientUploadId = row.clientUploadId ?: candidate.clientUploadId,
            metadata = mapOf(
                "source_type" to sourceKind.label,
                "source_available" to sourceAvailable.toString(),
                "cloud_state" to cloudStateLabel(lookup, candidate),
                "remote_media_id" to row.id,
                "matched_by" to if (candidate.clientUploadId?.let { lookup.rowsByClientUploadId.containsKey(it) } == true) {
                    "client_upload_id"
                } else {
                    "local_media_id"
                },
                "media_store_id" to candidate.localMediaId.toString(),
                "file_name" to item?.filename
            )
        )
        UploadLog.localUpdated(id, BackupState.COMPLETED.name)
    }

    private fun cloudCandidate(item: MediaItem, record: SyncRecord): CloudBackupCandidate =
        CloudBackupCandidate(
            localMediaId = item.mediaStoreId,
            clientUploadId = record.clientUploadId,
            fileSize = item.fileSizeBytes
        )

    private fun cloudCandidate(
        id: String,
        queueEntity: UploadQueueEntity?,
        record: SyncRecord
    ): CloudBackupCandidate = CloudBackupCandidate(
        localMediaId = queueEntity?.localMediaId ?: 0L,
        clientUploadId = record.clientUploadId,
        fileSize = queueEntity?.fileSize ?: 0L
    )

    /**
     * The cloud state as the Developer Console should show it:
     * `READY`, `CHANGED` (a finished cloud row exists for this identity but its
     * size no longer matches the local file), `PENDING` (a row exists that never
     * finished), `MISSING`, or `UNKNOWN` when nothing was proven.
     */
    private fun cloudStateLabel(
        lookup: CloudBackupLookup,
        candidate: CloudBackupCandidate
    ): String = when (lookup) {
        CloudBackupLookup.Unavailable -> "UNKNOWN"
        is CloudBackupLookup.Checked -> {
            val status = lookup.statusOf(candidate)
            when {
                lookup.rowFor(candidate) != null -> "READY"
                status == null -> "MISSING"
                status == "READY" -> "CHANGED"
                else -> "PENDING"
            }
        }
    }

    /**
     * Step 1: upload the photo to Cloudinary. Returns true on success.
     */
    private suspend fun uploadToCloudinary(id: String, item: MediaItem): ItemOutcome {
        val resourceType = if (item.type == MediaType.VIDEO) "video" else "image"
        val operationId = OperationTrace.idFor(id)
        val clientUploadId = syncRepository.records.value[id]?.clientUploadId
        DeveloperLogger.info(
            category = LogCategory.CLOUDINARY_AUTH,
            event = "UPLOAD_PIPELINE_START",
            message = "Upload pipeline started",
            operationId = operationId,
            localMediaId = id,
            clientUploadId = clientUploadId,
            metadata = mapOf(
                "file_name" to item.filename,
                "resource_type" to resourceType,
                "file_size" to item.fileSizeBytes.toString()
            )
        )
        syncRepository.updateState(id = id, state = BackupState.REQUESTING_CLOUDINARY_AUTH)

        val authResult = cloudinaryService.requestUploadAuth(
            resourceType = resourceType,
            operationId = operationId,
            localMediaId = id,
            clientUploadId = clientUploadId
        )

        when (authResult) {
            is CloudinaryAuthResult.Unauthorized -> {
                UploadLog.uploadFailed(id, "authorization_unauthorized")
                return deferForSession(id)
            }
            is CloudinaryAuthResult.Misconfigured -> {
                UploadLog.uploadFailed(id, "authorization_misconfigured")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary is not configured. Please check server settings."
                )
                return ItemOutcome.Failed
            }
            is CloudinaryAuthResult.NetworkUnavailable -> {
                UploadLog.uploadFailed(id, "authorization_network_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "No internet connection. This photo will stay in the queue."
                )
                return ItemOutcome.Failed
            }
            is CloudinaryAuthResult.Timeout -> {
                UploadLog.uploadFailed(id, "authorization_timeout")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Request timed out. Try again."
                )
                return ItemOutcome.Failed
            }
            is CloudinaryAuthResult.Error -> {
                UploadLog.uploadFailed(id, "authorization_error")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = authResult.message
                )
                return ItemOutcome.Failed
            }
            is CloudinaryAuthResult.Success -> {
                UploadLog.authObtained(id, resourceType)
            }
        }

        syncRepository.updateState(id = id, state = BackupState.UPLOADING_TO_CLOUDINARY)
        UploadLog.uploadStarted(id, resourceType)

        val uploadResult = cloudinaryService.uploadToCloudinary(
            auth = authResult as CloudinaryAuthResult.Success,
            mediaUri = item.uri,
            mimeType = item.mimeType,
            filename = item.filename,
            resourceType = resourceType,
            operationId = operationId,
            localMediaId = id,
            clientUploadId = clientUploadId,
            fileSize = item.fileSizeBytes
        )

        return when (uploadResult) {
            is CloudinaryUploadResult.Success -> {
                syncRepository.updateCloudinaryResult(
                    id = id,
                    assetId = uploadResult.assetId,
                    publicId = uploadResult.publicId,
                    secureUrl = uploadResult.secureUrl,
                    version = uploadResult.version,
                    format = uploadResult.format,
                    resourceType = uploadResult.resourceType
                )
                syncRepository.updateState(id = id, state = BackupState.CLOUDINARY_COMPLETED)
                UploadLog.uploadCompleted(id, uploadResult.assetId, uploadResult.publicId)
                ItemOutcome.Continue
            }
            is CloudinaryUploadResult.Unauthorized -> {
                UploadLog.uploadFailed(id, "upload_unauthorized")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary rejected the upload authorization."
                )
                ItemOutcome.Failed
            }
            is CloudinaryUploadResult.NetworkUnavailable -> {
                UploadLog.uploadFailed(id, "upload_network_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload failed due to network error. Will retry."
                )
                ItemOutcome.Failed
            }
            is CloudinaryUploadResult.Timeout -> {
                UploadLog.uploadFailed(id, "upload_timeout")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload timed out. Will retry."
                )
                ItemOutcome.Failed
            }
            is CloudinaryUploadResult.FileTooLarge -> {
                // Only reachable when the size was unknown up front and the stream
                // turned out to pass the single-request limit: a KNOWN large file is
                // carried by the chunked strategy instead of landing here.
                UploadLog.uploadFailed(id, "file_too_large")
                DeveloperLogger.error(
                    category = LogCategory.CLOUDINARY_UPLOAD,
                    event = "UPLOAD_FAILED",
                    message = "File exceeded the single-request limit with unknown size",
                    operationId = OperationTrace.idFor(id),
                    localMediaId = id,
                    metadata = mapOf(
                        "strategy" to "SINGLE_REQUEST",
                        "reported_size_bytes" to item.fileSizeBytes.toString(),
                        "single_request_max_bytes" to
                            CloudinaryUploadPlan.SINGLE_REQUEST_MAX_BYTES.toString(),
                        "reason" to "size_unknown_at_plan_time",
                        "retryable" to "true"
                    )
                )
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file is larger than 100 MB but its size could not be " +
                        "read beforehand, so a resumable upload could not be prepared. Retry."
                )
                ItemOutcome.Failed
            }
            is CloudinaryUploadResult.UnsupportedMedia -> {
                UploadLog.uploadFailed(id, "unsupported_media")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file type is not supported for Cloudinary upload."
                )
                ItemOutcome.Failed
            }
            is CloudinaryUploadResult.MediaUnavailable -> {
                UploadLog.uploadFailed(id, "media_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This photo is no longer available on this device."
                )
                ItemOutcome.Failed
            }
            is CloudinaryUploadResult.UploadFailed -> {
                UploadLog.uploadFailed(id, "http_${uploadResult.httpStatus}")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary upload failed (${uploadResult.httpStatus})."
                )
                ItemOutcome.Failed
            }
            is CloudinaryUploadResult.Error -> {
                UploadLog.uploadFailed(id, "upload_error")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = uploadResult.message
                )
                ItemOutcome.Failed
            }
        }
    }

    /**
     * Step 2: record the finished Cloudinary asset in Supabase. On success the
     * local item is COMPLETED; on failure it stays retryable with the
     * Cloudinary identifier preserved for a safe retry.
     */
    private suspend fun finalizeOnSupabase(id: String, item: MediaItem): ItemOutcome {
        val record = syncRepository.records.value[id] ?: return ItemOutcome.Failed
        val assetId = record.cloudinaryAssetId
        val publicId = record.cloudinaryPublicId
        val secureUrl = record.cloudinarySecureUrl
        if (assetId.isNullOrBlank() || publicId.isNullOrBlank() || secureUrl.isNullOrBlank()) {
            UploadLog.finalizeFailed(id, "incomplete_cloudinary_result")
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "Cloudinary upload result is incomplete. Please retry."
            )
            return ItemOutcome.Failed
        }

        syncRepository.updateState(id = id, state = BackupState.FINALIZING_SUPABASE)

        val deviceId = runCatching { deviceIdProvider() }.getOrNull()
        if (deviceId.isNullOrBlank()) {
            UploadLog.finalizeFailed(id, "device_not_registered")
            return when (sessionProvider.prepare()) {
                is PreparedAuth.Available -> {
                    syncRepository.updateState(
                        id = id,
                        state = BackupState.FAILED,
                        errorMessage = "Couldn't register this device. Will retry."
                    )
                    ItemOutcome.Failed
                }
                else -> deferForSession(id)
            }
        }

        val clientUploadId = record.clientUploadId
        if (clientUploadId.isNullOrBlank()) {
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "Upload identity is missing. Please retry."
            )
            return ItemOutcome.Failed
        }

        val request = MediaFinalizeRequest(
            clientUploadId = clientUploadId,
            deviceId = deviceId,
            localMediaId = item.mediaStoreId.takeIf { it > 0L },
            fileName = item.filename,
            mimeType = item.mimeType,
            fileSize = item.fileSizeBytes,
            width = item.width.takeIf { it > 0 },
            height = item.height.takeIf { it > 0 },
            durationMs = item.durationMillis,
            assetId = assetId,
            publicId = publicId,
            secureUrl = secureUrl,
            version = record.cloudinaryVersion ?: 0L,
            format = record.cloudinaryFormat.orEmpty(),
            resourceType = record.cloudinaryResourceType.orEmpty()
        )

        UploadLog.finalizeStarted(id, request.clientUploadId)
        val operationId = OperationTrace.idFor(id)
        return when (val result = mediaFinalizeService.finalize(
            request = request,
            operationId = operationId,
            localMediaId = id
        )) {
            is FinalizeResult.Success -> {
                syncRepository.updateFinalizedResult(id, result.mediaId)
                syncRepository.updateState(id = id, state = BackupState.COMPLETED)
                UploadLog.finalizeSucceeded(id, result.mediaId)
                UploadLog.localUpdated(id, BackupState.COMPLETED.name)
                DeveloperLogger.info(
                    category = LogCategory.REPLICATION,
                    event = "MEDIA_ASSETS_RECORDED",
                    message = "media_assets row recorded; backend may enqueue replication jobs",
                    operationId = OperationTrace.idFor(id),
                    localMediaId = id,
                    clientUploadId = request.clientUploadId,
                    metadata = mapOf("remote_media_id" to result.mediaId)
                )
                ItemOutcome.Continue
            }
            is FinalizeResult.Unauthorized -> {
                UploadLog.finalizeFailed(id, "unauthorized")
                deferForSession(id)
            }
            is FinalizeResult.NetworkUnavailable -> {
                UploadLog.finalizeFailed(id, "network_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Couldn't save the backup record. Will retry."
                )
                ItemOutcome.Failed
            }
            is FinalizeResult.Timeout -> {
                UploadLog.finalizeFailed(id, "timeout")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Saving the backup record timed out. Will retry."
                )
                ItemOutcome.Failed
            }
            is FinalizeResult.Misconfigured -> {
                UploadLog.finalizeFailed(id, "misconfigured")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Backup service is not configured. Please check server settings."
                )
                ItemOutcome.Failed
            }
            is FinalizeResult.Rejected -> {
                UploadLog.finalizeFailed(id, "rejected")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = result.message
                )
                ItemOutcome.Failed
            }
            is FinalizeResult.Error -> {
                UploadLog.finalizeFailed(id, "error")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = result.message
                )
                ItemOutcome.Failed
            }
        }
    }

    private suspend fun deferForSession(id: String): ItemOutcome {
        val prepared = sessionProvider.prepare(forceRefresh = true)
        return when (prepared) {
            is PreparedAuth.Available -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Couldn't authorize the upload. Will retry."
                )
                ItemOutcome.Failed
            }
            is PreparedAuth.NetworkError -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.WAITING,
                    errorMessage = "Waiting for a valid session. Will retry."
                )
                ItemOutcome.HaltQueue
            }
            is PreparedAuth.SignedOut -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.WAITING,
                    errorMessage = "Sign in to continue this backup."
                )
                ItemOutcome.HaltQueue
            }
        }
    }

    /**
     * Structured upload-source state for the Developer Console.
     *
     * Enough to tell the three cases apart at a glance —
     * `source_type=MEDIASTORE|LOCAL_FILE|REMOTE|UNKNOWN`, `source_available`,
     * `cloud_state=READY|PENDING|MISSING|UNKNOWN` — without logging the media
     * itself. Delivery URLs are redacted to scheme + host: they are private media
     * addresses and the log needs to say *what kind* of source it was, not where
     * the file lives.
     */
    private fun uriMetadata(
        entity: UploadQueueEntity?,
        probe: MediaUriProbe?,
        kind: MediaSourceKind,
        record: SyncRecord?
    ): Map<String, String?> = mapOf(
        "content_uri" to redactSourceUri(entity?.contentUri),
        "uri_authority" to probe?.authority,
        "uri_scheme" to (probe?.scheme ?: kind.name.lowercase()),
        "source_type" to kind.label,
        "source_is_local" to kind.isLocalUploadSource.toString(),
        "source_available" to (probe?.inputStreamOpened == true).toString(),
        "cloud_state" to localCloudStateLabel(record),
        "media_store_id" to (probe?.mediaStoreId ?: entity?.localMediaId)?.toString(),
        "display_name" to entity?.fileName,
        "mime_type" to entity?.mimeType,
        "expected_size" to entity?.fileSize?.toString(),
        "actual_readable_size" to probe?.readableSize?.toString(),
        "query_found" to probe?.queryFound?.toString(),
        "input_stream_opened" to probe?.inputStreamOpened?.toString(),
        "file_descriptor_opened" to probe?.fileDescriptorOpened?.toString()
    )

    private fun uriMetadata(
        item: MediaItem,
        probe: MediaUriProbe,
        kind: MediaSourceKind,
        record: SyncRecord?
    ): Map<String, String?> = mapOf(
        "content_uri" to redactSourceUri(item.uri),
        "uri_authority" to probe.authority,
        "uri_scheme" to (probe.scheme ?: kind.name.lowercase()),
        "source_type" to kind.label,
        "source_is_local" to kind.isLocalUploadSource.toString(),
        "source_available" to probe.inputStreamOpened.toString(),
        "cloud_state" to localCloudStateLabel(record),
        "media_store_id" to (probe.mediaStoreId ?: item.mediaStoreId).toString(),
        "display_name" to item.filename,
        "mime_type" to item.mimeType,
        "expected_size" to item.fileSizeBytes.toString(),
        "actual_readable_size" to probe.readableSize?.toString(),
        "query_found" to probe.queryFound.toString(),
        "input_stream_opened" to probe.inputStreamOpened.toString(),
        "file_descriptor_opened" to probe.fileDescriptorOpened.toString()
    )

    /**
     * The cloud state as the *local* record knows it. Only ever a hint for the log:
     * the authoritative answer comes from `verifyCloudBackedUp`, because this
     * install-local view is exactly what is lost on a reinstall.
     */
    private fun localCloudStateLabel(record: SyncRecord?): String = when {
        record == null -> "UNKNOWN"
        record.remoteMediaId != null || record.cloudinaryAssetId != null -> "READY"
        record.state.toBackupState().isRetryable -> "MISSING"
        else -> "PENDING"
    }

    /**
     * Keeps a `content://` source identifiable while never writing a media URL into
     * the log: content authorities and MediaStore ids are kept, everything else is
     * reduced to its scheme and host.
     */
    private fun redactSourceUri(rawUri: String?): String? {
        val raw = rawUri?.takeIf { it.isNotBlank() } ?: return null
        return when (val kind = classifyMediaSource(raw)) {
            MediaSourceKind.MEDIASTORE, MediaSourceKind.LOCAL_FILE, MediaSourceKind.APP_PRIVATE_FILE -> raw
            MediaSourceKind.REMOTE -> runCatching {
                val parsed = android.net.Uri.parse(raw)
                "${kind.name.lowercase()}://${parsed.host.orEmpty()}/…"
            }.getOrDefault("REMOTE")
            MediaSourceKind.UNKNOWN -> kind.name
        }
    }

    private enum class ItemOutcome {
        Continue,
        Failed,
        HaltQueue
    }

    enum class QueueDrain {
        Idle,
        AwaitingSession,
        NetworkUnavailable
    }
}
