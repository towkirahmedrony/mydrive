package com.mydrive.app

import android.app.Application
import com.mydrive.app.data.local.DeviceIdStore
import com.mydrive.app.data.local.FavoritesStore
import com.mydrive.app.data.local.GalleryTabStore
import com.mydrive.app.data.local.SyncStateStore
import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.local.UploadQueueDatabase
import com.mydrive.app.data.media.MediaDiskCache
import com.mydrive.app.data.media.MediaPermissions
import com.mydrive.app.data.media.MediaStoreDataSource
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.accessibility.AccessibilityMonitoringCoordinator
import com.mydrive.app.data.accessibility.AccessibilityRepository
import com.mydrive.app.data.remote.CloudinaryUploadService
import com.mydrive.app.data.remote.DurableMediaLifecycleClient
import com.mydrive.app.data.remote.MediaFinalizeService
import com.mydrive.app.data.remote.NetworkMonitor
import com.mydrive.app.data.remote.SupabaseConfig
import com.mydrive.app.data.remote.SupabaseModule
import com.mydrive.app.data.remote.TelegramApiVerifier
import com.mydrive.app.data.backup.AutomaticBackupCoordinator
import com.mydrive.app.data.backup.BackupDiscoveryReason
import com.mydrive.app.data.worker.BackupDiscoveryScheduler
import com.mydrive.app.data.worker.UploadWorkScheduler
import com.mydrive.app.data.local.LibraryVisibilityStore
import com.mydrive.app.data.local.MediaSyncCursorStore
import com.mydrive.app.data.repository.AuthRepository
import com.mydrive.app.data.repository.BackupRepository
import com.mydrive.app.data.repository.MediaAssetsRepository
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.data.repository.SyncRepository
import com.mydrive.app.data.session.AccountSessionCoordinator
import com.mydrive.app.debug.DeveloperLogDatabase
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.DeveloperModeStore
import com.mydrive.app.debug.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyDriveApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val galleryTabStore: GalleryTabStore by lazy { GalleryTabStore(this) }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(SyncStateStore(this), UploadQueueDatabase.get(this))
    }

    val telegramSettingsStore: TelegramSettingsStore by lazy { TelegramSettingsStore(this) }

    private val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(this) }

    /** Supabase access for the accessibility monitoring tables. */
    private val accessibilityRepository by lazy {
        AccessibilityRepository(supabaseClient, sessionProvider, networkMonitor)
    }

    /**
     * Accessibility monitoring foundation for company-owned devices.
     *
     * Buffers processed accessibility events in the existing Room outbox and
     * uploads them through the existing WorkManager infrastructure, so the
     * AccessibilityService itself performs no database or network I/O. Read by
     * [com.mydrive.app.data.accessibility.MyDriveAccessibilityService].
     */
    val accessibilityMonitoringCoordinator by lazy {
        AccessibilityMonitoringCoordinator(
            context = this,
            outboxDao = UploadQueueDatabase.get(this).accessibilityOutboxDao(),
            repository = accessibilityRepository,
            deviceIdProvider = { authRepository.ensureDeviceRegistered() }
        )
    }

    /**
     * Server-side media lifecycle. Owns the one Android-reachable permanent
     * deletion path (persistent thumbnail purge); Trash and Restore never call it.
     */
    private val mediaLifecycleClient by lazy {
        DurableMediaLifecycleClient(supabaseClient, sessionProvider, networkMonitor)
    }

    val mediaAssetsRepository: MediaAssetsRepository by lazy {
        // The connectivity check lets catalog reconciliation skip remote requests
        // while the device is clearly offline instead of failing them.
        MediaAssetsRepository(
            client = supabaseClient,
            sessionProvider = sessionProvider,
            network = networkMonitor,
            mediaLifecycleClient = mediaLifecycleClient
        )
    }

    val mediaRepository: MediaRepository by lazy {
        MediaRepository(
            mediaStore = MediaStoreDataSource(this),
            favorites = FavoritesStore(this),
            permissions = MediaPermissions(this),
            syncRepository = syncRepository,
            telegramSettingsStore = telegramSettingsStore,
            telegramApiVerifier = TelegramApiVerifier(networkMonitor),
            mediaAssetsRepository = mediaAssetsRepository,
            visibilityStore = LibraryVisibilityStore(this),
            // Per-account incremental synchronization position, so a refresh only
            // fetches the media records that changed since the last one.
            mediaSyncCursorStore = MediaSyncCursorStore(this),
            scope = applicationScope
        )
    }

    val developerModeStore: DeveloperModeStore by lazy { DeveloperModeStore(this) }

    val supabaseClient by lazy {
        if (SupabaseConfig.isConfigured) SupabaseModule.create() else null
    }

    val sessionProvider by lazy {
        AuthenticatedSessionProvider(supabaseClient)
    }

    private val cloudinaryService by lazy {
        CloudinaryUploadService(this, supabaseClient, sessionProvider, networkMonitor)
    }

    private val mediaFinalizeService by lazy {
        MediaFinalizeService(this, supabaseClient, sessionProvider, networkMonitor)
    }

    private val mediaStoreDataSource by lazy { MediaStoreDataSource(this) }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            syncRepository = syncRepository,
            cloudinaryService = cloudinaryService,
            mediaFinalizeService = mediaFinalizeService,
            network = networkMonitor,
            sessionProvider = sessionProvider,
            deviceIdProvider = { authRepository.ensureDeviceRegistered() },
            mediaLookup = mediaRepository::mediaById,
            queueLookup = syncRepository::queueEntity,
            queuedMediaResolver = mediaStoreDataSource::resolveQueuedMedia,
            uriProbe = mediaStoreDataSource::probeUri,
            scheduleUploadWork = {
                val prefs = mediaRepository.preferences.value
                UploadWorkScheduler.schedule(
                    context = this,
                    wifiOnly = prefs.wifiOnly,
                    charging = prefs.uploadWhileCharging
                )
            }
        )
    }

    val automaticBackupCoordinator: AutomaticBackupCoordinator by lazy {
        AutomaticBackupCoordinator(
            canReadMedia = { mediaRepository.hasMediaReadPermission() },
            automaticBackupEnabled = { mediaRepository.preferences.value.automaticBackup },
            currentUserId = { sessionProvider.currentUserIdOrNull() },
            refreshLibrary = { force, localOverlayOnly ->
                mediaRepository.refresh(force = force, localOverlayOnly = localOverlayOnly)
            },
            deviceMedia = { mediaRepository.deviceMedia.value },
            libraryMedia = { mediaRepository.media.value },
            records = { syncRepository.records.value },
            startBackup = { ids, resumeIfPaused ->
                backupRepository.startBackup(ids, resumeIfPaused = resumeIfPaused)
            },
            isPaused = { syncRepository.paused.value }
        )
    }

    private val accountSessionCoordinator: AccountSessionCoordinator by lazy {
        AccountSessionCoordinator(
            context = this,
            mediaRepository = mediaRepository,
            syncRepository = syncRepository,
            onAuthenticatedReady = {
                applicationScope.launch {
                    automaticBackupCoordinator.request(BackupDiscoveryReason.AUTHENTICATED)
                }
            }
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            client = supabaseClient,
            sessionProvider = sessionProvider,
            deviceIdStore = DeviceIdStore(this),
            network = networkMonitor,
            scope = applicationScope,
            onSignedOut = accountSessionCoordinator::onSignedOut,
            onAuthenticated = accountSessionCoordinator::onAuthenticated
        )
    }

    override fun onCreate() {
        super.onCreate()
        DeveloperLogger.attach(DeveloperLogDatabase.get(this))
        DeveloperLogger.info(
            category = LogCategory.SYSTEM,
            event = "APP_STARTED",
            message = "My Drive started"
        )
        authRepository
        UploadWorkScheduler.schedule(this)
        BackupDiscoveryScheduler.schedule(this)
        // One sweep per process start reclaims whatever the previous run left
        // behind — interrupted writes included — before the caches grow again.
        applicationScope.launch { MediaDiskCache.trim(filesDir) }
        applicationScope.launch {
            automaticBackupCoordinator.request(BackupDiscoveryReason.APP_START)
        }
    }
}
