package com.mydrive.app.data.repository

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
    private val deviceIdProvider: suspend () -> String?,
    private val mediaLookup: (String) -> MediaItem?,
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
        if (gate() != BackupGate.Ready) return
        syncRepository.setPaused(false)
        syncRepository.enqueue(ids)
        scheduleUploadWork()
    }

    fun retry(id: String) = retryAll(listOf(id))

    fun retryAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        if (gate() != BackupGate.Ready) return
        syncRepository.setPaused(false)
        syncRepository.retryAll(ids)
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

    suspend fun processPendingQueue() {
        workerMutex.withLock { processQueue() }
    }

    private suspend fun processQueue() {
        while (true) {
            if (syncRepository.paused.value) break
            if (gate() != BackupGate.Ready) break
            val next = nextWaiting() ?: break
            processOne(next)
        }
    }

    private fun nextWaiting(): String? = syncRepository.records.value
        .asSequence()
        .filter { it.value.state.toBackupState().resumeLocally() == BackupState.WAITING }
        .minByOrNull { it.value.queuedAtMillis }
        ?.key

    private suspend fun processOne(id: String) {
        UploadLog.itemClaimed(id)
        val record = syncRepository.records.value[id] ?: return
        if (record.state.toBackupState().resumeLocally() != BackupState.WAITING) return

        val item = mediaLookup(id)
        if (item == null) {
            UploadLog.uploadFailed(id, "media_unavailable")
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "This photo is no longer available on this device."
            )
            return
        }

        // A previous attempt already uploaded this photo to Cloudinary (the
        // asset identifiers are persisted). Do NOT re-upload it — jump
        // straight to finalization so Supabase-only failures stay retryable
        // without burning upload bandwidth.
        val uploaded = record.cloudinaryAssetId != null && record.cloudinaryPublicId != null

        if (!uploaded) {
            if (uploadToCloudinary(id, item)) {
                finalizeOnSupabase(id, item)
            }
        } else {
            finalizeOnSupabase(id, item)
        }
    }

    /**
     * Step 1: upload the photo to Cloudinary. Returns true on success.
     */
    private suspend fun uploadToCloudinary(id: String, item: MediaItem): Boolean {
        // ── Step 1: Upload to Cloudinary ────────────────────────────────
        val resourceType = if (item.type == MediaType.VIDEO) "video" else "image"
        syncRepository.updateState(id = id, state = BackupState.REQUESTING_CLOUDINARY_AUTH)

        val authResult = cloudinaryService.requestUploadAuth(
            resourceType = resourceType
        )

        when (authResult) {
            is CloudinaryAuthResult.Unauthorized -> {
                UploadLog.uploadFailed(id, "authorization_unauthorized")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Your session has expired. Please sign in again."
                )
                return false
            }
            is CloudinaryAuthResult.Misconfigured -> {
                UploadLog.uploadFailed(id, "authorization_misconfigured")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary is not configured. Please check server settings."
                )
                return false
            }
            is CloudinaryAuthResult.NetworkUnavailable -> {
                UploadLog.uploadFailed(id, "authorization_network_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "No internet connection. This photo will stay in the queue."
                )
                return false
            }
            is CloudinaryAuthResult.Timeout -> {
                UploadLog.uploadFailed(id, "authorization_timeout")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Request timed out. Try again."
                )
                return false
            }
            is CloudinaryAuthResult.Error -> {
                UploadLog.uploadFailed(id, "authorization_error")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = authResult.message
                )
                return false
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
            filename = item.filename
        )

        when (uploadResult) {
            is CloudinaryUploadResult.Success -> {
                // Persist the full Cloudinary asset result so retries after a
                // finalize failure can reuse it without a re-upload.
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
                return true
            }
            is CloudinaryUploadResult.Unauthorized -> {
                UploadLog.uploadFailed(id, "upload_unauthorized")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary rejected the upload authorization."
                )
                return false
            }
            is CloudinaryUploadResult.NetworkUnavailable -> {
                UploadLog.uploadFailed(id, "upload_network_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload failed due to network error. Will retry."
                )
                return false
            }
            is CloudinaryUploadResult.Timeout -> {
                UploadLog.uploadFailed(id, "upload_timeout")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload timed out. Will retry."
                )
                return false
            }
            is CloudinaryUploadResult.FileTooLarge -> {
                UploadLog.uploadFailed(id, "file_too_large")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file is too large for Cloudinary."
                )
                return false
            }
            is CloudinaryUploadResult.UnsupportedMedia -> {
                UploadLog.uploadFailed(id, "unsupported_media")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file type is not supported for Cloudinary upload."
                )
                return false
            }
            is CloudinaryUploadResult.MediaUnavailable -> {
                UploadLog.uploadFailed(id, "media_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This photo is no longer available on this device."
                )
                return false
            }
            is CloudinaryUploadResult.UploadFailed -> {
                UploadLog.uploadFailed(id, "http_${uploadResult.httpStatus}")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary upload failed (${uploadResult.httpStatus})."
                )
                return false
            }
            is CloudinaryUploadResult.Error -> {
                UploadLog.uploadFailed(id, "upload_error")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = uploadResult.message
                )
                return false
            }
        }
    }

    /**
     * Step 2: record the finished Cloudinary asset in Supabase. On success the
     * local item is COMPLETED; on failure it stays retryable with the
     * Cloudinary identifier preserved for a safe retry.
     */
    private suspend fun finalizeOnSupabase(id: String, item: MediaItem) {
        val record = syncRepository.records.value[id] ?: return
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
            return
        }

        syncRepository.updateState(id = id, state = BackupState.FINALIZING_SUPABASE)

        val deviceId = runCatching { deviceIdProvider() }.getOrNull()
        if (deviceId.isNullOrBlank()) {
            UploadLog.finalizeFailed(id, "device_not_registered")
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "Your session has expired. Please sign in again."
            )
            return
        }

        val request = MediaFinalizeRequest(
            clientUploadId = record.clientUploadId
                ?: return syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload identity is missing. Please retry."
                ),
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
        when (val result = mediaFinalizeService.finalize(request)) {
            is FinalizeResult.Success -> {
                // Cloudinary upload is the primary (and only) destination.
                // Telegram replication will happen server-side after Supabase
                // media finalization.
                syncRepository.updateFinalizedResult(id, result.mediaId)
                syncRepository.updateState(id = id, state = BackupState.COMPLETED)
                UploadLog.finalizeSucceeded(id, result.mediaId)
                UploadLog.localUpdated(id, BackupState.COMPLETED.name)
            }
            is FinalizeResult.Unauthorized -> {
                UploadLog.finalizeFailed(id, "unauthorized")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Your session has expired. Please sign in again."
                )
            }
            is FinalizeResult.NetworkUnavailable -> {
                UploadLog.finalizeFailed(id, "network_unavailable")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Couldn't save the backup record. Will retry."
                )
            }
            is FinalizeResult.Timeout -> {
                UploadLog.finalizeFailed(id, "timeout")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Saving the backup record timed out. Will retry."
                )
            }
            is FinalizeResult.Misconfigured -> {
                UploadLog.finalizeFailed(id, "misconfigured")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Backup service is not configured. Please check server settings."
                )
            }
            is FinalizeResult.Rejected -> {
                UploadLog.finalizeFailed(id, "rejected")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = result.message
                )
            }
            is FinalizeResult.Error -> {
                UploadLog.finalizeFailed(id, "error")
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = result.message
                )
            }
        }
    }
}
