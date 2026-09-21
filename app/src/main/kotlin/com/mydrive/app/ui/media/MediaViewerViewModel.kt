package com.mydrive.app.ui.media

import android.app.Application
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.repository.DeleteMediaResult
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaViewerUiState(
    val items: List<MediaItem>,
    val initialIndex: Int
)

data class DeleteConfirmationRequest(val itemId: String, val intentSender: android.content.IntentSender)

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
    private var pendingDeleteCompletion: ((Int?) -> Unit)? = null

    val uiState: StateFlow<MediaViewerUiState> = repository.media
        .map { media ->
            val byId = media.associateBy { it.id }
            val items = snapshot.map { original -> byId[original.id] ?: original }
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

    fun editMedia(item: MediaItem) {
        if (item.uri.isBlank()) return
        try {
            val editUri = Uri.parse(item.uri)
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(editUri, item.mimeType.ifBlank {
                    if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Edit with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, "No editor available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestDelete(itemId: String) { _pendingOperation.value = MediaOperation.DeleteConfirm(itemId) }
    fun dismissOperation() { _pendingOperation.value = MediaOperation.Idle }

    fun confirmDelete(itemId: String, onDeleted: (nextIndex: Int?) -> Unit) {
        _pendingOperation.value = MediaOperation.Idle
        pendingDeleteCompletion = onDeleted
        performDelete(itemId)
    }

    fun onDeleteConfirmationResult(approved: Boolean) {
        val request = _deleteConfirmation.value ?: return
        _deleteConfirmation.value = null
        if (approved) {
            finalizeDelete(request.itemId)
        } else {
            pendingDeleteCompletion = null
            DeveloperLogger.info(LogCategory.MEDIASTORE, "MEDIA_DELETE_CONFIRMATION_CANCELLED", "User cancelled MediaStore delete confirmation", localMediaId = request.itemId)
        }
    }

    private fun performDelete(itemId: String) {
        viewModelScope.launch {
            val items = uiState.value.items
            val currentIndex = items.indexOfFirst { it.id == itemId }
            when (val result = repository.deleteMediaWithResult(context, itemId)) {
                is DeleteMediaResult.NeedsConfirmation -> {
                    _deleteConfirmation.value = DeleteConfirmationRequest(itemId, result.intentSender)
                }
                DeleteMediaResult.Deleted -> finalizeDelete(itemId, currentIndex)
                DeleteMediaResult.Failed -> {
                    pendingDeleteCompletion = null
                    withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not delete this item", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun finalizeDelete(itemId: String, knownIndex: Int? = null) {
        viewModelScope.launch {
            val currentIndex = knownIndex ?: uiState.value.items.indexOfFirst { it.id == itemId }
            if (repository.finalizeLocalDelete(itemId)) {
                val completion = pendingDeleteCompletion
                pendingDeleteCompletion = null
                withContext(Dispatchers.Main) {
                    val remaining = uiState.value.items
                    completion?.invoke(if (remaining.isEmpty()) null else currentIndex.coerceAtMost(remaining.lastIndex))
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not update this item", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
