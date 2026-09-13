package com.mydrive.app.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.ui.gallery.MediaGroup
import com.mydrive.app.ui.util.dateGroupLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class AlbumDetailUiState(
    val album: AlbumFolder?,
    val groups: List<MediaGroup>,
    val query: String = ""
)

class AlbumDetailViewModel(
    private val repository: MediaRepository,
    private val albumId: String
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<AlbumDetailUiState> = combine(
        repository.media,
        query
    ) { media, currentQuery ->
        val items = media
            .filter { it.albumId == albumId }
            .filter { currentQuery.isBlank() || it.filename.contains(currentQuery, ignoreCase = true) }
            .sortedByDescending { it.capturedAtMillis }
        val groups = items
            .groupBy { dateGroupLabel(it.capturedAtMillis) }
            .map { (label, grouped) -> MediaGroup(label, grouped) }
        AlbumDetailUiState(
            album = repository.albumById(albumId),
            groups = groups,
            query = currentQuery
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumDetailUiState(repository.albumById(albumId), emptyList())
    )

    fun setQuery(value: String) {
        query.update { value }
    }

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
