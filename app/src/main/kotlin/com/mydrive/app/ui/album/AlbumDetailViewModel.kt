package com.mydrive.app.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.gallery.MediaGroup
import com.mydrive.app.ui.util.dateGroupLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AlbumDetailUiState(
    val album: AlbumFolder?,
    val groups: List<MediaGroup>
)

class AlbumDetailViewModel(
    private val repository: MediaRepository,
    private val albumId: String
) : ViewModel() {

    val uiState: StateFlow<AlbumDetailUiState> = repository.media
        .map { media ->
            val items = media
                .filter { it.albumId == albumId }
                .sortedByDescending { it.capturedAtMillis }
            val groups = items
                .groupBy { dateGroupLabel(it.capturedAtMillis) }
                .map { (label, grouped) -> MediaGroup(label, grouped) }
            AlbumDetailUiState(
                album = repository.albumById(albumId),
                groups = groups
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AlbumDetailUiState(repository.albumById(albumId), emptyList())
        )

    companion object {
        fun factory(repository: MediaRepository, albumId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AlbumDetailViewModel(repository, albumId) as T
                }
            }
    }
}
