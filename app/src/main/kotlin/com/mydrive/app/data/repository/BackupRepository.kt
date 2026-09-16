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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val scope: CoroutineScope
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
        launchWorker()
    }

    fun retry(id: String) = retryAll(listOf(id))

    fun retryAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        if (gate() != BackupGate.Ready) return
        syncRepository.setPaused(false)
        syncRepository.retryAll(ids)
        launchWorker()
    }

    fun pause() {
        syncRepository.setPaused(true)
    }

    fun resume() {
        if (gate() != BackupGate.Ready) return
        syncRepository.setPaused(false)
        launchWorker()
    }

    fun cancel(id: String) {
        syncRepository.cancel(id)
    }

    private fun launchWorker() {
        scope.launch {
            workerMutex.withLock { processQueue() }
        }
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
        val record = syncRepository.records.value[id] ?: return
        if (record.state.toBackupState().resumeLocally() != BackupState.WAITING) return

        val item = mediaLookup(id)
        if (item == null) {
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "This photo is no longer available on this device."
            )
            return
        }
        if (item.type != MediaType.PHOTO) {
            syncRepository.updateState(id = id, state = BackupState.NOT_STARTED)
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
        syncRepository.updateState(id = id, state = BackupState.REQUESTING_CLOUDINARY_AUTH)

        val authResult = cloudinaryService.requestUploadAuth(
            resourceType = if (item.type == MediaType.VIDEO) "video" else "image"
        )

        when (authResult) {
            is CloudinaryAuthResult.Unauthorized -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Your session has expired. Please sign in again."
                )
                return false
            }
            is CloudinaryAuthResult.Misconfigured -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary is not configured. Please check server settings."
                )
                return false
            }
            is CloudinaryAuthResult.NetworkUnavailable -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "No internet connection. This photo will stay in the queue."
                )
                return false
            }
            is CloudinaryAuthResult.Timeout -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Request timed out. Try again."
                )
                return false
            }
            is CloudinaryAuthResult.Error -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = authResult.message
                )
                return false
            }
            is CloudinaryAuthResult.Success -> {
                // Auth obtained, proceed to upload
            }
        }

        syncRepository.updateState(id = id, state = BackupState.UPLOADING_TO_CLOUDINARY)

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
                return true
            }
            is CloudinaryUploadResult.Unauthorized -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary rejected the upload authorization."
                )
                return false
            }
            is CloudinaryUploadResult.NetworkUnavailable -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload failed due to network error. Will retry."
                )
                return false
            }
            is CloudinaryUploadResult.Timeout -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload timed out. Will retry."
                )
                return false
            }
            is CloudinaryUploadResult.FileTooLarge -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file is too large for Cloudinary."
                )
                return false
            }
            is CloudinaryUploadResult.UnsupportedMedia -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file type is not supported for Cloudinary upload."
                )
                return false
            }
            is CloudinaryUploadResult.MediaUnavailable -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This photo is no longer available on this device."
                )
                return false
            }
            is CloudinaryUploadResult.UploadFailed -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary upload failed (${uploadResult.httpStatus})."
                )
                return false
            }
            is CloudinaryUploadResult.Error -> {
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
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "Cloudinary upload result is incomplete. Please retry."
            )
            return
        }

        syncRepository.updateState(id = id, state = BackupState.FINALIZING_SUPABASE)

        val deviceId = deviceIdProvider()
        if (deviceId.isNullOrBlank()) {
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = "Your session has expired. Please sign in again."
            )
            return
        }

        val request = MediaFinalizeRequest(
            clientUploadId = record.clientUploadId
                ?: java.util.UUID.randomUUID().toString(),
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

        when (val result = mediaFinalizeService.finalize(request)) {
            is FinalizeResult.Success -> {
                // Cloudinary upload is the primary (and only) destination.
                // Telegram replication will happen server-side after Supabase
                // media finalization.
                syncRepository.updateState(id = id, state = BackupState.COMPLETED)
            }
            is FinalizeResult.Unauthorized -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Your session has expired. Please sign in again."
                )
            }
            is FinalizeResult.NetworkUnavailable -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Couldn't save the backup record. Will retry."
                )
            }
            is FinalizeResult.Timeout -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Saving the backup record timed out. Will retry."
                )
            }
            is FinalizeResult.Misconfigured -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Backup service is not configured. Please check server settings."
                )
            }
            is FinalizeResult.Rejected -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = result.message
                )
            }
            is FinalizeResult.Error -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = result.message
                )
            }
        }
    }
}