package com.mydrive.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object FullImageLoader {

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun peek(uriString: String, maxDimPx: Int): Bitmap? {
        if (uriString.isBlank()) return null
        return cache.get(cacheKey(uriString, maxDimPx))
    }

    suspend fun load(
        context: Context,
        uriString: String,
        maxDimPx: Int = 2048
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        val key = cacheKey(uriString, maxDimPx)
        cache.get(key)?.let { return@withContext it }

        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return@withContext null
        }

        val bitmap = decode(context.applicationContext, uri, maxDimPx) ?: return@withContext null
        cache.put(key, bitmap)
        bitmap
    }

    private fun decode(context: Context, uri: Uri, maxDimPx: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(context, uri, maxDimPx)
            } else {
                decodeSampled(context, uri, maxDimPx)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxDimPx: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height

                val maxDimension = maxOf(width, height)
                var sample = 1
                while (maxDimension / sample > maxDimPx && (maxDimension / (sample * 2)) >= maxDimPx) {
                    sample *= 2
                }

                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) {
            decodeSampled(context, uri, maxDimPx)
        }
    }

    private fun decodeSampled(context: Context, uri: Uri, maxDimPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sample = 1
        val maxDimension = maxOf(width, height)
        while (maxDimension / sample > maxDimPx) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun open(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKey(uriString: String, maxDimPx: Int): String = "$uriString@$maxDimPx"

    private fun cacheKb(): Int {
        val max = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (max / 4).coerceIn(8192, 49_152)
    }
}
