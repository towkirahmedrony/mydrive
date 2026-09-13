package com.mydrive.app.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.repository.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MediaViewerUiState(
    val items: List<MediaItem>,
    val initialIndex: Int
)

class MediaViewerViewModel(
    private val repository: MediaRepository,
    private val mediaId: String
) : ViewModel() {

    val uiState: StateFlow<MediaViewerUiState> = repository.media
        .map { media ->
            val sorted = media.sortedByDescending { it.capturedAtMillis }
            val index = sorted.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            MediaViewerUiState(items = sorted, initialIndex = index)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = run {
                val sorted = repository.media.value.sortedByDescending { it.capturedAtMillis }
                MediaViewerUiState(
                    items = sorted,
                    initialIndex = sorted.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
                )
            }
        )

    fun toggleFavorite(id: String) {
        repository.toggleFavorite(id)
    }

    companion object {
        fun factory(repository: MediaRepository, mediaId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MediaViewerViewModel(repository, mediaId) as T
                }
            }
    }
}
