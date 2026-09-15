package com.mydrive.app.data.repository

import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.TelegramConnectionState
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

    fun message(): String = when (this) {
        Ready -> ""
        Disabled -> "Telegram backup is turned off. Enable it in Telegram Backup settings."
        NotConfigured -> "Telegram isn't configured yet. Add your bot token and chat ID."
        NotVerified -> "Telegram setup isn't verified. Test the connection before backing up."
        Offline -> "You're offline. Connect to the internet to back up photos."
    }
}

class BackupRepository(
    private val syncRepository: SyncRepository,
    private val settingsStore: TelegramSettingsStore,
    private val uploadService: TelegramUploadService,
    private val network: NetworkMonitor,
    private val mediaLookup: (String) -> MediaItem?,
    private val scope: CoroutineScope
) {

    private val workerMutex = Mutex()

    fun gate(): BackupGate {
        val settings = settingsStore.settings.value
        if (!settings.enabled) return BackupGate.Disabled
        if (!settings.tokenConfigured || settings.chatId.isBlank()) return BackupGate.NotConfigured
        if (settings.connectionState != TelegramConnectionState.CONNECTED) {
            return BackupGate.NotVerified
        }
        if (settingsStore.credentials() == null) return BackupGate.NotConfigured
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

        val credentials = settingsStore.credentials()
        if (credentials == null) {
            syncRepository.updateState(
                id = id,
                state = BackupState.FAILED,
                errorMessage = BackupGate.NotConfigured.message()
            )
            return
        }

        syncRepository.updateState(id = id, state = BackupState.UPLOADING)

        val result = uploadService.uploadPhoto(
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

        when (result) {
            is TelegramUploadResult.Success ->
                syncRepository.updateState(id = id, state = BackupState.COMPLETED)
            else -> {
                if (result.configurationRejected) {
                    settingsStore.markConnectionFailed(result.message())
                }
                syncRepository.updateState(
                    id = id,
                    state = BackupState.FAILED,
                    errorMessage = result.message()
                )
            }
        }
    }
}
