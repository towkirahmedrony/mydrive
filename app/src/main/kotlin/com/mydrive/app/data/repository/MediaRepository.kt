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
import com.mydrive.app.data.local.CloudLibraryEntry
import com.mydrive.app.data.local.FavoritesStore
import com.mydrive.app.data.local.LibraryVisibilityStore
import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.media.FullImageLoader
import com.mydrive.app.data.media.MediaAlbumStats
import com.mydrive.app.data.media.MediaLibraryPaging
import com.mydrive.app.data.media.MediaPageCursor
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.data.session.AccountSession
import com.mydrive.app.data.media.MediaAccess
import com.mydrive.app.data.media.MediaPermissions
import com.mydrive.app.data.media.MediaQueryException
import com.mydrive.app.data.media.MediaStoreDataSource
import com.mydrive.app.data.remote.dto.MediaAssetRow
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

sealed class RemoveFromLibraryResult {
    data object Success : RemoveFromLibraryResult()
    data object NotFound : RemoveFromLibraryResult()
    data object Unauthorized : RemoveFromLibraryResult()
    data object Failed : RemoveFromLibraryResult()
}

class MediaRepository(
    private val mediaStore: MediaStoreDataSource,
    private val favorites: FavoritesStore,
    private val permissions: MediaPermissions,
    private val syncRepository: SyncRepository,
    private val telegramSettingsStore: TelegramSettingsStore,
    private val telegramApiVerifier: TelegramApiVerifier,
    private val mediaAssetsRepository: MediaAssetsRepository,
    private val visibilityStore: LibraryVisibilityStore,
    private val scope: CoroutineScope
) {

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()

    private val _deviceMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val deviceMedia: StateFlow<List<MediaItem>> = _deviceMedia.asStateFlow()

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
    private val sessionLock = Any()
    private var lastRefreshAt = 0L
    private val locallyHiddenIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var boundUserId: String? = null
    private var overlayRefreshJob: Job? = null
    private var catalogEpoch = 0
    private var nextPageCursor: MediaPageCursor? = null
    private var loadedRemoteRows: List<MediaAssetRow> = emptyList()
    private var cloudAlbumStats: MediaAlbumStats = MediaAlbumStats()

    init {
        scope.launch {
            syncRepository.records.collect { records ->
                synchronized(sessionLock) {
                    if (boundUserId.isNullOrBlank()) return@collect
                    val currentLibrary = _media.value
                    val currentDevice = _deviceMedia.value
                    if (currentLibrary.isEmpty() && currentDevice.isEmpty()) return@synchronized
                    val updatedLibrary = currentLibrary.map { it.withRecord(records[it.id]) }
                    val updatedDevice = currentDevice.map { it.withRecord(records[it.id]) }
                    if (updatedLibrary != currentLibrary) {
                        _media.value = updatedLibrary
                        updateStorage(updatedLibrary)
                    }
                    if (updatedDevice != currentDevice) {
                        _deviceMedia.value = updatedDevice
                    }
                }
            }
        }
    }

    fun requiredPermissions(): Array<String> = permissions.requiredPermissions()
    fun markPermissionAsked() { permissions.markAsked() }

    fun bindAccount(userId: String) {
        synchronized(sessionLock) {
            boundUserId = userId
            visibilityStore.bindUser(userId)
            favorites.bindUser(userId)
            resetCatalog(showLoading = true)
        }
    }

    fun clearAccountSession() {
        synchronized(sessionLock) {
            boundUserId = null
            viewerSessionIds = null
            locallyHiddenIds.clear()
            visibilityStore.clearSession()
            favorites.clearSession()
            ThumbnailLoader.evictMemory()
            FullImageLoader.evictMemory()
            resetCatalog(showLoading = false)
        }
    }

    private fun resetCatalog(showLoading: Boolean) {
        lastRefreshAt = 0L
        overlayRefreshJob?.cancel()
        overlayRefreshJob = null
        catalogEpoch += 1
        nextPageCursor = null
        loadedRemoteRows = emptyList()
        cloudAlbumStats = MediaAlbumStats()
        _media.value = emptyList()
        _deviceMedia.value = emptyList()
        _albums.value = emptyList()
        publishTrash(emptyList())
        _loadState.update {
            it.copy(
                isLoading = showLoading,
                isRefreshing = false,
                isLoadingMore = false,
                hasNextPage = false,
                errorMessage = null
            )
        }
    }

    fun onMediaStoreChanged() {
        if (boundUserId.isNullOrBlank()) return
        overlayRefreshJob?.cancel()
        overlayRefreshJob = scope.launch {
            delay(50)
            refresh(force = true, localOverlayOnly = true)
        }
    }

    @Volatile
    private var viewerSessionIds: List<String>? = null

    fun mediaById(id: String): MediaItem? =
        _media.value.firstOrNull { it.id == id } ?: _deviceMedia.value.firstOrNull { it.id == id }
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
        if (boundUserId.isNullOrBlank()) return
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

    suspend fun loadFirstPage() = refresh(force = true)

    suspend fun appendNextPage() = loadNextPage()

    suspend fun refresh(force: Boolean = false, localOverlayOnly: Boolean = false) {
        refreshMutex.withLock {
            val session = AccountSession.snapshot()
            val ownerId = boundUserId ?: session.userId
            if (ownerId.isNullOrBlank()) {
                synchronized(sessionLock) { resetCatalog(showLoading = false) }
                applyAccessState()
                return
            }
            val now = System.currentTimeMillis()
            if (!force && !localOverlayOnly && _media.value.isNotEmpty() && now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) {
                applyAccessState()
                return
            }
            applyAccessState()
            val canReadLocal = permissions.canReadMedia()
            if (!localOverlayOnly) {
                catalogEpoch += 1
                nextPageCursor = null
            }
            val showSpinner = !localOverlayOnly && _media.value.isEmpty()
            _loadState.update {
                it.copy(
                    isLoading = showSpinner,
                    isRefreshing = !localOverlayOnly && !showSpinner,
                    isLoadingMore = false,
                    hasNextPage = if (localOverlayOnly) it.hasNextPage else false,
                    errorMessage = null
                )
            }
            try {
                val favoriteIds = favorites.ids.value
                val scanned = if (canReadLocal) mediaStore.loadMedia() else emptyList()
                val trashed = if (canReadLocal) mediaStore.loadTrashedMedia() else emptyList()
                if (!sessionStillCurrent(session, ownerId)) return
                val scannedIds = scanned.mapTo(HashSet(scanned.size)) { it.id }
                val trashedIds = trashed.mapTo(HashSet(trashed.size)) { it.id }
                if (canReadLocal) {
                    locallyHiddenIds.removeAll { it !in scannedIds && it !in trashedIds }
                }
                val records = syncRepository.records.value
                val deviceItems = if (canReadLocal) {
                    scanned
                        .filter { it.id !in locallyHiddenIds }
                        .map { item -> item.copy(isFavorite = item.id in favoriteIds).withRecord(records[item.id]) }
                } else {
                    _deviceMedia.value
                }
                if (!sessionStillCurrent(session, ownerId)) return
                if (canReadLocal) {
                    syncRepository.reconcileMedia(deviceItems, expectedOwner = ownerId)
                }
                if (!sessionStillCurrent(session, ownerId)) return
                if (canReadLocal && permissions.access() == MediaAccess.GRANTED) {
                    val presentIds = withContext(Dispatchers.Default) { deviceItems.mapTo(HashSet(deviceItems.size)) { it.id } }
                    synchronized(sessionLock) {
                        if (!sessionStillCurrent(session, ownerId)) return
                        favorites.retainAll(
                            presentIds +
                                visibilityStore.hiddenLocalIds() +
                                visibilityStore.cloudEntries().keys +
                                favorites.ids.value.filter { it.startsWith("cloud-") }
                        )
                        // Missing MediaStore ids are local-unavailable, not cloud-deleted.
                        syncRepository.reconcile(presentIds + locallyHiddenIds + trashedIds)
                    }
                }
                synchronized(sessionLock) {
                    if (!sessionStillCurrent(session, ownerId)) return
                    if (canReadLocal) {
                        val nextIds = deviceItems.mapTo(HashSet(deviceItems.size)) { it.id }
                        _deviceMedia.value.forEach { previous ->
                            if (previous.id !in nextIds) rememberCloudCopy(previous.id)
                        }
                    }
                    if (canReadLocal) {
                        _deviceMedia.value = deviceItems
                        publishTrash(trashed)
                    }
                    if (localOverlayOnly) {
                        val cachedLibrary = composeLibrary(
                            deviceItems,
                            trashed,
                            favoriteIds,
                            records,
                            loadedRemoteRows
                        )
                        val libraryItems = mergePreservedCloudItems(cachedLibrary, deviceItems)
                        _media.value = libraryItems
                        _albums.value = buildAlbums(libraryItems, cloudAlbumStats)
                        updateStorage(libraryItems)
                        _loadState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false) }
                    }
                }
                if (localOverlayOnly) return
                loadFirstPageLocked(session, ownerId, deviceItems, trashed, favoriteIds, records, now)
            } catch (_: MediaQueryException) {
                synchronized(sessionLock) {
                    if (!sessionStillCurrent(session, ownerId)) return
                    applyAccessState()
                    val keepExisting = _media.value.isNotEmpty()
                    _loadState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            errorMessage = if (keepExisting) null else "Couldn't load your photos and videos."
                        )
                    }
                }
            } catch (_: SecurityException) {
                synchronized(sessionLock) {
                    if (!sessionStillCurrent(session, ownerId)) return
                    applyAccessState()
                    _loadState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, errorMessage = null) }
                }
            }
        }
    }

    suspend fun loadNextPage() {
        refreshMutex.withLock {
            val session = AccountSession.snapshot()
            val ownerId = boundUserId ?: session.userId
            if (ownerId.isNullOrBlank()) return
            val load = _loadState.value
            if (load.isLoading || load.isRefreshing || load.isLoadingMore || !load.hasNextPage) return
            val cursor = nextPageCursor ?: return
            _loadState.update { it.copy(isLoadingMore = true) }
            val epoch = catalogEpoch
            try {
                val page = mediaAssetsRepository.loadOwnerAssetsPage(cursor)
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                val favoriteIds = favorites.ids.value
                val records = syncRepository.records.value
                val deviceItems = _deviceMedia.value
                val trashed = _trashedMedia.value
                synchronized(sessionLock) {
                    if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                    loadedRemoteRows = MediaLibraryPaging.mergeRows(loadedRemoteRows, page.rows)
                    nextPageCursor = page.nextCursor
                    val libraryItems = composeLibrary(deviceItems, trashed, favoriteIds, records, loadedRemoteRows)
                    _media.value = libraryItems
                    _albums.value = buildAlbums(libraryItems, cloudAlbumStats)
                    updateStorage(libraryItems)
                    _loadState.update {
                        it.copy(
                            isLoadingMore = false,
                            hasNextPage = page.hasNextPage,
                            errorMessage = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                _loadState.update { it.copy(isLoadingMore = false) }
                throw cancelled
            } catch (_: Exception) {
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                _loadState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    private suspend fun loadFirstPageLocked(
        session: AccountSession.Snapshot,
        ownerId: String,
        deviceItems: List<MediaItem>,
        trashed: List<MediaItem>,
        favoriteIds: Set<String>,
        records: Map<String, SyncRecord>,
        now: Long
    ) {
        val replacing = _media.value.isNotEmpty()
        _loadState.update {
            it.copy(
                isLoading = !replacing,
                isRefreshing = replacing,
                isLoadingMore = false,
                errorMessage = null
            )
        }
        val epoch = catalogEpoch
        try {
            val page = mediaAssetsRepository.loadOwnerAssetsPage()
            val albumStats = runCatching { mediaAssetsRepository.loadCloudAlbumStats() }.getOrDefault(MediaAlbumStats())
            synchronized(sessionLock) {
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                loadedRemoteRows = page.rows
                nextPageCursor = page.nextCursor
                cloudAlbumStats = albumStats
                val libraryItems = composeLibrary(deviceItems, trashed, favoriteIds, records, page.rows)
                _media.value = libraryItems
                _albums.value = buildAlbums(libraryItems, albumStats)
                lastRefreshAt = now
                updateStorage(libraryItems)
                _loadState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        hasNextPage = page.hasNextPage,
                        errorMessage = null
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(sessionLock) {
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                val keepExisting = _media.value.isNotEmpty()
                _loadState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = if (keepExisting) null else "Couldn't load your photos and videos."
                    )
                }
            }
        }
    }

    private fun sessionStillCurrent(session: AccountSession.Snapshot, ownerId: String): Boolean {
        return boundUserId == ownerId &&
            AccountSession.isCurrent(session.userId, session.generation) &&
            AccountSession.userId == ownerId
    }

    private fun initialLoadState(): MediaLoadState {
        val access = permissions.access()
        return MediaLoadState(accessGranted = access == MediaAccess.GRANTED, accessPartial = access == MediaAccess.PARTIAL, needsPermission = access == MediaAccess.NEEDS_REQUEST, permissionDenied = access == MediaAccess.DENIED, isLoading = true)
    }

    private fun applyAccessState() {
        val access = permissions.access()
        _loadState.update { it.copy(accessGranted = access == MediaAccess.GRANTED, accessPartial = access == MediaAccess.PARTIAL, needsPermission = access == MediaAccess.NEEDS_REQUEST, permissionDenied = access == MediaAccess.DENIED) }
    }

    private fun buildAlbums(
        items: List<MediaItem>,
        cloudStats: MediaAlbumStats = cloudAlbumStats
    ): List<AlbumFolder> {
        val grouped = items.groupBy { it.albumId }.map { (albumId, albumItems) ->
            val cover = albumItems.maxByOrNull { it.capturedAtMillis }
            val count = if (albumId == "mydrive") {
                maxOf(albumItems.size, cloudStats.cloudOnlyCount)
            } else {
                albumItems.size
            }
            AlbumFolder(
                id = albumId,
                name = cover?.albumName?.ifBlank { "Other" } ?: "Other",
                coverSeed = cover?.thumbnailSeed ?: 0,
                coverType = cover?.type ?: MediaType.PHOTO,
                mediaCount = count,
                coverUri = cover?.displayUri.orEmpty(),
                coverRemoteMediaId = cover?.remoteMediaId
            )
        }.toMutableList()
        if (cloudStats.cloudOnlyCount > 0 && grouped.none { it.id == "mydrive" }) {
            val cover = cloudStats.cover
            val preview = cover?.thumbnailUrl ?: cover?.storageUrl.orEmpty()
            grouped += AlbumFolder(
                id = "mydrive",
                name = "My Drive",
                coverSeed = cover?.id.hashCode(),
                coverType = if (cover?.mimeType?.startsWith("video/") == true) MediaType.VIDEO else MediaType.PHOTO,
                mediaCount = cloudStats.cloudOnlyCount,
                coverUri = preview,
                coverRemoteMediaId = cover?.id
            )
        }
        return grouped.sortedByDescending { it.mediaCount }
    }

    private fun updateStorage(items: List<MediaItem>) {
        val photos = items.count { it.type == MediaType.PHOTO }
        val videos = items.count { it.type == MediaType.VIDEO }
        val deviceItems = _deviceMedia.value
        _storage.value = StorageSummary(totalMedia = items.size, photos = photos, videos = videos, pendingUploads = deviceItems.count { it.backupState != BackupState.COMPLETED })
        _todayStats.value = TodayStats(photosBackedUp = 0, videosBackedUp = 0, pending = deviceItems.count { it.backupState != BackupState.COMPLETED }, failed = deviceItems.count { it.backupState == BackupState.FAILED })
        refreshSyncSummary()
    }

    private fun refreshSyncSummary() {
        val items = _deviceMedia.value
        _syncSummary.update { it.copy(inProgressCount = items.count { it.backupState.isActive }, completedToday = 0) }
    }

    private fun MediaItem.withRecord(record: SyncRecord?): MediaItem {
        if (record == null) {
            if (backupState == BackupState.NOT_STARTED) return this
            return copy(backupState = BackupState.NOT_STARTED, backupCompleted = false, progress = 0f, errorMessage = null, cloudinaryAssetId = null, cloudinaryPublicId = null)
        }
        val state = record.state.toBackupState().resumeLocally()
        return copy(
            backupState = state,
            backupCompleted = state == BackupState.COMPLETED,
            progress = 0f,
            errorMessage = record.errorMessage,
            cloudinaryAssetId = record.cloudinaryAssetId,
            cloudinaryPublicId = record.cloudinaryPublicId,
            remoteMediaId = remoteMediaId ?: record.remoteMediaId,
            thumbnailUrl = thumbnailUrl ?: record.cloudinarySecureUrl
        )
    }

    private fun composeLibrary(
        deviceItems: List<MediaItem>,
        trashed: List<MediaItem>,
        favoriteIds: Set<String>,
        records: Map<String, SyncRecord>,
        remoteRows: List<com.mydrive.app.data.remote.dto.MediaAssetRow>
    ): List<MediaItem> {
        val hidden = HashSet(visibilityStore.hiddenLocalIds())
        val byLocalMediaId = remoteRows.mapNotNull { row ->
            row.localMediaId?.takeIf { it > 0L }?.let { it to row }
        }.toMap()
        val byRemoteId = remoteRows.associateBy { it.id }
        val byClientUpload = remoteRows.mapNotNull { row ->
            row.clientUploadId?.takeIf { it.isNotBlank() }?.let { it to row }
        }.toMap()

        fun matchRow(item: MediaItem, record: SyncRecord?) =
            byLocalMediaId[item.mediaStoreId]
                ?: record?.remoteMediaId?.let { byRemoteId[it] }
                ?: item.remoteMediaId?.let { byRemoteId[it] }
                ?: record?.clientUploadId?.let { byClientUpload[it] }

        for (item in deviceItems + trashed) {
            val record = records[item.id]
            val row = matchRow(item, record)
            if (row != null) {
                rememberCloudFromItem(item, row, record)
                if (row.isHiddenFromLibrary) hidden += item.id
            }
        }
        for (row in remoteRows) {
            if (!row.isHiddenFromLibrary) continue
            records.entries.firstOrNull { it.value.remoteMediaId == row.id }?.key?.let { hidden += it }
            records.entries.firstOrNull { it.value.clientUploadId == row.clientUploadId }?.key?.let { hidden += it }
        }
        visibilityStore.replaceHidden(hidden)

        val library = ArrayList<MediaItem>(deviceItems.size + remoteRows.size)
        val present = HashSet<String>()
        val presentRemoteIds = HashSet<String>()
        for (item in deviceItems) {
            if (item.id in hidden) continue
            val record = records[item.id]
            val row = matchRow(item, record)
            library += item.copy(
                remoteMediaId = row?.id ?: record?.remoteMediaId,
                thumbnailUrl = row?.thumbnailUrl ?: row?.storageUrl ?: record?.cloudinarySecureUrl,
                originLocal = true,
                hiddenFromLibrary = false
            )
            present += item.id
            row?.id?.let { presentRemoteIds += it }
        }

        val deviceIds = deviceItems.mapTo(HashSet()) { it.id }
        for (item in trashed) {
            if (item.id in hidden || item.id in present || item.id in deviceIds) continue
            val record = records[item.id]
            val row = matchRow(item, record)
            val cloud = visibilityStore.cloudEntry(item.id)
            if (row != null && !row.isHiddenFromLibrary && row.status != "DELETED") {
                rememberCloudFromItem(item, row, record)
                library += cloudMediaItem(item, row, record, favoriteIds)
                present += item.id
            } else if (cloud != null && row?.status != "DELETED") {
                library += cloud.toMediaItem(favoriteIds, record)
                present += item.id
            }
        }

        for ((localId, entry) in visibilityStore.cloudEntries()) {
            if (localId in hidden || localId in present || localId in deviceIds) continue
            library += entry.toMediaItem(favoriteIds, records[localId])
            presentRemoteIds += entry.remoteMediaId
        }

        // MediaStore is only the local-copy index. A backed-up row remains part
        // of My Drive even after another Gallery/File Manager removes its copy.
        val cachedByRemoteId = visibilityStore.cloudEntries().values.associateBy { it.remoteMediaId }
        for (row in remoteRows) {
            if (!row.isCloudAvailable || row.isHiddenFromLibrary || row.id in presentRemoteIds) continue
            val cached = cachedByRemoteId[row.id]
            val cloudId = cached?.localId ?: "cloud-${row.id}"
            library += cached?.toMediaItem(favoriteIds, records[cloudId])
                ?: row.toCloudOnlyMediaItem(cloudId, favoriteIds)
            rememberCloudFromRow(row, cloudId)
            presentRemoteIds += row.id
        }
        return library
            .distinctBy { it.remoteMediaId?.takeIf(String::isNotBlank) ?: "local:${it.id}" }
            .sortedByDescending { it.capturedAtMillis }
    }

    private fun mergePreservedCloudItems(
        overlay: List<MediaItem>,
        deviceItems: List<MediaItem>
    ): List<MediaItem> {
        val overlayIds = overlay.mapTo(HashSet(overlay.size)) { it.id }
        val overlayRemoteIds = overlay.mapNotNullTo(HashSet()) { it.remoteMediaId?.takeIf(String::isNotBlank) }
        val deviceIds = deviceItems.mapTo(HashSet(deviceItems.size)) { it.id }
        val preserved = _media.value.filter { item ->
            item.id !in overlayIds &&
                item.id !in deviceIds &&
                item.remoteMediaId !in overlayRemoteIds &&
                (item.backupCompleted || !item.originLocal || !item.remoteMediaId.isNullOrBlank())
        }
        if (preserved.isEmpty()) return overlay
        return (overlay + preserved)
            .distinctBy { it.remoteMediaId?.takeIf(String::isNotBlank) ?: "local:${it.id}" }
            .sortedByDescending { it.capturedAtMillis }
    }

    private fun com.mydrive.app.data.remote.dto.MediaAssetRow.toCloudOnlyMediaItem(
        stableId: String,
        favoriteIds: Set<String>
    ): MediaItem {
        val mediaType = if (mimeType?.startsWith("video/") == true) MediaType.VIDEO else MediaType.PHOTO
        val preview = thumbnailUrl ?: storageUrl.orEmpty()
        val captured = runCatching { java.time.Instant.parse(createdAt ?: "").toEpochMilli() }.getOrDefault(0L)
            .takeIf { it > 0L }
            ?: runCatching { java.time.Instant.parse(uploadedAt ?: "").toEpochMilli() }.getOrDefault(0L)
        return MediaItem(
            id = stableId,
            filename = fileName.orEmpty().ifBlank { "My Drive media" },
            type = mediaType,
            fileSizeBytes = fileSize ?: 0L,
            capturedAtMillis = captured,
            device = "My Drive",
            resolution = if ((width ?: 0) > 0 && (height ?: 0) > 0) "$width x $height" else "Unknown",
            durationSeconds = durationMs?.div(1000L)?.toInt(),
            isFavorite = stableId in favoriteIds,
            backupState = BackupState.COMPLETED,
            backupCompleted = true,
            thumbnailSeed = stableId.hashCode(),
            uri = preview,
            mimeType = mimeType.orEmpty(),
            width = width ?: 0,
            height = height ?: 0,
            durationMillis = durationMs,
            remoteMediaId = id,
            thumbnailUrl = preview.takeIf { it.isNotBlank() },
            originLocal = false,
            albumId = "mydrive",
            albumName = "My Drive"
        )
    }

    private fun rememberCloudCopy(id: String) {
        val item = lookupAnyItem(id) ?: return
        val record = syncRepository.records.value[id]
        val remoteId = item.remoteMediaId ?: record?.remoteMediaId.orEmpty()
        if (remoteId.isBlank()) return
        val preview = item.thumbnailUrl
            ?: record?.cloudinarySecureUrl
            ?: item.uri.takeIf { it.startsWith("http") }
            ?: ""
        visibilityStore.putCloud(
            CloudLibraryEntry(
                localId = id,
                remoteMediaId = remoteId,
                uri = preview,
                thumbnailUrl = preview,
                filename = item.filename,
                mimeType = item.mimeType,
                fileSizeBytes = item.fileSizeBytes,
                width = item.width,
                height = item.height,
                durationMillis = item.durationMillis,
                capturedAtMillis = item.capturedAtMillis,
                albumId = item.albumId,
                albumName = item.albumName,
                type = item.type.name
            )
        )
    }

    private fun rememberCloudFromItem(
        item: MediaItem,
        row: com.mydrive.app.data.remote.dto.MediaAssetRow,
        record: SyncRecord?
    ) {
        if (!row.isCloudAvailable) return
        val preview = row.thumbnailUrl ?: row.storageUrl ?: record?.cloudinarySecureUrl ?: ""
        visibilityStore.putCloud(
            CloudLibraryEntry(
                localId = item.id,
                remoteMediaId = row.id,
                uri = preview,
                thumbnailUrl = preview.takeIf { it.isNotBlank() },
                filename = item.filename,
                mimeType = item.mimeType.ifBlank { row.mimeType.orEmpty() },
                fileSizeBytes = item.fileSizeBytes.takeIf { it > 0L } ?: row.fileSize ?: 0L,
                width = item.width.takeIf { it > 0 } ?: row.width ?: 0,
                height = item.height.takeIf { it > 0 } ?: row.height ?: 0,
                durationMillis = item.durationMillis ?: row.durationMs,
                capturedAtMillis = item.capturedAtMillis,
                albumId = item.albumId,
                albumName = item.albumName,
                type = item.type.name
            )
        )
    }

    private fun rememberCloudFromRow(
        row: com.mydrive.app.data.remote.dto.MediaAssetRow,
        stableId: String
    ) {
        if (!row.isCloudAvailable) return
        val preview = row.thumbnailUrl ?: row.storageUrl ?: ""
        val mediaType = if (row.mimeType?.startsWith("video/") == true) MediaType.VIDEO else MediaType.PHOTO
        val captured = runCatching { java.time.Instant.parse(row.createdAt ?: "").toEpochMilli() }.getOrDefault(0L)
            .takeIf { it > 0L }
            ?: runCatching { java.time.Instant.parse(row.uploadedAt ?: "").toEpochMilli() }.getOrDefault(0L)
        visibilityStore.putCloud(
            CloudLibraryEntry(
                localId = stableId,
                remoteMediaId = row.id,
                uri = preview,
                thumbnailUrl = preview.takeIf { it.isNotBlank() },
                filename = row.fileName.orEmpty().ifBlank { "My Drive media" },
                mimeType = row.mimeType.orEmpty(),
                fileSizeBytes = row.fileSize ?: 0L,
                width = row.width ?: 0,
                height = row.height ?: 0,
                durationMillis = row.durationMs,
                capturedAtMillis = captured,
                albumId = "mydrive",
                albumName = "My Drive",
                type = mediaType.name
            )
        )
    }

    private fun cloudMediaItem(
        item: MediaItem,
        row: com.mydrive.app.data.remote.dto.MediaAssetRow,
        record: SyncRecord?,
        favoriteIds: Set<String>
    ): MediaItem {
        val preview = row.thumbnailUrl ?: row.storageUrl ?: record?.cloudinarySecureUrl ?: item.uri
        return item.copy(
            uri = preview,
            thumbnailUrl = preview,
            remoteMediaId = row.id,
            originLocal = false,
            isTrashed = false,
            hiddenFromLibrary = false,
            isFavorite = item.id in favoriteIds,
            backupState = BackupState.COMPLETED,
            backupCompleted = true
        )
    }

    private fun CloudLibraryEntry.toMediaItem(favoriteIds: Set<String>, record: SyncRecord?): MediaItem {
        val mediaType = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.PHOTO)
        val preview = thumbnailUrl ?: uri
        return MediaItem(
            id = localId,
            filename = filename,
            type = mediaType,
            fileSizeBytes = fileSizeBytes,
            capturedAtMillis = capturedAtMillis,
            device = "My Drive",
            resolution = if (width > 0 && height > 0) "$width x $height" else "Unknown",
            durationSeconds = durationMillis?.div(1000L)?.toInt(),
            isFavorite = localId in favoriteIds,
            backupState = BackupState.COMPLETED,
            backupCompleted = true,
            thumbnailSeed = localId.hashCode(),
            albumId = albumId,
            albumName = albumName,
            uri = preview,
            mimeType = mimeType,
            width = width,
            height = height,
            durationMillis = durationMillis,
            remoteMediaId = remoteMediaId.takeIf { it.isNotBlank() } ?: record?.remoteMediaId,
            thumbnailUrl = preview,
            originLocal = false,
            hiddenFromLibrary = false
        )
    }

    private fun lookupDeviceItem(id: String): MediaItem? =
        _deviceMedia.value.firstOrNull { it.id == id }
            ?: _media.value.firstOrNull { it.id == id && it.originLocal }

    private fun lookupAnyItem(id: String): MediaItem? =
        _media.value.firstOrNull { it.id == id }
            ?: _deviceMedia.value.firstOrNull { it.id == id }
            ?: _trashedMedia.value.firstOrNull { it.id == id }

    fun retryBackup(id: String) { syncRepository.retry(id) }

    suspend fun deleteMediaWithResult(context: Context, id: String): DeleteMediaResult = withContext(Dispatchers.IO) {
        val item = lookupDeviceItem(id)
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
        val existedOnDevice = _deviceMedia.value.any { it.id == id }
        _deviceMedia.update { items -> items.filter { it.id != id } }
        rememberCloudCopy(id)
        // Local MediaStore/Room refresh only. user_hidden_at, media_assets, and archive data remain untouched.
        refresh(force = true)
        existedOnDevice || _media.value.any { it.id == id } || !_deviceMedia.value.any { it.id == id }
    }

    suspend fun hideFromMyDrive(id: String): RemoveFromLibraryResult = withContext(Dispatchers.IO) {
        val item = lookupAnyItem(id) ?: return@withContext RemoveFromLibraryResult.NotFound
        val record = syncRepository.records.value[id]
        val remoteId = item.remoteMediaId ?: record?.remoteMediaId
        val result = mediaAssetsRepository.hideMatchingAsset(
            remoteMediaId = remoteId,
            localMediaId = item.mediaStoreId.takeIf { it > 0L },
            clientUploadId = record?.clientUploadId
        )
        val keepLocalHide = when (result) {
            HideMediaResult.Success -> true
            HideMediaResult.NotFound -> remoteId.isNullOrBlank()
            HideMediaResult.Unauthorized -> false
            HideMediaResult.Failed -> false
        }
        if (keepLocalHide) {
            visibilityStore.hideLocal(id)
            _media.update { items -> items.filter { it.id != id } }
            rebuildAlbums()
        }
        when (result) {
            HideMediaResult.Success -> {
                DeveloperLogger.info(
                    LogCategory.DATABASE,
                    "MEDIA_HIDDEN_FROM_LIBRARY",
                    "Hidden from My Drive library via user_hidden_at; device and archive unchanged",
                    localMediaId = id,
                    metadata = mapOf(
                        "remote_media_id" to remoteId,
                        "status_changed" to "false",
                        "deleted_at_changed" to "false"
                    )
                )
                RemoveFromLibraryResult.Success
            }
            HideMediaResult.NotFound -> if (keepLocalHide) RemoveFromLibraryResult.Success else RemoveFromLibraryResult.NotFound
            HideMediaResult.Unauthorized -> RemoveFromLibraryResult.Unauthorized
            HideMediaResult.Failed -> RemoveFromLibraryResult.Failed
        }
    }

    suspend fun unhideFromMyDrive(id: String): RemoveFromLibraryResult = withContext(Dispatchers.IO) {
        val item = lookupAnyItem(id)
        val record = syncRepository.records.value[id]
        visibilityStore.unhideLocal(id)
        val result = mediaAssetsRepository.unhideMatchingAsset(
            remoteMediaId = item?.remoteMediaId ?: record?.remoteMediaId,
            localMediaId = item?.mediaStoreId?.takeIf { it > 0L },
            clientUploadId = record?.clientUploadId
        )
        refresh(force = true)
        when (result) {
            HideMediaResult.Success -> RemoveFromLibraryResult.Success
            HideMediaResult.NotFound -> RemoveFromLibraryResult.NotFound
            HideMediaResult.Unauthorized -> RemoveFromLibraryResult.Unauthorized
            HideMediaResult.Failed -> RemoveFromLibraryResult.Failed
        }
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
        ids.forEach { restoreCloudTrash(it) }
        locallyHiddenIds.removeAll(idSet)
        _trashedMedia.update { items -> items.filter { it.id !in idSet } }
        publishTrash(_trashedMedia.value)
        refresh(force = true)
    }

    suspend fun finalizePermanentTrashDelete(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val idSet = ids.toSet()
        locallyHiddenIds.removeAll(idSet)
        _trashedMedia.update { items -> items.filter { it.id !in idSet } }
        publishTrash(_trashedMedia.value)
        refresh(force = true)
    }

    /** Move the cloud-backed record into the existing My Drive hidden/trash lifecycle. */
    suspend fun moveCloudToTrash(id: String): RemoveFromLibraryResult = withContext(Dispatchers.IO) {
        val item = lookupAnyItem(id)
        val record = syncRepository.records.value[id]
        val result = mediaAssetsRepository.hideMatchingAsset(
            remoteMediaId = item?.remoteMediaId ?: record?.remoteMediaId,
            localMediaId = item?.mediaStoreId?.takeIf { it > 0L },
            clientUploadId = record?.clientUploadId
        )
        if (result == HideMediaResult.Success) {
            refresh(force = true)
            val trashedItem = item?.copy(isTrashed = true, hiddenFromLibrary = true)
            if (trashedItem != null && _trashedMedia.value.none { it.id == id }) {
                publishTrash(_trashedMedia.value + trashedItem)
            }
        }
        when (result) {
            HideMediaResult.Success, HideMediaResult.NotFound -> RemoveFromLibraryResult.Success
            HideMediaResult.Unauthorized -> RemoveFromLibraryResult.Unauthorized
            HideMediaResult.Failed -> RemoveFromLibraryResult.Failed
        }
    }

    private suspend fun restoreCloudTrash(id: String) {
        val item = lookupAnyItem(id)
        val record = syncRepository.records.value[id]
        mediaAssetsRepository.unhideMatchingAsset(
            remoteMediaId = item?.remoteMediaId ?: record?.remoteMediaId,
            localMediaId = item?.mediaStoreId?.takeIf { it > 0L },
            clientUploadId = record?.clientUploadId
        )
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
        val item = lookupDeviceItem(id) ?: return@withContext false
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
        val item = lookupDeviceItem(id) ?: return@withContext false
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
            _deviceMedia.update { items ->
                items.map { if (it.id == id) updatedItem else it }
            }
            _media.update { items ->
                items.map { if (it.id == id) updatedItem else it }
            }
            syncRepository.updateQueueMedia(id, updatedItem)
            rebuildAlbums()
        }
        updated
    }

    suspend fun rotateMedia(context: Context, id: String, degrees: Float): Boolean = withContext(Dispatchers.IO) {
        val item = lookupDeviceItem(id) ?: return@withContext false
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
                fun applyRotation(current: MediaItem): MediaItem {
                    if (current.id != id) return current
                    val newW = if (swap) current.height else current.width
                    val newH = if (swap) current.width else current.height
                    return current.copy(
                        width = newW,
                        height = newH,
                        resolution = if (newW > 0 && newH > 0) "$newW x $newH" else current.resolution,
                        dateModifiedMillis = System.currentTimeMillis()
                    )
                }
                _deviceMedia.update { items -> items.map(::applyRotation) }
                _media.update { items -> items.map(::applyRotation) }
                FullImageLoader.clearCache()
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

    private fun rebuildAlbums() { _albums.value = buildAlbums(_media.value, cloudAlbumStats) }

    companion object { private const val MIN_REFRESH_INTERVAL_MS = 1_500L }
}
