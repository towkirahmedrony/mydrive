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
    val cloudinaryPublicId: String? = null
)

data class AlbumFolder(
    val id: String,
    val name: String,
    val coverSeed: Int,
    val coverType: MediaType = MediaType.PHOTO,
    val mediaCount: Int = 0,
    val coverUri: String = ""
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
    val errorMessage: String? = null
)
