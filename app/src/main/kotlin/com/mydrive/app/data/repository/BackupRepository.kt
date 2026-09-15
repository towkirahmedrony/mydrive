package com.mydrive.app.data.repository

import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.remote.CloudinaryUploadService
import com.mydrive.app.data.remote.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Backup gate — now checks only network + Cloudinary readiness.
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
    private val network: NetworkMonitor,
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

        // ── Step 1: Upload to Cloudinary ────────────────────────────────
        syncRepository.updateState(id = id, state = BackupState.REQUESTING_CLOUDINARY_AUTH)

        val authResult = cloudinaryService.requestUploadAuth(
            resourceType = if (item.type == MediaType.VIDEO) "video" else "image"
        )

        when (authResult) {
            is com.mydrive.app.data.remote.CloudinaryAuthResult.Unauthorized -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Your session has expired. Please sign in again."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryAuthResult.Misconfigured -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary is not configured. Please check server settings."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryAuthResult.NetworkUnavailable -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "No internet connection. This photo will stay in the queue."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryAuthResult.Timeout -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Request timed out. Try again."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryAuthResult.Error -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = authResult.message
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryAuthResult.Success -> {
                // Auth obtained, proceed to upload
            }
        }

        syncRepository.updateState(id = id, state = BackupState.UPLOADING_TO_CLOUDINARY)

        val uploadResult = cloudinaryService.uploadToCloudinary(
            auth = authResult as com.mydrive.app.data.remote.CloudinaryAuthResult.Success,
            mediaUri = item.uri,
            mimeType = item.mimeType,
            filename = item.filename
        )

        when (uploadResult) {
            is com.mydrive.app.data.remote.CloudinaryUploadResult.Success -> {
                // Persist Cloudinary asset info
                syncRepository.updateCloudinaryResult(
                    id = id,
                    assetId = uploadResult.assetId,
                    publicId = uploadResult.publicId
                )
                syncRepository.updateState(id = id, state = BackupState.CLOUDINARY_COMPLETED)
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.Unauthorized -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary rejected the upload authorization."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.NetworkUnavailable -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload failed due to network error. Will retry."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.Timeout -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Upload timed out. Will retry."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.FileTooLarge -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file is too large for Cloudinary."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.UnsupportedMedia -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This file type is not supported for Cloudinary upload."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.MediaUnavailable -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "This photo is no longer available on this device."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.UploadFailed -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = "Cloudinary upload failed (${uploadResult.httpStatus})."
                )
                return
            }
            is com.mydrive.app.data.remote.CloudinaryUploadResult.Error -> {
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = uploadResult.message
                )
                return
            }
        }

        // Cloudinary upload is the primary (and only) destination.
        // Telegram replication will happen server-side after Supabase media finalization.
        syncRepository.updateState(id = id, state = BackupState.COMPLETED)
    }
}
