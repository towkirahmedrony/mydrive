package com.mydrive.app.ui.album

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.album.AlbumMutationResult
import com.mydrive.app.data.album.GalleryAlbumCatalog
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

enum class AlbumSheet {
    HIDDEN,
    RENAME,
    DELETE,
    ADD_MEDIA,
    MOVE_TO_ALBUM,
    REMOVE_CONFIRM
}

data class AlbumDetailUiState(
    val album: AlbumFolder?,
    val groups: List<MediaGroup>,
    val query: String = "",
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val bulkProgress: BulkActionProgress = BulkActionProgress(),
    val isUserAlbum: Boolean = false,
    val sheet: AlbumSheet = AlbumSheet.HIDDEN,
    val renameValue: String = "",
    val renameError: String? = null,
    val pickerQuery: String = "",
    val pickerItems: List<MediaItem> = emptyList(),
    val pickerSelectedIds: Set<String> = emptySet(),
    val destinationAlbums: List<AlbumFolder> = emptyList(),
    val userMessage: String? = null,
    val albumDeleted: Boolean = false
)

class AlbumDetailViewModel(
    private val repository: MediaRepository,
    private val albumId: String,
    app: Application
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sheet = MutableStateFlow(AlbumSheet.HIDDEN)
    private val renameValue = MutableStateFlow("")
    private val renameError = MutableStateFlow<String?>(null)
    private val pickerQuery = MutableStateFlow("")
    private val pickerSelectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val userMessage = MutableStateFlow<String?>(null)
    private val albumDeleted = MutableStateFlow(false)

    val selection = GallerySelectionController(
        repository = repository,
        app = app,
        scope = viewModelScope,
        visibleItems = { currentVisibleItems() }
    )

    init {
        viewModelScope.launch {
            combine(repository.media, repository.albums, query) { _, _, _ -> currentVisibleItems() }
                .collect { selection.published(it) }
        }
    }

    val uiState: StateFlow<AlbumDetailUiState> = combine(
        combine(
            repository.media,
            repository.albums,
            repository.loadState,
            query
        ) { _, albums, load, currentQuery ->
            val album = albums.firstOrNull { it.id == albumId } ?: repository.albumById(albumId)
            val items = albumItems(currentQuery)
            val groups = MediaLibraryPaging.groupChronologically(items) { dateGroupLabel(it) }
                .map { (label, grouped) -> MediaGroup(label, grouped) }
            AlbumBase(
                album = album,
                groups = groups,
                query = currentQuery,
                isLoadingMore = load.isLoadingMore,
                hasNextPage = load.hasNextPage,
                destinationAlbums = albums.filter { it.isUserAlbum && it.id != albumId }
            )
        },
        combine(selection.selection, selection.progress, sheet, renameValue) { rawSelection, progress, currentSheet, rename ->
            SelectionSlice(rawSelection, progress, currentSheet, rename)
        },
        combine(renameError, pickerQuery, pickerSelectedIds, userMessage, albumDeleted) { error, picker, selected, message, deleted ->
            DialogSlice(error, picker, selected, message, deleted)
        }
    ) { base, selectionSlice, dialog ->
        val pruned = MediaSelection.intersectVisible(
            selectionSlice.selection,
            MediaSelection.eligibleIdSet(base.groups.flatMap { it.items })
        )
        val pickerItems = if (selectionSlice.sheet == AlbumSheet.ADD_MEDIA) {
            repository.eligibleMediaForAlbum(albumId).filter {
                dialog.pickerQuery.isBlank() || it.filename.contains(dialog.pickerQuery, ignoreCase = true)
            }
        } else {
            emptyList()
        }
        AlbumDetailUiState(
            album = base.album,
            groups = base.groups,
            query = base.query,
            isLoadingMore = base.isLoadingMore,
            hasNextPage = base.hasNextPage,
            selectedIds = pruned.selectedIds,
            selectionMode = pruned.mode,
            bulkProgress = selectionSlice.progress,
            isUserAlbum = base.album?.isUserAlbum == true,
            sheet = selectionSlice.sheet,
            renameValue = selectionSlice.rename,
            renameError = dialog.renameError,
            pickerQuery = dialog.pickerQuery,
            pickerItems = pickerItems,
            pickerSelectedIds = dialog.pickerSelectedIds,
            destinationAlbums = base.destinationAlbums,
            userMessage = dialog.userMessage,
            albumDeleted = dialog.albumDeleted
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumDetailUiState(
            album = repository.albumById(albumId),
            groups = emptyList(),
            hasNextPage = repository.loadState.value.hasNextPage,
            isUserAlbum = repository.albumById(albumId)?.isUserAlbum == true
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

    fun openRename() {
        renameValue.value = uiState.value.album?.name.orEmpty()
        renameError.value = null
        sheet.value = AlbumSheet.RENAME
    }

    fun setRenameValue(value: String) {
        renameValue.value = value
        renameError.value = null
    }

    fun confirmRename() {
        val result = repository.renameAlbum(albumId, renameValue.value)
        val error = GalleryAlbumCatalog.nameErrorMessage(result)
        if (error != null) {
            renameError.value = error
            return
        }
        sheet.value = AlbumSheet.HIDDEN
    }

    fun openDelete() {
        sheet.value = AlbumSheet.DELETE
    }

    fun confirmDeleteAlbum() {
        val result = repository.deleteAlbum(albumId)
        if (result is AlbumMutationResult.Deleted) {
            albumDeleted.value = true
        }
        sheet.value = AlbumSheet.HIDDEN
    }

    fun openAddMedia() {
        pickerQuery.value = ""
        pickerSelectedIds.value = emptySet()
        sheet.value = AlbumSheet.ADD_MEDIA
    }

    fun setPickerQuery(value: String) {
        pickerQuery.value = value
    }

    fun togglePickerItem(id: String) {
        pickerSelectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun confirmAddMedia() {
        val ids = pickerSelectedIds.value
        sheet.value = AlbumSheet.HIDDEN
        pickerSelectedIds.value = emptySet()
        if (ids.isEmpty()) return
        val result = repository.addMediaToAlbum(albumId, ids)
        val added = (result as? AlbumMutationResult.MembersChanged)?.added ?: 0
        if (added > 0) userMessage.value = GalleryAlbumCatalog.addToAlbumMessage(added)
    }

    fun requestRemoveSelected() {
        if (selection.selection.value.selectedIds.isEmpty()) return
        sheet.value = AlbumSheet.REMOVE_CONFIRM
    }

    fun confirmRemoveSelected() {
        val ids = selection.selection.value.selectedIds.toList()
        sheet.value = AlbumSheet.HIDDEN
        if (ids.isEmpty()) return
        val result = repository.removeMediaFromAlbum(albumId, ids)
        val removed = (result as? AlbumMutationResult.MembersChanged)?.removed ?: 0
        if (removed > 0) {
            selection.clearSelection()
            userMessage.value = GalleryAlbumCatalog.removeFromAlbumMessage(removed to 0)
        }
    }

    fun requestMoveToAlbum() {
        if (selection.selection.value.selectedIds.isEmpty()) return
        sheet.value = AlbumSheet.MOVE_TO_ALBUM
    }

    fun confirmMoveToAlbum(destinationId: String) {
        val ids = selection.selection.value.selectedIds.toList()
        sheet.value = AlbumSheet.HIDDEN
        if (ids.isEmpty()) return
        val result = repository.moveMediaToAlbum(albumId, destinationId, ids)
        val added = (result as? AlbumMutationResult.MembersChanged)?.added ?: 0
        if (added > 0) {
            selection.clearSelection()
            userMessage.value = GalleryAlbumCatalog.moveToAlbumMessage(added)
        }
    }

    fun setCover(mediaId: String) {
        val result = repository.setAlbumCover(albumId, mediaId)
        if (result is AlbumMutationResult.CoverChanged) {
            selection.clearSelection()
            userMessage.value = "Album cover updated"
        }
    }

    fun dismissSheet() {
        sheet.value = AlbumSheet.HIDDEN
        pickerSelectedIds.value = emptySet()
    }

    fun consumeUserMessage() {
        userMessage.value = null
    }

    private fun currentVisibleItems(): List<MediaItem> =
        albumItems(query.value)

    private fun albumItems(currentQuery: String): List<MediaItem> =
        repository.mediaForAlbum(albumId)
            .filter { currentQuery.isBlank() || it.filename.contains(currentQuery, ignoreCase = true) }

    private data class AlbumBase(
        val album: AlbumFolder?,
        val groups: List<MediaGroup>,
        val query: String,
        val isLoadingMore: Boolean,
        val hasNextPage: Boolean,
        val destinationAlbums: List<AlbumFolder>
    )

    private data class SelectionSlice(
        val selection: com.mydrive.app.ui.selection.MediaSelectionState,
        val progress: BulkActionProgress,
        val sheet: AlbumSheet,
        val rename: String
    )

    private data class DialogSlice(
        val renameError: String?,
        val pickerQuery: String,
        val pickerSelectedIds: Set<String>,
        val userMessage: String?,
        val albumDeleted: Boolean
    )

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
