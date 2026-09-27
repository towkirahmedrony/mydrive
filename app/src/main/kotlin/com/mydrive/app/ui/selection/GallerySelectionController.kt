package com.mydrive.app.ui.selection

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.data.repository.RemoveFromLibraryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryDeleteConfirmation(
    val intentSender: IntentSender,
    val alreadyPerformedOnApproval: Boolean,
    val currentId: String,
    val remainingIds: List<String>
)

data class GalleryManageMediaRequest(
    val currentId: String,
    val remainingIds: List<String>
)

sealed class GallerySelectionSheet {
    data object Hidden : GallerySelectionSheet()
    data class TrashConfirm(val ids: List<String>) : GallerySelectionSheet()
}

/**
 * Shared Photos/Albums multi-select coordinator.
 *
 * Tracks selection by stable media id and routes bulk Delete/Move through the
 * existing repository trash and move architecture. One instance per screen.
 */
class GallerySelectionController(
    private val repository: MediaRepository,
    app: Application,
    private val scope: CoroutineScope,
    private val visibleItems: () -> List<MediaItem>
) {
    private val context: Context = app.applicationContext

    private val _selection = MutableStateFlow(MediaSelectionState())
    val selection: StateFlow<MediaSelectionState> = _selection

    private val _progress = MutableStateFlow(BulkActionProgress())
    val progress: StateFlow<BulkActionProgress> = _progress

    private val _sheet = MutableStateFlow<GallerySelectionSheet>(GallerySelectionSheet.Hidden)
    val sheet: StateFlow<GallerySelectionSheet> = _sheet

    private val _confirmation = MutableStateFlow<GalleryDeleteConfirmation?>(null)
    val confirmation: StateFlow<GalleryDeleteConfirmation?> = _confirmation

    private val _manageMedia = MutableStateFlow<GalleryManageMediaRequest?>(null)
    val manageMedia: StateFlow<GalleryManageMediaRequest?> = _manageMedia

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage

    private val _pendingMove = MutableStateFlow(false)
    val pendingMove: StateFlow<Boolean> = _pendingMove

    private var manageMediaRetryId: String? = null
    private var trashCounts = BulkItemCounts()
    private var trashSucceeded = ArrayList<String>()

    fun published(visible: List<MediaItem>): MediaSelectionState {
        val eligible = MediaSelection.eligibleIdSet(visible)
        val next = MediaSelection.intersectVisible(_selection.value, eligible)
        if (next != _selection.value) _selection.value = next
        return next
    }

    fun onItemClick(id: String, openViewer: (String) -> Unit) {
        if (_selection.value.mode) {
            toggle(id)
        } else {
            openViewer(id)
        }
    }

    fun onItemLongClick(id: String) {
        val eligible = MediaSelection.eligibleIdSet(visibleItems())
        _selection.value = if (_selection.value.mode) {
            MediaSelection.toggle(_selection.value, id, eligible)
        } else {
            MediaSelection.enter(id, eligible)
        }
    }

    fun toggle(id: String) {
        val eligible = MediaSelection.eligibleIdSet(visibleItems())
        _selection.value = MediaSelection.toggle(_selection.value, id, eligible)
    }

    fun selectAll() {
        _selection.value = MediaSelection.selectAll(MediaSelection.eligibleIds(visibleItems()))
    }

    fun clearSelection() {
        _selection.value = MediaSelection.exit()
        _sheet.value = GallerySelectionSheet.Hidden
        _pendingMove.value = false
    }

    fun requestDeleteSelected() {
        val ids = _selection.value.selectedIds.toList()
        if (ids.isEmpty() || _progress.value.inProgress) return
        _sheet.value = GallerySelectionSheet.TrashConfirm(ids)
    }

    fun dismissSheet() {
        _sheet.value = GallerySelectionSheet.Hidden
    }

    fun confirmDeleteSelected() {
        val ids = when (val sheet = _sheet.value) {
            is GallerySelectionSheet.TrashConfirm -> sheet.ids
            GallerySelectionSheet.Hidden -> emptyList()
        }
        _sheet.value = GallerySelectionSheet.Hidden
        if (ids.isEmpty()) return
        trashCounts = BulkItemCounts()
        trashSucceeded = ArrayList()
        manageMediaRetryId = null
        runTrash(ids)
    }

    fun requestMoveSelected() {
        if (_selection.value.selectedIds.isEmpty() || _progress.value.inProgress) return
        _pendingMove.value = true
    }

    fun cancelPendingMove() {
        _pendingMove.value = false
    }

    fun moveToDestination(destUri: Uri) {
        _pendingMove.value = false
        val ids = _selection.value.selectedIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            val succeededIds = ArrayList<String>()
            var failed = 0
            withContext(Dispatchers.IO) {
                ids.forEachIndexed { index, id ->
                    _progress.value = BulkActionProgress(
                        inProgress = true,
                        kind = BulkActionKind.MOVE,
                        processed = index,
                        total = ids.size
                    )
                    val ok = repository.moveMedia(context, id, destUri, refreshAfter = false)
                    if (ok) succeededIds.add(id) else failed++
                }
                repository.refresh(force = true)
            }
            val counts = BulkItemCounts(succeeded = succeededIds.size, failed = failed)
            _progress.value = BulkActionProgress()
            if (succeededIds.isNotEmpty()) {
                _selection.value = MediaSelection.minus(_selection.value, succeededIds)
            }
            if (_selection.value.selectedIds.isEmpty()) clearSelection()
            _userMessage.value = MediaSelection.moveResultMessage(counts)
        }
    }

    fun onSystemConfirmationResult(approved: Boolean) {
        val request = _confirmation.value ?: return
        _confirmation.value = null
        if (!approved) {
            trashCounts = recordTrashFailure(trashCounts)
            finishOrContinue(skipId = request.currentId, remaining = request.remainingIds)
            return
        }
        if (request.alreadyPerformedOnApproval) {
            scope.launch {
                val snapshot = repository.mediaById(request.currentId)
                val finalized = repository.finalizeLocalDelete(request.currentId, refreshAfter = false)
                if (finalized) {
                    repository.moveCloudToTrash(request.currentId, snapshot, refreshAfter = false)
                    trashCounts = recordTrashSuccess(trashCounts, RemoveFromLibraryResult.Success)
                    trashSucceeded.add(request.currentId)
                } else {
                    trashCounts = recordTrashFailure(trashCounts)
                }
                finishOrContinue(skipId = request.currentId, remaining = request.remainingIds)
            }
        } else {
            runTrash(listOf(request.currentId) + request.remainingIds)
        }
    }

    fun manageMediaIntent() = repository.manageMediaRequestIntent()

    fun onManageMediaAccessResult() {
        val request = _manageMedia.value ?: return
        _manageMedia.value = null
        repository.markManageMediaAsked()
        if (repository.canManageMedia()) {
            runTrash(listOf(request.currentId) + request.remainingIds)
        } else {
            trashCounts = recordTrashFailure(trashCounts)
            finishOrContinue(skipId = request.currentId, remaining = request.remainingIds)
        }
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    private fun runTrash(ids: List<String>) {
        if (ids.isEmpty()) {
            completeTrash()
            return
        }
        scope.launch { processTrashQueue(ids) }
    }

    private suspend fun processTrashQueue(ids: List<String>) {
        var remaining = ids
        while (remaining.isNotEmpty()) {
            val id = remaining.first()
            val rest = remaining.drop(1)
            val processed = trashCounts.total
            val total = processed + remaining.size
            _progress.value = BulkActionProgress(
                inProgress = true,
                kind = BulkActionKind.DELETE,
                processed = processed,
                total = total
            )
            val item = visibleItems().firstOrNull { it.id == id } ?: repository.mediaById(id)
            when (trashAttemptFor(item)) {
                TrashAttempt.CLOUD_ONLY -> {
                    val cloud = repository.moveCloudToTrash(id, item, refreshAfter = false)
                    trashCounts = recordTrashSuccess(trashCounts, cloud)
                    trashSucceeded.add(id)
                    remaining = rest
                }
                TrashAttempt.LOCAL_THEN_CLOUD -> {
                    when (val decision = interpretLocalTrash(repository.deleteMediaWithResult(context, id))) {
                        LocalTrashDecision.Finalize -> {
                            val snapshot = item ?: repository.mediaById(id)
                            repository.finalizeLocalDelete(id, refreshAfter = false)
                            val cloud = repository.moveCloudToTrash(id, snapshot, refreshAfter = false)
                            trashCounts = recordTrashSuccess(trashCounts, cloud)
                            trashSucceeded.add(id)
                            remaining = rest
                        }
                        LocalTrashDecision.CloudOnly -> {
                            val cloud = repository.moveCloudToTrash(id, item, refreshAfter = false)
                            trashCounts = recordTrashSuccess(trashCounts, cloud)
                            trashSucceeded.add(id)
                            remaining = rest
                        }
                        is LocalTrashDecision.PauseSystem -> {
                            _confirmation.value = GalleryDeleteConfirmation(
                                intentSender = decision.intentSender,
                                alreadyPerformedOnApproval = decision.alreadyPerformedOnApproval,
                                currentId = id,
                                remainingIds = rest
                            )
                            return
                        }
                        LocalTrashDecision.PauseManageMedia -> {
                            if (manageMediaRetryId == id) {
                                manageMediaRetryId = null
                                trashCounts = recordTrashFailure(trashCounts)
                                remaining = rest
                            } else {
                                manageMediaRetryId = id
                                _manageMedia.value = GalleryManageMediaRequest(id, rest)
                                return
                            }
                        }
                        LocalTrashDecision.Fail -> {
                            trashCounts = recordTrashFailure(trashCounts)
                            remaining = rest
                        }
                    }
                }
            }
        }
        completeTrash()
    }

    private fun finishOrContinue(skipId: String, remaining: List<String>) {
        _selection.value = MediaSelection.minus(_selection.value, listOf(skipId))
        if (remaining.isEmpty()) {
            completeTrash()
        } else {
            runTrash(remaining)
        }
    }

    private fun completeTrash() {
        scope.launch {
            repository.refresh(force = true)
            _progress.value = BulkActionProgress()
            if (trashSucceeded.isNotEmpty()) {
                _selection.value = MediaSelection.minus(_selection.value, trashSucceeded)
            }
            if (_selection.value.selectedIds.isEmpty()) {
                _selection.value = MediaSelection.exit()
            }
            _userMessage.value = MediaSelection.trashResultMessage(trashCounts)
            trashCounts = BulkItemCounts()
            trashSucceeded = ArrayList()
            manageMediaRetryId = null
        }
    }
}
