package com.mydrive.app.data.mock

import com.mydrive.app.data.model.ActivityEvent
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.BackupOverview
import com.mydrive.app.data.model.BackupPreferences
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.ConnectionStatus
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.StorageSummary
import com.mydrive.app.data.model.SyncSummary
import com.mydrive.app.data.model.TelegramSettings
import com.mydrive.app.data.model.TodayStats
import com.mydrive.app.data.model.UserProfile

object MockMediaData {

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val now = System.currentTimeMillis()

    val profile = UserProfile(
        name = "Elena Voss",
        email = "elena@albums.local",
        accountStatus = "Active"
    )

    val backupOverview = BackupOverview(
        status = ConnectionStatus.CONNECTED,
        headline = "All caught up",
        description = "Your recent media is safely backed up",
        lastSyncLabel = "Last sync 12 min ago",
        telegramConnected = true,
        progress = null
    )

    val todayStats = TodayStats(
        photosBackedUp = 18,
        videosBackedUp = 3,
        pending = 2,
        failed = 1
    )

    val telegramSettings = TelegramSettings(
        connected = true,
        botTokenMasked = "••••••••••••••••",
        chatId = "48291037"
    )

    val backupPreferences = BackupPreferences(
        automaticBackup = true,
        backupPhotos = true,
        backupVideos = true,
        wifiOnly = true,
        uploadWhileCharging = false
    )

    val storageSummary = StorageSummary(
        totalMedia = 248,
        photos = 211,
        videos = 37,
        pendingUploads = 2
    )

    val syncSummary = SyncSummary(
        inProgressCount = 3,
        completedToday = 12
    )

    val albums: List<AlbumFolder> = listOf(
        AlbumFolder(id = "camera", name = "Camera", coverSeed = 12, mediaCount = 8),
        AlbumFolder(id = "screenshots", name = "Screenshots", coverSeed = 44, mediaCount = 4),
        AlbumFolder(id = "downloads", name = "Downloads", coverSeed = 27, mediaCount = 3),
        AlbumFolder(id = "whatsapp", name = "WhatsApp Images", coverSeed = 73, mediaCount = 4),
        AlbumFolder(id = "videos", name = "Videos", coverSeed = 31, coverType = MediaType.VIDEO, mediaCount = 5)
    )

    val mediaItems: List<MediaItem> = listOf(
        MediaItem(
            id = "m1",
            filename = "IMG_20260912_183421.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 4_812_400,
            capturedAtMillis = now - 40 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4080 x 3072",
            isFavorite = true,
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 12,
            albumId = "camera"
        ),
        MediaItem(
            id = "m2",
            filename = "VID_20260912_171102.mp4",
            type = MediaType.VIDEO,
            fileSizeBytes = 48_220_000,
            capturedAtMillis = now - 2 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1920 x 1080",
            durationSeconds = 46,
            backupState = BackupState.SENDING_TELEGRAM,
            backupCompleted = true,
            telegramCompleted = false,
            thumbnailSeed = 31,
            progress = 0.72f,
            albumId = "videos"
        ),
        MediaItem(
            id = "m3",
            filename = "IMG_20260912_142210.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 3_240_800,
            capturedAtMillis = now - 5 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4000 x 3000",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 7,
            albumId = "camera"
        ),
        MediaItem(
            id = "m10",
            filename = "IMG_20260912_090014.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 2_440_000,
            capturedAtMillis = now - 9 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "3024 x 4032",
            backupState = BackupState.UPLOADING,
            backupCompleted = false,
            telegramCompleted = false,
            thumbnailSeed = 38,
            progress = 0.41f,
            albumId = "camera"
        ),
        MediaItem(
            id = "m12",
            filename = "VID_20260912_112233.mp4",
            type = MediaType.VIDEO,
            fileSizeBytes = 18_900_000,
            capturedAtMillis = now - 7 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1920 x 1080",
            durationSeconds = 14,
            backupState = BackupState.PROCESSING,
            backupCompleted = false,
            telegramCompleted = false,
            thumbnailSeed = 15,
            progress = 0.58f,
            albumId = "videos"
        ),
        MediaItem(
            id = "m13",
            filename = "Screenshot_20260912_101122.png",
            type = MediaType.PHOTO,
            fileSizeBytes = 1_120_400,
            capturedAtMillis = now - 8 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1080 x 2400",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 44,
            albumId = "screenshots"
        ),
        MediaItem(
            id = "m4",
            filename = "IMG_20260911_214455.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 2_980_100,
            capturedAtMillis = now - DAY_MS - 90 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4032 x 3024",
            isFavorite = true,
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 44,
            albumId = "camera"
        ),
        MediaItem(
            id = "m5",
            filename = "VID_20260911_193012.mp4",
            type = MediaType.VIDEO,
            fileSizeBytes = 92_440_000,
            capturedAtMillis = now - DAY_MS - 4 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "3840 x 2160",
            durationSeconds = 128,
            isFavorite = true,
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 19,
            albumId = "videos"
        ),
        MediaItem(
            id = "m6",
            filename = "IMG_20260911_091133.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 5_110_200,
            capturedAtMillis = now - DAY_MS - 10 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4080 x 3072",
            backupState = BackupState.WAITING,
            backupCompleted = false,
            telegramCompleted = false,
            thumbnailSeed = 52,
            progress = 0f,
            albumId = "camera"
        ),
        MediaItem(
            id = "m14",
            filename = "IMG-20260911-WA0003.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 1_880_000,
            capturedAtMillis = now - DAY_MS - 6 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1600 x 1200",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 73,
            albumId = "whatsapp"
        ),
        MediaItem(
            id = "m15",
            filename = "Screenshot_20260911_154401.png",
            type = MediaType.PHOTO,
            fileSizeBytes = 980_200,
            capturedAtMillis = now - DAY_MS - 7 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1080 x 2400",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 9,
            albumId = "screenshots"
        ),
        MediaItem(
            id = "m7",
            filename = "IMG_20260910_180021.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 3_660_000,
            capturedAtMillis = now - 2 * DAY_MS - 2 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4000 x 3000",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 27,
            albumId = "camera"
        ),
        MediaItem(
            id = "m8",
            filename = "VID_20260910_154410.mp4",
            type = MediaType.VIDEO,
            fileSizeBytes = 31_200_000,
            capturedAtMillis = now - 2 * DAY_MS - 5 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1920 x 1080",
            durationSeconds = 22,
            isFavorite = true,
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 61,
            albumId = "videos"
        ),
        MediaItem(
            id = "m9",
            filename = "IMG_20260910_091802.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 4_010_500,
            capturedAtMillis = now - 2 * DAY_MS - 11 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4032 x 3024",
            backupState = BackupState.FAILED,
            backupCompleted = false,
            telegramCompleted = false,
            thumbnailSeed = 9,
            errorMessage = "Upload interrupted",
            albumId = "camera"
        ),
        MediaItem(
            id = "m16",
            filename = "download_skyline.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 2_210_000,
            capturedAtMillis = now - 2 * DAY_MS - 8 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "2400 x 1600",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 27,
            albumId = "downloads"
        ),
        MediaItem(
            id = "m11",
            filename = "IMG_20260909_201155.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 3_880_300,
            capturedAtMillis = now - 3 * DAY_MS - 1 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4080 x 3072",
            isFavorite = true,
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 73,
            albumId = "camera"
        ),
        MediaItem(
            id = "m17",
            filename = "IMG-20260909-WA0011.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 1_540_800,
            capturedAtMillis = now - 3 * DAY_MS - 3 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1600 x 1200",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 38,
            albumId = "whatsapp"
        ),
        MediaItem(
            id = "m18",
            filename = "Screenshot_20260909_112044.png",
            type = MediaType.PHOTO,
            fileSizeBytes = 1_040_000,
            capturedAtMillis = now - 3 * DAY_MS - 9 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1080 x 2400",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 52,
            albumId = "screenshots"
        ),
        MediaItem(
            id = "m19",
            filename = "VID_20260908_184422.mp4",
            type = MediaType.VIDEO,
            fileSizeBytes = 64_100_000,
            capturedAtMillis = now - 4 * DAY_MS - 2 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1920 x 1080",
            durationSeconds = 87,
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 15,
            albumId = "videos"
        ),
        MediaItem(
            id = "m20",
            filename = "download_poster.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 3_330_000,
            capturedAtMillis = now - 4 * DAY_MS - 6 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "2000 x 3000",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 61,
            albumId = "downloads"
        ),
        MediaItem(
            id = "m21",
            filename = "IMG-20260908-WA0008.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 1_760_000,
            capturedAtMillis = now - 4 * DAY_MS - 8 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1600 x 1200",
            isFavorite = true,
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 7,
            albumId = "whatsapp"
        ),
        MediaItem(
            id = "m22",
            filename = "IMG_20260907_073310.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 4_220_000,
            capturedAtMillis = now - 5 * DAY_MS - 1 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "4080 x 3072",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 12,
            albumId = "camera"
        ),
        MediaItem(
            id = "m23",
            filename = "Screenshot_20260907_221901.png",
            type = MediaType.PHOTO,
            fileSizeBytes = 890_400,
            capturedAtMillis = now - 5 * DAY_MS - 4 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1080 x 2400",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 31,
            albumId = "screenshots"
        ),
        MediaItem(
            id = "m24",
            filename = "download_map.jpg",
            type = MediaType.PHOTO,
            fileSizeBytes = 2_670_000,
            capturedAtMillis = now - 5 * DAY_MS - 7 * 60 * 60 * 1000,
            device = "Pixel 8",
            resolution = "1800 x 1200",
            backupState = BackupState.COMPLETED,
            thumbnailSeed = 19,
            albumId = "downloads"
        )
    )

    val recentActivity: List<ActivityEvent> = listOf(
        ActivityEvent("a1", "12 photos backed up", now - 18 * 60 * 1000),
        ActivityEvent("a2", "Video backup completed", now - 42 * 60 * 1000),
        ActivityEvent("a3", "Telegram sync completed", now - 55 * 60 * 1000),
        ActivityEvent("a4", "2 files waiting for upload", now - 80 * 60 * 1000)
    )
}
