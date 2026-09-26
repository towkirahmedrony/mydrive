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
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import androidx.exifinterface.media.ExifInterface
import com.mydrive.app.data.local.CloudLibraryEntry
import com.mydrive.app.data.local.FavoritesStore
import com.mydrive.app.data.local.LibraryVisibilityStore
import com.mydrive.app.data.local.MediaCatalogReconciler
import com.mydrive.app.data.local.MediaCatalogStore
import com.mydrive.app.data.local.MediaSyncCursorStore
import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.local.toMediaItem
import com.mydrive.app.data.media.FullImageLoader
import com.mydrive.app.data.media.MediaLibraryPaging
import com.mydrive.app.data.media.MediaPageCursor
import com.mydrive.app.data.media.MediaSyncCursor
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.data.session.AccountSession
import com.mydrive.app.data.media.MediaAccess
import com.mydrive.app.data.media.MediaPermissions
import com.mydrive.app.data.media.MediaQueryException
import com.mydrive.app.data.media.MediaStoreDataSource
import com.mydrive.app.data.media.RemoteFailureClassifier
import com.mydrive.app.data.media.RemoteRefreshGate
import com.mydrive.app.data.remote.dto.MediaAssetRow
import com.mydrive.app.data.mock.MockMediaData
import com.mydrive.app.data.model.ActivityEvent
import com.mydrive.app.data.media.AlbumCoverResolver
import com.mydrive.app.data.media.classifyMediaSource
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.BackupOverview
import com.mydrive.app.data.model.BackupPreferences
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaAlbumRef
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaLoadState
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.UNGROUPED_ALBUM_NAME
import com.mydrive.app.data.model.resolveAlbum
import com.mydrive.app.data.model.resolveAlbums
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
import com.mydrive.app.debug.LogLevel
import com.mydrive.app.debug.MediaDiagnosticLogger
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
    data object RequiresManageMedia : DeleteMediaResult()
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

/** Outcome of saving an edited photo as a new device media item. */
sealed class SaveEditedPhotoResult {
    data class Saved(
        val itemId: String,
        val mediaStoreId: Long,
        val filename: String
    ) : SaveEditedPhotoResult()

    /** The user has not granted the storage permission the legacy path needs. */
    data object PermissionDenied : SaveEditedPhotoResult()
    data object Failed : SaveEditedPhotoResult()
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
    private val mediaSyncCursorStore: MediaSyncCursorStore,
    /**
     * Last known composed gallery, on disk. It is the single source of truth the
     * UI is restored from at launch and written back to after every
     * reconciliation, so Photos/Albums never depend on a scan or a network round
     * trip to become visible.
     */
    private val mediaCatalogStore: MediaCatalogStore,
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
    /** Collapses equivalent refresh bursts into one remote pass plus one trailing pass. */
    private val remoteRefreshGate = RemoteRefreshGate()
    private val sessionLock = Any()
    private var lastRefreshAt = 0L
    private val locallyHiddenIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var boundUserId: String? = null
    private var overlayRefreshJob: Job? = null
    private var catalogEpoch = 0
    private var nextPageCursor: MediaPageCursor? = null
    private var loadedRemoteRows: List<MediaAssetRow> = emptyList()

    /**
     * The account whose persisted catalog is already in memory. Guarantees the
     * hydration runs once per bind instead of on every refresh trigger, and that
     * a re-bind of the same account never blanks a populated gallery.
     */
    private var catalogHydratedOwner: String? = null

    /** Coalesced writer for the persisted catalog: at most one write in flight. */
    private val catalogWriteMutex = Mutex()
    private var catalogDirty = false
    private var catalogWriterJob: Job? = null

    private companion object {
        /** Per-composition cap for the verbose cloud availability trace. */
        const val AVAILABILITY_TRACE_BUDGET = 30
        private const val MIN_REFRESH_INTERVAL_MS = 1_500L

        /** EXIF tags copied from the original when saving an edited photo. */
        private val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FOCAL_LENGTH
        )
    }

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
                        // A backup-progress or error-state change is a real change to
                        // the rendered library, so it is published and persisted the
                        // same way a reconciliation result is.
                        publishLibrary(updatedLibrary)
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
    fun canManageMedia(): Boolean = permissions.canManageMedia()
    fun hasAskedManageMedia(): Boolean = permissions.hasAskedManageMedia()
    fun markManageMediaAsked() { permissions.markManageMediaAsked() }
    fun manageMediaRequestIntent() = permissions.manageMediaRequestIntent()

    fun bindAccount(userId: String) {
        val previousOwner = synchronized(sessionLock) {
            val previous = boundUserId
            boundUserId = userId
            visibilityStore.bindUser(userId)
            favorites.bindUser(userId)
            // The synchronization cursor is account-scoped for the same reason
            // the visibility and favorite caches are: account B must never resume
            // from account A's position.
            mediaSyncCursorStore.bindUser(userId)
            if (previous != userId) {
                // A different account's catalog is not this account's data, so the
                // in-memory catalog is dropped. The persistence itself is keyed by
                // owner and is left alone: signing back in must find it again.
                catalogHydratedOwner = null
                resetCatalog(showLoading = true)
            } else {
                // Same account, still in memory (process alive, session refreshed):
                // keep showing it. There is nothing to reload.
                lastRefreshAt = 0L
            }
            previous
        }
        if (previousOwner == userId) return
        // Synchronous, on-disk hydration: by the time the authenticated state is
        // published and the Photos/Albums host composes, the last known library is
        // already in `_media`/`_albums`. No spinner, no temporary dataset.
        hydrateLocalCatalog(userId)
    }

    fun clearAccountSession() {
        synchronized(sessionLock) {
            boundUserId = null
            catalogHydratedOwner = null
            viewerSessionIds = null
            locallyHiddenIds.clear()
            visibilityStore.clearSession()
            favorites.clearSession()
            mediaSyncCursorStore.clearSession()
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
        cloudSnapshotFallback = null
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

    /**
     * Restores the last known gallery from disk.
     *
     * This runs before the first Photos/Albums composition and does no scan, no
     * network request and no reconciliation: it is a single indexed read of rows
     * this account already had. Afterwards the screen is showing real, usable
     * content, and the background reconciliation only has to *amend* it.
     *
     * When nothing is persisted for this account — a first launch, a fresh
     * install, or an install upgrading from a build that had no catalog — the
     * initialization state is kept. That is the only case where a loading state is
     * truthful, and it is deliberately preferred over publishing the cloud-only
     * snapshot, which is a *different, partial* dataset and would reproduce the
     * very jump this architecture removes. That snapshot is not thrown away: it
     * seeds the first pass and stands in whenever a refresh fails.
     */
    private fun hydrateLocalCatalog(userId: String) {
        if (catalogHydratedOwner == userId) return
        catalogHydratedOwner = userId
        val startedAt = System.currentTimeMillis()
        // Blocking-on-IO read, allowed on the main thread because the launch bind
        // runs there and the gallery has to have its data before the first
        // composition. Deferring this to a background read is what made a launch
        // render an empty library and then fill it in once the scan finished.
        val rows = mediaCatalogStore.snapshotForStartup(userId)
        synchronized(sessionLock) {
            if (boundUserId != userId) return
            // The cloud snapshot is only a synchronization seed and a failure
            // fallback; it is never the gallery.
            seedCloudRowsFromCache()
            if (rows.isNullOrEmpty()) {
                // `rows == null` means the read itself failed — never "empty".
                // Keep restoring and finish off-thread; a loading state here would
                // be a spinner over a gallery that may well exist.
                if (rows == null) {
                    _loadState.update { it.copy(isRestoring = true) }
                    scope.launch { hydrateLocalCatalogAsync(userId) }
                    return
                }
                // Nothing persisted for this account yet: a first launch, a fresh
                // install, or an install upgrading from a build without the
                // catalog. This is the one case where a loading state is honest —
                // publishing the cloud-only subset here would be the very
                // "partial dataset, then a different dataset" flicker.
                _loadState.update {
                    it.copy(
                        isLoading = true,
                        isRestoring = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = null
                    )
                }
                return
            }
            _media.value = rows.map { it.toMediaItem(favorites.ids.value) }.resolveAlbums()
            _albums.value = buildAlbums(_media.value)
            updateStorage(_media.value)
            _loadState.update { it.copy(isLoading = false, isRestoring = false, errorMessage = null) }
            DeveloperLogger.info(
                category = LogCategory.DATABASE,
                event = "CATALOG_HYDRATED",
                message = "Gallery restored from the local catalog before any scan or network pass",
                metadata = mapOf(
                    "media" to _media.value.size.toString(),
                    "albums" to _albums.value.size.toString(),
                    "rows" to rows.size.toString(),
                    "duration_ms" to (System.currentTimeMillis() - startedAt).toString()
                )
            )
        }
    }

    /**
     * Opens the local catalog database ahead of the launch bind.
     *
     * Opening the database — and running any pending migration — is the slowest
     * part of the first read and pure overhead on a cold start. Paying it here, on
     * IO and before auth resolves, keeps the launch read itself down to one
     * indexed scan.
     */
    fun warmLocalCatalog() {
        mediaCatalogStore.markWarmUpScheduled()
        scope.launch { mediaCatalogStore.warmUp() }
    }

    /**
     * Same restoration, off the caller's thread, for the case where the blocking
     * read could not be completed at all (a database failure). The screen owns the
     * loading state for those few milliseconds only.
     */
    private suspend fun hydrateLocalCatalogAsync(userId: String) {
        val rows = mediaCatalogStore.snapshot(userId)
        synchronized(sessionLock) {
            if (boundUserId != userId || _media.value.isNotEmpty()) {
                _loadState.update { it.copy(isRestoring = false) }
                return
            }
            if (rows.isEmpty()) {
                // Genuinely nothing cached for this account. Whether that deserves
                // a loading state is now the refresh's decision, not ours.
                _loadState.update { it.copy(isRestoring = false) }
                return
            }
            _media.value = rows.map { it.toMediaItem(favorites.ids.value) }.resolveAlbums()
            _albums.value = buildAlbums(_media.value)
            updateStorage(_media.value)
            _loadState.update { it.copy(isLoading = false, isRestoring = false, errorMessage = null) }
            DeveloperLogger.info(
                category = LogCategory.DATABASE,
                event = "CATALOG_HYDRATED",
                message = "Gallery restored from the local catalog before any scan or network pass",
                metadata = mapOf(
                    "restored_media" to _media.value.size.toString(),
                    "albums" to _albums.value.size.toString()
                )
            )
        }
    }

    /**
     * Publishes a newly composed library as the single UI source of truth.
     *
     * StateFlow only emits on a *changed* value, so an unchanged reconciliation is
     * already invisible to Compose; the persisted catalog is written for the same
     * reason only when something actually differs.
     */
    private fun publishLibrary(items: List<MediaItem>, prune: Boolean = true) {
        val previous = _media.value
        // Album identity is resolved once, here, for every path that reaches the
        // UI: composition, the local-overlay pass and the cloud-only fallback.
        val resolved = items.resolveAlbums()
        _media.value = resolved
        _albums.value = buildAlbums(resolved)
        updateStorage(resolved)
        scheduleCatalogPersist(previous, resolved, prune)
    }

    /**
     * Applies one local operation to the rendered library and the persisted
     * catalog together.
     *
     * A delete, trash, restore, rename or rotate is already correct in memory;
     * remembering it at the same moment is what keeps the next cold start correct
     * instead of serving media the user removed until a scan happens to notice.
     */
    private fun mutateLibrary(transform: (List<MediaItem>) -> List<MediaItem>) {
        val previous = _media.value
        val updated = transform(previous)
        if (updated == previous) return
        _media.value = updated
        _albums.value = buildAlbums(updated)
        updateStorage(updated)
        scheduleCatalogPersist(previous, updated)
    }

    /**
     * Writes the difference between [previous] and [next] to disk, coalesced.
     *
     * At most one writer runs at a time and a burst of publications collapses into
     * the newest snapshot: a reconciliation that touches ten items writes ten
     * rows, a reconciliation that changes nothing writes nothing.
     */
    private fun scheduleCatalogPersist(
        previous: List<MediaItem>,
        next: List<MediaItem>,
        prune: Boolean = true
    ) {
        val owner = boundUserId?.takeIf { it.isNotBlank() } ?: return
        if (MediaCatalogReconciler.diff(owner, previous, next).isEmpty) {
            // The rendered library did not change: nothing to write, and no
            // coroutine is started at all.
            return
        }
        catalogDirty = true
        pendingCatalogPrevious = previous
        pendingCatalogNext = next
        pendingCatalogPrune = prune
        if (catalogWriterJob?.isActive == true) return
        catalogWriterJob = scope.launch {
            catalogWriteMutex.withLock {
                while (catalogDirty) {
                    catalogDirty = false
                    val snapshotPrevious = pendingCatalogPrevious
                    val snapshotNext = pendingCatalogNext
                    val snapshotPrune = pendingCatalogPrune
                    if (boundUserId != owner) return@withLock
                    runCatching {
                        mediaCatalogStore.save(
                            ownerUserId = owner,
                            previous = snapshotPrevious,
                            next = snapshotNext,
                            prune = snapshotPrune
                        )
                    }.onFailure { error ->
                        // A cache write failure is not a gallery failure: the screen
                        // keeps the content it already has.
                        DeveloperLogger.warn(
                            category = LogCategory.DATABASE,
                            event = "CATALOG_PERSIST_FAILED",
                            message = "Could not persist the local gallery catalog; UI state is unaffected",
                            throwable = error
                        )
                    }
                }
            }
        }
    }

    @Volatile
    private var pendingCatalogPrevious: List<MediaItem> = emptyList()

    @Volatile
    private var pendingCatalogNext: List<MediaItem> = emptyList()

    /** Whether the pending write may delete rows this pass could not account for. */
    @Volatile
    private var pendingCatalogPrune: Boolean = true

    /**
     * The last known cloud-only snapshot, held as a failure fallback only.
     * Session-scoped: it is derived from [visibilityStore] and rebuilt on bind.
     */
    @Volatile
    private var cloudSnapshotFallback: List<MediaItem>? = null



    /**
     * Seeds the in-memory cloud rows from the persisted per-account cloud snapshot.
     *
     * These rows are NOT the gallery. They exist only so the first background pass
     * can be an incremental one instead of a full first-page load. The item list
     * is kept aside as a *fallback* — see [publishCloudFallbackIfEmpty] — because
     * it is a cloud-only subset and publishing it as the live gallery is exactly
     * the "partial dataset, then a different dataset" behaviour this architecture
     * removes.
     */
    private fun seedCloudRowsFromCache() {
        cloudSnapshotFallback = null
        val cached = visibilityStore.cloudEntries()
        if (cached.isEmpty()) return
        val records = syncRepository.records.value
        val favoriteIds = favorites.ids.value
        val cachedRows = cached.values.map { entry ->
            MediaAssetRow(
                id = entry.remoteMediaId,
                fileName = entry.filename,
                mimeType = entry.mimeType,
                fileSize = entry.fileSizeBytes,
                width = entry.width,
                height = entry.height,
                durationMs = entry.durationMillis,
                storageUrl = entry.uri.takeIf { it.isNotBlank() },
                thumbnailUrl = entry.thumbnailUrl,
                status = "READY",
                createdAt = entry.capturedAtMillis.takeIf { it > 0L }
                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
            )
        }
        loadedRemoteRows = cachedRows
        cloudSnapshotFallback = cached.values
            .map { it.toMediaItem(favoriteIds, records[it.localId]) }
            .sortedWith(compareByDescending<MediaItem> { it.capturedAtMillis }.thenBy { it.id })
    }

    /**
     * Shows the last known cloud-only snapshot when a refresh failed and there is
     * otherwise nothing to display.
     *
     * A failed refresh must never hide content the account already has. This is
     * deliberately memory-only: the cloud snapshot is not the composed gallery, so
     * it must not become the persisted catalog and be mistaken for one next launch.
     *
     * @return true when something was published.
     */
    private fun publishCloudFallbackIfEmpty(): Boolean {
        if (_media.value.isNotEmpty()) return false
        val fallback = cloudSnapshotFallback
        if (fallback.isNullOrEmpty()) return false
        _media.value = fallback
        _albums.value = buildAlbums(fallback)
        updateStorage(fallback)
        DeveloperLogger.info(
            category = LogCategory.DATABASE,
            event = "CATALOG_FALLBACK_PUBLISHED",
            message = "Refresh failed; showing the last known cloud snapshot instead of an error",
            metadata = mapOf("media" to fallback.size.toString())
        )
        return true
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

    /**
     * The locally-sourceable representation of [id], for upload only.
     *
     * Deliberately separate from [mediaById]: the composed library also carries
     * cloud-only representations whose `uri` is the Cloudinary delivery URL, which
     * is the right thing to *display* and the wrong thing to upload. Only a local
     * `originLocal` item with a local URI can be the body of an upload, so anything
     * else is reported as "no local source" rather than being handed to
     * `ContentResolver`.
     */
    fun localUploadSourceById(id: String): MediaItem? {
        fun locallySourceable(item: MediaItem): Boolean =
            item.originLocal && classifyMediaSource(item.uri, originLocal = true).isLocalUploadSource
        return _deviceMedia.value.firstOrNull { it.id == id }?.takeIf(::locallySourceable)
            ?: _media.value.firstOrNull { it.id == id }?.takeIf(::locallySourceable)
    }

    /**
     * Drops persisted cloud-index entries for [localIds].
     *
     * Used when the server stops confirming a media the local index believed was in
     * the cloud. Without this the stale belief would keep the media out of every
     * future backup run, so a genuinely removed cloud copy could never be restored.
     */
    fun forgetCloudIndexEntries(localIds: Set<String>) {
        if (localIds.isEmpty()) return
        localIds.forEach { visibilityStore.removeCloud(it) }
    }
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
        val previous = _media.value
        val updated = previous.map { item -> if (item.id == id) item.copy(isFavorite = favorite) else item }
        if (updated == previous) return
        _media.value = updated
        // Favorites are part of the rendered library, so the favorite state that a
        // cold start restores must include this one.
        scheduleCatalogPersist(previous, updated)
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
        // A remote pass is already running for this account: fold this request into
        // it instead of queueing a duplicate remote reconciliation behind the
        // mutex. The running pass re-reads the same catalog, so a second pass is
        // only owed for a request that was *forced* (a permission grant, an
        // explicit retry) and would otherwise be swallowed by a pass that started
        // without it. Local-overlay refreshes are never folded: they do no network
        // work and must reflect a MediaStore change immediately.
        val remoteRequested = !localOverlayOnly
        if (remoteRequested && !remoteRefreshGate.begin(force = force)) {
            applyAccessState()
            return
        }
        try {
            refreshInternal(force = force, localOverlayOnly = localOverlayOnly)
        } finally {
            if (remoteRequested && remoteRefreshGate.end()) {
                scope.launch { refresh(force = true) }
            }
        }
    }

    private suspend fun refreshInternal(force: Boolean, localOverlayOnly: Boolean) {
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
            // A routine refresh is incremental whenever this account already has a
            // synchronized position *and* its catalog is already in memory. The
            // initial load is still the paginated `created_at` load, so a cold
            // start, a new account or a new device keeps the existing behavior
            // and re-establishes the page cursor from real data.
            val incrementalCursor = if (localOverlayOnly) null else mediaSyncCursorStore.read()
            val incrementalSync = incrementalCursor != null && loadedRemoteRows.isNotEmpty()
            if (!localOverlayOnly) {
                // Only the epoch is bumped: the last known page cursor and rows are
                // kept so a failed refresh leaves pagination exactly as it was.
                catalogEpoch += 1
            }
            // A blocking loading state is only honest when there is genuinely
            // nothing to show: not while the persisted gallery is still being read
            // off disk, and not while content is on screen.
            val showSpinner = !localOverlayOnly && _media.value.isEmpty() &&
                !_loadState.value.isRestoring
            _loadState.update {
                it.copy(
                    isLoading = showSpinner,
                    isRefreshing = !localOverlayOnly && !showSpinner,
                    isLoadingMore = false,
                    // An incremental pass leaves the `created_at` keyset cursor and
                    // its paging state exactly as they were; only a first-page load
                    // restarts them, from its own response.
                    hasNextPage = if (localOverlayOnly || incrementalSync) it.hasNextPage else false,
                    errorMessage = null
                )
            }
            // Identifies this pass so a cancellation can only clean up after
            // itself, never after a newer refresh that already took over.
            val epoch = catalogEpoch
            try {
                val favoriteIds = favorites.ids.value
                // A MediaStore query that failed only proves that the scan is
                // incomplete. `localScanComplete` therefore gates every retention
                // step below: cloud rows, favorites and backup records survive a
                // partial or permission-limited scan.
                val scan = if (canReadLocal) {
                    mediaStore.loadMediaScan()
                } else {
                    MediaStoreDataSource.MediaScanResult(emptyList(), complete = false)
                }
                val trashScan = if (canReadLocal) {
                    mediaStore.loadTrashedMediaScan()
                } else {
                    MediaStoreDataSource.MediaScanResult(emptyList(), complete = false)
                }
                val scanned = scan.items
                val trashed = trashScan.items
                val localScanComplete = scan.complete
                if (!sessionStillCurrent(session, ownerId)) return
                val scannedIds = scanned.mapTo(HashSet(scanned.size)) { it.id }
                val trashedIds = trashed.mapTo(HashSet(trashed.size)) { it.id }
                if (canReadLocal) {
                    locallyHiddenIds.removeAll { it !in scannedIds && it !in trashedIds }
                }
                val records = syncRepository.records.value
                val verifiedItems = if (canReadLocal) {
                    scanned
                        .filter { it.id !in locallyHiddenIds }
                        .map { item -> item.copy(isFavorite = item.id in favoriteIds).withRecord(records[item.id]) }
                } else {
                    _deviceMedia.value
                }
                // A pass may only speak for the whole device when it could both
                // read local media and see all of it. Anything else — no read
                // permission, or "selected photos" partial access, or a failed
                // collection query — is an incomplete view of the device and must
                // never be allowed to shrink what the user can see.
                val authoritativeLocalRead = canReadLocal &&
                    localScanComplete &&
                    permissions.access() == MediaAccess.GRANTED
                val deviceItems = if (authoritativeLocalRead) {
                    verifiedItems
                } else {
                    // Carry over what this pass could not verify, from the library
                    // that is already on screen. The result is a superset of the
                    // visible set, so an incomplete scan can add media but never
                    // remove a folder or a photo the user already had.
                    val verifiedIds = if (canReadLocal) scannedIds + trashedIds else emptySet()
                    val carried = _media.value.filter { item ->
                        item.id !in verifiedIds && item.originLocal && !item.isTrashed
                    }
                    if (carried.isEmpty()) {
                        verifiedItems
                    } else {
                        DeveloperLogger.info(
                            category = LogCategory.MEDIASTORE,
                            event = "SCAN_INCOMPLETE_CARRYING_LIBRARY",
                            message = "Local scan could not see every folder; keeping the known library",
                            metadata = mapOf(
                                "verified" to verifiedItems.size.toString(),
                                "carried_over" to carried.size.toString(),
                                "full_access" to (permissions.access() == MediaAccess.GRANTED).toString(),
                                "scan_complete" to localScanComplete.toString()
                            )
                        )
                        verifiedItems + carried
                    }
                }
                if (!sessionStillCurrent(session, ownerId)) return
                if (canReadLocal) {
                    syncRepository.reconcileMedia(deviceItems, expectedOwner = ownerId)
                }
                if (!sessionStillCurrent(session, ownerId)) return
                if (canReadLocal && localScanComplete && permissions.access() == MediaAccess.GRANTED) {
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
                    if (canReadLocal && localScanComplete) {
                        _deviceMedia.value = deviceItems
                    }
                    if (canReadLocal) {
                        // An incomplete trash scan keeps the last known Trash list
                        // instead of showing an empty Trash that never happened.
                        if (trashScan.complete) publishTrash(trashed)
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
                        // A MediaStore notification may only add or update: only a
                        // pass that could see the whole device may remove rows.
                        publishLibrary(libraryItems, prune = authoritativeLocalRead)
                        _loadState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false) }
                    }
                }
                if (localOverlayOnly) return
                if (incrementalSync && incrementalCursor != null) {
                    syncChangedRows(
                        session = session,
                        ownerId = ownerId,
                        deviceItems = deviceItems,
                        trashed = trashed,
                        favoriteIds = favoriteIds,
                        records = records,
                        cursor = incrementalCursor,
                        prune = authoritativeLocalRead,
                        now = now
                    )
                } else {
                    loadFirstPageLocked(
                        session,
                        ownerId,
                        deviceItems,
                        trashed,
                        favoriteIds,
                        records,
                        prune = authoritativeLocalRead,
                        now = now
                    )
                }
            } catch (cancelled: CancellationException) {
                // The caller went away (screen left, refresh superseded). Nothing
                // partial was committed, and the transient loading flags are only
                // cleared while this pass still owns the catalog epoch, so a newer
                // refresh is never clobbered.
                synchronized(sessionLock) {
                    if (sessionStillCurrent(session, ownerId) && epoch == catalogEpoch) {
                        _loadState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                hasNextPage = nextPageCursor != null
                            )
                        }
                    }
                }
                throw cancelled
            } catch (_: MediaQueryException) {
                synchronized(sessionLock) {
                    if (!sessionStillCurrent(session, ownerId)) return
                    applyAccessState()
                    val keepExisting = keepOrFallbackContent()
                    _loadState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            // A local query failure is not an empty device and not
                            // an empty cloud catalog: keep whatever is loaded, and
                            // keep paging available while a cursor still exists.
                            hasNextPage = nextPageCursor != null,
                            errorMessage = if (keepExisting) null else "Couldn't load your photos and videos."
                        )
                    }
                }
            } catch (_: SecurityException) {
                synchronized(sessionLock) {
                    if (!sessionStillCurrent(session, ownerId)) return
                    applyAccessState()
                    _loadState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            hasNextPage = nextPageCursor != null,
                            errorMessage = null
                        )
                    }
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
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) {
                    // The page belongs to a superseded session/epoch: drop it, but
                    // always release the append guard, otherwise paging would stay
                    // blocked behind isLoadingMore forever.
                    _loadState.update { it.copy(isLoadingMore = false) }
                    return
                }
                val favoriteIds = favorites.ids.value
                val records = syncRepository.records.value
                val deviceItems = _deviceMedia.value
                val trashed = _trashedMedia.value
                synchronized(sessionLock) {
                    if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) {
                        _loadState.update { it.copy(isLoadingMore = false) }
                        return
                    }
                    loadedRemoteRows = MediaLibraryPaging.mergeRows(loadedRemoteRows, page.rows)
                    nextPageCursor = page.nextCursor
                    // Paging forward reads rows this account already had; it may
                    // therefore only ever move the synchronization position
                    // forward, never back.
                    mediaSyncCursorStore.advanceTo(
                        MediaLibraryPaging.latestSyncCursor(null, page.rows)
                    )
                    val libraryItems = composeLibrary(deviceItems, trashed, favoriteIds, records, loadedRemoteRows)
                    publishLibrary(libraryItems)
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
            } catch (error: Exception) {
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                // Page N+1 could not be read. The rows already loaded stay, the
                // cursor stays, and the user can simply scroll again.
                DeveloperLogger.warn(
                    category = LogCategory.DATABASE,
                    event = "CATALOG_PAGE_FAILED",
                    message = "Failed to append the next catalog page; keeping the loaded rows",
                    throwable = error,
                    metadata = mapOf("failure" to RemoteFailureClassifier.classify(error).name)
                )
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
        prune: Boolean,
        now: Long
    ) {
        // "Replacing" means the user already has something on screen — content, or
        // a catalog still being restored from disk. Both make a full-screen loading
        // state wrong.
        val replacing = _media.value.isNotEmpty() || _loadState.value.isRestoring
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
            synchronized(sessionLock) {
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                // The freshly read page is authoritative for the newest rows, but a
                // row that is simply not on page 1 has not stopped existing. Keeping
                // the previously known rows is what stops one launch from shrinking
                // the whole cloud catalog to 80 records — which is exactly how
                // already-uploaded media used to end up looking un-backed-up.
                loadedRemoteRows = MediaLibraryPaging.mergeRows(page.rows, loadedRemoteRows)
                nextPageCursor = page.nextCursor
                // The newest `updated_at` that was actually read and processed
                // becomes this account's synchronization position. It is written
                // here — after the page and its processing succeeded — and only
                // forwards, so an initial load can never rewind a later position.
                mediaSyncCursorStore.advanceTo(
                    MediaLibraryPaging.latestSyncCursor(null, page.rows)
                )
                val libraryItems = composeLibrary(deviceItems, trashed, favoriteIds, records, page.rows)
                publishLibrary(libraryItems, prune = prune)
                lastRefreshAt = now
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
        } catch (error: Exception) {
            // The request did not establish anything about the catalog, so the
            // last known rows, cursor, statistics and media list all stay exactly
            // as they were: only the loading flags and, when there is nothing to
            // show, the message change.
            val failure = RemoteFailureClassifier.classify(error)
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "CATALOG_REFRESH_FAILED",
                message = "Remote catalog refresh failed; keeping the last known catalog",
                throwable = error,
                metadata = mapOf(
                    "failure" to failure.name,
                    "kept_media" to _media.value.size.toString(),
                    "kept_cloud_rows" to loadedRemoteRows.size.toString()
                )
            )
            synchronized(sessionLock) {
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                val keepExisting = keepOrFallbackContent()
                _loadState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        // Paging continues to work against what is already loaded.
                        hasNextPage = nextPageCursor != null,
                        errorMessage = if (keepExisting) null else RemoteFailureClassifier.message(failure)
                    )
                }
            }
        }
    }

    /**
     * Whether a failed pass still leaves something usable on screen.
     *
     * Valid content wins over an error in every case: the composed library if there
     * is one, otherwise the last known cloud snapshot. Only a genuinely empty
     * account is allowed to show a failure.
     */
    private fun keepOrFallbackContent(): Boolean =
        _media.value.isNotEmpty() || publishCloudFallbackIfEmpty()

    /**
     * Incremental synchronization: read the rows that changed after [cursor] and
     * fold them into the catalog already loaded in memory.
     *
     * Unlike [loadFirstPageLocked] this never re-reads the visible page: a refresh
     * with nothing new costs one empty request, and a refresh after a handful of
     * changes transfers only those rows. The `created_at` keyset pagination and
     * its page cursor are left completely alone.
     *
     * The cursor is advanced exactly once, at the end, and only when the batch was
     * read and merged successfully. Offline, a timeout, a backend error, a
     * cancelled caller or a superseded session therefore all keep the previous
     * cursor: the next refresh resumes from the same position and applies the
     * changes it never saw instead of skipping them.
     */
    private suspend fun syncChangedRows(
        session: AccountSession.Snapshot,
        ownerId: String,
        deviceItems: List<MediaItem>,
        trashed: List<MediaItem>,
        favoriteIds: Set<String>,
        records: Map<String, SyncRecord>,
        cursor: MediaSyncCursor,
        prune: Boolean,
        now: Long
    ) {
        val epoch = catalogEpoch
        var merged = loadedRemoteRows
        var position = cursor
        var changedCount = 0
        var pages = 0
        val tombstoned = HashSet<String>()
        try {
            while (true) {
                val page = mediaAssetsRepository.loadChangedAssetsPage(position)
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                changedCount += page.rows.size
                for (row in page.rows) {
                    if (MediaLibraryPaging.isRemoteTombstone(row)) tombstoned += row.id
                }
                // Upsert by stable remote id: a changed row replaces the loaded
                // copy, an unknown id is appended, and no id is ever duplicated.
                merged = MediaLibraryPaging.upsertRows(merged, page.rows)
                val before = position
                position = MediaLibraryPaging.latestSyncCursor(position, page.rows) ?: position
                pages += 1
                val more = page.hasNextPage && page.nextCursor != null
                // A short page ends the batch; a page that could not move the
                // cursor would be requested again unchanged, so it ends it too.
                if (!more || MediaSyncCursor.compare(position, before) <= 0) break
                if (pages >= MediaLibraryPaging.MAX_INCREMENTAL_PAGES) break
            }
        } catch (cancelled: CancellationException) {
            // Nothing was committed and the cursor was not written: a cancelled
            // caller must never look like a completed synchronization.
            throw cancelled
        } catch (error: Exception) {
            val failure = RemoteFailureClassifier.classify(error)
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "CATALOG_SYNC_FAILED",
                message = "Incremental catalog sync failed; keeping the last known catalog and cursor",
                throwable = error,
                metadata = mapOf(
                    "failure" to failure.name,
                    "kept_media" to _media.value.size.toString(),
                    "kept_cloud_rows" to loadedRemoteRows.size.toString(),
                    "cursor_updated_at" to cursor.updatedAt,
                    "cursor_id" to cursor.id
                )
            )
            synchronized(sessionLock) {
                if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
                val keepExisting = keepOrFallbackContent()
                _loadState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        // Paging continues to work against what is already loaded.
                        hasNextPage = nextPageCursor != null,
                        errorMessage = if (keepExisting) null else RemoteFailureClassifier.message(failure)
                    )
                }
            }
            return
        }
        synchronized(sessionLock) {
            if (!sessionStillCurrent(session, ownerId) || epoch != catalogEpoch) return
            if (changedCount > 0) {
                loadedRemoteRows = merged
                if (tombstoned.isNotEmpty()) {
                    // Only an authoritative server tombstone (status = DELETED)
                    // removes a record. Absence from a response never does.
                    loadedRemoteRows = loadedRemoteRows.filterNot { it.id in tombstoned }
                    dropCachedCloudEntries(tombstoned)
                }
            }
            // Last, and only now: everything above is committed under the same
            // guard that proved this pass still owns the catalog.
            mediaSyncCursorStore.advanceTo(position)
            val libraryItems = composeLibrary(deviceItems, trashed, favoriteIds, records, loadedRemoteRows)
            publishLibrary(libraryItems, prune = prune)
            lastRefreshAt = now
            _loadState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    errorMessage = null
                )
            }
            DeveloperLogger.info(
                category = LogCategory.DATABASE,
                event = "CATALOG_SYNCED_INCREMENTALLY",
                message = "Incremental catalog sync applied",
                metadata = mapOf(
                    "changed_rows" to changedCount.toString(),
                    "pages" to pages.toString(),
                    "tombstones" to tombstoned.size.toString(),
                    "cursor_updated_at" to position.updatedAt,
                    "cursor_id" to position.id
                )
            )
        }
    }

    /**
     * Forgets the persisted catalog entry of an authoritative server tombstone.
     *
     * Without this the cached entry would keep re-adding the deleted record to
     * "My Drive" on every composition.
     */
    private fun dropCachedCloudEntries(remoteIds: Set<String>) {
        if (remoteIds.isEmpty()) return
        val cached = visibilityStore.cloudEntries()
        val remaining = cached.filterValues { it.remoteMediaId !in remoteIds }
        if (remaining.size != cached.size) visibilityStore.replaceCloud(remaining)
    }

    private fun sessionStillCurrent(session: AccountSession.Snapshot, ownerId: String): Boolean {
        return boundUserId == ownerId &&
            AccountSession.isCurrent(session.userId, session.generation) &&
            AccountSession.userId == ownerId
    }

    private fun initialLoadState(): MediaLoadState {
        val access = permissions.access()
        return MediaLoadState(accessGranted = access == MediaAccess.GRANTED, accessPartial = access == MediaAccess.PARTIAL, needsPermission = access == MediaAccess.NEEDS_REQUEST, permissionDenied = access == MediaAccess.DENIED, isLoading = true, isRestoring = true)
    }

    private fun applyAccessState() {
        val access = permissions.access()
        _loadState.update { it.copy(accessGranted = access == MediaAccess.GRANTED, accessPartial = access == MediaAccess.PARTIAL, needsPermission = access == MediaAccess.NEEDS_REQUEST, permissionDenied = access == MediaAccess.DENIED) }
    }

    /**
     * Writes an edited bitmap as a NEW image in the device MediaStore.
     *
     * On Android 10+ the item is created with `IS_PENDING=1`, the encoded bytes
     * are written, the write is verified against the file on disk, and only then
     * is `IS_PENDING` cleared — so a failure is cleaned up (the pending row is
     * deleted) instead of leaving a zero-byte or partially written item in the
     * Gallery. On API 26-28 the legacy `DATA` column is used.
     *
     * The original is never modified, no `media_assets` row is created here (the
     * normal MediaStore discovery + backup flow owns that), and the caller gets
     * the new item id so it can be surfaced. The EXIF block of [exifSourceUri] is
     * copied where practical.
     */
    suspend fun saveEditedPhoto(
        context: Context,
        source: MediaItem?,
        bitmap: Bitmap,
        exifSourceUri: String? = null
    ): SaveEditedPhotoResult = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext SaveEditedPhotoResult.Failed

        val baseName = source?.filename
            ?.substringBeforeLast('.', source.filename)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "My Drive photo"
        val sanitized = baseName
            .replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
            .take(80)
            .ifBlank { "My Drive photo" }
        val format = if (source?.mimeType?.contains("png", ignoreCase = true) == true) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
        val filename = "${sanitized}_edited_${System.currentTimeMillis()}.$extension"

        // Encode out of memory first: the EXIF block is copied into the file and
        // the byte count verified before anything is published to MediaStore.
        val tempFile = File(context.cacheDir, "editor-export-${System.currentTimeMillis()}.$extension")
        val encoded = runCatching {
            FileOutputStream(tempFile).use { out -> bitmap.compress(format, quality, out) }
            tempFile.length() > 0L
        }.getOrDefault(false)
        if (!encoded) {
            runCatching { tempFile.delete() }
            return@withContext SaveEditedPhotoResult.Failed
        }
        if (format == Bitmap.CompressFormat.JPEG) {
            copyExif(context, exifSourceUri, tempFile)
        }

        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                savePendingItem(context, tempFile, filename, mimeType, source)
            } else {
                saveLegacyItem(context, tempFile, filename, mimeType)
            }
        } catch (error: SecurityException) {
            logEditedPhotoSaveFailure(source, filename, error)
            SaveEditedPhotoResult.PermissionDenied
        } catch (error: Exception) {
            logEditedPhotoSaveFailure(source, filename, error)
            SaveEditedPhotoResult.Failed
        } finally {
            runCatching { tempFile.delete() }
        }

        // Make the new item discoverable immediately through the normal scan.
        if (result is SaveEditedPhotoResult.Saved) {
            runCatching { refresh(force = true) }
        }
        result
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun savePendingItem(
        context: Context,
        tempFile: File,
        filename: String,
        mimeType: String,
        source: MediaItem?
    ): SaveEditedPhotoResult {
        val resolver = context.contentResolver
        val expected = tempFile.length()
        if (expected <= 0L) return SaveEditedPhotoResult.Failed
        val now = System.currentTimeMillis()
        val relativePaths = pendingRelativePaths(source?.relativePath)
        var lastError: Exception? = null
        for (collection in pendingImageCollections()) {
            for (relativePath in relativePaths) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.DATE_TAKEN, now)
                }
                val uri = try {
                    resolver.insert(collection, values)
                } catch (error: SecurityException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error
                    null
                } ?: continue
                try {
                    val written = resolver.openOutputStream(uri)?.use { output ->
                        val count = tempFile.inputStream().use { input ->
                            input.copyTo(output, bufferSize = 65_536)
                        }
                        output.flush()
                        count
                    } ?: -1L
                    if (written != expected) {
                        lastError = IllegalStateException("Wrote $written bytes, expected $expected")
                        failPending(resolver, uri)
                        continue
                    }
                    val pendingSize = queryDescriptorSize(resolver, uri)
                    if (pendingSize > 0L && pendingSize != expected) {
                        lastError = IllegalStateException("Pending descriptor size $pendingSize, expected $expected")
                        failPending(resolver, uri)
                        continue
                    }
                    val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    val published = try {
                        resolver.update(uri, publish, null, null) >= 0
                    } catch (error: Exception) {
                        lastError = error
                        false
                    }
                    if (!published) {
                        failPending(resolver, uri)
                        continue
                    }
                    if (!isMediaReadable(resolver, uri)) {
                        lastError = FileNotFoundException("Published MediaStore URI is not readable")
                        failPending(resolver, uri)
                        continue
                    }
                    val publishedSize = queryDescriptorSize(resolver, uri)
                    if (publishedSize == 0L) {
                        lastError = IllegalStateException("Published MediaStore item is zero bytes")
                        failPending(resolver, uri)
                        continue
                    }
                    val id = uri.lastPathSegment?.toLongOrNull() ?: 0L
                    return SaveEditedPhotoResult.Saved(itemId = "img-$id", mediaStoreId = id, filename = filename)
                } catch (error: SecurityException) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw error
                } catch (error: Exception) {
                    lastError = error
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
        if (lastError != null) throw lastError
        return SaveEditedPhotoResult.Failed
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun pendingImageCollections(): List<Uri> {
        val uris = linkedSetOf<Uri>()
        runCatching { uris += MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
        runCatching { uris += MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) }
        uris += MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return uris.toList()
    }

    private fun pendingRelativePaths(sourcePath: String?): List<String> {
        val fallback = Environment.DIRECTORY_PICTURES + File.separator + "My Drive" + File.separator
        val sanitized = sanitizeRelativePath(sourcePath)
        return listOfNotNull(sanitized, fallback).distinct()
    }

    private fun sanitizeRelativePath(sourcePath: String?): String? {
        val raw = sourcePath?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: return null
        val root = raw.substringBefore('/')
        val allowed = setOf(
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_DOWNLOADS
        )
        if (root !in allowed) return null
        return "$raw/"
    }

    private fun failPending(resolver: ContentResolver, uri: Uri): SaveEditedPhotoResult {
        runCatching { resolver.delete(uri, null, null) }
        return SaveEditedPhotoResult.Failed
    }

    private fun saveLegacyItem(
        context: Context,
        tempFile: File,
        filename: String,
        mimeType: String
    ): SaveEditedPhotoResult {
        val resolver = context.contentResolver
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, "My Drive").apply { mkdirs() }
        val target = File(directory, filename)
        val copied = runCatching {
            tempFile.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, bufferSize = 65_536) }
            }
            target.length() > 0L
        }.getOrDefault(false)
        if (!copied) {
            runCatching { target.delete() }
            return SaveEditedPhotoResult.Failed
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        @Suppress("DEPRECATION")
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
        if (uri == null || queryMediaSize(resolver, uri) <= 0L) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            runCatching { target.delete() }
            return SaveEditedPhotoResult.Failed
        }
        val id = uri.lastPathSegment?.toLongOrNull() ?: 0L
        return SaveEditedPhotoResult.Saved(itemId = "img-$id", mediaStoreId = id, filename = filename)
    }

    private fun queryMediaSize(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun queryDescriptorSize(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)

    private fun isMediaReadable(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { input -> input.read() >= 0 } ?: false
    }.getOrDefault(false)

    private fun logEditedPhotoSaveFailure(source: MediaItem?, filename: String, error: Throwable) {
        DeveloperLogger.error(
            LogCategory.MEDIASTORE,
            "MEDIA_EDIT_SAVE_FAILED",
            "Edited photo MediaStore save failed",
            localMediaId = source?.id,
            throwable = error,
            metadata = mapOf(
                "filename" to filename,
                "source_uri" to source?.uri,
                "android_api" to Build.VERSION.SDK_INT.toString(),
                "exception_class" to error.javaClass.name,
                "exception_message" to error.message
            )
        )
    }

    /**
     * Copies the relevant EXIF block (timestamps, camera, GPS, orientation) from
     * [sourceUri] into [target] where the format supports it. Failures are
     * ignored: metadata is a nice-to-have, the pixels are the point.
     */
    private fun copyExif(context: Context, sourceUri: String?, target: File) {
        if (sourceUri.isNullOrBlank() || target.length() <= 0L) return
        runCatching {
            val source = context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { ExifInterface(it) }
                ?: return
            val destination = ExifInterface(target.absolutePath)
            EXIF_TAGS.forEach { tag ->
                source.getAttribute(tag)?.let { value -> destination.setAttribute(tag, value) }
            }
            destination.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            destination.saveAttributes()
        }
    }

    /**
     * Groups the library into the user's own folders.
     *
     * There is deliberately no "cloud" folder and no synthetic album of any kind:
     * an album is a device folder, and the only entries here are folders that at
     * least one item actually belongs to. A photo backed up to My Drive is still
     * a Camera photo, and cloud-only media is filed under the folder it originally
     * came from — see [resolveAlbum].
     */
    private fun buildAlbums(items: List<MediaItem>): List<AlbumFolder> {
        return items
            .groupBy { resolveAlbum(it.albumId, it.albumName).id }
            .map { (albumId, albumItems) ->
                val newest = albumItems.maxByOrNull { it.capturedAtMillis }
                // The cover keeps its cloud fallback so a device URI whose MediaStore
                // row disappeared does not blank out the album; the stored delivery
                // URL is also what the resolver rewrites to a small Cloudinary
                // derivative instead of downloading a full-size original.
                val cover = AlbumCoverResolver.select(albumItems)
                AlbumFolder(
                    id = albumId,
                    name = newest?.let { resolveAlbum(it.albumId, it.albumName).name }
                        ?: UNGROUPED_ALBUM_NAME,
                    coverSeed = cover?.seed ?: 0,
                    coverType = cover?.type ?: MediaType.PHOTO,
                    mediaCount = albumItems.size,
                    coverUri = cover?.uri.orEmpty(),
                    coverPreviewUri = cover?.previewUri,
                    coverRemoteMediaId = cover?.remoteMediaId,
                    coverVersion = cover?.version
                )
            }
            .sortedByDescending { it.mediaCount }
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
            // No local queue record. That is not evidence of "needs backup": if the
            // cloud catalog still holds an available record for this media, it is
            // already backed up, so it must not be queued again or shown as pending.
            if (cloudBackedUp) {
                return copy(
                    backupState = BackupState.COMPLETED,
                    backupCompleted = true,
                    progress = 0f,
                    errorMessage = null
                )
            }
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
        // One snapshot of the persisted catalog for the whole composition. An
        // incremental page can now carry rows that left the library, so the
        // hidden mapping and the cloud-only pass both need it; looking it up per
        // item re-parsed the entire map every time.
        val cloudCache = visibilityStore.cloudEntries()
        val cachedByRemoteId = cloudCache.values.associateBy { it.remoteMediaId }
        // Only rows the catalog rule accepts may be composed into the library.
        // The rule itself is unchanged (`status = READY` and not hidden) — it used
        // to be enforced by the request filter, and is now enforced here as well
        // because the incremental request deliberately returns departing rows too.
        val visibleRows = remoteRows.filter { MediaLibraryPaging.isVisibleInCatalog(it) }
        logCloudAvailability(visibleRows, remoteRows)
        val byLocalMediaId = visibleRows.mapNotNull { row ->
            row.localMediaId?.takeIf { it > 0L }?.let { it to row }
        }.toMap()
        val byRemoteId = visibleRows.associateBy { it.id }
        val byClientUpload = visibleRows.mapNotNull { row ->
            row.clientUploadId?.takeIf { it.isNotBlank() }?.let { it to row }
        }.toMap()

        fun matchRow(item: MediaItem, record: SyncRecord?) =
            byLocalMediaId[item.mediaStoreId]
                ?: record?.remoteMediaId?.let { byRemoteId[it] }
                ?: item.remoteMediaId?.let { byRemoteId[it] }
                ?: record?.clientUploadId?.let { byClientUpload[it] }

        /**
         * Whether the persisted cloud index proves this media is in the cloud even
         * though the loaded page does not carry its row.
         *
         * Consulted last so live rows always win, but consulted at all because one
         * page cannot speak for the whole catalog: with ~1.1k cloud rows and an
         * 80-row page, a launch that replaced its cloud evidence with page 1 marked
         * everything outside that page as un-backed-up and uploaded it again.
         *
         * Entries are only ever written from an available `READY` row and are
         * dropped when the row is hidden, trashed away or tombstoned, and backup
         * discovery additionally prunes any entry the server stops confirming.
         */
        fun provenByCloudIndex(item: MediaItem): Boolean =
            cloudCache[item.id]?.remoteMediaId?.isNotBlank() == true

        for (item in deviceItems + trashed) {
            val record = records[item.id]
            val row = matchRow(item, record)
            if (row != null) {
                rememberCloudFromItem(item, row, record)
            }
        }
        for (row in remoteRows) {
            if (!row.isHiddenFromLibrary) continue
            records.entries.firstOrNull { it.value.remoteMediaId == row.id }?.key?.let { hidden += it }
            records.entries.firstOrNull { it.value.clientUploadId == row.clientUploadId }?.key?.let { hidden += it }
            // A cloud-only record has neither a MediaStore row nor a queue
            // record, so the persisted catalog entry is the only handle on its
            // local id. Without it, a record trashed elsewhere would stay on
            // screen, and a restored one would never come back.
            cachedByRemoteId[row.id]?.let { hidden += it.localId }
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
                // The persistent thumbnail (`…/thumbnails/…`) is the tile preview
                // and outlives the original.
                thumbnailUrl = row?.persistentThumbnailUrl
                    ?: record?.cloudinarySecureUrl?.takeUnless { row?.isPrimaryCleaned == true },
                // The original is tracked separately: it is what full resolution
                // may use, and it disappears with the verified cleanup.
                originalUrl = row?.originalCloudUrl
                    ?: record?.cloudinarySecureUrl?.takeUnless { row?.isPrimaryCleaned == true },
                // Authoritative "already backed up" evidence, taken ONLY from a
                // catalog-visible media_assets row that matched this local item.
                // Independent of device_id and of the install-local upload queue,
                // so a second install or a lost queue cannot re-upload media that
                // the cloud already holds.
                cloudBackedUp = row != null || provenByCloudIndex(item),
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
            val cloud = cloudCache[item.id]
            if (row != null && !row.isHiddenFromLibrary && row.status != "DELETED") {
                rememberCloudFromItem(item, row, record)
                library += cloudMediaItem(item, row, record, favoriteIds)
                present += item.id
            } else if (cloud != null && row?.status != "DELETED") {
                library += cloud.toMediaItem(favoriteIds, record)
                present += item.id
            }
        }

        for ((localId, entry) in cloudCache) {
            if (localId in hidden || localId in present || localId in deviceIds ||
                entry.remoteMediaId in byRemoteId
            ) continue
            library += entry.toMediaItem(favoriteIds, records[localId])
            presentRemoteIds += entry.remoteMediaId
        }

        // MediaStore is only the local-copy index. A backed-up row remains part
        // of My Drive even after another Gallery/File Manager removes its copy.
        for (row in visibleRows) {
            if (!row.isCloudAvailable || row.id in presentRemoteIds) continue
            val cached = cachedByRemoteId[row.id]
            val cloudId = cached?.localId ?: "cloud-${row.id}"
            // The media's OWN folder, remembered from when its local copy was
            // still indexable. The storage provider is not a folder, so a row
            // whose original folder is unknown is ungrouped rather than filed
            // under "My Drive".
            val album = resolveAlbum(cached?.albumId, cached?.albumName)
            library += row.toCloudOnlyMediaItem(cloudId, favoriteIds, album)
            rememberCloudFromRow(row, cloudId, album)
            presentRemoteIds += row.id
        }
        return library
            .distinctBy { it.remoteMediaId?.takeIf(String::isNotBlank) ?: "local:${it.id}" }
            // Fully deterministic: the tiebreak makes the composed order equal to
            // the order the persisted catalog is read back in, so restoring the
            // gallery from disk cannot reshuffle tiles within the same timestamp.
            .sortedWith(compareByDescending<MediaItem> { it.capturedAtMillis }.thenBy { it.id })
    }

    /**
     * Point-to-point diagnostics for the availability decision on cloud-backed
     * rows. The full per-row trace is emitted for the first [AVAILABILITY_TRACE_BUDGET]
     * rows of a composition plus every row that is NOT cloud-available, so a
     * thousand-row catalog never floods the Developer Log.
     */
    private fun logCloudAvailability(
        visibleRows: List<com.mydrive.app.data.remote.dto.MediaAssetRow>,
        allRows: List<com.mydrive.app.data.remote.dto.MediaAssetRow>
    ) {
        var traced = 0
        val visibleIds = visibleRows.mapTo(HashSet()) { it.id }
        for (row in allRows) {
            val visible = row.id in visibleIds
            val cloudAvailable = row.isCloudAvailable
            val shouldTrace = traced < AVAILABILITY_TRACE_BUDGET || !cloudAvailable || !visible
            if (!shouldTrace) continue
            traced += 1
            MediaDiagnosticLogger.availabilityStart(
                mediaId = row.id,
                localMediaId = row.localMediaId?.toString(),
                status = row.status,
                storageProvider = null,
                hasStorageUrl = !row.storageUrl.isNullOrBlank(),
                hasThumbnailUrl = !row.thumbnailUrl.isNullOrBlank(),
                storageAssetIdPresent = !row.storageAssetId.isNullOrBlank(),
                driveArchivedAtPresent = !row.driveArchivedAt.isNullOrBlank(),
                deletedAtPresent = !row.deletedAt.isNullOrBlank(),
                userHiddenAtPresent = row.isHiddenFromLibrary,
                localFileExists = null,
                localUriPresent = row.localMediaId != null
            )
            MediaDiagnosticLogger.cloudMetadata(
                mediaId = row.id,
                localMediaId = row.localMediaId?.toString(),
                ownerIdPresent = !row.ownerId.isNullOrBlank(),
                status = row.status,
                storageProvider = null,
                storageUrlPresent = !row.storageUrl.isNullOrBlank(),
                thumbnailUrlPresent = !row.thumbnailUrl.isNullOrBlank(),
                storageAssetIdPresent = !row.storageAssetId.isNullOrBlank(),
                driveArchivedAtPresent = !row.driveArchivedAt.isNullOrBlank(),
                deletedAtPresent = !row.deletedAt.isNullOrBlank(),
                userHiddenAtPresent = row.isHiddenFromLibrary,
                primaryCleaned = row.isPrimaryCleaned
            )
            // Thumbnail availability is tracked separately from original
            // availability: a row whose Cloudinary ORIGINAL was cleaned up is
            // still fully displayable from its persistent thumbnail.
            if (row.isThumbnailOnly) {
                MediaDiagnosticLogger.log(
                    LogLevel.INFO,
                    LogCategory.THUMBNAIL,
                    "THUMBNAIL_ONLY_AVAILABILITY",
                    "original cleaned up; media stays displayable from its persistent thumbnail",
                    mapOf(
                        "media_id" to row.id,
                        "thumbnail_available" to "true",
                        "original_available" to "false",
                        "drive_archived" to (!row.driveArchivedAt.isNullOrBlank()).toString()
                    )
                )
            }
            val driveOnly = row.originalCloudUrl.isNullOrBlank() &&
                row.persistentThumbnailUrl.isNullOrBlank() &&
                (!row.driveArchivedAt.isNullOrBlank() || row.hasCompletedDriveArchive)
            if (driveOnly) {
                MediaDiagnosticLogger.cloudDriveCheck(
                    mediaId = row.id,
                    localMediaId = row.localMediaId?.toString(),
                    driveArchivedAtPresent = !row.driveArchivedAt.isNullOrBlank() || row.hasCompletedDriveArchive,
                    storageUrlPresent = false,
                    thumbnailUrlPresent = false
                )
            }
            val reason = when {
                row.status == "DELETED" -> "DELETED"
                row.isHiddenFromLibrary -> "USER_HIDDEN"
                row.status != "READY" -> "MEDIA_STATUS_NOT_READY"
                // Still displayable: the original is gone but the persistent
                // thumbnail is not, so this is NOT "no remote source".
                row.isThumbnailOnly -> "THUMBNAIL_ONLY"
                row.isPrimaryCleaned && !driveOnly -> "NO_REMOTE_SOURCE"
                cloudAvailable -> "AVAILABLE"
                else -> "NO_REMOTE_SOURCE"
            }
            val attempt = MediaDiagnosticLogger.attempt(
                variant = MediaDiagnosticLogger.Variant.THUMBNAIL,
                mediaId = row.id,
                localMediaId = row.localMediaId?.toString()
            )
            MediaDiagnosticLogger.availabilityResult(
                attempt = attempt,
                available = visible && cloudAvailable,
                reason = reason,
                selectedSource = when {
                    !cloudAvailable -> "NONE"
                    driveOnly -> "DRIVE"
                    row.isThumbnailOnly -> "CLOUDINARY_THUMBNAIL"
                    else -> "CLOUDINARY"
                }
            )
        }
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
            .sortedWith(compareByDescending<MediaItem> { it.capturedAtMillis }.thenBy { it.id })
    }

    private fun com.mydrive.app.data.remote.dto.MediaAssetRow.toCloudOnlyMediaItem(
        stableId: String,
        favoriteIds: Set<String>,
        album: MediaAlbumRef
    ): MediaItem {
        val mediaType = if (mimeType?.startsWith("video/") == true) MediaType.VIDEO else MediaType.PHOTO
        // The persistent thumbnail drives the tile; the original drives full
        // resolution and playback, and is absent once the verified cleanup ran.
        val preview = persistentThumbnailUrl
        val original = originalCloudUrl
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
            // The original, and only the original: a legacy row whose Cloudinary
            // original is already gone keeps today's behaviour (Drive serves it).
            uri = original.orEmpty(),
            mimeType = mimeType.orEmpty(),
            width = width ?: 0,
            height = height ?: 0,
            durationMillis = durationMs,
            remoteMediaId = id,
            thumbnailUrl = preview,
            originLocal = false,
            // Cloud storage is where this media lives, not somewhere the user put
            // it: `device` records the source, the album stays the real folder.
            albumId = album.id,
            albumName = album.name,
            originalUrl = original
        )
    }

    private fun rememberCloudCopy(id: String) {
        val item = lookupAnyItem(id) ?: return
        val record = syncRepository.records.value[id]
        val remoteId = item.remoteMediaId ?: record?.remoteMediaId.orEmpty()
        if (remoteId.isBlank()) return
        // Remember the folder, never the storage provider: this entry is the only
        // album metadata a cloud-only item will have once the local copy is gone.
        val resolved = resolveAlbum(item.albumId, item.albumName)
        val preview = item.thumbnailUrl
            ?: record?.cloudinarySecureUrl
            ?: item.uri.takeIf { it.startsWith("http") }
            ?: ""
        val original = item.originalUrl
            ?: record?.cloudinarySecureUrl
            ?: item.uri.takeIf { it.startsWith("http") }
        visibilityStore.putCloud(
            CloudLibraryEntry(
                localId = id,
                remoteMediaId = remoteId,
                uri = original.orEmpty(),
                thumbnailUrl = preview.takeIf { it.isNotBlank() },
                originalUrl = original,
                filename = item.filename,
                mimeType = item.mimeType,
                fileSizeBytes = item.fileSizeBytes,
                width = item.width,
                height = item.height,
                durationMillis = item.durationMillis,
                capturedAtMillis = item.capturedAtMillis,
                albumId = resolved.id,
                albumName = resolved.name,
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
        val original = row.originalCloudUrl
            ?: record?.cloudinarySecureUrl?.takeUnless { row.isPrimaryCleaned }
        val preview = row.persistentThumbnailUrl ?: original ?: ""
        visibilityStore.putCloud(
            CloudLibraryEntry(
                localId = item.id,
                remoteMediaId = row.id,
                uri = original.orEmpty(),
                thumbnailUrl = preview.takeIf { it.isNotBlank() },
                originalUrl = original,
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
        stableId: String,
        album: MediaAlbumRef
    ) {
        if (!row.isCloudAvailable) return
        val original = row.originalCloudUrl
        val preview = row.persistentThumbnailUrl ?: original.orEmpty()
        val mediaType = if (row.mimeType?.startsWith("video/") == true) MediaType.VIDEO else MediaType.PHOTO
        val captured = runCatching { java.time.Instant.parse(row.createdAt ?: "").toEpochMilli() }.getOrDefault(0L)
            .takeIf { it > 0L }
            ?: runCatching { java.time.Instant.parse(row.uploadedAt ?: "").toEpochMilli() }.getOrDefault(0L)
        visibilityStore.putCloud(
            CloudLibraryEntry(
                localId = stableId,
                remoteMediaId = row.id,
                uri = original.orEmpty(),
                thumbnailUrl = preview.takeIf { it.isNotBlank() },
                originalUrl = original,
                filename = row.fileName.orEmpty().ifBlank { "My Drive media" },
                mimeType = row.mimeType.orEmpty(),
                fileSizeBytes = row.fileSize ?: 0L,
                width = row.width ?: 0,
                height = row.height ?: 0,
                durationMillis = row.durationMs,
                capturedAtMillis = captured,
                // Only ever the media's real folder. Writing the provider here is
                // what used to make every cloud-only item claim a "My Drive" album;
                // an unknown folder is remembered as ungrouped instead.
                albumId = album.id,
                albumName = album.name,
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
        val original = row.originalCloudUrl
            ?: record?.cloudinarySecureUrl?.takeUnless { row.isPrimaryCleaned }
        val preview = row.persistentThumbnailUrl ?: original
        return item.copy(
            // `uri` is the full-size candidate; the tile uses `thumbnailUrl`.
            uri = original.orEmpty(),
            thumbnailUrl = preview,
            originalUrl = original,
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
        // The persisted entry keeps the thumbnail and the original apart; an entry
        // written by an earlier build has no original and falls back to `uri`.
        val preview = thumbnailUrl ?: uri
        val original = originalUrl ?: uri.takeIf { it.isNotBlank() && it != preview }
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
            hiddenFromLibrary = false,
            originalUrl = original
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
        val canManageMedia = permissions.canManageMedia()
        val ownerPackage = queryOwnerPackage(context, uri)
        val ownedByApp = ownerPackage == context.packageName
        val alreadyTrashed = isUriTrashed(context, uri)
        val baseMeta = mediaActionMetadata("DELETE", item) + mapOf(
            "owner_package" to ownerPackage,
            "owned_by_app" to ownedByApp.toString(),
            "can_manage_media" to canManageMedia.toString(),
            "already_trashed" to alreadyTrashed.toString(),
            "permission_granted" to permissions.canReadMedia().toString()
        )
        DeveloperLogger.info(
            LogCategory.MEDIASTORE,
            "MEDIA_DELETE_START",
            "Starting local MediaStore trash operation",
            localMediaId = item.id,
            metadata = baseMeta + mapOf("operation" to "start")
        )
        if (alreadyTrashed) {
            logTrashResult(item, baseMeta, "already_trashed", "SUCCESS", systemConfirmation = false)
            return DeleteMediaResult.Success
        }
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    trashOnApiRPlus(context, item, uri, baseMeta, canManageMedia)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteOnApiQ(context, item, uri, baseMeta)
                else -> deleteLegacy(context, item, uri, baseMeta)
            }
        } catch (error: RecoverableSecurityException) {
            recoverTrashWithoutSystemDialog(context, item, uri, baseMeta, error)
        } catch (error: SecurityException) {
            recoverTrashWithoutSystemDialog(context, item, uri, baseMeta, error)
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
        baseMeta: Map<String, String?>,
        canManageMedia: Boolean
    ): DeleteMediaResult {
        val direct = tryDirectTrash(context, uri)
        if (direct.success) {
            logTrashResult(item, baseMeta, "direct_is_trashed", "SUCCESS", systemConfirmation = false)
            return DeleteMediaResult.Success
        }
        if (isUriTrashed(context, uri) || !uriStillExists(context, uri)) {
            logTrashResult(item, baseMeta, "already_trashed", "SUCCESS", systemConfirmation = false)
            return DeleteMediaResult.Success
        }
        if (canManageMedia) {
            return requestManagedTrash(context, item, uri, baseMeta, direct.error)
        }
        return recoverTrashWithoutSystemDialog(context, item, uri, baseMeta, direct.error)
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
            logTrashResult(
                item,
                baseMeta,
                "recoverable_security_intent",
                "REQUIRES_SYSTEM_CONFIRMATION",
                systemConfirmation = true,
                error = error
            )
            DeleteMediaResult.RequiresSystemConfirmation(
                error.userAction.actionIntent.intentSender,
                alreadyPerformedOnApproval = false
            )
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

    private data class DirectTrashAttempt(
        val success: Boolean,
        val error: Exception? = null
    )

    private fun tryDirectTrash(context: Context, uri: Uri): DirectTrashAttempt {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 1)
        }
        return try {
            DirectTrashAttempt(success = context.contentResolver.update(uri, values, null, null) > 0)
        } catch (error: RecoverableSecurityException) {
            DirectTrashAttempt(success = false, error = error)
        } catch (error: SecurityException) {
            DirectTrashAttempt(success = false, error = error)
        } catch (error: Exception) {
            DirectTrashAttempt(success = false, error = error)
        }
    }

    private fun recoverTrashWithoutSystemDialog(
        context: Context,
        item: MediaItem,
        uri: Uri,
        baseMeta: Map<String, String?>,
        error: Exception?
    ): DeleteMediaResult {
        if (isUriTrashed(context, uri) || !uriStillExists(context, uri)) {
            logTrashResult(item, baseMeta, "already_trashed", "SUCCESS", systemConfirmation = false, error = error)
            return DeleteMediaResult.Success
        }
        if (permissions.canManageMedia()) {
            return requestManagedTrash(context, item, uri, baseMeta, error)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !permissions.canManageMedia()) {
            logTrashResult(
                item,
                baseMeta,
                "manage_media_required",
                "REQUIRES_MANAGE_MEDIA",
                systemConfirmation = false,
                error = error
            )
            return DeleteMediaResult.RequiresManageMedia
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            return try {
                val sender = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true).intentSender
                logTrashResult(
                    item,
                    baseMeta,
                    "create_trash_request",
                    "REQUIRES_SYSTEM_CONFIRMATION",
                    systemConfirmation = true,
                    error = error
                )
                DeleteMediaResult.RequiresSystemConfirmation(sender, alreadyPerformedOnApproval = true)
            } catch (requestError: Exception) {
                logMediaActionFailure(
                    "DELETE",
                    item,
                    requestError,
                    baseMeta + mapOf(
                        "android_flow" to "create_trash_request",
                        "result" to "FAILED",
                        "cause_exception" to error?.javaClass?.simpleName
                    )
                )
                DeleteMediaResult.Failed
            }
        }
        val denied = error ?: SecurityException("No write access to this MediaStore item")
        logMediaActionFailure(
            "DELETE",
            item,
            denied,
            baseMeta + mapOf(
                "android_flow" to "direct_is_trashed",
                "result" to "PERMISSION_DENIED",
                "system_confirmation_required" to "false"
            )
        )
        return DeleteMediaResult.PermissionDenied
    }

    private fun requestManagedTrash(
        context: Context,
        item: MediaItem,
        uri: Uri,
        baseMeta: Map<String, String?>,
        error: Exception?
    ): DeleteMediaResult {
        return try {
            val sender = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true).intentSender
            logTrashResult(
                item,
                baseMeta,
                "create_trash_request_manage_media",
                "REQUIRES_SYSTEM_CONFIRMATION",
                systemConfirmation = false,
                error = error
            )
            // MANAGE_MEDIA makes this request complete without an Android
            // confirmation dialog; the existing launcher still receives the
            // result and finalizes the My Drive lifecycle.
            DeleteMediaResult.RequiresSystemConfirmation(sender, alreadyPerformedOnApproval = true)
        } catch (requestError: Exception) {
            logMediaActionFailure(
                "DELETE",
                item,
                requestError,
                baseMeta + mapOf(
                    "android_flow" to "create_trash_request_manage_media",
                    "result" to "FAILED",
                    "cause_exception" to error?.javaClass?.simpleName
                )
            )
            DeleteMediaResult.Failed
        }
    }

    private fun isUriTrashed(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_TRASHED),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                index >= 0 && cursor.getInt(index) == 1
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun uriStillExists(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: true
        } catch (_: Exception) {
            true
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
            mutateLibrary { items -> items.filter { it.id != id } }
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
        ids.forEach { visibilityStore.unhideLocal(it) }
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
    suspend fun moveCloudToTrash(
        id: String,
        itemSnapshot: MediaItem? = null
    ): RemoveFromLibraryResult = withContext(Dispatchers.IO) {
        // Local MediaStore trash finalization refreshes the catalog before this
        // cloud mutation runs. Keep the pre-refresh identity so a successful
        // Android trash operation cannot lose its remote/local correlation.
        val item = itemSnapshot ?: lookupAnyItem(id)
        val record = syncRepository.records.value[id]
        val result = mediaAssetsRepository.hideMatchingAsset(
            remoteMediaId = item?.remoteMediaId ?: record?.remoteMediaId,
            localMediaId = item?.mediaStoreId?.takeIf { it > 0L },
            clientUploadId = record?.clientUploadId
        )
        if (result == HideMediaResult.Success) {
            val remoteId = item?.remoteMediaId ?: record?.remoteMediaId
            // Keep the local visibility overlay in sync immediately. The next
            // refresh may still be composing from a cached page, so relying only
            // on the server response can leave the same cloud copy visible in an
            // album while the local copy is already in Trash.
            visibilityStore.hideLocal(id)
            // Remember the removal now: waiting for a future cold-start scan to
            // notice would put the trashed item back on screen first.
            mutateLibrary { items ->
                items.filterNot { candidate ->
                    candidate.id == id ||
                        (!remoteId.isNullOrBlank() && candidate.remoteMediaId == remoteId)
                }
            }
            // Trash is reversible, so the persistent thumbnail is deliberately KEPT:
            // only the permanent-delete lifecycle may purge it. Moving media to
            // Trash must never make the thumbnail eligible for deletion.
            logThumbnailLifecycle(
                operation = "move_to_trash",
                mediaId = remoteId.orEmpty(),
                result = "KEPT",
                reason = "RESTORABLE"
            )
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

    /**
     * Permanently deletes media that exists only in My Drive, through the
     * server-side lifecycle.
     *
     * This is the ONLY Android path that makes a persistent thumbnail eligible
     * for deletion. Moving media to Trash (`moveCloudToTrash` / `hideMatchingAsset`)
     * and restoring it never call it, so:
     *
     *   Move to Trash -> thumbnail kept (item is restorable)
     *   Restore       -> thumbnail kept, and never re-created
     *   Permanent delete -> server purge, thumbnail removed, record tombstoned
     *
     * Failure safety: the server operation IS the deletion, so a media item is
     * only reported as deleted when the server confirmed the purge. Nothing is
     * removed from the local trash list (or the catalog) on a failure, and a
     * partial purge reconciles exactly the records the server did purge.
     */
    private suspend fun permanentlyDeleteCloudMedia(items: List<MediaItem>): TrashMutationResult {
        val remoteIds = items
            .mapNotNull { item -> resolveRemoteMediaId(item) }
            .distinct()
        if (remoteIds.isEmpty()) {
            // Cloud-only media with no server identity cannot be deleted anywhere.
            DeveloperLogger.error(
                category = LogCategory.THUMBNAIL,
                event = "PERMANENT_DELETE",
                message = permanentDeleteLog(
                    mediaId = items.firstOrNull()?.id.orEmpty(),
                    requested = false,
                    result = "NO_REMOTE_IDENTITY"
                ),
                metadata = mapOf(
                    "operation" to "permanent_delete",
                    "result" to "FAILED",
                    "reason" to "NO_REMOTE_IDENTITY"
                )
            )
            return TrashMutationResult.Failed
        }

        DeveloperLogger.info(
            category = LogCategory.THUMBNAIL,
            event = "THUMBNAIL_LIFECYCLE",
            message = buildString {
                append("[THUMBNAIL_LIFECYCLE]\n")
                append("operation=permanent_delete\n")
                append("mediaId=").append(remoteIds.first()).append('\n')
                append("result=REQUESTED\n")
                append("reason=USER_PERMANENT_DELETE")
            },
            metadata = mapOf(
                "operation" to "permanent_delete",
                "media_count" to remoteIds.size.toString(),
                "result" to "REQUESTED",
                "reason" to "USER_PERMANENT_DELETE"
            )
        )

        val purgedIds = when (val result = mediaAssetsRepository.purgeMediaAssets(remoteIds)) {
            is PurgeMediaResult.Success -> result.purgedIds
            is PurgeMediaResult.Partial -> {
                // The server purged some records: drop exactly those locally so the
                // catalog and trash list match the server, then report the failure.
                if (result.purgedIds.isNotEmpty()) {
                    finalizePermanentTrashDelete(remoteIdsForIds(items, result.purgedIds))
                }
                logPermanentDeleteOutcome(
                    remoteIds.first(),
                    requested = true,
                    result = "PARTIAL_PURGE",
                    reason = "SERVER_PURGE_INCOMPLETE",
                    detail = "purged=${result.purgedIds.size}/${result.requested}"
                )
                return TrashMutationResult.Failed
            }
            PurgeMediaResult.NotFound -> {
                logPermanentDeleteOutcome(
                    remoteIds.first(), requested = false, result = "NOT_FOUND",
                    reason = "NO_REMOTE_RECORD"
                )
                return TrashMutationResult.NotFound
            }
            PurgeMediaResult.Unauthorized -> {
                logPermanentDeleteOutcome(
                    remoteIds.first(), requested = true, result = "UNAUTHORIZED",
                    reason = "SESSION_EXPIRED"
                )
                return TrashMutationResult.Failed
            }
            PurgeMediaResult.Failed -> {
                logPermanentDeleteOutcome(
                    remoteIds.first(), requested = true, result = "FAILED",
                    reason = "SERVER_PURGE_FAILED"
                )
                return TrashMutationResult.Failed
            }
        }

        logPermanentDeleteOutcome(
            remoteIds.first(),
            requested = true,
            result = "PURGED",
            reason = "PERMANENT_DELETE"
        )
        logThumbnailLifecycle(
            operation = "permanent_delete",
            mediaId = remoteIds.first(),
            result = "PURGED",
            reason = "PERMANENT_DELETE"
        )
        // The caller (Trash UI) finalizes local state on Success.
        if (purgedIds.isEmpty()) return TrashMutationResult.Failed
        return TrashMutationResult.Success
    }

    /** Maps server-purged remote ids back onto the local trash item ids. */
    private fun remoteIdsForIds(items: List<MediaItem>, purgedRemoteIds: List<String>): List<String> {
        val purged = purgedRemoteIds.toSet()
        return items.filter { item -> resolveRemoteMediaId(item) in purged }.map { it.id }
    }

    private fun resolveRemoteMediaId(item: MediaItem): String? {
        val record = syncRepository.records.value[item.id]
        return (item.remoteMediaId ?: record?.remoteMediaId)?.takeIf { it.isNotBlank() }
    }

    private fun logPermanentDeleteOutcome(
        mediaId: String,
        requested: Boolean,
        result: String,
        reason: String,
        detail: String? = null
    ) {
        DeveloperLogger.info(
            category = LogCategory.THUMBNAIL,
            event = "PERMANENT_DELETE",
            message = permanentDeleteLog(mediaId, requested, result),
            metadata = mapOf(
                "operation" to "permanent_delete",
                "media_id" to mediaId,
                "server_purge_requested" to requested.toString(),
                "server_purge_result" to result,
                "reason" to reason,
                "detail" to detail
            )
        )
    }

    private fun logThumbnailLifecycle(
        operation: String,
        mediaId: String,
        result: String,
        reason: String
    ) {
        DeveloperLogger.info(
            category = LogCategory.THUMBNAIL,
            event = "THUMBNAIL_LIFECYCLE",
            message = buildString {
                append("[THUMBNAIL_LIFECYCLE]\n")
                append("operation=").append(operation).append('\n')
                append("mediaId=").append(mediaId).append('\n')
                append("result=").append(result).append('\n')
                append("reason=").append(reason)
            },
            metadata = mapOf(
                "operation" to operation,
                "media_id" to mediaId,
                "result" to result,
                "reason" to reason
            )
        )
    }

    private fun permanentDeleteLog(mediaId: String, requested: Boolean, result: String): String =
        buildString {
            append("[PERMANENT_DELETE]\n")
            append("mediaId=").append(mediaId).append('\n')
            append("serverPurgeRequested=").append(requested).append('\n')
            append("serverPurgeResult=").append(result)
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

    private suspend fun mutateTrashedMedia(context: Context, id: String, restore: Boolean): TrashMutationResult {
        val item = _trashedMedia.value.firstOrNull { it.id == id }
            ?: return TrashMutationResult.NotFound

        // A media item with no local MediaStore copy lives only in My Drive. Its
        // permanent deletion is the SERVER-side lifecycle (persistent thumbnail
        // purge + tombstone), so it runs before the device-trash capability and
        // permission checks: no MediaStore API is involved.
        if (!restore && parseContentUri(item.uri) == null) {
            return permanentlyDeleteCloudMedia(listOf(item))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return TrashMutationResult.Unsupported
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

    private suspend fun mutateTrashedMediaBatch(
        context: Context,
        ids: Collection<String>,
        restore: Boolean
    ): TrashMutationResult {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return TrashMutationResult.Success
        val items = uniqueIds.mapNotNull { id -> _trashedMedia.value.firstOrNull { it.id == id } }
        if (items.isEmpty()) return TrashMutationResult.NotFound

        // Items with no local MediaStore copy live only in My Drive. Their permanent
        // deletion is the server-side lifecycle, which needs neither the device
        // trash API nor media permission — so resolve it before those checks.
        val localItems = items.filter { parseContentUri(it.uri) != null }
        if (!restore && localItems.size != items.size) {
            val cloudOnly = items.filterNot { parseContentUri(it.uri) != null }
            when (val purged = permanentlyDeleteCloudMedia(cloudOnly)) {
                // Nothing is deleted locally unless the server deletion succeeded,
                // so a failed purge can never leave a half-deleted selection.
                is TrashMutationResult.Success -> Unit
                else -> return purged
            }
        }
        if (localItems.isEmpty()) {
            // Restore keeps its existing behaviour: a cloud-only item is restored
            // through the cloud trash path, not here.
            return if (restore) TrashMutationResult.Failed else TrashMutationResult.Success
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return TrashMutationResult.Unsupported
        if (!permissions.canReadMedia()) return TrashMutationResult.PermissionDenied
        val uris = localItems.mapNotNull { parseContentUri(it.uri) }
        if (uris.isEmpty()) return TrashMutationResult.Failed
        val action = if (restore) "RESTORE" else "PERMANENT_DELETE"
        val sample = localItems.first()
        val baseMeta = mediaActionMetadata(action, sample) +
            mapOf("item_count" to localItems.size.toString())
        _trashProgress.value = TrashOperationProgress(
            inProgress = true,
            processed = 0,
            total = localItems.size
        )
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
                if (!permissions.canManageMedia()) {
                    requestTrashMutationConfirmation(context, uris, restore = false, sample, baseMeta, null)
                } else {
                    requestTrashMutationConfirmation(context, uris, restore = false, sample, baseMeta, null)
                }
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
            mutateLibrary { items ->
                items.map { if (it.id == id) updatedItem else it }
            }
            syncRepository.updateQueueMedia(id, updatedItem)
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
                // New dimensions and a new modification time: persisting them is
                // what lets the rotated thumbnail be re-derived instead of the
                // stale one being served from cache on the next launch.
                mutateLibrary { items -> items.map(::applyRotation) }
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



}
