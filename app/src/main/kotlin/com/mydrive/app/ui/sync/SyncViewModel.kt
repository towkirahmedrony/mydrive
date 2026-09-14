package com.mydrive.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.repository.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncUiState(
    val headline: String,
    val inProgress: List<MediaItem>,
    val waiting: List<MediaItem>,
    val completed: List<MediaItem>,
    val failed: List<MediaItem>,
    val notStartedCount: Int,
    val completedCount: Int,
    val totalMediaCount: Int
)

class SyncViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    init {
        viewModelScope.launch { repository.refresh(force = false) }
    }

    val uiState: StateFlow<SyncUiState> = repository.media
        .map { media ->
            val inProgress = media.filter {
                it.backupState == BackupState.UPLOADING ||
                    it.backupState == BackupState.PROCESSING ||
                    it.backupState == BackupState.SENDING_TELEGRAM
            }
            val waiting = media.filter { it.backupState == BackupState.WAITING }
            val failed = media.filter { it.backupState == BackupState.FAILED }
            val completed = media.filter { it.backupState == BackupState.COMPLETED }.take(8)
            val completedCount = media.count { it.backupState == BackupState.COMPLETED }
            val notStartedCount = media.count { it.backupState == BackupState.NOT_STARTED }
            val active = inProgress.size + waiting.size
            val headline = when {
                failed.isNotEmpty() && active > 0 -> "$active items syncing · ${failed.size} failed"
                failed.isNotEmpty() -> "${failed.size} ${if (failed.size == 1) "item" else "items"} failed"
                active > 0 -> "$active ${if (active == 1) "item" else "items"} syncing"
                notStartedCount > 0 -> "$notStartedCount ${if (notStartedCount == 1) "item is" else "items are"} ready to back up"
                else -> "Everything is up to date"
            }
            SyncUiState(
                headline = headline,
                inProgress = inProgress,
                waiting = waiting,
                completed = completed,
                failed = failed,
                notStartedCount = notStartedCount,
                completedCount = completedCount,
                totalMediaCount = media.size
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SyncUiState(
                headline = "Everything is up to date",
                inProgress = emptyList(),
                waiting = emptyList(),
                completed = emptyList(),
                failed = emptyList(),
                notStartedCount = 0,
                completedCount = 0,
                totalMediaCount = 0
            )
        )

    fun startSync() {
        viewModelScope.launch {
            repository.refresh(force = false)
            repository.queueForBackup()
        }
    }

    fun retry(id: String) {
        repository.retryBackup(id)
    }

    fun retryFailed() {
        repository.retryFailed()
    }

    fun visibleItemIds(): List<String> {
        val state = uiState.value
        return (state.inProgress + state.waiting + state.completed + state.failed)
            .map { it.id }
            .distinct()
    }

    companion object {
        fun factory(repository: MediaRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SyncViewModel(repository) as T
                }
            }
    }
}
