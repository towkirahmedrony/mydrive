package com.mydrive.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.media.MediaLibraryPaging
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.gallery.MediaGroup
import com.mydrive.app.ui.util.dateGroupLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class FavoritesUiState(
    val groups: List<MediaGroup>
)

class FavoritesViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    val uiState: StateFlow<FavoritesUiState> = repository.media
        .map { media ->
            val favorites = media.filter { it.isFavorite }
            val groups = MediaLibraryPaging.groupChronologically(favorites) { dateGroupLabel(it) }
                .map { (label, items) -> MediaGroup(label, items) }
            FavoritesUiState(groups)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FavoritesUiState(emptyList())
        )

    companion object {
        fun factory(repository: MediaRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FavoritesViewModel(repository) as T
                }
            }
    }
}
