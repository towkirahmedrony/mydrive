package com.mydrive.app

import android.app.Application
import com.mydrive.app.data.local.DeviceIdStore
import com.mydrive.app.data.local.FavoritesStore
import com.mydrive.app.data.local.GalleryTabStore
import com.mydrive.app.data.local.SyncStateStore
import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.local.UploadQueueDatabase
import com.mydrive.app.data.local.VaultDao
import com.mydrive.app.data.media.MediaDiskCache
import com.mydrive.app.data.media.MediaPermissions
import com.mydrive.app.data.media.MediaStoreDataSource
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.vault.VaultCrypto
import com.mydrive.app.data.vault.VaultPinManager
import com.mydrive.app.data.vault.VaultSession
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
import com.mydrive.app.data.local.LastAccountProfileStore
import com.mydrive.app.data.local.LibraryVisibilityStore
import com.mydrive.app.data.local.MediaCatalogStore
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
            // Last known composed gallery, in the app's existing Room database:
            // Photos/Albums are restored from here before any scan or network pass.
            mediaCatalogStore = MediaCatalogStore(UploadQueueDatabase.get(this).mediaCatalogDao()),
            scope = applicationScope
        )
    }

    val developerModeStore: DeveloperModeStore by lazy { DeveloperModeStore(this) }

    /** Private Vault: local state, at-rest encryption and the PIN verifier. */
    val vaultDao: VaultDao by lazy { UploadQueueDatabase.get(this).vaultDao() }
    val vaultCrypto: VaultCrypto by lazy { VaultCrypto(this) }
    val vaultPinManager: VaultPinManager by lazy { VaultPinManager(this) }

    /**
     * App-scoped on purpose: the unlocked state must survive navigation between the
     * vault screen and the viewer, and be shared by every vault screen. It holds a
     * boolean and a timestamp only — never decrypted content or key material.
     */
    val vaultSession: VaultSession by lazy { VaultSession() }

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
            // Lets a launch render the last known account's gallery from local
            // state instead of waiting on session and profile round trips.
            lastProfileStore = LastAccountProfileStore(this),
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                vaultSession.onBackgrounded()
            }
        })
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
