package com.mydrive.app.data.repository

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

    fun markPermissionAsked() {
        permissions.markAsked()
    }

    @Volatile
    private var viewerSessionIds: List<String>? = null

    fun mediaById(id: String): MediaItem? = _media.value.firstOrNull { it.id == id }

    fun albumById(id: String): AlbumFolder? = _albums.value.firstOrNull { it.id == id }

    fun albums(): List<AlbumFolder> = _albums.value

    fun beginViewerSession(ids: List<String>) {
        viewerSessionIds = ids
    }

    fun viewerSessionIds(): List<String>? = viewerSessionIds

    fun mediaForViewer(startId: String, albumId: String?): List<MediaItem> {
        val current = _media.value
        val byId = current.associateBy { it.id }
        val session = viewerSessionIds
            ?.mapNotNull { byId[it] }
            ?.takeIf { items -> items.any { it.id == startId } }
        if (session != null) return session
        val scoped = if (!albumId.isNullOrBlank()) {
            current.filter { it.albumId == albumId }
        } else {
            current
        }.sortedByDescending { it.capturedAtMillis }
        if (scoped.any { it.id == startId }) return scoped
        val start = byId[startId]
        return if (start != null) listOf(start) else scoped
    }

    fun toggleFavorite(id: String) {
        favorites.toggle(id)
        val favorite = favorites.contains(id)
        _media.update { items ->
            items.map { item ->
                if (item.id == id) item.copy(isFavorite = favorite) else item
            }
        }
    }

    fun updatePreferences(transform: (BackupPreferences) -> BackupPreferences) {
        _preferences.update(transform)
    }

    fun saveTelegramConfiguration(botToken: String?, chatId: String, enabled: Boolean) {
        telegramSettingsStore.saveConfiguration(
            botToken = botToken,
            chatId = chatId,
            enabled = enabled
        )
    }

    fun setTelegramBackupEnabled(enabled: Boolean) {
        telegramSettingsStore.setEnabled(enabled)
    }

    suspend fun testTelegramConnection(): TelegramVerificationResult {
        if (telegram.value.connectionState == TelegramConnectionState.TESTING) {
            return TelegramVerificationResult.TelegramUnavailable
        }
        val credentials = telegramSettingsStore.credentials()
        if (credentials == null) {
            telegramSettingsStore.markConnectionFailed("Configuration incomplete.")
            return TelegramVerificationResult.InvalidChatId
        }

        val result = try {
            telegramSettingsStore.markTesting()
            telegramApiVerifier.verify(
                botToken = credentials.botToken,
                chatId = credentials.chatId
            )
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

    fun markTelegramConnectionFailed(message: String) {
        telegramSettingsStore.markConnectionFailed(message)
    }

    fun clearTelegramConfiguration() {
        telegramSettingsStore.clear()
    }

    suspend fun refresh(force: Boolean = false) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && _media.value.isNotEmpty() && now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) {
                applyAccessState()
                return
            }
            applyAccessState()
            if (!permissions.canReadMedia()) {
                _media.value = emptyList()
                _albums.value = emptyList()
                _storage.value = StorageSummary(0, 0, 0, 0)
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
                return
            }
            val showSpinner = _media.value.isEmpty()
            _loadState.update { it.copy(isLoading = showSpinner, errorMessage = null) }
            try {
                val favoriteIds = favorites.ids.value
                val items = mediaStore.loadMedia().map { item ->
                    item.copy(isFavorite = item.id in favoriteIds)
                }
                // Prune references to media that no longer exists. This is only
                // safe after a complete load with full media access, otherwise a
                // partial query could wrongly discard still-valid references.
                if (permissions.access() == MediaAccess.GRANTED) {
                    val presentIds = withContext(Dispatchers.Default) {
                        items.mapTo(HashSet(items.size)) { it.id }
                    }
                    favorites.retainAll(presentIds)
                    syncRepository.reconcile(presentIds)
                }
                val records = syncRepository.records.value
                val merged = items.map { it.withRecord(records[it.id]) }
                _media.value = merged
                _albums.value = buildAlbums(merged)
                lastRefreshAt = now
                updateStorage(merged)
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
            } catch (_: MediaQueryException) {
                val keepExisting = _media.value.isNotEmpty()
                _loadState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (keepExisting) null else "Couldn't load your photos and videos."
                    )
                }
            } catch (_: SecurityException) {
                applyAccessState()
                _media.value = emptyList()
                _albums.value = emptyList()
                _loadState.update { it.copy(isLoading = false, errorMessage = null) }
            }
        }
    }

    private fun initialLoadState(): MediaLoadState {
        val access = permissions.access()
        val canRead = access == MediaAccess.GRANTED || access == MediaAccess.PARTIAL
        return MediaLoadState(
            accessGranted = access == MediaAccess.GRANTED,
            accessPartial = access == MediaAccess.PARTIAL,
            needsPermission = access == MediaAccess.NEEDS_REQUEST,
            permissionDenied = access == MediaAccess.DENIED,
            isLoading = canRead
        )
    }

    private fun applyAccessState() {
        val access = permissions.access()
        _loadState.update {
            it.copy(
                accessGranted = access == MediaAccess.GRANTED,
                accessPartial = access == MediaAccess.PARTIAL,
                needsPermission = access == MediaAccess.NEEDS_REQUEST,
                permissionDenied = access == MediaAccess.DENIED
            )
        }
    }

    private fun buildAlbums(items: List<MediaItem>): List<AlbumFolder> {
        return items
            .groupBy { it.albumId }
            .map { (albumId, albumItems) ->
                val cover = albumItems.maxByOrNull { it.capturedAtMillis }
                AlbumFolder(
                    id = albumId,
                    name = cover?.albumName?.ifBlank { "Other" } ?: "Other",
                    coverSeed = cover?.thumbnailSeed ?: 0,
                    coverType = cover?.type ?: MediaType.PHOTO,
                    mediaCount = albumItems.size,
                    coverUri = cover?.uri.orEmpty()
                )
            }
            .sortedByDescending { it.mediaCount }
    }

    private fun updateStorage(items: List<MediaItem>) {
        val photos = items.count { it.type == MediaType.PHOTO }
        val videos = items.count { it.type == MediaType.VIDEO }
        _storage.value = StorageSummary(
            totalMedia = items.size,
            photos = photos,
            videos = videos,
            pendingUploads = items.count { it.backupState != BackupState.COMPLETED }
        )
        _todayStats.value = TodayStats(
            photosBackedUp = 0,
            videosBackedUp = 0,
            pending = items.count { it.backupState != BackupState.COMPLETED },
            failed = items.count { it.backupState == BackupState.FAILED }
        )
        refreshSyncSummary()
    }

    private fun refreshSyncSummary() {
        val items = _media.value
        val inProgress = items.count { it.backupState.isActive }
        _syncSummary.update { it.copy(inProgressCount = inProgress, completedToday = 0) }
    }

    private fun MediaItem.withRecord(record: SyncRecord?): MediaItem {
        if (record == null) {
            if (backupState == BackupState.NOT_STARTED) return this
            return copy(
                backupState = BackupState.NOT_STARTED,
                backupCompleted = false,
                progress = 0f,
                errorMessage = null,
                cloudinaryAssetId = null,
                cloudinaryPublicId = null
            )
        }
        val state = record.state.toBackupState().resumeLocally()
        return copy(
            backupState = state,
            backupCompleted = state == BackupState.COMPLETED,
            progress = 0f,
            errorMessage = record.errorMessage,
            cloudinaryAssetId = record.cloudinaryAssetId,
            cloudinaryPublicId = record.cloudinaryPublicId
        )
    }

    fun retryBackup(id: String) {
        syncRepository.retry(id)
    }

    companion object {
        private const val MIN_REFRESH_INTERVAL_MS = 1_500L
    }
}
