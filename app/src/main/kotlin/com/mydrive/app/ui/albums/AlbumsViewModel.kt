package com.mydrive.app.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.album.AlbumMutationResult
import com.mydrive.app.data.album.AlbumSort
import com.mydrive.app.data.album.GalleryAlbumCatalog
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumsUiState(
    val query: String,
    val albums: List<AlbumFolder>,
    val isLoading: Boolean = false,
    /** The persisted folders are being read; nothing is known yet, so no spinner. */
    val isRestoring: Boolean = false,
    /** A background reconciliation is running while the folders stay on screen. */
    val isRefreshing: Boolean = false,
    val needsPermission: Boolean = false,
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
    val hasAlbums: Boolean = false,
    val trashCount: Int = 0,
    val trashSizeBytes: Long = 0L,
    val sort: AlbumSort = AlbumSort.RECENTLY_UPDATED,
    val showCreateDialog: Boolean = false,
    val createName: String = "",
    val createError: String? = null
)

class AlbumsViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(AlbumSort.RECENTLY_UPDATED)
    private val createDialog = MutableStateFlow(CreateAlbumDialogState())

    init {
        refresh(force = false)
    }

    val uiState: StateFlow<AlbumsUiState> = combine(
        combine(repository.albums, repository.loadState, repository.trashSummary, query) { albums, load, trash, currentQuery ->
            AlbumQuerySlice(albums, load, trash, currentQuery)
        },
        sort,
        createDialog
    ) { slice, currentSort, dialog ->
        val filtered = slice.albums.filter { album ->
            slice.query.isBlank() || album.name.contains(slice.query, ignoreCase = true)
        }
        val sorted = GalleryAlbumCatalog.sortAlbums(filtered, currentSort)
        AlbumsUiState(
            query = slice.query,
            albums = sorted,
            isLoading = slice.load.isLoading,
            isRestoring = slice.load.isRestoring,
            isRefreshing = slice.load.isRefreshing,
            needsPermission = slice.load.needsPermission,
            permissionDenied = slice.load.permissionDenied,
            errorMessage = slice.load.errorMessage,
            hasAlbums = sorted.isNotEmpty() || slice.query.isNotBlank(),
            trashCount = slice.trash.count,
            trashSizeBytes = slice.trash.totalSizeBytes,
            sort = currentSort,
            showCreateDialog = dialog.visible,
            createName = dialog.name,
            createError = dialog.error
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumsUiState(
            query = "",
            albums = emptyList(),
            isLoading = repository.loadState.value.isLoading,
            isRestoring = repository.loadState.value.isRestoring,
            isRefreshing = repository.loadState.value.isRefreshing,
            needsPermission = repository.loadState.value.needsPermission,
            permissionDenied = repository.loadState.value.permissionDenied,
            trashCount = repository.trashSummary.value.count,
            trashSizeBytes = repository.trashSummary.value.totalSizeBytes
        )
    )

    fun setQuery(value: String) {
        query.update { value }
    }

    fun setSort(value: AlbumSort) {
        sort.value = value
    }

    fun openCreateAlbum() {
        createDialog.value = CreateAlbumDialogState(visible = true)
    }

    fun dismissCreateAlbum() {
        createDialog.value = CreateAlbumDialogState()
    }

    fun setCreateName(value: String) {
        createDialog.update { it.copy(name = value, error = null) }
    }

    fun confirmCreateAlbum(): String? {
        val result = repository.createAlbum(createDialog.value.name)
        val error = GalleryAlbumCatalog.nameErrorMessage(result)
        if (error != null) {
            createDialog.update { it.copy(error = error) }
            return null
        }
        val created = result as? AlbumMutationResult.Created
        createDialog.value = CreateAlbumDialogState()
        return created?.albumId
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

    private data class CreateAlbumDialogState(
        val visible: Boolean = false,
        val name: String = "",
        val error: String? = null
    )

    private data class AlbumQuerySlice(
        val albums: List<AlbumFolder>,
        val load: com.mydrive.app.data.model.MediaLoadState,
        val trash: com.mydrive.app.data.model.TrashSummary,
        val query: String
    )

    companion object {
        fun factory(repository: MediaRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AlbumsViewModel(repository) as T
                }
            }
    }
}
