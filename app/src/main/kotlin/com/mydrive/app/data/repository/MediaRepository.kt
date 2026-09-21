package com.mydrive.app.data.repository

import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.mydrive.app.data.local.FavoritesStore
import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.media.MediaAccess
import com.mydrive.app.data.media.MediaPermissions
import com.mydrive.app.data.media.MediaQueryException
import com.mydrive.app.data.media.MediaStoreDataSource
import com.mydrive.app.data.mock.MockMediaData
import com.mydrive.app.data.model.ActivityEvent
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.BackupOverview
import com.mydrive.app.data.model.BackupPreferences
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaLoadState
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.StorageSummary
import com.mydrive.app.data.model.SyncSummary
import com.mydrive.app.data.model.TelegramSettings
import com.mydrive.app.data.model.TelegramConnectionState
import com.mydrive.app.data.model.TodayStats
import com.mydrive.app.data.model.UserProfile
import com.mydrive.app.data.model.isActive
import com.mydrive.app.data.remote.TelegramApiVerifier
import com.mydrive.app.data.remote.TelegramVerificationResult
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class DeleteMediaResult {
    data object Deleted : DeleteMediaResult()
    data class NeedsConfirmation(val intentSender: android.content.IntentSender) : DeleteMediaResult()
    data object Failed : DeleteMediaResult()
}

class MediaRepository(
    private val mediaStore: MediaStoreDataSource,
    private val favorites: FavoritesStore,
    private val permissions: MediaPermissions,
    private val syncRepository: SyncRepository,
    private val telegramSettingsStore: TelegramSettingsStore,
    private val telegramApiVerifier: TelegramApiVerifier,
    scope: CoroutineScope
) {

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumFolder>>(emptyList())
    val albums: StateFlow<List<AlbumFolder>> = _albums.asStateFlow()

    private val _loadState = MutableStateFlow(initialLoadState())
    val loadState: StateFlow<MediaLoadState> = _loadState.asStateFlow()

    private val _overview = MutableStateFlow(MockMediaData.backupOverview)
    val overview: StateFlow<BackupOverview> = _overview.asStateFlow()

    private val _todayStats = MutableStateFlow(MockMediaData.todayStats)
    val todayStats: StateFlow<TodayStats> = _todayStats.asStateFlow()

    private val _activity = MutableStateFlow(MockMediaData.recentActivity)
    val activity: StateFlow<List<ActivityEvent>> = _activity.asStateFlow()

    private val _profile = MutableStateFlow(MockMediaData.profile)
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _preferences = MutableStateFlow(MockMediaData.backupPreferences)
    val preferences: StateFlow<BackupPreferences> = _preferences.asStateFlow()

    val telegram: StateFlow<TelegramSettings> = telegramSettingsStore.settings

    private val _storage = MutableStateFlow(MockMediaData.storageSummary)
    val storage: StateFlow<StorageSummary> = _storage.asStateFlow()

    private val _syncSummary = MutableStateFlow(MockMediaData.syncSummary)
    val syncSummary: StateFlow<SyncSummary> = _syncSummary.asStateFlow()

    private val refreshMutex = Mutex()
    private var lastRefreshAt = 0L

    init {
        scope.launch {
            syncRepository.records.collect { records ->
                val current = _media.value
                if (current.isEmpty()) return@collect
                val updated = current.map { it.withRecord(records[it.id]) }
                if (updated != current) {
                    _media.value = updated
                    updateStorage(updated)
                }
            }
        }
    }

    fun requiredPermissions(): Array<String> = permissions.requiredPermissions()
    fun markPermissionAsked() { permissions.markAsked() }

    @Volatile
    private var viewerSessionIds: List<String>? = null

    fun mediaById(id: String): MediaItem? = _media.value.firstOrNull { it.id == id }
    fun albumById(id: String): AlbumFolder? = _albums.value.firstOrNull { it.id == id }
    fun albums(): List<AlbumFolder> = _albums.value
    fun beginViewerSession(ids: List<String>) { viewerSessionIds = ids }
    fun viewerSessionIds(): List<String>? = viewerSessionIds

    fun mediaForViewer(startId: String, albumId: String?): List<MediaItem> {
        val current = _media.value
        val byId = current.associateBy { it.id }
        val session = viewerSessionIds?.mapNotNull { byId[it] }?.takeIf { items -> items.any { it.id == startId } }
        if (session != null) return session
        val scoped = if (!albumId.isNullOrBlank()) current.filter { it.albumId == albumId } else current
            .sortedByDescending { it.capturedAtMillis }
        if (scoped.any { it.id == startId }) return scoped
        val start = byId[startId]
        return if (start != null) listOf(start) else scoped
    }

    fun toggleFavorite(id: String) {
        favorites.toggle(id)
        val favorite = favorites.contains(id)
        _media.update { items -> items.map { item -> if (item.id == id) item.copy(isFavorite = favorite) else item } }
    }

    fun updatePreferences(transform: (BackupPreferences) -> BackupPreferences) { _preferences.update(transform) }
    fun saveTelegramConfiguration(botToken: String?, chatId: String, enabled: Boolean) {
        telegramSettingsStore.saveConfiguration(botToken = botToken, chatId = chatId, enabled = enabled)
    }
    fun setTelegramBackupEnabled(enabled: Boolean) { telegramSettingsStore.setEnabled(enabled) }

    suspend fun testTelegramConnection(): TelegramVerificationResult {
        if (telegram.value.connectionState == TelegramConnectionState.TESTING) return TelegramVerificationResult.TelegramUnavailable
        val credentials = telegramSettingsStore.credentials()
        if (credentials == null) {
            telegramSettingsStore.markConnectionFailed("Configuration incomplete.")
            return TelegramVerificationResult.InvalidChatId
        }
        val result = try {
            telegramSettingsStore.markTesting()
            telegramApiVerifier.verify(botToken = credentials.botToken, chatId = credentials.chatId)
        } catch (cancellation: CancellationException) {
            telegramSettingsStore.markNotTested()
            throw cancellation
        }
        when (result) {
            TelegramVerificationResult.Success -> telegramSettingsStore.markConnected()
            else -> telegramSettingsStore.markConnectionFailed(result.message())
        }
        return result
    }

    fun markTelegramConnectionFailed(message: String) { telegramSettingsStore.markConnectionFailed(message) }
    fun clearTelegramConfiguration() { telegramSettingsStore.clear() }

    suspend fun refresh(force: Boolean = false) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && _media.value.isNotEmpty() && now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) { applyAccessState(); return }
            applyAccessState()
            if (!permissions.canReadMedia()) {
                _media.value = emptyList(); _albums.value = emptyList(); _storage.value = StorageSummary(0, 0, 0, 0)
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
                return
            }
            val showSpinner = _media.value.isEmpty()
            _loadState.update { it.copy(isLoading = showSpinner, errorMessage = null) }
            try {
                val favoriteIds = favorites.ids.value
                val items = mediaStore.loadMedia().map { item -> item.copy(isFavorite = item.id in favoriteIds) }
                syncRepository.reconcileMedia(items)
                if (permissions.access() == MediaAccess.GRANTED) {
                    val presentIds = withContext(Dispatchers.Default) { items.mapTo(HashSet(items.size)) { it.id } }
                    favorites.retainAll(presentIds)
                    syncRepository.reconcile(presentIds)
                }
                val records = syncRepository.records.value
                val merged = items.map { it.withRecord(records[it.id]) }
                _media.value = merged; _albums.value = buildAlbums(merged); lastRefreshAt = now; updateStorage(merged)
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
            } catch (_: MediaQueryException) {
                val keepExisting = _media.value.isNotEmpty()
                _loadState.update { it.copy(isLoading = false, errorMessage = if (keepExisting) null else "Couldn't load your photos and videos.") }
            } catch (_: SecurityException) {
                applyAccessState(); _media.value = emptyList(); _albums.value = emptyList()
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
            }
        }
    }

    private fun initialLoadState(): MediaLoadState {
        val access = permissions.access()
        val canRead = access == MediaAccess.GRANTED || access == MediaAccess.PARTIAL
        return MediaLoadState(accessGranted = access == MediaAccess.GRANTED, accessPartial = access == MediaAccess.PARTIAL, needsPermission = access == MediaAccess.NEEDS_REQUEST, permissionDenied = access == MediaAccess.DENIED, isLoading = canRead)
    }

    private fun applyAccessState() {
        val access = permissions.access()
        _loadState.update { it.copy(accessGranted = access == MediaAccess.GRANTED, accessPartial = access == MediaAccess.PARTIAL, needsPermission = access == MediaAccess.NEEDS_REQUEST, permissionDenied = access == MediaAccess.DENIED) }
    }

    private fun buildAlbums(items: List<MediaItem>): List<AlbumFolder> = items.groupBy { it.albumId }.map { (albumId, albumItems) ->
        val cover = albumItems.maxByOrNull { it.capturedAtMillis }
        AlbumFolder(id = albumId, name = cover?.albumName?.ifBlank { "Other" } ?: "Other", coverSeed = cover?.thumbnailSeed ?: 0, coverType = cover?.type ?: MediaType.PHOTO, mediaCount = albumItems.size, coverUri = cover?.uri.orEmpty())
    }.sortedByDescending { it.mediaCount }

    private fun updateStorage(items: List<MediaItem>) {
        val photos = items.count { it.type == MediaType.PHOTO }
        val videos = items.count { it.type == MediaType.VIDEO }
        _storage.value = StorageSummary(totalMedia = items.size, photos = photos, videos = videos, pendingUploads = items.count { it.backupState != BackupState.COMPLETED })
        _todayStats.value = TodayStats(photosBackedUp = 0, videosBackedUp = 0, pending = items.count { it.backupState != BackupState.COMPLETED }, failed = items.count { it.backupState == BackupState.FAILED })
        refreshSyncSummary()
    }

    private fun refreshSyncSummary() {
        val items = _media.value
        _syncSummary.update { it.copy(inProgressCount = items.count { it.backupState.isActive }, completedToday = 0) }
    }

    private fun MediaItem.withRecord(record: SyncRecord?): MediaItem {
        if (record == null) {
            if (backupState == BackupState.NOT_STARTED) return this
            return copy(backupState = BackupState.NOT_STARTED, backupCompleted = false, progress = 0f, errorMessage = null, cloudinaryAssetId = null, cloudinaryPublicId = null)
        }
        val state = record.state.toBackupState().resumeLocally()
        return copy(backupState = state, backupCompleted = state == BackupState.COMPLETED, progress = 0f, errorMessage = record.errorMessage, cloudinaryAssetId = record.cloudinaryAssetId, cloudinaryPublicId = record.cloudinaryPublicId)
    }

    fun retryBackup(id: String) { syncRepository.retry(id) }

    suspend fun deleteMediaWithResult(context: Context, id: String): DeleteMediaResult = withContext(Dispatchers.IO) {
        val item = _media.value.firstOrNull { it.id == id } ?: return@withContext DeleteMediaResult.Failed
        if (item.uri.isBlank()) return@withContext DeleteMediaResult.Failed
        val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
        if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
            logMediaActionFailure("DELETE", item, IllegalArgumentException("Delete requires a valid content:// URI"))
            return@withContext DeleteMediaResult.Failed
        }
        if (!permissions.canReadMedia()) {
            val error = SecurityException("Required media read permission is not granted")
            logMediaActionFailure("DELETE", item, error, mapOf("permission_granted" to "false"))
            return@withContext DeleteMediaResult.Failed
        }
        val probe = mediaStore.probeUri(item.uri)
        if (!probe.queryFound) {
            val error = SecurityException(probe.errorMessage ?: "MediaStore URI is not accessible")
            logMediaActionFailure("DELETE", item, error, mapOf("uri_probe" to "query_not_found"))
            return@withContext DeleteMediaResult.Failed
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sender = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true).intentSender
                DeveloperLogger.info(
                    LogCategory.MEDIASTORE,
                    "MEDIA_DELETE_TRASH_REQUESTED",
                    "MediaStore trash request created; waiting for system result",
                    localMediaId = item.id,
                    metadata = mediaActionMetadata("DELETE", item) + ("android_flow" to "create_trash_request")
                )
                DeleteMediaResult.NeedsConfirmation(sender)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, 1)
                }
                if (context.contentResolver.update(uri, values, null, null) > 0) {
                    DeleteMediaResult.Deleted
                } else {
                    logMediaActionFailure("DELETE", item, IllegalStateException("MediaStore trash update returned 0 rows"))
                    DeleteMediaResult.Failed
                }
            }
        } catch (error: Exception) {
            logMediaActionFailure("DELETE", item, error, mapOf("android_flow" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "create_trash_request" else "trash_update"))
            DeleteMediaResult.Failed
        }
    }

    suspend fun finalizeLocalDelete(id: String): Boolean = withContext(Dispatchers.IO) {
        val existed = _media.value.any { it.id == id }
        if (!existed) return@withContext false
        _media.update { items -> items.filter { it.id != id } }
        favorites.retainAll(_media.value.mapTo(HashSet()) { it.id })
        rebuildAlbums()
        // Local MediaStore/Room refresh only. Supabase media_assets and archive data remain untouched.
        refresh(force = true)
        true
    }

    suspend fun deleteMedia(context: Context, id: String): Boolean =
        deleteMediaWithResult(context, id) is DeleteMediaResult.Deleted

    fun hasMediaReadPermission(): Boolean = permissions.canReadMedia()
    fun probeMediaUri(rawUri: String) = mediaStore.probeUri(rawUri)

    private fun mediaActionMetadata(action: String, item: MediaItem): Map<String, String?> = mapOf(
        "action" to action,
        "media_uri" to item.uri,
        "mime_type" to item.mimeType,
        "android_api" to Build.VERSION.SDK_INT.toString()
    )

    private fun logMediaActionFailure(action: String, item: MediaItem, error: Throwable, extra: Map<String, String?> = emptyMap()) {
        DeveloperLogger.error(LogCategory.MEDIASTORE, "MEDIA_${action}_FAILED", "Media Viewer $action failed", localMediaId = item.id, throwable = error, metadata = mediaActionMetadata(action, item) + extra)
    }

    suspend fun copyMedia(context: Context, id: String, destFolderUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val item = _media.value.firstOrNull { it.id == id } ?: return@withContext false
        val sourceUri = Uri.parse(item.uri)
        try {
            val mimeType = item.mimeType.ifBlank { if (item.type == MediaType.VIDEO) "video/*" else "image/*" }
            val docUri = android.provider.DocumentsContract.createDocument(context.contentResolver, destFolderUri, mimeType, item.filename)
                ?: return@withContext false
            val success = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(docUri)?.use { output ->
                    input.copyTo(output, bufferSize = 65536)
                    true
                } ?: false
            } ?: false

            if (success) {
                refresh(force = true)
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    suspend fun moveMedia(context: Context, id: String, destFolderUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val copied = copyMedia(context, id, destFolderUri)
        if (copied) {
            deleteMedia(context, id)
        } else {
            false
        }
    }

    suspend fun renameMedia(context: Context, id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val item = _media.value.firstOrNull { it.id == id } ?: return@withContext false
        var trimmed = newName.trim()
        if (trimmed.isBlank()) return@withContext false

        val oldExt = item.filename.substringAfterLast('.', "")
        if (oldExt.isNotBlank() && !trimmed.contains('.')) {
            trimmed = "$trimmed.$oldExt"
        }

        if (trimmed == item.filename) return@withContext false

        val uri = Uri.parse(item.uri)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, trimmed)
        }

        val updated = try {
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }

        if (updated) {
            val updatedItem = item.copy(filename = trimmed)
            _media.update { items ->
                items.map { if (it.id == id) updatedItem else it }
            }
            syncRepository.updateQueueMedia(id, updatedItem)
            rebuildAlbums()
        }
        updated
    }

    suspend fun rotateMedia(context: Context, id: String, degrees: Float): Boolean = withContext(Dispatchers.IO) {
        val item = _media.value.firstOrNull { it.id == id } ?: return@withContext false
        if (item.type != MediaType.PHOTO) return@withContext false
        val uri = Uri.parse(item.uri)
        try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: return@withContext false

            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }

            val tempFile = java.io.File(context.cacheDir, "temp_rotate_${System.currentTimeMillis()}.tmp")
            val format = if (item.mimeType.contains("png", ignoreCase = true)) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            val quality = if (format == Bitmap.CompressFormat.JPEG) 95 else 100

            java.io.FileOutputStream(tempFile).use { out ->
                rotated.compress(format, quality, out)
            }
            rotated.recycle()

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return@withContext false
            }

            val written = java.io.FileInputStream(tempFile).use { tempIn ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { targetOut ->
                    tempIn.copyTo(targetOut, bufferSize = 65536)
                    true
                } ?: false
            }
            tempFile.delete()

            if (written) {
                val swap = degrees == 90f || degrees == -90f || degrees == 270f || degrees == -270f
                _media.update { items ->
                    items.map { current ->
                        if (current.id == id) {
                            val newW = if (swap) current.height else current.width
                            val newH = if (swap) current.width else current.height
                            current.copy(
                                width = newW,
                                height = newH,
                                resolution = if (newW > 0 && newH > 0) "$newW x $newH" else current.resolution,
                                dateModifiedMillis = System.currentTimeMillis()
                            )
                        } else current
                    }
                }
                com.mydrive.app.data.media.FullImageLoader.clearCache()
            }
            written
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getMediaFilePath(context: Context, item: MediaItem): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return@withContext null
        val uri = Uri.parse(item.uri)
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        try { context.contentResolver.query(uri, projection, null, null, null)?.use { cursor -> if (cursor.moveToFirst()) { val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA); if (idx >= 0) cursor.getString(idx) else null } else null } } catch (_: Exception) { null }
    }

    private fun rebuildAlbums() { _albums.value = buildAlbums(_media.value) }

    companion object { private const val MIN_REFRESH_INTERVAL_MS = 1_500L }
}
