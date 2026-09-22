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
import com.mydrive.app.data.repository.TrashOperationProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashUiState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val progress: TrashOperationProgress = TrashOperationProgress()
) {
    val selectedCount: Int get() = selectedIds.size
    val allSelected: Boolean get() = items.isNotEmpty() && selectedIds.size == items.size
}

data class TrashConfirmationRequest(
    val intentSender: IntentSender,
    val restore: Boolean,
    val ids: List<String>
)

sealed class TrashSheet {
    data object Hidden : TrashSheet()
    data class PermanentDelete(val ids: List<String>) : TrashSheet()
    data class EmptyTrash(val count: Int) : TrashSheet()
}

class TrashViewModel(
    private val repository: MediaRepository,
    app: Application
) : ViewModel() {

    private val context: Context = app.applicationContext
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _sheet = MutableStateFlow<TrashSheet>(TrashSheet.Hidden)
    val sheet: StateFlow<TrashSheet> = _sheet
    private val _confirmation = MutableStateFlow<TrashConfirmationRequest?>(null)
    val confirmation: StateFlow<TrashConfirmationRequest?> = _confirmation
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage

    val uiState: StateFlow<TrashUiState> = combine(
        repository.trashedMedia,
        repository.loadState,
        repository.trashProgress,
        selectedIds
    ) { items, load, progress, selected ->
        val visibleSelected = selected.filter { id -> items.any { it.id == id } }.toSet()
        TrashUiState(
            items = items,
            isLoading = load.isLoading && items.isEmpty(),
            selectedIds = visibleSelected,
            selectionMode = visibleSelected.isNotEmpty(),
            progress = progress
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrashUiState(
            items = repository.trashedMedia.value,
            isLoading = repository.loadState.value.isLoading && repository.trashedMedia.value.isEmpty()
        )
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh(force = true) }
    }

    fun onItemClick(id: String, openViewer: (String) -> Unit) {
        if (uiState.value.selectionMode) {
            toggleSelection(id)
        } else {
            openViewer(id)
        }
    }

    fun onItemLongClick(id: String) {
        toggleSelection(id)
    }

    fun toggleSelection(id: String) {
        selectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun selectAll() {
        selectedIds.value = uiState.value.items.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun visibleItemIds(): List<String> = uiState.value.items.map { it.id }

    fun requestEmptyTrash() {
        val count = uiState.value.items.size
        if (count == 0) return
        _sheet.value = TrashSheet.EmptyTrash(count)
    }

    fun requestPermanentDeleteSelected() {
        val ids = uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _sheet.value = TrashSheet.PermanentDelete(ids)
    }

    fun dismissSheet() {
        _sheet.value = TrashSheet.Hidden
    }

    fun restoreSelected() {
        val ids = uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        restore(ids)
    }

    fun confirmPermanentDelete() {
        val ids = when (val sheet = _sheet.value) {
            is TrashSheet.PermanentDelete -> sheet.ids
            is TrashSheet.EmptyTrash -> uiState.value.items.map { it.id }
            TrashSheet.Hidden -> emptyList()
        }
        _sheet.value = TrashSheet.Hidden
        if (ids.isEmpty()) return
        permanentlyDelete(ids)
    }

    fun onSystemConfirmationResult(approved: Boolean) {
        val request = _confirmation.value ?: return
        _confirmation.value = null
        if (!approved) {
            repository.clearTrashProgress()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (request.restore) {
                repository.finalizeTrashRestore(request.ids)
                selectedIds.update { it - request.ids.toSet() }
                _userMessage.value = restoredMessage(request.ids.size)
            } else {
                repository.finalizePermanentTrashDelete(request.ids)
                selectedIds.update { it - request.ids.toSet() }
                _userMessage.value = deletedMessage(request.ids.size)
            }
            repository.clearTrashProgress()
        }
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    private fun restore(ids: List<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _userMessage.value = "Trash restore is not available on this Android version."
            return
        }
        viewModelScope.launch {
            when (val result = repository.restoreTrashedMedia(context, ids)) {
                TrashMutationResult.Success -> {
                    repository.finalizeTrashRestore(ids)
                    selectedIds.update { it - ids.toSet() }
                    _userMessage.value = restoredMessage(ids.size)
                    repository.clearTrashProgress()
                }
                is TrashMutationResult.RequiresSystemConfirmation -> {
                    _confirmation.value = TrashConfirmationRequest(result.intentSender, restore = true, ids = ids)
                }
                TrashMutationResult.NotFound -> {
                    repository.finalizeTrashRestore(ids)
                    selectedIds.update { it - ids.toSet() }
                    _userMessage.value = "This item is no longer in Trash."
                }
                TrashMutationResult.PermissionDenied -> _userMessage.value = "Android requires permission to restore this item."
                TrashMutationResult.Failed -> _userMessage.value = "This item could not be restored."
                TrashMutationResult.Unsupported -> _userMessage.value = "Trash restore is not available on this Android version."
            }
        }
    }

    private fun permanentlyDelete(ids: List<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _userMessage.value = "Permanent delete is not available on this Android version."
            return
        }
        viewModelScope.launch {
            when (val result = repository.permanentlyDeleteTrashedMedia(context, ids)) {
                TrashMutationResult.Success -> {
                    repository.finalizePermanentTrashDelete(ids)
                    selectedIds.update { it - ids.toSet() }
                    _userMessage.value = deletedMessage(ids.size)
                    repository.clearTrashProgress()
                }
                is TrashMutationResult.RequiresSystemConfirmation -> {
                    _confirmation.value = TrashConfirmationRequest(result.intentSender, restore = false, ids = ids)
                }
                TrashMutationResult.NotFound -> {
                    repository.finalizePermanentTrashDelete(ids)
                    selectedIds.update { it - ids.toSet() }
                    _userMessage.value = "This item is no longer in Trash."
                }
                TrashMutationResult.PermissionDenied -> _userMessage.value = "Android requires permission to delete this item."
                TrashMutationResult.Failed -> _userMessage.value = "This item could not be deleted."
                TrashMutationResult.Unsupported -> _userMessage.value = "Permanent delete is not available on this Android version."
            }
        }
    }

    private fun restoredMessage(count: Int): String =
        if (count == 1) "Item restored" else "$count items restored"

    private fun deletedMessage(count: Int): String =
        if (count == 1) "Item deleted permanently" else "$count items deleted permanently"

    companion object {
        fun factory(repository: MediaRepository, app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TrashViewModel(repository, app) as T
                }
            }
    }
}
