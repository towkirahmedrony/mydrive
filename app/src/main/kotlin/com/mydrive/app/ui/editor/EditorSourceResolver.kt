package com.mydrive.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mydrive.app.MyDriveApp
import com.mydrive.app.data.media.FullImageLoader
import com.mydrive.app.data.media.MediaCacheKeys
import com.mydrive.app.data.model.MediaItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the picture the editor should open, for both kinds of library item:
 *
 *  - a local photo is opened straight from its MediaStore URI when that URI still
 *    resolves;
 *  - a cloud-only photo — the device copy is gone (or never existed) — has its
 *    highest-quality available original fetched through the existing cloud media
 *    pipeline ([FullImageLoader], which already knows Cloudinary and the
 *    authenticated Drive copy) and written to app-private cache storage.
 *
 * A local source is never downloaded, and the app-private copy is never inserted
 * into MediaStore, never exposed to other apps and never given a `media_assets`
 * row. It is a throwaway editor input, deleted when the editor closes.
 */
object EditorSourceResolver {

    /** Editing tolerates a large download; the preview pipeline down-samples anyway. */
    private const val CLOUD_MAX_DIM_PX = 3000

    /** Stale editor inputs from a previous session are reclaimed on the next download. */
    private const val MAX_TEMP_AGE_MS = 24L * 60L * 60L * 1000L

    sealed class Result {
        data class Local(val uri: String) : Result()
        data class Downloaded(val file: File) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun resolve(context: Context, item: MediaItem): Result = withContext(Dispatchers.IO) {
        if (item.originLocal && item.uri.isNotBlank() && isReadable(context, item.uri)) {
            return@withContext Result.Local(item.uri)
        }

        // The highest-quality original first (the Cloudinary source), then the
        // persistent thumbnail, and finally the authenticated Drive copy through
        // the media id. Never invented here — only the URLs the catalog carries.
        val primary = item.originalUrl?.takeIf { it.isNotBlank() }
            ?: item.uri.takeIf { !item.originLocal && MediaCacheKeys.isRemoteUri(it) }
            ?: ""
        val preview = item.thumbnailUrl?.takeIf { it.isNotBlank() }
        if (primary.isBlank() && preview.isNullOrBlank() && item.remoteMediaId.isNullOrBlank()) {
            return@withContext Result.Failed("This photo isn't available on this device or in My Drive.")
        }

        val app = context.applicationContext as? MyDriveApp
        val bitmap = runCatching {
            FullImageLoader.load(
                context = context,
                uriString = primary,
                maxDimPx = CLOUD_MAX_DIM_PX,
                fallbackMediaId = item.remoteMediaId,
                previewUri = preview,
                sessionProvider = app?.sessionProvider
            )
        }.getOrNull()
            ?: return@withContext Result.Failed("Couldn't download this photo from My Drive. Check your connection and try again.")

        val file = runCatching { writeAppPrivate(context, item, bitmap) }.getOrNull()
        if (!bitmap.isRecycled) bitmap.recycle()
        file?.let { Result.Downloaded(it) }
            ?: Result.Failed("Couldn't prepare this photo for editing. Please try again.")
    }

    private fun writeAppPrivate(context: Context, item: MediaItem, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "editor-source").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { stale ->
            if (now - stale.lastModified() > MAX_TEMP_AGE_MS) runCatching { stale.delete() }
        }
        val safeId = item.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val file = File(dir, "source-$safeId-$now.jpg")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        if (!file.exists() || file.length() <= 0L) {
            runCatching { file.delete() }
            throw IllegalStateException("empty cloud source")
        }
        return file
    }

    private fun isReadable(context: Context, uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val probe = ByteArray(1)
            stream.read(probe) >= 0
        } ?: false
    }.getOrDefault(false)
}
