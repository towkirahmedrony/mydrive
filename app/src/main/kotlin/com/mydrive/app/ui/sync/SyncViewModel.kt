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

data class SyncUiState(
    val headline: String,
    val inProgress: List<MediaItem>,
    val waiting: List<MediaItem>,
    val completed: List<MediaItem>,
    val failed: List<MediaItem>
)

class SyncViewModel(
    private val repository: MediaRepository
) : ViewModel() {

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
            val active = inProgress.size + waiting.size
            val headline = when {
                failed.isNotEmpty() && active > 0 -> "$active items syncing · ${failed.size} failed"
                failed.isNotEmpty() -> "${failed.size} ${if (failed.size == 1) "item" else "items"} failed"
                active > 0 -> "$active ${if (active == 1) "item" else "items"} syncing"
                else -> "Everything is up to date"
            }
            SyncUiState(
                headline = headline,
                inProgress = inProgress,
                waiting = waiting,
                completed = completed,
                failed = failed
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
                failed = emptyList()
            )
        )

    fun retry(id: String) {
        repository.retryBackup(id)
    }

    fun retryFailed() {
        repository.retryFailed()
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
