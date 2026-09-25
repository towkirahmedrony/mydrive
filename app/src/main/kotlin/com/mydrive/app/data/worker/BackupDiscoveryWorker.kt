package com.mydrive.app.data.worker

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import androidx.work.workDataOf
import com.mydrive.app.MyDriveApp
import com.mydrive.app.data.backup.BackupDiscoveryReason
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import java.util.concurrent.TimeUnit

object BackupDiscoveryScheduler {
    const val CONTENT_WORK_NAME = "mydrive-backup-discovery-content"
    const val PERIODIC_WORK_NAME = "mydrive-backup-discovery-periodic"
    const val KEY_REASON = "reason"

    fun schedule(context: Context) {
        scheduleContentWatch(context)
        schedulePeriodic(context)
    }

    fun scheduleContentWatch(
        context: Context,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        val constraints = Constraints.Builder().apply {
            addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                addContentUriTrigger(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                addContentUriTrigger(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            }
            setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
        }.build()
        val request = OneTimeWorkRequestBuilder<BackupDiscoveryWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_REASON to BackupDiscoveryReason.MEDIASTORE.name))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CONTENT_WORK_NAME, policy, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupDiscoveryWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_REASON to BackupDiscoveryReason.PERIODIC.name))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

/**
 * Discovers newly eligible media while the app is not in the foreground.
 * Upload itself stays on [UploadWorkScheduler] / [UploadWorker].
 */
class BackupDiscoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MyDriveApp
        val reason = runCatching {
            BackupDiscoveryReason.valueOf(inputData.getString(BackupDiscoveryScheduler.KEY_REASON).orEmpty())
        }.getOrDefault(BackupDiscoveryReason.PERIODIC)
        DeveloperLogger.info(
            category = LogCategory.WORKMANAGER,
            event = "BACKUP_DISCOVERY_WORKER",
            message = "Backup discovery worker started",
            workerId = id.toString(),
            metadata = mapOf("reason" to reason.name)
        )
        return try {
            app.automaticBackupCoordinator.request(reason)
            Result.success()
        } catch (error: Throwable) {
            DeveloperLogger.error(
                category = LogCategory.WORKMANAGER,
                event = "BACKUP_DISCOVERY_WORKER_FAILED",
                message = "Backup discovery worker failed",
                workerId = id.toString(),
                throwable = error
            )
            if (runAttemptCount < 3) Result.retry() else Result.success()
        } finally {
            if (reason == BackupDiscoveryReason.MEDIASTORE) {
                BackupDiscoveryScheduler.scheduleContentWatch(
                    applicationContext,
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                )
            }
        }
    }
}
