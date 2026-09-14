package com.mydrive.app.data.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * High-resolution decoder for the full-screen media viewer.
 *
 * The grid keeps using [ThumbnailLoader] (small system thumbnails). This loader
 * decodes the ORIGINAL MediaStore bytes at a size suitable for the device
 * viewport (with zoom headroom), handles EXIF orientation, and caches results
 * in its own LRU cache. It is intentionally used only for the currently visible
 * viewer page (plus prefetched neighbors) so gallery memory stays small.
 */
object FullImageLoader {

    /** Never decode beyond this longest-side pixel count (memory safety cap). */
    private const val HARD_CAP_PX = 4096

    private val cache = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        // Bitmaps are still referenced by the UI while on screen; eviction must
        // never recycle them, the GC reclaims the memory once unreferenced.
    }

    fun peek(uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return cache.get(uriString)
    }

    /**
     * Decodes the original image behind [uriString] so its longest side is at
     * least [targetPx] pixels (bounded by [HARD_CAP_PX]). Returns the cached
     * bitmap when present. Returns null when the media cannot be read/decoded.
     */
    suspend fun load(
        context: Context,
        uriString: String,
        targetPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        cache.get(uriString)?.let { return@withContext it }
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return@withContext null
        }
        val appContext = context.applicationContext
        val bitmap = try {
            decode(appContext, uri, targetPx)
        } catch (_: SecurityException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) {
            cache.put(uriString, bitmap)
        }
        bitmap
    }

    private fun decode(context: Context, uri: Uri, targetPx: Int): Bitmap? {
        val want = targetPx.coerceIn(480, HARD_CAP_PX)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, uri, want)
        } else {
            decodeWithBitmapFactory(context, uri, want)
        }
    }

    /**
     * API 28+: ImageDecoder honors EXIF orientation automatically and supports
     * power-of-two sampling without a second bounds pass. A hardware-allocated
     * bitmap keeps the large result off the Java heap; failures fall back to a
     * software decode at a slightly smaller target.
     */
    private fun decodeWithImageDecoder(context: Context, uri: Uri, want: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { info, decoder, _ ->
                val srcMax = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                val sample = sampleFor(srcMax, want)
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            }
        } catch (_: OutOfMemoryError) {
            decodeWithImageDecoderSoftware(context, uri, minOf(want, 2880))
        } catch (_: Exception) {
            decodeWithImageDecoderSoftware(context, uri, minOf(want, 2880))
        }
    }

    private fun decodeWithImageDecoderSoftware(context: Context, uri: Uri, want: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { info, decoder, _ ->
                val srcMax = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                val sample = sampleFor(srcMax, want)
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample)
                }
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * API 26/27: sampled BitmapFactory decode plus explicit EXIF orientation
     * correction (BitmapFactory does not apply it, MediaStore thumbnails did).
     */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri, want: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcMax = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcMax <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(srcMax, want)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        return applyOrientation(context.contentResolver, uri, decoded)
    }

    private fun applyOrientation(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            @Suppress("DEPRECATION")
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.ImageColumns.ORIENTATION),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            } ?: 0
        } catch (_: Exception) {
            0
        }
        if (orientation == 0) return bitmap
        val matrix = Matrix()
        when (orientation) {
            90 -> matrix.postRotate(90f)
            180 -> matrix.postRotate(180f)
            270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: OutOfMemoryError) {
            bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    /**
     * Largest power-of-two sample that still keeps the longest side at or above
     * [want] (never downsamples below the sharpness target), then raised if
     * needed to respect [HARD_CAP_PX].
     */
    private fun sampleFor(srcMax: Int, want: Int): Int {
        var sample = 1
        while (srcMax / (sample * 2) >= want) {
            sample *= 2
        }
        while (srcMax / sample > HARD_CAP_PX) {
            sample *= 2
        }
        return sample
    }

    private fun open(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKb(): Int {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxKb / 4).coerceIn(32_768, 131_072)
    }
}
