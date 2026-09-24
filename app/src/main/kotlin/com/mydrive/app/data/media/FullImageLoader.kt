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
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.LogLevel
import com.mydrive.app.debug.MediaDiagnosticLogger

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

    suspend fun load(
        context: Context,
        uriString: String,
        maxDimPx: Int = 2048,
        fallbackMediaId: String? = null,
        previewUri: String? = null,
        sessionProvider: AuthenticatedSessionProvider? = null,
        userId: String? = AccountSession.userId
    ): Bitmap? = withContext(Dispatchers.IO) {
        val preview = previewUri?.trim().orEmpty()
        if (uriString.isBlank() && preview.isBlank() && fallbackMediaId.isNullOrBlank()) return@withContext null
        val session = AccountSession.snapshot()
        val ownerId = userId ?: session.userId
        val remote = MediaCacheKeys.isRemoteUri(uriString) || MediaCacheKeys.isRemoteUri(preview)

        val attempt = MediaDiagnosticLogger.attempt(
            variant = MediaDiagnosticLogger.Variant.ORIGINAL,
            mediaId = fallbackMediaId,
            localMediaId = null,
            mimeType = null,
            fileName = null
        )
        fun trace(event: String, message: String, metadata: Map<String, String?> = emptyMap()) {
            MediaDiagnosticLogger.log(LogLevel.INFO, LogCategory.ORIGINAL, event, message, metadata)
        }
        trace(
            "ORIGINAL_START",
            "original load started",
            mapOf(
                "uri" to MediaDiagnosticLogger.safeUri(uriString.ifBlank { preview }),
                "max_dim_px" to maxDimPx.toString()
            )
        )
        if (remote && ownerId.isNullOrBlank()) {
            MediaDiagnosticLogger.error(attempt, "AUTH", "REMOTE_BUT_NO_SESSION", "remote original requested without a session")
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }
        val key = MediaCacheKeys.memoryKey(
            userId = ownerId,
            mediaId = fallbackMediaId,
            uri = uriString.ifBlank { preview },
            variant = MediaCacheKeys.VARIANT_ORIGINAL,
            sizePx = maxDimPx
        )
        if (key == "blocked-remote") {
            MediaDiagnosticLogger.error(attempt, "AVAILABILITY", "REMOTE_CACHE_KEY_BLOCKED", "no owner id for a remote URI")
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }
        cache.get(key)?.let {
            MediaDiagnosticLogger.cacheLookup(attempt, "MEMORY", key, "HIT")
            attempt.cacheResult = "MEMORY_HIT"
            attempt.selectedSource = "CACHE"
            attempt.finalResult = "SUCCESS"
            trace("ORIGINAL_RESOLVE", "resolve step=MEMORY_CACHE result=HIT", mapOf("cache_key" to key))
            MediaDiagnosticLogger.summary(attempt)
            return@withContext it
        }
        MediaDiagnosticLogger.cacheLookup(attempt, "MEMORY", key, "MISS")
        trace("ORIGINAL_RESOLVE", "resolve step=MEMORY_CACHE result=MISS", mapOf("cache_key" to key))

        val appContext = context.applicationContext
        val diskFile = if (!ownerId.isNullOrBlank() && !fallbackMediaId.isNullOrBlank()) {
            MediaCacheKeys.originalFile(appContext.filesDir, ownerId, fallbackMediaId)
        } else {
            null
        }
        val cloudCandidate = if (MediaCacheKeys.isRemoteUri(uriString)) uriString else preview
        val bitmap = run {
            for (step in MediaFetchOrder.steps(
                uriString,
                hasStableMediaId = !fallbackMediaId.isNullOrBlank(),
                previewUri = preview
            )) {
                if (!stillCurrent(session, ownerId)) {
                    MediaDiagnosticLogger.cancelled(attempt, "SESSION_NO_LONGER_CURRENT")
                    return@withContext null
                }
                val decoded = when (step) {
                    MediaFetchSource.DISK -> {
                        val disk = diskFile?.let { readDisk(it, maxDimPx) }
                        trace(
                            "ORIGINAL_RESOLVE",
                            "resolve step=DISK_CACHE result=${if (disk != null) "HIT" else "MISS"}",
                            mapOf(
                                "cache_key" to (diskFile?.name ?: ""),
                                "result" to if (disk != null) "HIT" else "MISS"
                            )
                        )
                        if (disk != null) attempt.cacheResult = "DISK_HIT"
                        disk
                    }
                    MediaFetchSource.LOCAL -> {
                        val startedAt = System.currentTimeMillis()
                        val localUri = runCatching { Uri.parse(uriString) }.getOrNull()
                        val decodedLocal = localUri?.let { decode(appContext, it, maxDimPx) }
                        trace(
                            "ORIGINAL_RESOLVE",
                            "resolve step=LOCAL_MEDIASTORE result=${if (decodedLocal != null) "AVAILABLE" else "MISSING"}",
                            mapOf(
                                "content_uri" to MediaDiagnosticLogger.safeUri(uriString),
                                "result" to if (decodedLocal != null) "AVAILABLE" else "MISSING",
                                "elapsed_ms" to (System.currentTimeMillis() - startedAt).toString()
                            )
                        )
                        decodedLocal
                    }
                    MediaFetchSource.CLOUDINARY -> {
                        val candidate = cloudCandidate.takeIf { MediaCacheKeys.isRemoteUri(it) }
                        trace(
                            "ORIGINAL_RESOLVE",
                            "resolve step=CLOUDINARY result=${if (candidate != null) "SELECTED" else "SKIPPED"}",
                            mapOf("url_present" to (candidate != null).toString())
                        )
                        candidate
                            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            ?.let { uri ->
                                downloadHttp(attempt, uri)?.let { bytes ->
                                    diskFile?.let { writeCache(appContext, it, bytes) }
                                    decodeBytes(attempt, "CLOUDINARY", null, bytes, maxDimPx)
                                }
                            }
                    }
                    MediaFetchSource.DRIVE -> {
                        val canDrive = !fallbackMediaId.isNullOrBlank() && sessionProvider != null
                        trace(
                            "ORIGINAL_RESOLVE",
                            "resolve step=MEDIA_DRIVE result=${if (canDrive) "SELECTED" else "SKIPPED"}",
                            emptyMap()
                        )
                        if (canDrive) {
                            fallbackMediaId?.let { mediaId ->
                                sessionProvider?.let { provider ->
                                    downloadDriveOriginal(attempt, mediaId, provider)?.let { bytes ->
                                        diskFile?.let { writeCache(appContext, it, bytes) }
                                        decodeBytes(attempt, "MEDIA_DRIVE", null, bytes, maxDimPx)
                                    }
                                }
                            }
                        } else {
                            null
                        }
                    }
                }
                if (decoded != null) {
                    attempt.selectedSource = step.name
                    return@run decoded
                }
            }
            null
        } ?: run {
            MediaDiagnosticLogger.error(attempt, "RESOLVE", "RESOLVER_RETURNED_NULL", "all sources returned null for the original")
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }

        if (!stillCurrent(session, ownerId)) {
            MediaDiagnosticLogger.cancelled(attempt, "SESSION_NO_LONGER_CURRENT")
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }
        cache.put(key, bitmap)
        attempt.finalResult = "SUCCESS"
        MediaDiagnosticLogger.summary(attempt)
        bitmap
    }

    private fun stillCurrent(session: AccountSession.Snapshot, ownerId: String?): Boolean {
        if (ownerId.isNullOrBlank()) return true
        return AccountSession.isCurrent(session.userId, session.generation) &&
            AccountSession.userId == ownerId
    }

    private fun downloadHttp(attempt: MediaDiagnosticLogger.Attempt, uri: Uri): ByteArray? {
        val startedAt = System.currentTimeMillis()
        MediaDiagnosticLogger.httpStart(attempt, "CLOUDINARY", uri.host, uri.encodedPath?.take(80))
        try {
            val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                useCaches = false
            }
            try {
                val status = connection.responseCode
                MediaDiagnosticLogger.httpResponse(
                    attempt, "CLOUDINARY", status, connection.contentType,
                    connection.contentLengthLong.takeIf { it >= 0 },
                    System.currentTimeMillis() - startedAt
                )
                if (status !in 200..299) {
                    null
                } else if (connection.contentLengthLong > MAX_CACHE_FILE_BYTES) {
                    null
                } else {
                    connection.inputStream.use { it.readBounded(MAX_CACHE_FILE_BYTES) }
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            MediaDiagnosticLogger.httpError(
                attempt, "CLOUDINARY", error.javaClass.simpleName, error.message,
                System.currentTimeMillis() - startedAt
            )
            return null
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
        attempt: MediaDiagnosticLogger.Attempt,
        mediaId: String,
        sessionProvider: AuthenticatedSessionProvider
    ): ByteArray? = MediaDriveClient.fetchBytes(
        attempt = attempt,
        mediaId = mediaId,
        variant = MediaDriveClient.VARIANT_ORIGINAL,
        sessionProvider = sessionProvider,
        maxBytes = MAX_CACHE_FILE_BYTES,
        connectTimeoutMs = 10_000,
        readTimeoutMs = 120_000
    )

    private fun decodeBytes(attempt: MediaDiagnosticLogger.Attempt?, source: String, contentType: String?, bytes: ByteArray, maxDimPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            attempt?.let {
                MediaDiagnosticLogger.decodeError(
                    it, source, contentType, "EmptyDecodeBounds",
                    "bounds ${bounds.outWidth}x${bounds.outHeight}"
                )
            }
            return null
        }
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDimension / sample > maxDimPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
        if (decoded == null) {
            attempt?.let {
                MediaDiagnosticLogger.decodeError(it, source, contentType, "DecodeByteArrayNull", "BitmapFactory returned null")
            }
            return null
        }
        attempt?.let {
            MediaDiagnosticLogger.success(it, source, decoded.width, decoded.height, 0L)
        }
        return decoded
    }

    private fun readDisk(file: File, maxDimPx: Int): Bitmap? {
        if (!MediaDiskCache.isComplete(file)) {
            MediaDiskCache.discardInvalid(file)
            return null
        }
        val bitmap = MediaDiskCache.pinned(file) {
            runCatching { decodeBytes(null, "DISK", null, file.readBytes(), maxDimPx) }.getOrNull()
        } ?: return null
        MediaDiskCache.touch(file)
        return bitmap
    }

    private fun writeCache(context: Context, file: File, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_CACHE_FILE_BYTES) return
        if (MediaDiskCache.write(file, bytes)) MediaDiskCache.scheduleTrim(context)
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
