package com.mydrive.app.data.repository

import com.mydrive.app.data.local.FavoritesStore
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
import com.mydrive.app.data.model.TodayStats
import com.mydrive.app.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaRepository(
    private val mediaStore: MediaStoreDataSource,
    private val favorites: FavoritesStore,
    private val permissions: MediaPermissions
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

    private val _telegram = MutableStateFlow(MockMediaData.telegramSettings)
    val telegram: StateFlow<TelegramSettings> = _telegram.asStateFlow()

    private val _storage = MutableStateFlow(MockMediaData.storageSummary)
    val storage: StateFlow<StorageSummary> = _storage.asStateFlow()

    private val _syncSummary = MutableStateFlow(MockMediaData.syncSummary)
    val syncSummary: StateFlow<SyncSummary> = _syncSummary.asStateFlow()

    private val refreshMutex = Mutex()
    private var lastRefreshAt = 0L

    fun requiredPermissions(): Array<String> = permissions.requiredPermissions()

    fun markPermissionAsked() {
        permissions.markAsked()
    }

    fun mediaById(id: String): MediaItem? = _media.value.firstOrNull { it.id == id }

    fun albumById(id: String): AlbumFolder? = _albums.value.firstOrNull { it.id == id }

    fun albums(): List<AlbumFolder> = _albums.value

    fun toggleFavorite(id: String) {
        favorites.toggle(id)
        val favorite = favorites.contains(id)
        _media.update { items ->
            items.map { item ->
                if (item.id == id) item.copy(isFavorite = favorite) else item
            }
        }
    }

    fun retryBackup(id: String) {
        _media.update { items ->
            items.map { item ->
                if (item.id == id && item.backupState == BackupState.FAILED) {
                    item.copy(
                        backupState = BackupState.UPLOADING,
                        progress = 0.12f,
                        errorMessage = null,
                        backupCompleted = false,
                        telegramCompleted = false
                    )
                } else {
                    item
                }
            }
        }
        refreshSyncSummary()
    }

    fun retryFailed() {
        _media.update { items ->
            items.map { item ->
                if (item.backupState == BackupState.FAILED) {
                    item.copy(
                        backupState = BackupState.UPLOADING,
                        progress = 0.12f,
                        errorMessage = null,
                        backupCompleted = false,
                        telegramCompleted = false
                    )
                } else {
                    item
                }
            }
        }
        refreshSyncSummary()
    }

    fun updatePreferences(transform: (BackupPreferences) -> BackupPreferences) {
        _preferences.update(transform)
    }

    fun disconnectTelegram() {
        _telegram.update { it.copy(connected = false, chatId = "") }
    }

    fun connectTelegram() {
        _telegram.update {
            it.copy(connected = true, chatId = if (it.chatId.isBlank()) "48291037" else it.chatId)
        }
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
                _media.value = items
                _albums.value = buildAlbums(items)
                lastRefreshAt = now
                updateStorage(items)
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
            pendingUploads = items.size
        )
        _todayStats.value = TodayStats(
            photosBackedUp = 0,
            videosBackedUp = 0,
            pending = items.size,
            failed = 0
        )
        refreshSyncSummary()
    }

    private fun refreshSyncSummary() {
        val items = _media.value
        val inProgress = items.count {
            it.backupState == BackupState.UPLOADING ||
                it.backupState == BackupState.PROCESSING ||
                it.backupState == BackupState.SENDING_TELEGRAM
        }
        _syncSummary.update { it.copy(inProgressCount = inProgress, completedToday = 0) }
    }

    companion object {
        private const val MIN_REFRESH_INTERVAL_MS = 1_500L
    }
}
