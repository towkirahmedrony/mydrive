package com.mydrive.app.data.session

import android.content.Context
import androidx.work.WorkManager
import com.mydrive.app.data.media.FullImageLoader
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.data.repository.SyncRepository
import com.mydrive.app.data.worker.BackupDiscoveryScheduler
import com.mydrive.app.data.worker.UploadWorkScheduler
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.SecretRedactor
import java.util.concurrent.atomic.AtomicBoolean

class AccountSessionCoordinator(
    private val context: Context,
    private val mediaRepository: MediaRepository,
    private val syncRepository: SyncRepository,
    private val onAuthenticatedReady: () -> Unit = {}
) {
    private val switching = AtomicBoolean(false)

    fun onSignedOut(previousUserId: String?) {
        switching.set(true)
        AccountSession.bind(null)
        WorkManager.getInstance(context).cancelUniqueWork(UploadWorkScheduler.UNIQUE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(BackupDiscoveryScheduler.CONTENT_WORK_NAME)
        mediaRepository.clearAccountSession()
        syncRepository.clearSession()
        ThumbnailLoader.evictMemory()
        FullImageLoader.evictMemory()
        DeveloperLogger.info(
            category = LogCategory.AUTH,
            event = "ACCOUNT_SESSION_CLEARED",
            message = "Local account session isolated after sign-out",
            metadata = mapOf("user_id" to SecretRedactor.maskUserId(previousUserId).orEmpty())
        )
        switching.set(false)
    }

    fun onAuthenticated(userId: String) {
        switching.set(true)
        AccountSession.bind(userId)
        mediaRepository.bindAccount(userId)
        syncRepository.bindOwner(userId)
        ThumbnailLoader.evictMemory()
        FullImageLoader.evictMemory()
        UploadWorkScheduler.schedule(context, replace = true)
        onAuthenticatedReady()
        DeveloperLogger.info(
            category = LogCategory.AUTH,
            event = "ACCOUNT_SESSION_BOUND",
            message = "Local account session bound",
            metadata = mapOf("user_id" to SecretRedactor.maskUserId(userId).orEmpty())
        )
        switching.set(false)
    }
}
