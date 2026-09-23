package com.mydrive.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.session.AccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object FullImageLoader {
    private const val MAX_CACHE_FILE_BYTES = 80L * 1024L * 1024L
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun clearCache() {
        cache.evictAll()
    }

    fun evictMemory() {
        cache.evictAll()
    }

    fun peek(
        uriString: String,
        maxDimPx: Int = 2048,
        mediaId: String? = null,
        userId: String? = AccountSession.userId
    ): Bitmap? {
        if (uriString.isBlank() && mediaId.isNullOrBlank()) return null
        val key = MediaCacheKeys.memoryKey(
            userId = userId,
            mediaId = mediaId,
            uri = uriString,
            variant = MediaCacheKeys.VARIANT_ORIGINAL,
            sizePx = maxDimPx
        )
        if (key == "blocked-remote") return null
        return cache.get(key)
    }

    suspend fun ensureOriginalFile(
        context: Context,
        uriString: String,
        mediaId: String?,
        sessionProvider: AuthenticatedSessionProvider?,
        userId: String? = AccountSession.userId
    ): Uri? = withContext(Dispatchers.IO) {
        val session = AccountSession.snapshot()
        val ownerId = userId ?: session.userId
        if (mediaId.isNullOrBlank() || ownerId.isNullOrBlank()) {
            return@withContext uriString.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        }
        val file = MediaCacheKeys.originalFile(context.applicationContext.filesDir, ownerId, mediaId)
        if (file.isFile) return@withContext Uri.fromFile(file)
        val fallbackUri = uriString.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        for (step in MediaFetchOrder.steps(uriString, hasStableMediaId = true, includeDisk = false)) {
            if (!stillCurrent(session, ownerId)) return@withContext null
            when (step) {
                MediaFetchSource.DISK -> Unit
                MediaFetchSource.LOCAL -> if (fallbackUri != null) return@withContext fallbackUri
                MediaFetchSource.CLOUDINARY -> {
                    val bytes = fallbackUri?.let { downloadHttp(it) }
                    if (bytes != null) {
                        writeCache(file, bytes)
                        if (file.isFile) return@withContext Uri.fromFile(file)
                    }
                }
                MediaFetchSource.DRIVE -> {
                    val bytes = sessionProvider?.let { downloadDriveOriginal(mediaId, it) }
                    if (bytes != null) {
                        writeCache(file, bytes)
                        if (file.isFile) return@withContext Uri.fromFile(file)
                    }
                }
            }
        }
        fallbackUri
    }

    suspend fun load(
        context: Context,
        uriString: String,
        maxDimPx: Int = 2048,
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
            variant = MediaCacheKeys.VARIANT_ORIGINAL,
            sizePx = maxDimPx
        )
        if (key == "blocked-remote") return@withContext null
        cache.get(key)?.let { return@withContext it }

        val appContext = context.applicationContext
        val diskFile = if (!ownerId.isNullOrBlank() && !fallbackMediaId.isNullOrBlank()) {
            MediaCacheKeys.originalFile(appContext.filesDir, ownerId, fallbackMediaId)
        } else {
            null
        }
        val bitmap = run {
            for (step in MediaFetchOrder.steps(uriString, hasStableMediaId = !fallbackMediaId.isNullOrBlank())) {
                if (!stillCurrent(session, ownerId)) return@withContext null
                val decoded = when (step) {
                    MediaFetchSource.DISK -> diskFile?.takeIf { it.isFile }?.let { decodeFile(it, maxDimPx) }
                    MediaFetchSource.LOCAL -> {
                        runCatching { Uri.parse(uriString) }.getOrNull()?.let { decode(appContext, it, maxDimPx) }
                    }
                    MediaFetchSource.CLOUDINARY -> {
                        runCatching { Uri.parse(uriString) }.getOrNull()?.let { uri ->
                            downloadHttp(uri)?.let { bytes ->
                                diskFile?.let { writeCache(it, bytes) }
                                decodeBytes(bytes, maxDimPx)
                            }
                        }
                    }
                    MediaFetchSource.DRIVE -> {
                        fallbackMediaId?.let { mediaId ->
                            sessionProvider?.let { provider ->
                                downloadDriveOriginal(mediaId, provider)?.let { bytes ->
                                    diskFile?.let { writeCache(it, bytes) }
                                    decodeBytes(bytes, maxDimPx)
                                }
                            }
                        }
                    }
                }
                if (decoded != null) return@run decoded
            }
            null
        } ?: return@withContext null

        if (!stillCurrent(session, ownerId)) return@withContext null
        cache.put(key, bitmap)
        bitmap
    }

    private fun stillCurrent(session: AccountSession.Snapshot, ownerId: String?): Boolean {
        if (ownerId.isNullOrBlank()) return true
        return AccountSession.isCurrent(session.userId, session.generation) &&
            AccountSession.userId == ownerId
    }

    private fun downloadHttp(uri: Uri): ByteArray? {
        return try {
            val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                useCaches = false
            }
            try {
                if (connection.responseCode !in 200..299) {
                    null
                } else if (connection.contentLengthLong > MAX_CACHE_FILE_BYTES) {
                    null
                } else {
                    connection.inputStream.use { it.readBounded(MAX_CACHE_FILE_BYTES) }
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return ByteArray(0)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private suspend fun downloadDriveOriginal(
        mediaId: String,
        sessionProvider: AuthenticatedSessionProvider
    ): ByteArray? = MediaDriveClient.fetchBytes(
        mediaId = mediaId,
        variant = MediaDriveClient.VARIANT_ORIGINAL,
        sessionProvider = sessionProvider,
        maxBytes = MAX_CACHE_FILE_BYTES,
        connectTimeoutMs = 10_000,
        readTimeoutMs = 120_000
    )

    private fun decodeBytes(bytes: ByteArray, maxDimPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDimension / sample > maxDimPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun decodeFile(file: File, maxDimPx: Int): Bitmap? =
        runCatching { decodeBytes(file.readBytes(), maxDimPx) }.getOrNull()

    private fun writeCache(file: File, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_CACHE_FILE_BYTES) return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) file.writeBytes(bytes)
            temporary.delete()
        }
    }

    private fun decode(context: Context, uri: Uri, maxDimPx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeWithImageDecoder(context, uri, maxDimPx)
        else decodeSampled(context, uri, maxDimPx)
    } catch (_: Exception) {
        null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxDimPx: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / sample > maxDimPx &&
                maxOf(info.size.width, info.size.height) / (sample * 2) >= maxDimPx) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        decodeSampled(context, uri, maxDimPx)
    }

    private fun decodeSampled(context: Context, uri: Uri, maxDimPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimPx) sample *= 2
        return open(context, uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) }
    }

    private fun open(context: Context, uri: Uri): InputStream? = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()

    private fun cacheKb(): Int {
        val max = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (max / 4).coerceIn(8192, 49_152)
    }
}
