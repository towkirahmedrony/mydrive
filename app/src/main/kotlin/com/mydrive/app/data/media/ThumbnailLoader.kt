package com.mydrive.app.data.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.session.AccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection

object ThumbnailLoader {

    /** The preview size album covers, grid cells and viewer neighbours ask for. */
    const val PREVIEW_SIZE_PX = 256

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun evictMemory() {
        cache.evictAll()
    }

    fun peek(
        uriString: String,
        sizePx: Int,
        mediaId: String? = null,
        userId: String? = AccountSession.userId
    ): Bitmap? {
        if (uriString.isBlank() && mediaId.isNullOrBlank()) return null
        val key = MediaCacheKeys.memoryKey(
            userId = userId,
            mediaId = mediaId,
            uri = uriString,
            variant = MediaCacheKeys.VARIANT_THUMBNAIL,
            sizePx = sizePx
        )
        if (key == "blocked-remote") return null
        return cache.get(key)
    }

    suspend fun load(
        context: Context,
        uriString: String,
        sizePx: Int,
        fallbackMediaId: String? = null,
        previewUri: String? = null,
        sessionProvider: AuthenticatedSessionProvider? = null,
        userId: String? = AccountSession.userId
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank() && previewUri.isNullOrBlank() && fallbackMediaId.isNullOrBlank()) {
            return@withContext null
        }
        val session = AccountSession.snapshot()
        val ownerId = userId ?: session.userId
        val preview = previewUri?.trim().orEmpty()
        val remote = MediaCacheKeys.isRemoteUri(uriString)
        // A cloud candidate must never be fetched for an unauthenticated (or
        // previous-account) session, whichever URL carries it.
        if ((remote || MediaCacheKeys.isRemoteUri(preview)) && ownerId.isNullOrBlank()) {
            return@withContext null
        }
        // The cache identity stays {user, media id, variant, size}: a rotating or
        // re-derived URL never splits an entry for the same media.
        val key = MediaCacheKeys.memoryKey(
            userId = ownerId,
            mediaId = fallbackMediaId,
            uri = uriString.ifBlank { preview },
            variant = MediaCacheKeys.VARIANT_THUMBNAIL,
            sizePx = sizePx
        )
        if (key == "blocked-remote") return@withContext null
        cache.get(key)?.let { return@withContext it }
        if (!stillCurrent(session, ownerId)) return@withContext null
        val appContext = context.applicationContext
        var fromDisk = false
        val cloudCandidate = if (remote) uriString else preview
        val bitmap = run {
            for (step in MediaFetchOrder.steps(
                uriString,
                hasStableMediaId = !fallbackMediaId.isNullOrBlank(),
                previewUri = preview
            )) {
                if (!stillCurrent(session, ownerId)) return@withContext null
                val decoded = when (step) {
                    MediaFetchSource.DISK -> {
                        if (!ownerId.isNullOrBlank() && !fallbackMediaId.isNullOrBlank()) {
                            readDisk(appContext, ownerId, fallbackMediaId, sizePx)
                        } else {
                            null
                        }
                    }
                    MediaFetchSource.LOCAL -> {
                        runCatching { Uri.parse(uriString) }.getOrNull()?.let { decode(appContext, it, sizePx) }
                    }
                    MediaFetchSource.CLOUDINARY -> {
                        // A stored delivery URL is the full-size original; ask
                        // Cloudinary for a thumbnail-sized derivative instead of
                        // downloading the original to show a preview.
                        CloudinaryPreview.previewUrl(cloudCandidate, sizePx)
                            ?.let { decodeHttp(Uri.parse(it), sizePx) }
                    }
                    MediaFetchSource.DRIVE -> {
                        fallbackMediaId?.let { mediaId ->
                            sessionProvider?.let { provider -> decodeDriveThumbnail(mediaId, sizePx, provider) }
                        }
                    }
                }
                if (decoded != null) {
                    fromDisk = step == MediaFetchSource.DISK
                    return@run decoded
                }
            }
            null
        } ?: return@withContext null
        if (!stillCurrent(session, ownerId)) return@withContext null
        cache.put(key, bitmap)
        if (!fromDisk && !ownerId.isNullOrBlank() && !fallbackMediaId.isNullOrBlank()) {
            writeDisk(appContext, ownerId, fallbackMediaId, sizePx, bitmap)
        }
        bitmap
    }

    private fun stillCurrent(session: AccountSession.Snapshot, ownerId: String?): Boolean {
        if (ownerId.isNullOrBlank()) return true
        return AccountSession.isCurrent(session.userId, session.generation) &&
            AccountSession.userId == ownerId
    }

    private fun decodeHttp(uri: Uri, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val first = openHttp(uri) ?: return null
        try {
            first.inputStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) {
            return null
        } finally {
            first.disconnect()
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > sizePx * 2) sample *= 2
        val second = openHttp(uri) ?: return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            second.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        } finally {
            second.disconnect()
        }
    }

    private suspend fun decodeDriveThumbnail(
        mediaId: String,
        sizePx: Int,
        sessionProvider: AuthenticatedSessionProvider
    ): Bitmap? {
        val bytes = MediaDriveClient.fetchBytes(
            mediaId = mediaId,
            variant = MediaDriveClient.VARIANT_THUMB,
            sessionProvider = sessionProvider,
            maxBytes = MAX_THUMBNAIL_BYTES.toLong(),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 15_000
        ) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > sizePx * 2) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun openHttp(uri: Uri): HttpURLConnection? {
        return try {
            (java.net.URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                useCaches = true
                requestMethod = "GET"
                setRequestProperty("Accept", "image/*")
                connect()
                if (responseCode !in 200..299) {
                    disconnect()
                    null
                } else {
                    this
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), CancellationSignal())
            } else {
                legacyThumbnail(context, uri, sizePx)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyThumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val id = try {
            ContentUris.parseId(uri)
        } catch (_: Exception) {
            -1L
        }
        if (id >= 0L) {
            val kind = if (sizePx <= 96) {
                MediaStore.Images.Thumbnails.MICRO_KIND
            } else {
                MediaStore.Images.Thumbnails.MINI_KIND
            }
            val fromStore = try {
                if (uri.toString().contains("/video/", ignoreCase = true)) {
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        id,
                        kind,
                        null
                    )
                } else {
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver,
                        id,
                        kind,
                        null
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (fromStore != null) return fromStore
        }
        return decodeSampled(context, uri, sizePx)
    }

    private fun decodeSampled(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sample = 1
        val maxDim = maxOf(width, height)
        while (maxDim / sample > sizePx * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun open(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKb(): Int {
        val max = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (max / 8).coerceIn(4096, 24_576)
    }

    private fun readDisk(context: Context, userId: String, mediaId: String, sizePx: Int): Bitmap? {
        val file = MediaCacheKeys.thumbnailFile(context.filesDir, userId, mediaId, sizePx)
        if (!MediaDiskCache.isComplete(file)) {
            MediaDiskCache.discardInvalid(file)
            return null
        }
        val bitmap = MediaDiskCache.pinned(file) {
            runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
        }
        if (bitmap == null) {
            runCatching { file.delete() }
            return null
        }
        MediaDiskCache.touch(file)
        return bitmap
    }

    private fun writeDisk(context: Context, userId: String, mediaId: String, sizePx: Int, bitmap: Bitmap) {
        val file = MediaCacheKeys.thumbnailFile(context.filesDir, userId, mediaId, sizePx)
        val encoded = runCatching {
            ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.WEBP, 88, it)
            }.toByteArray()
        }.getOrNull() ?: return
        if (MediaDiskCache.write(file, encoded)) MediaDiskCache.scheduleTrim(context)
    }

    private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
}
