package com.mydrive.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaLoadState
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.TelegramConnectionState
import com.mydrive.app.data.model.TelegramSettings
import com.mydrive.app.data.model.isActive
import com.mydrive.app.data.model.isRetryable
import com.mydrive.app.data.repository.BackupGate
import com.mydrive.app.data.repository.BackupRepository
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.data.repository.SyncRepository
import com.mydrive.app.data.repository.resumeLocally
import com.mydrive.app.data.repository.toBackupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncJob(
    val media: MediaItem,
    val state: BackupState,
    val errorMessage: String?,
    val updatedAtMillis: Long
)

enum class SyncStatus {
    NO_MEDIA,
    ALL_BACKED_UP,
    READY,
    WAITING,
    IN_PROGRESS,
    PAUSED,
    FAILED
}

data class SyncUiState(
    val status: SyncStatus,
    val headline: String,
    val description: String,
    val paused: Boolean,
    val active: List<SyncJob>,
    val waiting: List<SyncJob>,
    val failed: List<SyncJob>,
    val completed: List<SyncJob>,
    val completedCount: Int,
    val waitingCount: Int,
    val failedCount: Int,
    val notStartedCount: Int,
    val totalCount: Int,
    val lastUpdatedMillis: Long,
    val isLoading: Boolean,
    val needsPermission: Boolean,
    val permissionDenied: Boolean,
    val telegramEnabled: Boolean,
    val telegramConfigured: Boolean,
    val telegramConnected: Boolean,
    val eligiblePhotoCount: Int,
    val pendingVideoCount: Int,
    val actionNotice: String?
) {
    val pendingCount: Int get() = waitingCount + notStartedCount
    val activeCount: Int get() = active.size
    val hasMedia: Boolean get() = totalCount > 0
    val telegramReady: Boolean get() = telegramEnabled && telegramConfigured && telegramConnected
    val hasEligible: Boolean get() = eligiblePhotoCount > 0
    val isRunning: Boolean
        get() = status == SyncStatus.WAITING || status == SyncStatus.IN_PROGRESS || status == SyncStatus.PAUSED
}

class SyncViewModel(
    private val repository: MediaRepository,
    private val syncRepository: SyncRepository,
    private val backupRepository: BackupRepository
) : ViewModel() {

    private val actionNotice = MutableStateFlow<String?>(null)

    init {
        refresh(force = false)
    }

    private val coreState = combine(
        repository.media,
        repository.loadState,
        syncRepository.records,
        syncRepository.paused,
        repository.telegram
    ) { media, load, records, paused, telegram ->
        buildState(media, load, records, paused, telegram)
    }

    val uiState: StateFlow<SyncUiState> = combine(
        coreState,
        actionNotice
    ) { state, notice ->
        state.copy(actionNotice = notice)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyState(repository.loadState.value)
    )

    fun startBackup() {
        viewModelScope.launch {
            actionNotice.value = null
            val gate = backupRepository.gate()
            if (gate != BackupGate.Ready) {
                actionNotice.value = gate.message()
                return@launch
            }
            val eligible = withContext(Dispatchers.Default) {
                val records = syncRepository.records.value
                repository.media.value
                    .filter { item ->
                        val state = records[item.id]?.state?.toBackupState()?.resumeLocally()
                            ?: item.backupState
                        state == BackupState.NOT_STARTED || state.isRetryable
                    }
                    .map { it.id }
            }
            if (eligible.isEmpty()) {
                actionNotice.value = "No photos or videos are ready to back up."
                return@launch
            }
            backupRepository.startBackup(eligible)
        }
    }

    fun pauseBackup() {
        backupRepository.pause()
    }

    fun resumeBackup() {
        val gate = backupRepository.gate()
        if (gate != BackupGate.Ready) {
            actionNotice.value = gate.message()
            return
        }
        backupRepository.resume()
    }

    fun retry(id: String) {
        val gate = backupRepository.gate()
        if (gate != BackupGate.Ready) {
            actionNotice.value = gate.message()
            return
        }
        backupRepository.retry(id)
    }

    fun retryFailed() {
        val gate = backupRepository.gate()
        if (gate != BackupGate.Ready) {
            actionNotice.value = gate.message()
            return
        }
        val ids = uiState.value.failed.map { it.media.id }
        backupRepository.retryAll(ids)
    }

    fun cancel(id: String) {
        backupRepository.cancel(id)
    }

    fun clearNotice() {
        actionNotice.value = null
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            repository.refresh(force = force)
        }
    }

    fun permissionPermissions(): Array<String> = repository.requiredPermissions()

    fun onPermissionResult() {
        repository.markPermissionAsked()
        refresh(force = true)
    }

    fun visibleItemIds(): List<String> {
        val state = uiState.value
        return (state.active + state.waiting + state.failed + state.completed)
            .map { it.media.id }
            .distinct()
    }

    private fun emptyState(load: MediaLoadState): SyncUiState {
        val telegram = repository.telegram.value
        return SyncUiState(
            status = SyncStatus.NO_MEDIA,
            headline = "Checking your library",
            description = "Looking for photos and videos to back up.",
            paused = false,
            active = emptyList(),
            waiting = emptyList(),
            failed = emptyList(),
            completed = emptyList(),
            completedCount = 0,
            waitingCount = 0,
            failedCount = 0,
            notStartedCount = 0,
            totalCount = 0,
            lastUpdatedMillis = 0L,
            isLoading = load.isLoading,
            needsPermission = load.needsPermission,
            permissionDenied = load.permissionDenied,
            telegramEnabled = telegram.enabled,
            telegramConfigured = telegram.tokenConfigured && telegram.chatId.isNotBlank(),
            telegramConnected = telegram.connectionState == TelegramConnectionState.CONNECTED,
            eligiblePhotoCount = 0,
            pendingVideoCount = 0,
            actionNotice = null
        )
    }

    private fun buildState(
        media: List<MediaItem>,
        load: MediaLoadState,
        records: Map<String, SyncRecord>,
        paused: Boolean,
        telegram: TelegramSettings
    ): SyncUiState {
        val jobs = ArrayList<SyncJob>(media.size)
        for (item in media) {
            val record = records[item.id]
            val state = record?.state?.toBackupState()?.resumeLocally() ?: item.backupState
            jobs += SyncJob(
                media = item,
                state = state,
                errorMessage = record?.errorMessage,
                updatedAtMillis = record?.updatedAtMillis ?: 0L
            )
        }
        val active = jobs.filter { it.state.isActive }.sortedByDescending { it.updatedAtMillis }
        val waiting = jobs.filter { it.state == BackupState.WAITING }.sortedBy { it.updatedAtMillis }
        val failed = jobs.filter { it.state.isRetryable }.sortedByDescending { it.updatedAtMillis }
        val completed = jobs.filter { it.state == BackupState.COMPLETED }
            .sortedByDescending { it.updatedAtMillis }
        val notStartedCount = jobs.count { it.state == BackupState.NOT_STARTED }
        val eligiblePhotoCount = jobs.count { job ->
            job.media.type == MediaType.PHOTO &&
                (job.state == BackupState.NOT_STARTED || job.state.isRetryable)
        }
        val pendingVideoCount = jobs.count {
            it.media.type == MediaType.VIDEO && it.state == BackupState.NOT_STARTED
        }
        val lastUpdated = records.values.maxOfOrNull { it.updatedAtMillis } ?: 0L

        val status = when {
            jobs.isEmpty() -> SyncStatus.NO_MEDIA
            active.isNotEmpty() && !paused -> SyncStatus.IN_PROGRESS
            paused && waiting.isNotEmpty() -> SyncStatus.PAUSED
            waiting.isNotEmpty() -> SyncStatus.WAITING
            failed.isNotEmpty() -> SyncStatus.FAILED
            notStartedCount > 0 -> SyncStatus.READY
            else -> SyncStatus.ALL_BACKED_UP
        }
        val copy = statusCopy(
            status = status,
            activeCount = active.size,
            waitingCount = waiting.size,
            failedCount = failed.size,
            notStartedCount = notStartedCount
        )
        return SyncUiState(
            status = status,
            headline = copy.first,
            description = copy.second,
            paused = paused,
            active = active,
            waiting = waiting,
            failed = failed,
            completed = completed,
            completedCount = completed.size,
            waitingCount = waiting.size,
            failedCount = failed.size,
            notStartedCount = notStartedCount,
            totalCount = jobs.size,
            lastUpdatedMillis = lastUpdated,
            isLoading = load.isLoading,
            needsPermission = load.needsPermission,
            permissionDenied = load.permissionDenied,
            telegramEnabled = telegram.enabled,
            telegramConfigured = telegram.tokenConfigured && telegram.chatId.isNotBlank(),
            telegramConnected = telegram.connectionState == TelegramConnectionState.CONNECTED,
            eligiblePhotoCount = eligiblePhotoCount,
            pendingVideoCount = pendingVideoCount,
            actionNotice = null
        )
    }

    private fun statusCopy(
        status: SyncStatus,
        activeCount: Int,
        waitingCount: Int,
        failedCount: Int,
        notStartedCount: Int
    ): Pair<String, String> = when (status) {
        SyncStatus.NO_MEDIA -> "Nothing to back up yet" to
            "Photos and videos on this device will appear here."
        SyncStatus.ALL_BACKED_UP -> "All photos backed up" to
            "Every photo on this device has been backed up."
        SyncStatus.READY -> "Ready to back up" to
            "$notStartedCount ${plural(notStartedCount, "item", "items")} waiting to be added to the queue."
        SyncStatus.WAITING -> "Waiting for backup" to
            "$waitingCount ${plural(waitingCount, "item is", "items are")} queued and waiting to be backed up."
        SyncStatus.IN_PROGRESS -> "Backup in progress" to
            "$activeCount ${plural(activeCount, "item is", "items are")} being backed up."
        SyncStatus.PAUSED -> "Backup paused" to
            "$waitingCount queued ${plural(waitingCount, "item", "items")} will continue when you resume."
        SyncStatus.FAILED -> "Backup needs attention" to
            "$failedCount ${plural(failedCount, "item", "items")} could not be backed up."
    }

    private fun plural(count: Int, singular: String, plural: String): String =
        if (count == 1) singular else plural

    companion object {
        fun factory(
            repository: MediaRepository,
            syncRepository: SyncRepository,
            backupRepository: BackupRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SyncViewModel(repository, syncRepository, backupRepository) as T
                }
            }
    }
}
