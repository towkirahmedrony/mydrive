package com.mydrive.app.ui.trash

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.data.repository.TrashMutationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrashViewerUiState(
    val items: List<MediaItem>,
    val initialIndex: Int
)

data class TrashViewerConfirmation(
    val intentSender: IntentSender,
    val restore: Boolean,
    val itemId: String
)

sealed class TrashViewerSheet {
    data object Hidden : TrashViewerSheet()
    data class PermanentDelete(val itemId: String) : TrashViewerSheet()
}

class TrashViewerViewModel(
    private val repository: MediaRepository,
    private val mediaId: String,
    app: Application
) : ViewModel() {

    private val context: Context = app.applicationContext
    private val snapshot: List<MediaItem> = repository.mediaForTrashViewer(mediaId)

    private val _sheet = MutableStateFlow<TrashViewerSheet>(TrashViewerSheet.Hidden)
    val sheet: StateFlow<TrashViewerSheet> = _sheet
    private val _confirmation = MutableStateFlow<TrashViewerConfirmation?>(null)
    val confirmation: StateFlow<TrashViewerConfirmation?> = _confirmation
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage
    private var pendingClose: (() -> Unit)? = null

    val uiState: StateFlow<TrashViewerUiState> = repository.trashedMedia
        .map { media ->
            val byId = media.associateBy { it.id }
            val items = snapshot.mapNotNull { original -> byId[original.id] }.ifEmpty { media }
            TrashViewerUiState(
                items = items,
                initialIndex = items.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TrashViewerUiState(
                items = snapshot,
                initialIndex = snapshot.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            )
        )

    fun requestPermanentDelete(itemId: String) {
        _sheet.value = TrashViewerSheet.PermanentDelete(itemId)
    }

    fun dismissSheet() {
        _sheet.value = TrashViewerSheet.Hidden
    }

    fun restore(itemId: String, onRemoved: (Int?) -> Unit) {
        mutate(itemId, restore = true, onRemoved)
    }

    fun confirmPermanentDelete(onRemoved: (Int?) -> Unit) {
        val itemId = ( _sheet.value as? TrashViewerSheet.PermanentDelete)?.itemId ?: return
        _sheet.value = TrashViewerSheet.Hidden
        mutate(itemId, restore = false, onRemoved)
    }

    fun onSystemConfirmationResult(approved: Boolean) {
        val request = _confirmation.value ?: return
        _confirmation.value = null
        if (!approved) {
            pendingClose = null
            return
        }
        viewModelScope.launch {
            if (request.restore) {
                repository.finalizeTrashRestore(listOf(request.itemId))
                _userMessage.value = "Item restored"
            } else {
                repository.finalizePermanentTrashDelete(listOf(request.itemId))
                _userMessage.value = "Item deleted permanently"
            }
            notifyRemoved(request.itemId)
        }
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    private fun mutate(itemId: String, restore: Boolean, onRemoved: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _userMessage.value = "This action is not available on this Android version."
            return
        }
        pendingClose = { onRemoved(nextIndexAfter(itemId)) }
        viewModelScope.launch {
            val result = if (restore) {
                repository.restoreTrashedMedia(context, itemId)
            } else {
                repository.permanentlyDeleteTrashedMedia(context, itemId)
            }
            when (result) {
                TrashMutationResult.Success -> {
                    if (restore) {
                        repository.finalizeTrashRestore(listOf(itemId))
                        _userMessage.value = "Item restored"
                    } else {
                        repository.finalizePermanentTrashDelete(listOf(itemId))
                        _userMessage.value = "Item deleted permanently"
                    }
                    notifyRemoved(itemId)
                }
                is TrashMutationResult.RequiresSystemConfirmation -> {
                    _confirmation.value = TrashViewerConfirmation(result.intentSender, restore, itemId)
                }
                TrashMutationResult.NotFound -> {
                    if (restore) repository.finalizeTrashRestore(listOf(itemId))
                    else repository.finalizePermanentTrashDelete(listOf(itemId))
                    notifyRemoved(itemId)
                }
                TrashMutationResult.PermissionDenied -> {
                    pendingClose = null
                    _userMessage.value = "Android requires permission to modify this item."
                }
                TrashMutationResult.Failed -> {
                    pendingClose = null
                    _userMessage.value = if (restore) "This item could not be restored." else "This item could not be deleted."
                }
                TrashMutationResult.Unsupported -> {
                    pendingClose = null
                    _userMessage.value = "This action is not available on this Android version."
                }
            }
        }
    }

    private fun nextIndexAfter(itemId: String): Int? {
        val remaining = repository.trashedMedia.value.filter { it.id != itemId }
        if (remaining.isEmpty()) return null
        val currentIndex = uiState.value.items.indexOfFirst { it.id == itemId }
        return currentIndex.coerceAtLeast(0).coerceAtMost(remaining.lastIndex)
    }

    private fun notifyRemoved(itemId: String) {
        val close = pendingClose
        pendingClose = null
        close?.invoke() ?: run {
            if (uiState.value.items.none { it.id == itemId } || uiState.value.items.size <= 1) {
                // no-op; UI observes emptied trash
            }
        }
    }

    companion object {
        fun factory(repository: MediaRepository, mediaId: String, app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TrashViewerViewModel(repository, mediaId, app) as T
                }
            }
    }
}
