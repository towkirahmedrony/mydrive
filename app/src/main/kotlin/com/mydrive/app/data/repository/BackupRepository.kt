package com.mydrive.app.data.repository

import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.local.UploadQueueEntity
import com.mydrive.app.data.media.MediaUriProbe
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.remote.CloudinaryAuthResult
import com.mydrive.app.data.remote.CloudinaryUploadResult
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
    private val mediaLookup: (String) -> MediaItem?,
    private val queueLookup: suspend (String) -> UploadQueueEntity?,
    private val queuedMediaResolver: suspend (UploadQueueEntity) -> MediaItem?,
    private val uriProbe: (String) -> MediaUriProbe,
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

    fun startBackup(ids: Collection<String>) {
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
        syncRepository.setPaused(false)
        syncRepository.enqueue(ids, ownerUserId)
        scheduleUploadWork()
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
        val storedProbe = queueEntity?.contentUri?.let(uriProbe)
        if (queueEntity != null) {
            DeveloperLogger.info(
                category = LogCategory.MEDIASTORE,
                event = "MEDIA_URI_PROBE",
                message = "Checked persisted media URI before upload",
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                metadata = uriMetadata(queueEntity, storedProbe)
            )
        }
        // The gallery StateFlow can be stale or temporarily partial. Room's
        // persisted URI is the queue source of truth; re-scan MediaStore only
        // when the in-memory lookup cannot find the item.
        val item = mediaLookup(id) ?: queueEntity?.let { queuedMediaResolver(it) }
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
            val event = if (queueEntity == null || storedProbe?.queryFound != true) {
                "MEDIASTORE_ITEM_NOT_FOUND"
            } else {
                "MEDIA_OPEN_FAILED"
            }
            DeveloperLogger.error(
                category = LogCategory.MEDIASTORE,
                event = event,
                message = if (event == "MEDIASTORE_ITEM_NOT_FOUND") {
                    "MediaStore row was not found for queued media"
                } else {
                    "Persisted media URI could not be opened"
                },
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                throwable = storedProbe?.errorType?.let { IllegalStateException(storedProbe.errorMessage) },
                metadata = uriMetadata(queueEntity, storedProbe)
            )
            UploadLog.uploadFailed(id, "media_unavailable")
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "This photo is no longer available on this device."
            )
            return ItemOutcome.Continue
        }

        if (queueEntity != null && queueEntity.contentUri != item.uri) {
            syncRepository.updateQueueMedia(id, item)
            DeveloperLogger.info(
                category = LogCategory.ROOM,
                event = "QUEUE_MEDIA_URI_REFRESHED",
                message = "Updated queued media metadata after MediaStore re-resolution",
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                metadata = uriMetadata(item, uriProbe(item.uri))
            )
        }

        val uploadProbe = uriProbe(item.uri)
        if (!uploadProbe.inputStreamOpened || !uploadProbe.fileDescriptorOpened) {
            DeveloperLogger.error(
                category = LogCategory.MEDIASTORE,
                event = "MEDIA_OPEN_FAILED",
                message = "Media item was found but its upload URI could not be opened",
                operationId = operationId,
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                throwable = uploadProbe.errorType?.let { IllegalStateException(uploadProbe.errorMessage) },
                metadata = uriMetadata(item, uploadProbe)
            )
            UploadLog.uploadFailed(id, "media_open_failed")
            syncRepository.updateState(id, BackupState.FAILED, "This media could not be opened for upload.")
            return ItemOutcome.Continue
        }

        val uploaded = record.cloudinaryAssetId != null && record.cloudinaryPublicId != null
        return if (!uploaded) {
            when (val upload = uploadToCloudinary(id, item)) {
                ItemOutcome.Continue -> finalizeOnSupabase(id, item)
                else -> upload
            }
        } else {
            finalizeOnSupabase(id, item)
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
                UploadLog.uploadFailed(id, "file_too_large")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file is too large for Cloudinary."
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

    private fun uriMetadata(entity: UploadQueueEntity?, probe: MediaUriProbe?): Map<String, String?> = mapOf(
        "content_uri" to entity?.contentUri,
        "uri_authority" to probe?.authority,
        "uri_scheme" to probe?.scheme,
        "media_store_id" to (probe?.mediaStoreId ?: entity?.localMediaId)?.toString(),
        "display_name" to entity?.fileName,
        "mime_type" to entity?.mimeType,
        "expected_size" to entity?.fileSize?.toString(),
        "actual_readable_size" to probe?.readableSize?.toString(),
        "query_found" to probe?.queryFound?.toString(),
        "input_stream_opened" to probe?.inputStreamOpened?.toString(),
        "file_descriptor_opened" to probe?.fileDescriptorOpened?.toString()
    )

    private fun uriMetadata(item: MediaItem, probe: MediaUriProbe): Map<String, String?> = mapOf(
        "content_uri" to item.uri,
        "uri_authority" to probe.authority,
        "uri_scheme" to probe.scheme,
        "media_store_id" to (probe.mediaStoreId ?: item.mediaStoreId).toString(),
        "display_name" to item.filename,
        "mime_type" to item.mimeType,
        "expected_size" to item.fileSizeBytes.toString(),
        "actual_readable_size" to probe.readableSize?.toString(),
        "query_found" to probe.queryFound.toString(),
        "input_stream_opened" to probe.inputStreamOpened.toString(),
        "file_descriptor_opened" to probe.fileDescriptorOpened.toString()
    )

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
