package com.mydrive.app.data.mock

import com.mydrive.app.data.model.ActivityEvent
import com.mydrive.app.data.model.BackupOverview
import com.mydrive.app.data.model.BackupPreferences
import com.mydrive.app.data.model.ConnectionStatus
import com.mydrive.app.data.model.StorageSummary
import com.mydrive.app.data.model.SyncSummary
import com.mydrive.app.data.model.TelegramSettings
import com.mydrive.app.data.model.TodayStats
import com.mydrive.app.data.model.UserProfile

object MockMediaData {

    val profile = UserProfile(
        name = "Elena Voss",
        email = "elena@albums.local",
        accountStatus = "Active"
    )

    val backupOverview = BackupOverview(
        status = ConnectionStatus.ATTENTION,
        headline = "Backup not started",
        description = "Cloud backup is not configured yet",
        lastSyncLabel = "Not synced yet",
        telegramConnected = false,
        progress = null
    )

    val todayStats = TodayStats(
        photosBackedUp = 0,
        videosBackedUp = 0,
        pending = 0,
        failed = 0
    )

    val telegramSettings = TelegramSettings(
        enabled = false,
        tokenConfigured = false,
        chatId = ""
    )

    val backupPreferences = BackupPreferences(
        automaticBackup = true,
        backupPhotos = true,
        backupVideos = true,
        wifiOnly = true,
        uploadWhileCharging = false
    )

    val storageSummary = StorageSummary(
        totalMedia = 0,
        photos = 0,
        videos = 0,
        pendingUploads = 0
    )

    val syncSummary = SyncSummary(
        inProgressCount = 0,
        completedToday = 0
    )

    val recentActivity: List<ActivityEvent> = emptyList()
}
