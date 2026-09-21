package com.mydrive.app.ui.media

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.repository.MediaRepository
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

    val uiState: StateFlow<MediaViewerUiState> = repository.media
        .map { media ->
            val byId = media.associateBy { it.id }
            val items = snapshot.map { original ->
                val live = byId[original.id]
                live ?: original
            }
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

    fun toggleFavorite(id: String) {
        repository.toggleFavorite(id)
    }

    fun shareMedia(item: MediaItem) {
        if (item.uri.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank {
                    if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                }
                putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, item.filename))
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to share this item", Toast.LENGTH_SHORT).show()
        }
    }

    fun editMedia(item: MediaItem) {
        if (item.uri.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(
                    Uri.parse(item.uri),
                    item.mimeType.ifBlank {
                        if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                    }
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Edit with"))
        } catch (_: Exception) {
            Toast.makeText(context, "No editor available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestDelete(itemId: String) {
        _pendingOperation.value = MediaOperation.DeleteConfirm(itemId)
    }

    fun dismissOperation() {
        _pendingOperation.value = MediaOperation.Idle
    }

    fun confirmDelete(
        itemId: String,
        onDeleted: (nextIndex: Int?) -> Unit
    ) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val items = uiState.value.items
            val currentIndex = items.indexOfFirst { it.id == itemId }
            val success = repository.deleteMedia(context, itemId)
            if (success) {
                val remainingItems = uiState.value.items
                val nextIndex = when {
                    remainingItems.isEmpty() -> null
                    currentIndex < remainingItems.size -> currentIndex
                    else -> (remainingItems.size - 1).coerceAtLeast(0)
                }
                withContext(Dispatchers.Main) {
                    onDeleted(nextIndex)
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not delete this item", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun requestRename(itemId: String) {
        _pendingOperation.value = MediaOperation.Rename(itemId)
    }

    fun confirmRename(itemId: String, newName: String) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val success = repository.renameMedia(context, itemId, newName)
            withContext(Dispatchers.Main) {
                if (!success) {
                    Toast.makeText(context, "Could not rename this item", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun requestMoreMenu(itemId: String) {
        _pendingOperation.value = MediaOperation.MoreMenu(itemId)
    }

    fun requestDetails(itemId: String) {
        _pendingOperation.value = MediaOperation.Details(itemId)
    }

    fun rotateLeft(itemId: String) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val success = repository.rotateMedia(context, itemId, -90f)
            withContext(Dispatchers.Main) {
                if (!success) {
                    Toast.makeText(context, "Could not rotate this item", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun rotateRight(itemId: String) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val success = repository.rotateMedia(context, itemId, 90f)
            withContext(Dispatchers.Main) {
                if (!success) {
                    Toast.makeText(context, "Could not rotate this item", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setAsWallpaper(item: MediaItem) {
        if (item.uri.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                type = item.mimeType.ifBlank {
                    if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                }
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
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse(item.uri),
                    item.mimeType.ifBlank {
                        if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                    }
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to open this item", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestCopy(itemId: String) {
        _pendingOperation.value = MediaOperation.CopyTo(itemId)
    }

    fun copyToDestination(itemId: String, destUri: Uri) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val success = copyMediaToUri(itemId, destUri)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(context, "Copied successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Copy failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun requestMove(itemId: String) {
        _pendingOperation.value = MediaOperation.MoveTo(itemId)
    }

    fun moveToDestination(itemId: String, destUri: Uri) {
        _pendingOperation.value = MediaOperation.Idle
        viewModelScope.launch {
            val copied = copyMediaToUri(itemId, destUri)
            if (copied) {
                val deleted = repository.deleteMedia(context, itemId)
                withContext(Dispatchers.Main) {
                    if (deleted) {
                        Toast.makeText(context, "Moved successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Copied but could not delete original", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Move failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun copyMediaToUri(itemId: String, destFolderUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val item = uiState.value.items.firstOrNull { it.id == itemId } ?: return@withContext false
            val sourceUri = Uri.parse(item.uri)
            try {
                val resolver = context.contentResolver
                val mimeType = item.mimeType.ifBlank {
                    if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                }
                // Create new file in destination folder
                val docUri = resolver.createDocument(destFolderUri, mimeType, item.filename)
                    ?: return@withContext false
                // Copy bytes
                resolver.openInputStream(sourceUri)?.use { input ->
                    resolver.openOutputStream(docUri)?.use { output ->
                        input.copyTo(output)
                    } ?: return@withContext false
                } ?: return@withContext false
                true
            } catch (_: Exception) {
                false
            }
        }

    companion object {
        fun factory(
            repository: MediaRepository,
            mediaId: String,
            albumId: String?,
            app: Application
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MediaViewerViewModel(repository, mediaId, albumId, app) as T
                }
            }
    }
}
