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
import com.mydrive.app.BuildConfig
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.session.AccountSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object ThumbnailLoader {

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
        sessionProvider: AuthenticatedSessionProvider? = null,
        userId: String? = AccountSession.userId
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank() && fallbackMediaId.isNullOrBlank()) return@withContext null
        val session = AccountSession.snapshot()
        val ownerId = userId ?: session.userId
        val remote = MediaCacheKeys.isRemoteUri(uriString)
        if (remote && ownerId.isNullOrBlank()) return@withContext null
        val key = MediaCacheKeys.memoryKey(
            userId = ownerId,
            mediaId = fallbackMediaId,
            uri = uriString,
            variant = MediaCacheKeys.VARIANT_THUMBNAIL,
            sizePx = sizePx
        )
        if (key == "blocked-remote") return@withContext null
        cache.get(key)?.let { return@withContext it }
        if (!stillCurrent(session, ownerId)) return@withContext null
        fallbackMediaId?.let { id ->
            if (!ownerId.isNullOrBlank()) {
                readDisk(context.applicationContext, ownerId, id, sizePx)?.let {
                    if (!stillCurrent(session, ownerId)) return@withContext null
                    cache.put(key, it)
                    return@withContext it
                }
            }
        }
        val bitmap = if (uriString.isNotBlank()) {
            val uri = try {
                Uri.parse(uriString)
            } catch (_: Exception) {
                null
            }
            when {
                uri == null -> null
                uri.scheme == "http" || uri.scheme == "https" -> decodeHttp(uri, sizePx)
                else -> decode(context.applicationContext, uri, sizePx)
            }
        } else {
            null
        } ?: fallbackMediaId?.let { mediaId ->
            sessionProvider?.let { provider -> decodeDriveThumbnail(mediaId, sizePx, provider) }
        } ?: return@withContext null
        if (!stillCurrent(session, ownerId)) return@withContext null
        cache.put(key, bitmap)
        if (!ownerId.isNullOrBlank() && !fallbackMediaId.isNullOrBlank()) {
            writeDisk(context.applicationContext, ownerId, fallbackMediaId, sizePx, bitmap)
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
        val accessToken = when (val prepared = sessionProvider.prepare(forceRefresh = false)) {
            is PreparedAuth.Available -> prepared.accessToken
            else -> return null
        }
        val connection = try {
            (URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/media-drive")
                .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    outputStream.use { output ->
                        output.write(
                            buildJsonObject {
                                put("media_id", mediaId)
                                put("variant", "thumb")
                            }.toString().toByteArray(Charsets.UTF_8)
                        )
                    }
                }
        } catch (_: Exception) {
            return null
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_THUMBNAIL_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDim / sample > sizePx * 2) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
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

    private fun readDisk(context: Context, userId: String, mediaId: String, sizePx: Int): Bitmap? =
        runCatching {
            BitmapFactory.decodeFile(
                MediaCacheKeys.thumbnailFile(context.filesDir, userId, mediaId, sizePx).path
            )
        }.getOrNull()

    private fun writeDisk(context: Context, userId: String, mediaId: String, sizePx: Int, bitmap: Bitmap) {
        runCatching {
            val file = MediaCacheKeys.thumbnailFile(context.filesDir, userId, mediaId, sizePx)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 88, it) }
        }
    }

    private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
}
