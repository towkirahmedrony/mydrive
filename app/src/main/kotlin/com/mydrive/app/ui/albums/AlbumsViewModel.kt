package com.mydrive.app.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
    val needsPermission: Boolean = false,
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
    val hasAlbums: Boolean = false,
    val trashCount: Int = 0,
    val trashSizeBytes: Long = 0L
)

class AlbumsViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    init {
        refresh(force = false)
    }

    val uiState: StateFlow<AlbumsUiState> = combine(
        repository.albums,
        repository.loadState,
        repository.trashSummary,
        query
    ) { albums, load, trash, currentQuery ->
        val filtered = albums.filter { album ->
            currentQuery.isBlank() || album.name.contains(currentQuery, ignoreCase = true)
        }
        AlbumsUiState(
            query = currentQuery,
            albums = filtered,
            isLoading = load.isLoading,
            needsPermission = load.needsPermission,
            permissionDenied = load.permissionDenied,
            errorMessage = load.errorMessage,
            hasAlbums = filtered.isNotEmpty() || currentQuery.isNotBlank(),
            trashCount = trash.count,
            trashSizeBytes = trash.totalSizeBytes
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumsUiState(
            query = "",
            albums = emptyList(),
            isLoading = repository.loadState.value.isLoading,
            needsPermission = repository.loadState.value.needsPermission,
            permissionDenied = repository.loadState.value.permissionDenied,
            trashCount = repository.trashSummary.value.count,
            trashSizeBytes = repository.trashSummary.value.totalSizeBytes
        )
    )

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
