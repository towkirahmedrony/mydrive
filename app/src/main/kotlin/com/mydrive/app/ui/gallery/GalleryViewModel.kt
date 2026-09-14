package com.mydrive.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.util.dateGroupLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val isLoading: Boolean = false,
    val needsPermission: Boolean = false,
    val permissionDenied: Boolean = false,
    val accessPartial: Boolean = false,
    val errorMessage: String? = null,
    val hasMedia: Boolean = false
)

class GalleryViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val filter = MutableStateFlow(GalleryFilter.ALL)
    private val query = MutableStateFlow("")

    init {
        refresh(force = true)
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        repository.media,
        repository.loadState,
        filter,
        query
    ) { media, load, currentFilter, currentQuery ->
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

        GalleryUiState(
            filter = currentFilter,
            query = currentQuery,
            groups = groups,
            isLoading = load.isLoading,
            needsPermission = load.needsPermission,
            permissionDenied = load.permissionDenied,
            accessPartial = load.accessPartial,
            errorMessage = load.errorMessage,
            hasMedia = media.isNotEmpty()
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryUiState(
            filter = GalleryFilter.ALL,
            query = "",
            groups = emptyList(),
            isLoading = repository.loadState.value.isLoading,
            needsPermission = repository.loadState.value.needsPermission,
            permissionDenied = repository.loadState.value.permissionDenied,
            accessPartial = repository.loadState.value.accessPartial
        )
    )

    fun setFilter(value: GalleryFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.update { value }
    }

    fun permissionPermissions(): Array<String> = repository.requiredPermissions()

    fun onPermissionResult() {
        repository.markPermissionAsked()
        refresh(force = true)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            repository.refresh(force = force)
        }
    }

    fun visibleItemIds(): List<String> {
        return uiState.value.groups.flatMap { group -> group.items.map { it.id } }
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
