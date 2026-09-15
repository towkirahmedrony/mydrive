package com.mydrive.app.data.repository

import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.TelegramConnectionState
import com.mydrive.app.data.remote.CloudinaryUploadService
import com.mydrive.app.data.remote.NetworkMonitor
import com.mydrive.app.data.remote.TelegramPhoto
import com.mydrive.app.data.remote.TelegramUploadResult
import com.mydrive.app.data.remote.TelegramUploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BackupGate {
    data object Ready : BackupGate
    data object Disabled : BackupGate
    data object NotConfigured : BackupGate
    data object NotVerified : BackupGate
    data object Offline : BackupGate
    data object NotAuthenticated : BackupGate

    fun message(): String = when (this) {
        Ready -> ""
        Disabled -> "Telegram backup is turned off. Enable it in Telegram Backup settings."
        NotConfigured -> "Telegram isn't configured yet. Add your bot token and chat ID."
        NotVerified -> "Telegram setup isn't verified. Test the connection before backing up."
        Offline -> "You're offline. Connect to the internet to back up photos."
        NotAuthenticated -> "You're not signed in. Please sign in to back up photos."
    }
}

class BackupRepository(
    private val syncRepository: SyncRepository,
    private val settingsStore: TelegramSettingsStore,
    private val uploadService: TelegramUploadService,
    private val cloudinaryService: CloudinaryUploadService,
    private val network: NetworkMonitor,
    private val mediaLookup: (String) -> MediaItem?,
    private val scope: CoroutineScope
) {

    private val workerMutex = Mutex()

    /**
     * Check if the backup pipeline is ready.
     * Cloudinary requires network + auth; Telegram is optional.
     */
    fun gate(): BackupGate {
        if (!network.isOnline()) return BackupGate.Offline

        // Cloudinary requires authenticated session — check via service
        // (actual auth check happens when requesting upload auth)

        val settings = settingsStore.settings.value
        if (!settings.enabled) return BackupGate.Disabled
        if (!settings.tokenConfigured || settings.chatId.isBlank()) return BackupGate.NotConfigured
        if (settings.connectionState != TelegramConnectionState.CONNECTED) {
            return BackupGate.NotVerified
        }
        if (settingsStore.credentials() == null) return BackupGate.NotConfigured
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

        // ── Step 2: Optionally upload to Telegram ───────────────────────
        // Telegram is a secondary destination; only if configured
        val telegramSettings = settingsStore.settings.value
        if (telegramSettings.enabled &&
            telegramSettings.connected &&
            settingsStore.credentials() != null
        ) {
            syncRepository.updateState(id = id, state = BackupState.SENDING_TELEGRAM)

            val credentials = settingsStore.credentials()!!
            val telegramResult = uploadService.uploadPhoto(
                credentials = credentials,
                photo = TelegramPhoto(
                    uri = item.uri,
                    filename = item.filename,
                    mimeType = item.mimeType,
                    declaredSizeBytes = item.fileSizeBytes,
                    width = item.width,
                    height = item.height
                )
            )

            when (telegramResult) {
                is TelegramUploadResult.Success -> {
                    syncRepository.updateState(id = id, state = BackupState.COMPLETED)
                }
                else -> {
                    // Cloudinary succeeded, so mark completed even if Telegram fails
                    // Telegram failure is non-blocking for the primary backup
                    syncRepository.updateState(id = id, state = BackupState.COMPLETED)
                }
            }
        } else {
            // No Telegram configured — Cloudinary is the primary destination
            syncRepository.updateState(id = id, state = BackupState.COMPLETED)
        }
    }
}
