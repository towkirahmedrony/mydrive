package com.mydrive.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.components.compactBackupLabel
import com.mydrive.app.ui.util.dateGroupLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class GalleryFilter {
    ALL, PHOTOS, VIDEOS, FAVORITES
}

data class MediaGroup(
    val label: String,
    val items: List<MediaItem>
)

data class GalleryUiState(
    val filter: GalleryFilter,
    val query: String,
    val groups: List<MediaGroup>,
    val albums: List<AlbumFolder>,
    val syncingCount: Int,
    val failedCount: Int
)

class GalleryViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val filter = MutableStateFlow(GalleryFilter.ALL)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<GalleryUiState> = combine(
        repository.media,
        filter,
        query
    ) { media, currentFilter, currentQuery ->
        val filtered = media
            .filter { item ->
                when (currentFilter) {
                    GalleryFilter.ALL -> true
                    GalleryFilter.PHOTOS -> item.type == MediaType.PHOTO
                    GalleryFilter.VIDEOS -> item.type == MediaType.VIDEO
                    GalleryFilter.FAVORITES -> item.isFavorite
                }
            }
            .filter { item ->
                currentQuery.isBlank() || item.filename.contains(currentQuery, ignoreCase = true)
            }
            .sortedByDescending { it.capturedAtMillis }

        val groups = filtered
            .groupBy { dateGroupLabel(it.capturedAtMillis) }
            .map { (label, items) -> MediaGroup(label, items) }

        val (syncing, failed) = compactBackupLabel(media)

        GalleryUiState(
            filter = currentFilter,
            query = currentQuery,
            groups = groups,
            albums = if (currentFilter == GalleryFilter.ALL && currentQuery.isBlank()) {
                repository.albums()
            } else {
                emptyList()
            },
            syncingCount = syncing,
            failedCount = failed
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryUiState(GalleryFilter.ALL, "", emptyList(), emptyList(), 0, 0)
    )

    fun setFilter(value: GalleryFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.update { value }
    }

    companion object {
        fun factory(repository: MediaRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return GalleryViewModel(repository) as T
                }
            }
    }
}
