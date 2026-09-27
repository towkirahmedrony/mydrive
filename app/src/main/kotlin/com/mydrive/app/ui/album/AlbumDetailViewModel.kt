package com.mydrive.app.ui.album

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.media.MediaLibraryPaging
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.gallery.MediaGroup
import com.mydrive.app.ui.selection.BulkActionProgress
import com.mydrive.app.ui.selection.GallerySelectionController
import com.mydrive.app.ui.selection.MediaSelection
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

data class AlbumDetailUiState(
    val album: AlbumFolder?,
    val groups: List<MediaGroup>,
    val query: String = "",
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val bulkProgress: BulkActionProgress = BulkActionProgress()
)

class AlbumDetailViewModel(
    private val repository: MediaRepository,
    private val albumId: String,
    app: Application
) : ViewModel() {

    private val query = MutableStateFlow("")

    val selection = GallerySelectionController(
        repository = repository,
        app = app,
        scope = viewModelScope,
        visibleItems = { currentVisibleItems() }
    )

    init {
        viewModelScope.launch {
            combine(repository.media, query) { _, _ -> currentVisibleItems() }
                .collect { selection.published(it) }
        }
    }

    val uiState: StateFlow<AlbumDetailUiState> = combine(
        combine(repository.media, repository.loadState, query) { media, load, currentQuery ->
            val items = albumItems(media, currentQuery)
            val groups = MediaLibraryPaging.groupChronologically(items) { dateGroupLabel(it) }
                .map { (label, grouped) -> MediaGroup(label, grouped) }
            AlbumDetailUiState(
                album = repository.albumById(albumId),
                groups = groups,
                query = currentQuery,
                isLoadingMore = load.isLoadingMore,
                hasNextPage = load.hasNextPage
            )
        },
        selection.selection,
        selection.progress
    ) { base, rawSelection, progress ->
        val pruned = MediaSelection.intersectVisible(
            rawSelection,
            MediaSelection.eligibleIdSet(base.groups.flatMap { it.items })
        )
        base.copy(
            selectedIds = pruned.selectedIds,
            selectionMode = pruned.mode,
            bulkProgress = progress
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumDetailUiState(
            album = repository.albumById(albumId),
            groups = emptyList(),
            hasNextPage = repository.loadState.value.hasNextPage
        )
    )

    fun setQuery(value: String) {
        query.update { value }
    }

    fun loadMore() {
        val state = uiState.value
        if (!state.hasNextPage || state.isLoadingMore) return
        viewModelScope.launch {
            repository.appendNextPage()
        }
    }

    fun visibleItemIds(): List<String> {
        return uiState.value.groups.flatMap { group -> group.items.map { it.id } }
    }

    private fun currentVisibleItems(): List<MediaItem> =
        albumItems(repository.media.value, query.value)

    private fun albumItems(media: List<MediaItem>, currentQuery: String): List<MediaItem> = media
        .filter { it.albumId == albumId }
        .filter { currentQuery.isBlank() || it.filename.contains(currentQuery, ignoreCase = true) }

    companion object {
        fun factory(repository: MediaRepository, albumId: String, app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AlbumDetailViewModel(repository, albumId, app) as T
                }
            }
    }
}
