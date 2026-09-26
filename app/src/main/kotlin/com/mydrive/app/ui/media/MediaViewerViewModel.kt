package com.mydrive.app.ui.media

import android.app.Application
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.repository.DeleteMediaResult
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.data.repository.RemoveFromLibraryResult
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaViewerUiState(
    val items: List<MediaItem>,
    val initialIndex: Int
)

data class DeleteConfirmationRequest(
    val itemId: String,
    val intentSender: android.content.IntentSender,
    val alreadyPerformedOnApproval: Boolean
)

data class ManageMediaAccessRequest(
    val itemId: String
)

sealed class MediaOperation {
    data object Idle : MediaOperation()
    data class DeleteConfirm(val itemId: String) : MediaOperation()
    data class Rename(val itemId: String) : MediaOperation()
    data class MoreMenu(val itemId: String) : MediaOperation()
    data class Details(val itemId: String) : MediaOperation()
    data class CopyTo(val itemId: String) : MediaOperation()
    data class MoveTo(val itemId: String) : MediaOperation()
}

class MediaViewerViewModel(
    private val repository: MediaRepository,
    private val mediaId: String,
    private val albumId: String?,
    private val appContext: Application
) : ViewModel() {

    private val context: Context get() = appContext.applicationContext
    private val snapshot: List<MediaItem> = repository.mediaForViewer(mediaId, albumId)

    private val _pendingOperation = kotlinx.coroutines.flow.MutableStateFlow<MediaOperation>(MediaOperation.Idle)
    val pendingOperation: StateFlow<MediaOperation> = _pendingOperation
    private val _deleteConfirmation = kotlinx.coroutines.flow.MutableStateFlow<DeleteConfirmationRequest?>(null)
    val deleteConfirmation: StateFlow<DeleteConfirmationRequest?> = _deleteConfirmation
    private val _manageMediaRequest = kotlinx.coroutines.flow.MutableStateFlow<ManageMediaAccessRequest?>(null)
    val manageMediaRequest: StateFlow<ManageMediaAccessRequest?> = _manageMediaRequest
    private val _userMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage
    private var pendingDeleteCompletion: ((Int?) -> Unit)? = null
    private var manageMediaRetryId: String? = null

    val uiState: StateFlow<MediaViewerUiState> = repository.media
        .map { media ->
            val byId = media.associateBy { it.id }
            val items = snapshot.mapNotNull { original -> byId[original.id] }
            MediaViewerUiState(
                items = items,
                initialIndex = items.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MediaViewerUiState(
                items = snapshot,
                initialIndex = snapshot.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            )
        )

    fun toggleFavorite(id: String) = repository.toggleFavorite(id)

    fun shareMedia(item: MediaItem) {
        val shareUri = runCatching { Uri.parse(item.uri) }.getOrNull()
        val mimeType = item.mimeType.ifBlank { if (item.type == MediaType.VIDEO) "video/*" else "image/*" }
        val probe = if (shareUri != null) repository.probeMediaUri(item.uri) else null
        val permissionGranted = repository.hasMediaReadPermission()
        if (shareUri == null || shareUri.scheme != ContentResolver.SCHEME_CONTENT || shareUri.authority.isNullOrBlank() || probe == null || !probe.queryFound || !probe.inputStreamOpened || !permissionGranted) {
            val error = SecurityException(probe?.errorMessage ?: "Share requires an accessible content:// URI and media read permission")
            DeveloperLogger.error(LogCategory.MEDIASTORE, "MEDIA_SHARE_FAILED", "Media Viewer SHARE failed", localMediaId = item.id, throwable = error, metadata = mediaActionMetadata("SHARE", item, mimeType) + mapOf("uri_probe_query_found" to (probe?.queryFound?.toString() ?: "false"), "uri_probe_input_opened" to (probe?.inputStreamOpened?.toString() ?: "false"), "permission_granted" to permissionGranted.toString()))
            Toast.makeText(context, "Unable to share this item", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = ClipData.newRawUri(item.filename, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, item.filename).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (error: Exception) {
            DeveloperLogger.error(LogCategory.MEDIASTORE, "MEDIA_SHARE_FAILED", "Media Viewer SHARE failed", localMediaId = item.id, throwable = error, metadata = mediaActionMetadata("SHARE", item, mimeType))
            Toast.makeText(context, "Unable to share this item", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestDelete(itemId: String) { _pendingOperation.value = MediaOperation.DeleteConfirm(itemId) }
    fun dismissOperation() { _pendingOperation.value = MediaOperation.Idle }

    fun confirmRemove(itemId: String, onRemoved: (nextIndex: Int?) -> Unit) {
        _pendingOperation.value = MediaOperation.Idle
        pendingDeleteCompletion = onRemoved
        val item = uiState.value.items.firstOrNull { it.id == itemId }
        DeveloperLogger.info(
            LogCategory.UI,
            "MEDIA_REMOVE_CHOSEN",
            "User chose Move to Trash",
            localMediaId = itemId,
            metadata = mapOf(
                "action" to "MOVE_TO_TRASH",
                "media_uri" to item?.uri,
                "origin_local" to item?.originLocal?.toString(),
                "android_api" to Build.VERSION.SDK_INT.toString()
            )
        )
        performDelete(itemId)
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    fun onDeleteConfirmationResult(approved: Boolean) {
        val request = _deleteConfirmation.value ?: return
        _deleteConfirmation.value = null
        if (approved) {
            if (request.alreadyPerformedOnApproval) {
                finalizeDelete(request.itemId)
            } else {
                performDelete(request.itemId)
            }
        } else {
            pendingDeleteCompletion = null
            manageMediaRetryId = null
            DeveloperLogger.info(
                LogCategory.MEDIASTORE,
                "MEDIA_DELETE_CONFIRMATION_CANCELLED",
                "User cancelled Android MediaStore trash confirmation",
                localMediaId = request.itemId,
                metadata = mapOf(
                    "action" to "DELETE",
                    "android_api" to Build.VERSION.SDK_INT.toString(),
                    "result" to "CANCELLED",
                    "system_confirmation_required" to "true"
                )
            )
        }
    }

    fun manageMediaIntent() = repository.manageMediaRequestIntent()

    fun onManageMediaAccessResult() {
        val request = _manageMediaRequest.value ?: return
        _manageMediaRequest.value = null
        repository.markManageMediaAsked()
        if (repository.canManageMedia()) {
            performDelete(request.itemId)
        } else {
            pendingDeleteCompletion = null
            manageMediaRetryId = null
            viewModelScope.launch {
                showUserMessage("Allow media management in Settings, then try again.")
            }
        }
    }

    private fun performDelete(itemId: String) {
        viewModelScope.launch {
            val items = uiState.value.items
            val currentIndex = items.indexOfFirst { it.id == itemId }
            val item = items.firstOrNull { it.id == itemId }
            if (item != null && !item.originLocal) {
                moveCloudToTrash(itemId, currentIndex)
                return@launch
            }
            when (val result = repository.deleteMediaWithResult(context, itemId)) {
                is DeleteMediaResult.RequiresSystemConfirmation -> {
                    _deleteConfirmation.value = DeleteConfirmationRequest(
                        itemId = itemId,
                        intentSender = result.intentSender,
                        alreadyPerformedOnApproval = result.alreadyPerformedOnApproval,
                    )
                }
                DeleteMediaResult.RequiresManageMedia -> {
                    if (manageMediaRetryId == itemId) {
                        pendingDeleteCompletion = null
                        manageMediaRetryId = null
                        showUserMessage("Allow media management in Settings, then try again.")
                    } else {
                        manageMediaRetryId = itemId
                        _manageMediaRequest.value = ManageMediaAccessRequest(itemId)
                    }
                }
                DeleteMediaResult.Success -> finalizeDelete(itemId, currentIndex)
                DeleteMediaResult.NotFound -> {
                    moveCloudToTrash(itemId, currentIndex)
                }
                DeleteMediaResult.PermissionDenied -> {
                    pendingDeleteCompletion = null
                    showUserMessage("Android requires permission to modify this item.")
                }
                DeleteMediaResult.Failed -> {
                    pendingDeleteCompletion = null
                    showUserMessage("This item could not be moved to Trash.")
                }
            }
        }
    }

    private fun moveCloudToTrash(itemId: String, currentIndex: Int) {
        viewModelScope.launch {
            when (repository.moveCloudToTrash(itemId)) {
                RemoveFromLibraryResult.Success -> completeRemoval(
                    itemId = itemId,
                    currentIndex = currentIndex,
                    message = "Moved to Trash"
                )
                RemoveFromLibraryResult.Unauthorized -> {
                    pendingDeleteCompletion = null
                    showUserMessage("Sign in to move this item to Trash.")
                }
                RemoveFromLibraryResult.NotFound -> {
                    pendingDeleteCompletion = null
                    showUserMessage("This item could not be moved to Trash.")
                }
                RemoveFromLibraryResult.Failed -> {
                    pendingDeleteCompletion = null
                    showUserMessage("Couldn't move this item to Trash.")
                }
            }
        }
    }

    private fun finalizeDelete(itemId: String, knownIndex: Int? = null) {
        viewModelScope.launch {
            val currentIndex = knownIndex ?: uiState.value.items.indexOfFirst { it.id == itemId }
            if (repository.finalizeLocalDelete(itemId)) {
                when (repository.moveCloudToTrash(itemId)) {
                        RemoveFromLibraryResult.Success -> completeRemoval(
                            itemId = itemId,
                            currentIndex = currentIndex,
                            message = "Moved to Trash"
                        )
                        RemoveFromLibraryResult.Unauthorized -> {
                            pendingDeleteCompletion = null
                            showUserMessage("Moved to device Trash, but sign in is required to move it to My Drive Trash.")
                        }
                        RemoveFromLibraryResult.NotFound, RemoveFromLibraryResult.Failed -> {
                            pendingDeleteCompletion = null
                            showUserMessage("Moved to device Trash, but it could not be moved to My Drive Trash.")
                        }
                    }
            } else {
                pendingDeleteCompletion = null
                DeveloperLogger.error(
                    LogCategory.MEDIASTORE,
                    "MEDIA_DELETE_LOCAL_STATE_FAILED",
                    "Media Viewer DELETE succeeded in MediaStore but local state finalization failed",
                    localMediaId = itemId,
                    metadata = mapOf("action" to "DELETE", "android_api" to Build.VERSION.SDK_INT.toString())
                )
                showUserMessage("This item could not be moved to Trash.")
            }
        }
    }

    private suspend fun completeRemoval(
        itemId: String,
        currentIndex: Int,
        message: String,
        leaveViewer: Boolean = true
    ) {
        val completion = pendingDeleteCompletion
        pendingDeleteCompletion = null
        withContext(Dispatchers.Main) {
            val remaining = uiState.value.items.filter { it.id != itemId }
            val stillVisible = uiState.value.items.any { it.id == itemId }
            val shouldLeave = leaveViewer || !stillVisible
            val nextIndex = if (remaining.isEmpty()) null else currentIndex.coerceAtMost(remaining.lastIndex)
            if (shouldLeave) {
                completion?.invoke(nextIndex)
            }
            if (shouldLeave && remaining.isEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } else {
                _userMessage.value = message
            }
        }
    }

    private suspend fun showUserMessage(message: String) = withContext(Dispatchers.Main) {
        _userMessage.value = message
    }

    private fun mediaActionMetadata(action: String, item: MediaItem, mimeType: String): Map<String, String?> = mapOf(
        "action" to action,
        "media_uri" to item.uri,
        "mime_type" to mimeType,
        "android_api" to Build.VERSION.SDK_INT.toString()
    )

    fun requestRename(itemId: String) { _pendingOperation.value = MediaOperation.Rename(itemId) }
    fun confirmRename(itemId: String, newName: String) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            if (!repository.renameMedia(context, itemId, newName)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not rename this item", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun requestMoreMenu(itemId: String) { _pendingOperation.value = MediaOperation.MoreMenu(itemId) }
    fun requestDetails(itemId: String) { _pendingOperation.value = MediaOperation.Details(itemId) }

    fun rotateLeft(itemId: String) = rotate(itemId, -90f)
    fun rotateRight(itemId: String) = rotate(itemId, 90f)
    private fun rotate(itemId: String, degrees: Float) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            if (!repository.rotateMedia(context, itemId, degrees)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not rotate this item", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setAsWallpaper(item: MediaItem) {
        if (item.uri.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                type = item.mimeType.ifBlank { if (item.type == MediaType.VIDEO) "video/*" else "image/*" }
                putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Set as wallpaper"))
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to set wallpaper", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWith(item: MediaItem) {
        if (item.uri.isBlank()) return
        try {
            val viewUri = Uri.parse(item.uri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(viewUri, item.mimeType.ifBlank {
                    if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to open this item", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestCopy(itemId: String) { _pendingOperation.value = MediaOperation.CopyTo(itemId) }
    fun copyToDestination(itemId: String, destUri: Uri) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val success = repository.copyMedia(context, itemId, destUri)
            finishCopy(success, "Copied successfully", "Copy failed")
        }
    }

    fun requestMove(itemId: String) { _pendingOperation.value = MediaOperation.MoveTo(itemId) }
    fun moveToDestination(itemId: String, destUri: Uri) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val success = repository.moveMedia(context, itemId, destUri)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (success) "Moved successfully" else "Move failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun finishCopy(success: Boolean, ok: String, fail: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, if (success) ok else fail, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun factory(repository: MediaRepository, mediaId: String, albumId: String?, app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MediaViewerViewModel(repository, mediaId, albumId, app) as T
            }
    }
}
