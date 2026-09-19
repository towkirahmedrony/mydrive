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
import com.mydrive.app.data.repository.BackupRepository
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import java.util.concurrent.TimeUnit

object UploadWorkScheduler {
    private const val UNIQUE_WORK_NAME = "mydrive-media-upload-queue"

    fun schedule(context: Context, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        DeveloperLogger.info(
            category = LogCategory.WORKMANAGER,
            event = "WORK_REQUEST_CREATED",
            message = "Unique upload work enqueued",
            workerId = request.id.toString(),
            metadata = mapOf(
                "unique_name" to UNIQUE_WORK_NAME,
                "policy" to policy.name,
                "network" to "CONNECTED",
                "backoff" to "EXPONENTIAL_30s"
            )
        )
        UploadLog.workScheduled()
    }
}

class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MyDriveApp
        val workerId = id.toString()
        DeveloperLogger.info(
            category = LogCategory.WORKMANAGER,
            event = "WORKER_STARTED",
            message = "Upload worker started",
            workerId = workerId,
            retryCount = runAttemptCount,
            metadata = mapOf(
                "unique_name" to "mydrive-media-upload-queue",
                "run_attempt_count" to runAttemptCount.toString(),
                "network_required" to "CONNECTED"
            )
        )
        UploadLog.workStarted()
        return try {
            val drain = app.backupRepository.processPendingQueue()
            when (drain) {
                BackupRepository.QueueDrain.Idle -> {
                    DeveloperLogger.info(
                        category = LogCategory.WORKMANAGER,
                        event = "WORKER_SUCCEEDED",
                        message = "Upload worker finished idle",
                        workerId = workerId,
                        metadata = mapOf("drain" to drain.name)
                    )
                    Result.success()
                }
                BackupRepository.QueueDrain.AwaitingSession -> {
                    DeveloperLogger.warn(
                        category = LogCategory.WORKMANAGER,
                        event = "WORKER_STOPPED",
                        message = "Upload worker stopped: awaiting session",
                        workerId = workerId,
                        retryCount = runAttemptCount,
                        metadata = mapOf("drain" to drain.name, "error_source" to "local_session")
                    )
                    Result.success()
                }
                BackupRepository.QueueDrain.NetworkUnavailable -> {
                    if (runAttemptCount < 5) {
                        DeveloperLogger.warn(
                            category = LogCategory.WORKMANAGER,
                            event = "WORKER_RETRY",
                            message = "Upload worker retrying after network unavailable",
                            workerId = workerId,
                            retryCount = runAttemptCount + 1,
                            metadata = mapOf("drain" to drain.name)
                        )
                        UploadLog.retryScheduled(runAttemptCount + 1)
                        Result.retry()
                    } else {
                        DeveloperLogger.info(
                            category = LogCategory.WORKMANAGER,
                            event = "WORKER_STOPPED",
                            message = "Upload worker stopped after network retries",
                            workerId = workerId
                        )
                        Result.success()
                    }
                }
            }
        } catch (error: Throwable) {
            if (runAttemptCount < 5) {
                DeveloperLogger.error(
                    category = LogCategory.WORKMANAGER,
                    event = "WORKER_RETRY",
                    message = "Upload worker exception; retry scheduled",
                    workerId = workerId,
                    throwable = error,
                    retryCount = runAttemptCount + 1
                )
                UploadLog.retryScheduled(runAttemptCount + 1)
                Result.retry()
            } else {
                DeveloperLogger.error(
                    category = LogCategory.WORKMANAGER,
                    event = "WORKER_FAILED",
                    message = "Upload worker failed after attempt limit",
                    workerId = workerId,
                    throwable = error,
                    retryCount = runAttemptCount
                )
                UploadLog.permanentFailure("worker_attempt_limit")
                Result.failure()
            }
        }
    }
}

