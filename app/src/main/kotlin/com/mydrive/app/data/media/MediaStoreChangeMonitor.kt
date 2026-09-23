package com.mydrive.app.data.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory

/**
 * Foreground-only MediaStore observer. Callbacks are coalesced so a burst of
 * inserts/deletes/restores becomes one local overlay refresh, not a scan storm.
 */
class MediaStoreChangeMonitor(
    context: Context,
    private val onLocalMediaChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            schedule()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            schedule()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
            schedule()
        }
    }

    @Volatile
    private var registered = false

    private val debounce = Runnable {
        DeveloperLogger.info(
            category = LogCategory.MEDIASTORE,
            event = "MEDIASTORE_CHANGED",
            message = "MediaStore change coalesced; refreshing local overlay"
        )
        onLocalMediaChanged()
    }

    fun start() {
        if (registered) return
        try {
            observedUris().forEach { uri ->
                appContext.contentResolver.registerContentObserver(uri, true, observer)
            }
            registered = true
        } catch (error: SecurityException) {
            DeveloperLogger.error(
                category = LogCategory.MEDIASTORE,
                event = "MEDIASTORE_OBSERVER_FAILED",
                message = "Could not register MediaStore observer",
                throwable = error
            )
        }
    }

    fun stop() {
        handler.removeCallbacks(debounce)
        if (!registered) return
        try {
            appContext.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {
        }
        registered = false
    }

    private fun schedule() {
        handler.removeCallbacks(debounce)
        handler.postDelayed(debounce, DEBOUNCE_MS)
    }

    private fun observedUris(): List<Uri> {
        val uris = mutableListOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uris += MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            uris += MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        return uris.distinct()
    }

    companion object {
        const val DEBOUNCE_MS = 800L
    }
}
