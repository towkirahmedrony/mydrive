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
    private val mediaId: String,
    private val albumId: String?
) : ViewModel() {

    private val snapshot: List<MediaItem> = repository.mediaForViewer(mediaId, albumId)

    val uiState: StateFlow<MediaViewerUiState> = repository.media
        .map { media ->
            val byId = media.associateBy { it.id }
            val items = snapshot.map { original ->
                val live = byId[original.id]
                live ?: original
            }
            MediaViewerUiState(
                items = items,
                initialIndex = items.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MediaViewerUiState(
                items = snapshot,
                initialIndex = snapshot.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            )
        )

    fun toggleFavorite(id: String) {
        repository.toggleFavorite(id)
    }

    companion object {
        fun factory(
            repository: MediaRepository,
            mediaId: String,
            albumId: String?
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MediaViewerViewModel(repository, mediaId, albumId) as T
                }
            }
    }
}
