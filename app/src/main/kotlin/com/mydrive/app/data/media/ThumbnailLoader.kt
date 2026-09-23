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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection

object ThumbnailLoader {

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun peek(uriString: String, sizePx: Int): Bitmap? {
        if (uriString.isBlank()) return null
        return cache.get(cacheKey(uriString, sizePx))
    }

    suspend fun load(
        context: Context,
        uriString: String,
        sizePx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        val key = cacheKey(uriString, sizePx)
        cache.get(key)?.let { return@withContext it }
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return@withContext null
        }
        val bitmap = if (uri.scheme == "http" || uri.scheme == "https") {
            decodeHttp(uri, sizePx)
        } else {
            decode(context.applicationContext, uri, sizePx)
        } ?: return@withContext null
        cache.put(key, bitmap)
        bitmap
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

    private fun cacheKey(uriString: String, sizePx: Int): String = "$uriString@$sizePx"

    private fun cacheKb(): Int {
        val max = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (max / 8).coerceIn(4096, 24_576)
    }
}
