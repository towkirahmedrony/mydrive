package com.mydrive.app.data.model

enum class MediaType {
    PHOTO,
    VIDEO
}

enum class BackupState {
    NOT_STARTED,
    COMPLETED,
    PREPARING,
    UPLOADING,
    PROCESSING,
    SENDING_TELEGRAM,
    WAITING,
    PAUSED,
    FAILED,
    CANCELLED,
    REQUESTING_CLOUDINARY_AUTH,
    UPLOADING_TO_CLOUDINARY,
    CLOUDINARY_COMPLETED,
    FINALIZING_SUPABASE
}

val BackupState.isActive: Boolean
    get() = this == BackupState.PREPARING ||
        this == BackupState.UPLOADING ||
        this == BackupState.PROCESSING ||
        this == BackupState.SENDING_TELEGRAM ||
        this == BackupState.REQUESTING_CLOUDINARY_AUTH ||
        this == BackupState.UPLOADING_TO_CLOUDINARY ||
        this == BackupState.FINALIZING_SUPABASE

val BackupState.isQueued: Boolean
    get() = this == BackupState.WAITING || this == BackupState.PAUSED

val BackupState.isRetryable: Boolean
    get() = this == BackupState.FAILED || this == BackupState.CANCELLED

enum class ConnectionStatus {
    CONNECTED,
    SYNCING,
    ATTENTION
}

data class MediaItem(
    val id: String,
    val filename: String,
    val type: MediaType,
    val fileSizeBytes: Long,
    val capturedAtMillis: Long,
    val device: String,
    val resolution: String,
    val durationSeconds: Int? = null,
    val isFavorite: Boolean = false,
    val backupState: BackupState = BackupState.NOT_STARTED,
    val backupCompleted: Boolean = false,
    val telegramCompleted: Boolean = false,
    val thumbnailSeed: Int,
    val progress: Float = 0f,
    val errorMessage: String? = null,
    val albumId: String = "camera",
    val albumName: String = "",
    val mediaStoreId: Long = 0L,
    val uri: String = "",
    val mimeType: String = "",
    val dateAddedMillis: Long = 0L,
    val dateModifiedMillis: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
    val relativePath: String? = null,
    val cloudinaryAssetId: String? = null,
    val cloudinaryPublicId: String? = null,
    val isTrashed: Boolean = false,
    val dateExpiresMillis: Long = 0L,
    val remoteMediaId: String? = null,
    val thumbnailUrl: String? = null,
    val originLocal: Boolean = true,
    val hiddenFromLibrary: Boolean = false,
    /**
     * The ORIGINAL's cloud delivery URL, or `null` once the Cloudinary original
     * has been cleaned up.
     *
     * Kept strictly apart from [thumbnailUrl]: the thumbnail is the persistent
     * gallery asset (`…/thumbnails/…`, its own Cloudinary public ID) and is
     * available for the whole life of the media, while this is the full-size
     * source and disappears with the verified cleanup. Full resolution must never
     * be served by upscaling a thumbnail, so the two are never substituted for
     * each other.
     */
    val originalUrl: String? = null,
    /**
     * True when an authoritative `media_assets` row still proves this media is
     * backed up. Set from the cloud catalog, never from the local upload queue —
     * see [backupStateFor] / [isEligibleForBackup].
     */
    val cloudBackedUp: Boolean = false
) {
    val displayUri: String
        get() = when {
            uri.isNotBlank() && originLocal -> uri
            !thumbnailUrl.isNullOrBlank() -> thumbnailUrl
            uri.isNotBlank() -> uri
            else -> thumbnailUrl.orEmpty()
        }

    /**
     * The candidate for FULL RESOLUTION — never a thumbnail.
     *
     * For a device item this is its own MediaStore URI. For a cloud-only item it
     * is the Cloudinary original, or blank once that original is gone, which is
     * exactly when the authenticated Drive copy is the right source.
     */
    val originalUri: String
        get() = if (originLocal) uri else originalUrl.orEmpty()
}

/**
 * Whether an authoritative, still-available cloud record already exists for this
 * local media.
 *
 * Deliberately NOT derived from the device-local queue record: the queue is
 * install-local state, so a reinstall, a cleared data directory or an owner
 * re-bind loses it and every item looks un-backed-up again. Also deliberately
 * NOT keyed by `device_id`, which is volatile: the same physical photo backed up
 * from a second install must not be uploaded a second time.
 *
 * [cloudBackedUp] is set only from a catalog-visible `media_assets` row matched
 * to the local item (by `local_media_id`, or an already-known cloud id), so it
 * survives both losses.
 */
fun backupStateFor(item: MediaItem, recordState: BackupState?): BackupState = when {
    recordState != null -> recordState
    item.cloudBackedUp -> BackupState.COMPLETED
    else -> item.backupState
}

/**
 * The single eligibility rule for a backup run.
 *
 * An item needs backup only when it has never been backed up, or its previous
 * attempt is retryable, AND no authoritative cloud record already proves a
 * completed backup (`cloudBackedUp`).
 */
fun isEligibleForBackup(item: MediaItem, recordState: BackupState?): Boolean {
    if (item.cloudBackedUp) return false
    val state = backupStateFor(item, recordState)
    return state == BackupState.NOT_STARTED ||
        // Uploaded to Cloudinary but never recorded in Supabase: genuinely
        // incomplete, so it is resumed. BackupRepository skips the Cloudinary
        // upload when the record already holds an asset, and finalize-media is
        // idempotent on client_upload_id, so resuming cannot duplicate anything.
        state == BackupState.CLOUDINARY_COMPLETED ||
        state.isRetryable
}

data class TrashSummary(
    val count: Int = 0,
    val totalSizeBytes: Long = 0L
)

data class AlbumFolder(
    val id: String,
    val name: String,
    val coverSeed: Int,
    val coverType: MediaType = MediaType.PHOTO,
    val mediaCount: Int = 0,
    val coverUri: String = "",
    /** Lightweight cloud candidate used when [coverUri] is a stale device URI. */
    val coverPreviewUri: String? = null,
    val coverRemoteMediaId: String? = null
)

data class ActivityEvent(
    val id: String,
    val title: String,
    val timestampMillis: Long
)

data class BackupOverview(
    val status: ConnectionStatus,
    val headline: String,
    val description: String,
    val lastSyncLabel: String,
    val telegramConnected: Boolean,
    val progress: Float? = null
)

data class TodayStats(
    val photosBackedUp: Int,
    val videosBackedUp: Int,
    val pending: Int,
    val failed: Int
)

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val accountStatus: String = ""
)

data class BackupPreferences(
    val automaticBackup: Boolean,
    val backupPhotos: Boolean,
    val backupVideos: Boolean,
    val wifiOnly: Boolean,
    val uploadWhileCharging: Boolean
)

enum class TelegramConnectionState {
    NOT_CONFIGURED,
    INCOMPLETE,
    NOT_TESTED,
    TESTING,
    CONNECTED,
    FAILED
}

data class TelegramSettings(
    val enabled: Boolean = false,
    val tokenConfigured: Boolean = false,
    val botTokenMasked: String = "",
    val chatId: String = "",
    val connectionState: TelegramConnectionState = TelegramConnectionState.NOT_CONFIGURED,
    val connectionMessage: String = ""
) {
    val connected: Boolean
        get() = connectionState == TelegramConnectionState.CONNECTED
}

data class StorageSummary(
    val totalMedia: Int,
    val photos: Int,
    val videos: Int,
    val pendingUploads: Int
)

data class SyncSummary(
    val inProgressCount: Int,
    val completedToday: Int
)

data class MediaLoadState(
    val accessGranted: Boolean = false,
    val accessPartial: Boolean = false,
    val needsPermission: Boolean = true,
    val permissionDenied: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val errorMessage: String? = null
)
