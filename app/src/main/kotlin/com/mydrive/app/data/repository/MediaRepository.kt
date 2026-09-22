package com.mydrive.app.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.FileNotFoundException
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
import com.mydrive.app.data.model.TrashSummary
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
    data object Success : DeleteMediaResult()
    data class RequiresSystemConfirmation(
        val intentSender: IntentSender,
        val alreadyPerformedOnApproval: Boolean
    ) : DeleteMediaResult()
    data object NotFound : DeleteMediaResult()
    data object PermissionDenied : DeleteMediaResult()
    data object Failed : DeleteMediaResult()
}

sealed class TrashMutationResult {
    data object Success : TrashMutationResult()
    data class RequiresSystemConfirmation(
        val intentSender: IntentSender,
        val alreadyPerformedOnApproval: Boolean
    ) : TrashMutationResult()
    data object NotFound : TrashMutationResult()
    data object PermissionDenied : TrashMutationResult()
    data object Failed : TrashMutationResult()
    data object Unsupported : TrashMutationResult()
}

data class TrashOperationProgress(
    val inProgress: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0
)

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

    private val _trashedMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val trashedMedia: StateFlow<List<MediaItem>> = _trashedMedia.asStateFlow()

    private val _trashSummary = MutableStateFlow(TrashSummary())
    val trashSummary: StateFlow<TrashSummary> = _trashSummary.asStateFlow()

    private val _trashProgress = MutableStateFlow(TrashOperationProgress())
    val trashProgress: StateFlow<TrashOperationProgress> = _trashProgress.asStateFlow()

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
    private val locallyHiddenIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    fun trashedMediaById(id: String): MediaItem? = _trashedMedia.value.firstOrNull { it.id == id }
    fun albumById(id: String): AlbumFolder? = _albums.value.firstOrNull { it.id == id }
    fun albums(): List<AlbumFolder> = _albums.value
    fun beginViewerSession(ids: List<String>) { viewerSessionIds = ids }
    fun viewerSessionIds(): List<String>? = viewerSessionIds
    fun mediaForTrashViewer(startId: String): List<MediaItem> {
        val current = _trashedMedia.value
        if (current.any { it.id == startId }) return current
        val start = current.firstOrNull { it.id == startId } ?: trashedMediaById(startId)
        return if (start != null) listOf(start) else current
    }

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
                publishTrash(emptyList())
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
                return
            }
            val showSpinner = _media.value.isEmpty()
            _loadState.update { it.copy(isLoading = showSpinner, errorMessage = null) }
            try {
                val favoriteIds = favorites.ids.value
                val scanned = mediaStore.loadMedia()
                val trashed = mediaStore.loadTrashedMedia()
                val scannedIds = scanned.mapTo(HashSet(scanned.size)) { it.id }
                val trashedIds = trashed.mapTo(HashSet(trashed.size)) { it.id }
                locallyHiddenIds.removeAll { it !in scannedIds && it !in trashedIds }
                val items = scanned
                    .filter { it.id !in locallyHiddenIds }
                    .map { item -> item.copy(isFavorite = item.id in favoriteIds) }
                syncRepository.reconcileMedia(items)
                if (permissions.access() == MediaAccess.GRANTED) {
                    val presentIds = withContext(Dispatchers.Default) { items.mapTo(HashSet(items.size)) { it.id } }
                    favorites.retainAll(presentIds)
                    // Keep local backup metadata for items only moved to device Trash.
                    syncRepository.reconcile(presentIds + locallyHiddenIds + trashedIds)
                }
                val records = syncRepository.records.value
                val merged = items.map { it.withRecord(records[it.id]) }
                _media.value = merged; _albums.value = buildAlbums(merged); lastRefreshAt = now; updateStorage(merged)
                publishTrash(trashed)
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
            } catch (_: MediaQueryException) {
                val keepExisting = _media.value.isNotEmpty()
                _loadState.update { it.copy(isLoading = false, errorMessage = if (keepExisting) null else "Couldn't load your photos and videos.") }
            } catch (_: SecurityException) {
                applyAccessState(); _media.value = emptyList(); _albums.value = emptyList()
                publishTrash(emptyList())
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
        val item = _media.value.firstOrNull { it.id == id }
        if (item == null) {
            DeveloperLogger.info(
                LogCategory.MEDIASTORE,
                "MEDIA_DELETE_NOT_FOUND",
                "Delete requested for media that is no longer in the local gallery",
                localMediaId = id,
                metadata = mapOf(
                    "action" to "DELETE",
                    "android_api" to Build.VERSION.SDK_INT.toString(),
                    "operation" to "lookup",
                    "result" to "NOT_FOUND"
                )
            )
            return@withContext DeleteMediaResult.NotFound
        }
        if (item.uri.isBlank()) {
            logMediaActionFailure("DELETE", item, IllegalArgumentException("Delete requires a valid content:// URI"))
            return@withContext DeleteMediaResult.Failed
        }
        val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
        if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
            logMediaActionFailure("DELETE", item, IllegalArgumentException("Delete requires a valid content:// URI"))
            return@withContext DeleteMediaResult.Failed
        }
        if (!permissions.canReadMedia()) {
            val error = SecurityException("Required media read permission is not granted")
            logMediaActionFailure("DELETE", item, error, mapOf("permission_granted" to "false", "result" to "PERMISSION_DENIED"))
            return@withContext DeleteMediaResult.PermissionDenied
        }
        val probe = mediaStore.probeUri(item.uri)
        if (!probe.queryFound) {
            logMediaActionFailure(
                "DELETE",
                item,
                FileNotFoundException(probe.errorMessage ?: "MediaStore URI is not accessible"),
                mapOf("uri_probe" to "query_not_found", "result" to "NOT_FOUND")
            )
            return@withContext DeleteMediaResult.NotFound
        }
        trashLocalMedia(context, item, uri)
    }

    private fun trashLocalMedia(context: Context, item: MediaItem, uri: Uri): DeleteMediaResult {
        val canManageMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)
        val ownerPackage = queryOwnerPackage(context, uri)
        val ownedByApp = ownerPackage == context.packageName
        val baseMeta = mediaActionMetadata("DELETE", item) + mapOf(
            "owner_package" to ownerPackage,
            "owned_by_app" to ownedByApp.toString(),
            "can_manage_media" to canManageMedia.toString(),
            "permission_granted" to permissions.canReadMedia().toString()
        )
        DeveloperLogger.info(
            LogCategory.MEDIASTORE,
            "MEDIA_DELETE_START",
            "Starting local MediaStore trash operation",
            localMediaId = item.id,
            metadata = baseMeta + mapOf("operation" to "start")
        )
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> trashOnApiRPlus(context, item, uri, baseMeta)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteOnApiQ(context, item, uri, baseMeta)
                else -> deleteLegacy(context, item, uri, baseMeta)
            }
        } catch (error: RecoverableSecurityException) {
            requestSystemTrashConfirmation(context, item, uri, baseMeta, error)
        } catch (error: SecurityException) {
            requestSystemTrashConfirmation(context, item, uri, baseMeta, error)
        } catch (error: FileNotFoundException) {
            logMediaActionFailure("DELETE", item, error, baseMeta + mapOf("result" to "NOT_FOUND"))
            DeleteMediaResult.NotFound
        } catch (error: Exception) {
            logMediaActionFailure("DELETE", item, error, baseMeta + mapOf("result" to "FAILED"))
            DeleteMediaResult.Failed
        }
    }

    private fun trashOnApiRPlus(
        context: Context,
        item: MediaItem,
        uri: Uri,
        baseMeta: Map<String, String?>
    ): DeleteMediaResult {
        return if (tryDirectTrash(context, uri)) {
            logTrashResult(item, baseMeta, "direct_is_trashed", "SUCCESS", systemConfirmation = false)
            DeleteMediaResult.Success
        } else {
            logMediaActionFailure(
                "DELETE",
                item,
                FileNotFoundException("MediaStore trash update returned 0 rows"),
                baseMeta + mapOf("android_flow" to "direct_is_trashed", "result" to "NOT_FOUND")
            )
            DeleteMediaResult.NotFound
        }
    }

    private fun deleteOnApiQ(
        context: Context,
        item: MediaItem,
        uri: Uri,
        baseMeta: Map<String, String?>
    ): DeleteMediaResult {
        return try {
            if (context.contentResolver.delete(uri, null, null) > 0) {
                logTrashResult(item, baseMeta, "scoped_delete", "SUCCESS", systemConfirmation = false)
                DeleteMediaResult.Success
            } else {
                logMediaActionFailure(
                    "DELETE",
                    item,
                    FileNotFoundException("MediaStore delete returned 0 rows"),
                    baseMeta + mapOf("android_flow" to "scoped_delete", "result" to "NOT_FOUND")
                )
                DeleteMediaResult.NotFound
            }
        } catch (error: RecoverableSecurityException) {
            requestSystemTrashConfirmation(context, item, uri, baseMeta, error)
        }
    }

    private fun deleteLegacy(
        context: Context,
        item: MediaItem,
        uri: Uri,
        baseMeta: Map<String, String?>
    ): DeleteMediaResult {
        return if (context.contentResolver.delete(uri, null, null) > 0) {
            logTrashResult(item, baseMeta, "legacy_delete", "SUCCESS", systemConfirmation = false)
            DeleteMediaResult.Success
        } else {
            logMediaActionFailure(
                "DELETE",
                item,
                FileNotFoundException("MediaStore delete returned 0 rows"),
                baseMeta + mapOf("android_flow" to "legacy_delete", "result" to "NOT_FOUND")
            )
            DeleteMediaResult.NotFound
        }
    }

    private fun tryDirectTrash(context: Context, uri: Uri): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 1)
        }
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    private fun requestSystemTrashConfirmation(
        context: Context,
        item: MediaItem,
        uri: Uri,
        baseMeta: Map<String, String?>,
        error: Exception?
    ): DeleteMediaResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sender = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true).intentSender
                logTrashResult(item, baseMeta, "create_trash_request", "REQUIRES_SYSTEM_CONFIRMATION", systemConfirmation = true, error = error)
                DeleteMediaResult.RequiresSystemConfirmation(sender, alreadyPerformedOnApproval = true)
            } else if (error is RecoverableSecurityException) {
                val sender = error.userAction.actionIntent.intentSender
                logTrashResult(item, baseMeta, "recoverable_security_intent", "REQUIRES_SYSTEM_CONFIRMATION", systemConfirmation = true, error = error)
                DeleteMediaResult.RequiresSystemConfirmation(sender, alreadyPerformedOnApproval = false)
            } else {
                val denied = error ?: SecurityException("No write access to this MediaStore item")
                logMediaActionFailure(
                    "DELETE",
                    item,
                    denied,
                    baseMeta + mapOf(
                        "android_flow" to "no_system_confirmation_available",
                        "result" to "PERMISSION_DENIED",
                        "system_confirmation_required" to "true"
                    )
                )
                DeleteMediaResult.PermissionDenied
            }
        } catch (requestError: Exception) {
            logMediaActionFailure(
                "DELETE",
                item,
                requestError,
                baseMeta + mapOf(
                    "android_flow" to "create_trash_request",
                    "result" to "FAILED",
                    "system_confirmation_required" to "true",
                    "cause_exception" to error?.javaClass?.simpleName
                )
            )
            DeleteMediaResult.Failed
        }
    }

    private fun queryOwnerPackage(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun logTrashResult(
        item: MediaItem,
        baseMeta: Map<String, String?>,
        flow: String,
        result: String,
        systemConfirmation: Boolean,
        error: Exception? = null
    ) {
        val metadata = baseMeta + mapOf(
            "android_flow" to flow,
            "operation" to flow,
            "result" to result,
            "system_confirmation_required" to systemConfirmation.toString(),
            "exception_class" to error?.javaClass?.name,
            "exception_message" to error?.message
        )
        DeveloperLogger.info(
            LogCategory.MEDIASTORE,
            "MEDIA_DELETE_$result",
            if (systemConfirmation) "Local MediaStore trash requires Android confirmation" else "Local MediaStore trash result: $result",
            localMediaId = item.id,
            throwable = error,
            metadata = metadata
        )
    }

    suspend fun finalizeLocalDelete(id: String): Boolean = withContext(Dispatchers.IO) {
        locallyHiddenIds += id
        val existed = _media.value.any { it.id == id }
        _media.update { items -> items.filter { it.id != id } }
        favorites.retainAll(_media.value.mapTo(HashSet()) { it.id })
        rebuildAlbums()
        // Local MediaStore/Room refresh only. Supabase media_assets and archive data remain untouched.
        refresh(force = true)
        existed || !_media.value.any { it.id == id }
    }

    suspend fun restoreTrashedMedia(context: Context, id: String): TrashMutationResult = withContext(Dispatchers.IO) {
        mutateTrashedMedia(context, id, restore = true)
    }

    suspend fun permanentlyDeleteTrashedMedia(context: Context, id: String): TrashMutationResult = withContext(Dispatchers.IO) {
        mutateTrashedMedia(context, id, restore = false)
    }

    suspend fun restoreTrashedMedia(context: Context, ids: Collection<String>): TrashMutationResult = withContext(Dispatchers.IO) {
        mutateTrashedMediaBatch(context, ids, restore = true)
    }

    suspend fun permanentlyDeleteTrashedMedia(context: Context, ids: Collection<String>): TrashMutationResult = withContext(Dispatchers.IO) {
        mutateTrashedMediaBatch(context, ids, restore = false)
    }

    suspend fun emptyTrash(context: Context): TrashMutationResult = withContext(Dispatchers.IO) {
        val ids = _trashedMedia.value.map { it.id }
        if (ids.isEmpty()) return@withContext TrashMutationResult.Success
        mutateTrashedMediaBatch(context, ids, restore = false)
    }

    suspend fun finalizeTrashRestore(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val idSet = ids.toSet()
        locallyHiddenIds.removeAll(idSet)
        _trashedMedia.update { items -> items.filter { it.id !in idSet } }
        publishTrash(_trashedMedia.value)
        // Restore is local MediaStore only. Supabase media_assets remains untouched.
        refresh(force = true)
    }

    suspend fun finalizePermanentTrashDelete(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val idSet = ids.toSet()
        locallyHiddenIds.removeAll(idSet)
        _trashedMedia.update { items -> items.filter { it.id !in idSet } }
        publishTrash(_trashedMedia.value)
        // Permanent local delete only. Supabase media_assets and archive data remain untouched.
        refresh(force = true)
    }

    private fun publishTrash(items: List<MediaItem>) {
        _trashedMedia.value = items
        _trashSummary.value = TrashSummary(
            count = items.size,
            totalSizeBytes = items.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
        )
    }

    private fun mutateTrashedMedia(context: Context, id: String, restore: Boolean): TrashMutationResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return TrashMutationResult.Unsupported
        val item = _trashedMedia.value.firstOrNull { it.id == id }
        if (item == null) return TrashMutationResult.NotFound
        val uri = parseContentUri(item.uri) ?: return TrashMutationResult.Failed
        if (!permissions.canReadMedia()) return TrashMutationResult.PermissionDenied
        val action = if (restore) "RESTORE" else "PERMANENT_DELETE"
        val baseMeta = mediaActionMetadata(action, item)
        return try {
            if (restore) {
                if (tryDirectRestore(context, uri)) {
                    logTrashMutation(item, baseMeta, "direct_restore", "SUCCESS")
                    TrashMutationResult.Success
                } else {
                    requestTrashMutationConfirmation(context, listOf(uri), restore, item, baseMeta, null)
                }
            } else {
                requestTrashMutationConfirmation(context, listOf(uri), restore, item, baseMeta, null)
            }
        } catch (error: RecoverableSecurityException) {
            requestTrashMutationConfirmation(context, listOf(uri), restore, item, baseMeta, error)
        } catch (error: SecurityException) {
            requestTrashMutationConfirmation(context, listOf(uri), restore, item, baseMeta, error)
        } catch (error: FileNotFoundException) {
            logMediaActionFailure(action, item, error, baseMeta + mapOf("result" to "NOT_FOUND"))
            TrashMutationResult.NotFound
        } catch (error: Exception) {
            logMediaActionFailure(action, item, error, baseMeta + mapOf("result" to "FAILED"))
            TrashMutationResult.Failed
        }
    }

    private fun mutateTrashedMediaBatch(
        context: Context,
        ids: Collection<String>,
        restore: Boolean
    ): TrashMutationResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return TrashMutationResult.Unsupported
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return TrashMutationResult.Success
        if (!permissions.canReadMedia()) return TrashMutationResult.PermissionDenied
        val items = uniqueIds.mapNotNull { id -> _trashedMedia.value.firstOrNull { it.id == id } }
        if (items.isEmpty()) return TrashMutationResult.NotFound
        val uris = items.mapNotNull { parseContentUri(it.uri) }
        if (uris.isEmpty()) return TrashMutationResult.Failed
        val action = if (restore) "RESTORE" else "PERMANENT_DELETE"
        val sample = items.first()
        val baseMeta = mediaActionMetadata(action, sample) + mapOf("item_count" to items.size.toString())
        _trashProgress.value = TrashOperationProgress(inProgress = true, processed = 0, total = items.size)
        return try {
            if (restore) {
                val remaining = mutableListOf<Uri>()
                var restored = 0
                for (uri in uris) {
                    if (tryDirectRestore(context, uri)) restored += 1 else remaining += uri
                }
                if (remaining.isEmpty()) {
                    logTrashMutation(sample, baseMeta, "direct_restore_batch", "SUCCESS")
                    _trashProgress.value = TrashOperationProgress()
                    TrashMutationResult.Success
                } else {
                    requestTrashMutationConfirmation(context, remaining, restore = true, sample, baseMeta, null)
                }
            } else {
                requestTrashMutationConfirmation(context, uris, restore = false, sample, baseMeta, null)
            }
        } catch (error: RecoverableSecurityException) {
            requestTrashMutationConfirmation(context, uris, restore, sample, baseMeta, error)
        } catch (error: SecurityException) {
            requestTrashMutationConfirmation(context, uris, restore, sample, baseMeta, error)
        } catch (error: Exception) {
            _trashProgress.value = TrashOperationProgress()
            logMediaActionFailure(action, sample, error, baseMeta + mapOf("result" to "FAILED"))
            TrashMutationResult.Failed
        }
    }

    fun clearTrashProgress() {
        _trashProgress.value = TrashOperationProgress()
    }

    private fun tryDirectRestore(context: Context, uri: Uri): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 0)
        }
        return try {
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun requestTrashMutationConfirmation(
        context: Context,
        uris: List<Uri>,
        restore: Boolean,
        item: MediaItem,
        baseMeta: Map<String, String?>,
        error: Exception?
    ): TrashMutationResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pending = if (restore) {
                    MediaStore.createTrashRequest(context.contentResolver, uris, false)
                } else {
                    MediaStore.createDeleteRequest(context.contentResolver, uris)
                }
                val flow = if (restore) "create_restore_request" else "create_delete_request"
                logTrashMutation(item, baseMeta, flow, "REQUIRES_SYSTEM_CONFIRMATION", error)
                TrashMutationResult.RequiresSystemConfirmation(pending.intentSender, alreadyPerformedOnApproval = true)
            } else if (error is RecoverableSecurityException) {
                logTrashMutation(item, baseMeta, "recoverable_security_intent", "REQUIRES_SYSTEM_CONFIRMATION", error)
                TrashMutationResult.RequiresSystemConfirmation(error.userAction.actionIntent.intentSender, alreadyPerformedOnApproval = false)
            } else {
                _trashProgress.value = TrashOperationProgress()
                TrashMutationResult.PermissionDenied
            }
        } catch (requestError: Exception) {
            _trashProgress.value = TrashOperationProgress()
            logMediaActionFailure(
                if (restore) "RESTORE" else "PERMANENT_DELETE",
                item,
                requestError,
                baseMeta + mapOf("result" to "FAILED")
            )
            TrashMutationResult.Failed
        }
    }

    private fun parseContentUri(raw: String): Uri? {
        if (raw.isBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return if (uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank()) uri else null
    }

    private fun logTrashMutation(
        item: MediaItem,
        baseMeta: Map<String, String?>,
        flow: String,
        result: String,
        error: Exception? = null
    ) {
        DeveloperLogger.info(
            LogCategory.MEDIASTORE,
            "MEDIA_TRASH_$result",
            "Local MediaStore trash mutation: $flow",
            localMediaId = item.id,
            throwable = error,
            metadata = baseMeta + mapOf(
                "android_flow" to flow,
                "operation" to flow,
                "result" to result,
                "exception_class" to error?.javaClass?.name,
                "exception_message" to error?.message
            )
        )
    }

    suspend fun deleteMedia(context: Context, id: String): Boolean =
        deleteMediaWithResult(context, id) is DeleteMediaResult.Success

    fun hasMediaReadPermission(): Boolean = permissions.canReadMedia()
    fun probeMediaUri(rawUri: String) = mediaStore.probeUri(rawUri)

    private fun mediaActionMetadata(action: String, item: MediaItem): Map<String, String?> = mapOf(
        "action" to action,
        "media_uri" to item.uri,
        "mime_type" to item.mimeType,
        "android_api" to Build.VERSION.SDK_INT.toString()
    )

    private fun logMediaActionFailure(action: String, item: MediaItem, error: Throwable, extra: Map<String, String?> = emptyMap()) {
        DeveloperLogger.error(
            LogCategory.MEDIASTORE,
            "MEDIA_${action}_FAILED",
            "Media Viewer $action failed",
            localMediaId = item.id,
            throwable = error,
            metadata = mediaActionMetadata(action, item) + extra + mapOf(
                "exception_class" to error.javaClass.name,
                "exception_message" to error.message
            )
        )
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
