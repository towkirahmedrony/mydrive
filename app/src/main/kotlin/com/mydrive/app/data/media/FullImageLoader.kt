package com.mydrive.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.mydrive.app.BuildConfig
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.session.AccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val provider = sessionProvider ?: return@withContext uriString.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val bytes = downloadDriveOriginal(mediaId, provider) ?: return@withContext uriString.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (!stillCurrent(session, ownerId)) return@withContext null
        writeCache(file, bytes)
        Uri.fromFile(file)
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
        val bitmap = diskFile?.takeIf { it.isFile }?.let { decodeFile(it, maxDimPx) }
            ?: fallbackMediaId?.let { mediaId ->
                sessionProvider?.let { provider ->
                    downloadDriveOriginal(mediaId, provider)?.let { bytes ->
                        if (!stillCurrent(session, ownerId)) return@withContext null
                        diskFile?.let { writeCache(it, bytes) }
                        decodeBytes(bytes, maxDimPx)
                    }
                }
            }
            ?: run {
                if (uriString.isBlank()) return@run null
                val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                    ?: return@run null
                if (uri.scheme == "http" || uri.scheme == "https") {
                    decodeHttp(uri, maxDimPx)?.also { decoded ->
                        if (diskFile != null) runCatching { downloadHttp(uri)?.let { writeCache(diskFile, it) } }
                    }
                } else {
                    decode(appContext, uri, maxDimPx)
                }
            }
            ?: return@withContext null

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

    private fun decodeHttp(uri: Uri, maxDimPx: Int): Bitmap? =
        downloadHttp(uri)?.let { decodeBytes(it, maxDimPx) }

    private suspend fun downloadDriveOriginal(
        mediaId: String,
        sessionProvider: AuthenticatedSessionProvider
    ): ByteArray? {
        val accessToken = when (val prepared = sessionProvider.prepare(false)) {
            is PreparedAuth.Available -> prepared.accessToken
            else -> return null
        }
        val connection = try {
            (URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/media-drive")
                .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 120_000
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    outputStream.use { output ->
                        output.write(buildJsonObject {
                            put("media_id", mediaId)
                            put("variant", "original")
                        }.toString().toByteArray(Charsets.UTF_8))
                    }
                }
        } catch (_: Exception) {
            return null
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBounded(MAX_CACHE_FILE_BYTES) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
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
