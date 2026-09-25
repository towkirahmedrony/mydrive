package com.mydrive.app.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mydrive.app.MyDriveApp
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory

/**
 * Uploads buffered accessibility events in batches.
 *
 * Mirrors the existing [UploadWorkScheduler] pattern rather than introducing new
 * scheduling: unique work with a network constraint. Events stay in the Room
 * outbox until the server confirms the batch, so the feature degrades to
 * "buffered locally" while a device is offline and resumes automatically.
 */
object AccessibilitySyncScheduler {

    const val UNIQUE_WORK_NAME = "accessibility_sync"

    /**
     * Debounced flush: repeated calls collapse into one pending batch instead of
     * scheduling a request per accessibility event.
     */
    fun schedule(context: Context, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<AccessibilitySyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}

class AccessibilitySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MyDriveApp ?: return Result.success()
        return try {
            when (app.accessibilityMonitoringCoordinator.flush()) {
                // Nothing to send, or the batch was accepted.
                is com.mydrive.app.data.accessibility.AccessibilityMonitoringCoordinator.FlushResult.Complete ->
                    Result.success()
                // Keep the buffered events and let WorkManager retry with backoff.
                is com.mydrive.app.data.accessibility.AccessibilityMonitoringCoordinator.FlushResult.Retry ->
                    Result.retry()
                is com.mydrive.app.data.accessibility.AccessibilityMonitoringCoordinator.FlushResult.NotSignedIn ->
                    Result.success()
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.MEDIA,
                event = "ACCESSIBILITY_SYNC_FAILED",
                message = "Accessibility batch upload failed",
                metadata = mapOf("reason" to error.javaClass.simpleName)
            )
            Result.retry()
        }
    }
}
