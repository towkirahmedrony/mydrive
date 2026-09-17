package com.mydrive.app.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mydrive.app.MyDriveApp
import com.mydrive.app.data.remote.UploadLog
import java.util.concurrent.TimeUnit

object UploadWorkScheduler {
    private const val UNIQUE_WORK_NAME = "mydrive-media-upload-queue"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        UploadLog.workScheduled()
    }
}

class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MyDriveApp
        UploadLog.workStarted()
        return try {
            app.backupRepository.processPendingQueue()
            Result.success()
        } catch (error: Throwable) {
            if (runAttemptCount < 5) {
                UploadLog.retryScheduled(runAttemptCount + 1)
                Result.retry()
            } else {
                UploadLog.permanentFailure("worker_attempt_limit")
                Result.failure()
            }
        }
    }
}
