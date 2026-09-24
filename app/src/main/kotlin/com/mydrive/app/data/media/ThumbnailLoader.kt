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
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.LogLevel
import com.mydrive.app.debug.MediaDiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

object ThumbnailLoader {

    /** The preview size album covers, grid cells and viewer neighbours ask for. */
    const val PREVIEW_SIZE_PX = 256

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val refreshScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshJobs = ConcurrentHashMap<String, Job>()

    fun evictMemory() {
        cache.evictAll()
        refreshScope.coroutineContext.cancelChildren()
        refreshJobs.clear()
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
        userId: String? = AccountSession.userId,
        forceRefresh: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank() && previewUri.isNullOrBlank() && fallbackMediaId.isNullOrBlank()) {
            return@withContext null
        }
        val session = AccountSession.snapshot()
        val ownerId = userId ?: session.userId
        val preview = previewUri?.trim().orEmpty()
        val remote = MediaCacheKeys.isRemoteUri(uriString)

        val attempt = MediaDiagnosticLogger.attempt(
            variant = MediaDiagnosticLogger.Variant.THUMBNAIL,
            mediaId = fallbackMediaId,
            localMediaId = null,
            mimeType = null,
            fileName = null
        )
        val traceVerbose = !forceRefresh && MediaDiagnosticLogger.shouldTraceVerbose(attempt)
        fun trace(
            level: LogLevel,
            event: String,
            message: String,
            metadata: Map<String, String?> = emptyMap()
        ) {
            // Failures always trace; successes only inside the verbose budget so
            // fast grid scrolling cannot flood the Developer Log.
            if (traceVerbose || event.endsWith("_ERROR") || event.endsWith("_CANCELLED")) {
                MediaDiagnosticLogger.log(level, LogCategory.THUMBNAIL, event, message, metadata)
            }
        }

        // A cloud candidate must never be fetched for an unauthenticated (or
        // previous-account) session, whichever URL carries it.
        if (remote || MediaCacheKeys.isRemoteUri(preview)) {
            val sessionSnapshotUsable = ownerId != null
            MediaDiagnosticLogger.cloudAuthState(
                sessionAvailable = sessionSnapshotUsable,
                userIdPresent = !session.userId.isNullOrBlank(),
                tokenAvailable = sessionSnapshotUsable
            )
            if (!sessionSnapshotUsable) {
                MediaDiagnosticLogger.cloudAuthRace(
                    mediaId = fallbackMediaId,
                    localMediaId = null,
                    reason = "remote thumbnail candidate with no signed-in owner"
                )
            }
        }
        if ((remote || MediaCacheKeys.isRemoteUri(preview)) && ownerId.isNullOrBlank()) {
            MediaDiagnosticLogger.error(
                attempt,
                stage = "AUTH",
                reason = "REMOTE_BUT_NO_SESSION",
                detail = "remote URI present but no signed-in owner id"
            )
            MediaDiagnosticLogger.summary(attempt)
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
        if (key == "blocked-remote") {
            MediaDiagnosticLogger.error(attempt, "AVAILABILITY", "REMOTE_CACHE_KEY_BLOCKED", "no owner id for a remote URI")
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }
        trace(
            LogLevel.INFO,
            "THUMB_START",
            "thumbnail load started",
            mapOf("uri" to MediaDiagnosticLogger.safeUri(uriString.ifBlank { preview }), "size_px" to sizePx.toString())
        )
        if (!forceRefresh) {
            val memoryHit = cache.get(key)
            MediaDiagnosticLogger.cacheLookup(
                attempt, "MEMORY", key, if (memoryHit != null) "HIT" else "MISS"
            )
            if (memoryHit != null) {
                attempt.cacheResult = "MEMORY_HIT"
                attempt.selectedSource = "CACHE"
                attempt.finalResult = "SUCCESS"
                MediaDiagnosticLogger.summary(attempt)
                scheduleRefreshIfStale(context, ownerId, fallbackMediaId, sizePx, key, uriString, preview, sessionProvider)
                return@withContext memoryHit
            }
        }
        if (!stillCurrent(session, ownerId)) {
            MediaDiagnosticLogger.cancelled(attempt, "SESSION_NO_LONGER_CURRENT")
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }
        val appContext = context.applicationContext
        var fromDisk = false
        val cloudCandidate = if (remote) uriString else preview
        val bitmap = run {
            for (step in MediaFetchOrder.steps(
                uriString,
                hasStableMediaId = !fallbackMediaId.isNullOrBlank(),
                previewUri = preview
            ).let { steps -> if (forceRefresh) steps.filter { it != MediaFetchSource.DISK } else steps }) {
                if (!stillCurrent(session, ownerId)) {
                    MediaDiagnosticLogger.cancelled(attempt, "SESSION_NO_LONGER_CURRENT")
                    return@withContext null
                }
                val decoded = when (step) {
                    MediaFetchSource.DISK -> {
                        val disk = if (!ownerId.isNullOrBlank() && !fallbackMediaId.isNullOrBlank()) {
                            readDisk(appContext, ownerId, fallbackMediaId, sizePx)
                        } else {
                            null
                        }
                        trace(
                            LogLevel.INFO,
                            "THUMB_RESOLVE",
                            "resolve step=DISK",
                            mapOf("cache_key" to key, "result" to if (disk != null) "HIT" else "MISS")
                        )
                        attempt.cacheResult = if (disk != null) "DISK_HIT" else attempt.cacheResult
                        disk
                    }
                    MediaFetchSource.LOCAL -> {
                        val localUri = runCatching { Uri.parse(uriString) }.getOrNull()
                        val startedAt = System.currentTimeMillis()
                        val bitmapLocal = localUri?.let { decode(appContext, it, sizePx) }
                        trace(
                            LogLevel.INFO,
                            if (bitmapLocal != null) "LOCAL_THUMB_RESULT" else "LOCAL_THUMB_ERROR",
                            if (bitmapLocal != null) {
                                "MediaStore thumbnail loaded"
                            } else {
                                "MediaStore thumbnail failed uri=${MediaDiagnosticLogger.safeUri(uriString)}"
                            },
                            mapOf(
                                "content_uri" to MediaDiagnosticLogger.safeUri(uriString),
                                "result" to if (bitmapLocal != null) "SUCCESS" else "FAILURE",
                                "elapsed_ms" to (System.currentTimeMillis() - startedAt).toString()
                            )
                        )
                        bitmapLocal
                    }
                    MediaFetchSource.CLOUDINARY -> {
                        // A stored delivery URL is the full-size original; ask
                        // Cloudinary for a thumbnail-sized derivative instead of
                        // downloading the original to show a preview.
                        val previewUrl = CloudinaryPreview.previewUrl(cloudCandidate, sizePx)
                        trace(
                            LogLevel.INFO,
                            "THUMB_RESOLVE",
                            "resolve step=CLOUDINARY",
                            mapOf(
                                "url_present" to (previewUrl != null).toString(),
                                "result" to if (previewUrl != null) "SELECTED" else "SKIPPED"
                            )
                        )
                        previewUrl?.let { decodeHttp(attempt, "CLOUDINARY", Uri.parse(it), sizePx) }
                    }
                    MediaFetchSource.DRIVE -> {
                        val driveResult = fallbackMediaId?.let { mediaId ->
                            sessionProvider?.let { provider ->
                                decodeDriveThumbnail(attempt, mediaId, sizePx, provider)
                            }
                        }
                        trace(
                            LogLevel.INFO,
                            "THUMB_RESOLVE",
                            "resolve step=MEDIA_DRIVE",
                            mapOf(
                                "result" to if (driveResult != null) "SELECTED" else
                                    if (fallbackMediaId.isNullOrBlank() || sessionProvider == null) "SKIPPED" else "FAILED"
                            )
                        )
                        driveResult
                    }
                }
                if (decoded != null) {
                    fromDisk = step == MediaFetchSource.DISK
                    attempt.selectedSource = step.name
                    return@run decoded
                }
            }
            null
        } ?: run {
            attempt.failureStage = "RESOLVE"
            attempt.failureReason = "RESOLVER_RETURNED_NULL"
            trace(
                LogLevel.WARNING,
                "THUMB_RESOLVE_RESULT",
                "resolver returned null (all sources exhausted)",
                mapOf("result" to "FAILURE", "reason" to "RESOLVER_RETURNED_NULL")
            )
            MediaDiagnosticLogger.error(
                attempt,
                stage = "RESOLVE",
                reason = "RESOLVER_RETURNED_NULL",
                detail = "all sources returned null"
            )
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }
        if (!stillCurrent(session, ownerId)) {
            MediaDiagnosticLogger.cancelled(attempt, "SESSION_NO_LONGER_CURRENT")
            MediaDiagnosticLogger.summary(attempt)
            return@withContext null
        }
        cache.put(key, bitmap)
        if (!fromDisk && !ownerId.isNullOrBlank() && !fallbackMediaId.isNullOrBlank()) {
            writeDisk(appContext, ownerId, fallbackMediaId, sizePx, bitmap)
            MediaCacheFreshness.markThumbnailFresh(appContext.filesDir, ownerId, fallbackMediaId, sizePx)
        } else if (fromDisk) {
            scheduleRefreshIfStale(context, ownerId, fallbackMediaId, sizePx, key, uriString, preview, sessionProvider)
        }
        trace(
            LogLevel.INFO,
            "THUMB_RESOLVE_RESULT",
            "resolver success source=${attempt.selectedSource}",
            mapOf(
                "result" to "SUCCESS",
                "source" to attempt.selectedSource,
                "width" to bitmap.width.toString(),
                "height" to bitmap.height.toString()
            )
        )
        attempt.finalResult = "SUCCESS"
        MediaDiagnosticLogger.summary(attempt)
        bitmap
    }

    private fun scheduleRefreshIfStale(
        context: Context,
        ownerId: String?,
        mediaId: String?,
        sizePx: Int,
        key: String,
        uriString: String,
        previewUri: String,
        sessionProvider: AuthenticatedSessionProvider?
    ) {
        if (ownerId.isNullOrBlank() || mediaId.isNullOrBlank() || !isOnline(context)) return
        if (!MediaCacheFreshness.isThumbnailStale(context.filesDir, ownerId, mediaId, sizePx)) return
        val existing = refreshJobs[key]
        if (existing != null) {
            MediaDiagnosticLogger.deduplicated(mediaId, null, MediaDiagnosticLogger.Variant.THUMBNAIL, key)
            return
        }
        val job = refreshScope.launch {
            try {
                load(
                    context = context,
                    uriString = uriString,
                    sizePx = sizePx,
                    fallbackMediaId = mediaId,
                    previewUri = previewUri,
                    sessionProvider = sessionProvider,
                    userId = ownerId,
                    forceRefresh = true
                )
            } finally {
                refreshJobs.remove(key)
            }
        }
        refreshJobs.putIfAbsent(key, job)?.let { replaced ->
            replaced.cancel()
            MediaDiagnosticLogger.replaced(mediaId, null, MediaDiagnosticLogger.Variant.THUMBNAIL)
        }
    }

    private fun isOnline(context: Context): Boolean {
        val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return true
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun stillCurrent(session: AccountSession.Snapshot, ownerId: String?): Boolean {
        if (ownerId.isNullOrBlank()) return true
        return AccountSession.isCurrent(session.userId, session.generation) &&
            AccountSession.userId == ownerId
    }

    private fun decodeHttp(attempt: MediaDiagnosticLogger.Attempt, source: String, uri: Uri, sizePx: Int): Bitmap? {
        val startedAt = System.currentTimeMillis()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val first = openHttp(attempt, source, uri) ?: return null
        try {
            first.inputStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (error: Exception) {
            MediaDiagnosticLogger.decodeError(
                attempt, source, first.contentType, error.javaClass.simpleName, error.message
            )
            return null
        } finally {
            first.disconnect()
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            MediaDiagnosticLogger.decodeError(
                attempt, source, first.contentType, "EmptyDecodeBounds", "decoded bounds ${bounds.outWidth}x${bounds.outHeight}"
            )
            return null
        }

        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > sizePx * 2) sample *= 2
        val second = openHttp(attempt, source, uri) ?: return null
        val decoded: Bitmap? = try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decodedBitmap = second.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
            if (decodedBitmap == null) {
                MediaDiagnosticLogger.decodeError(attempt, source, second.contentType, "DecodeStreamNull", "BitmapFactory returned null")
            }
            decodedBitmap
        } catch (error: Exception) {
            MediaDiagnosticLogger.decodeError(attempt, source, second.contentType, error.javaClass.simpleName, error.message)
            null
        } finally {
            second.disconnect()
        }
        if (decoded != null) {
            attempt.selectedSource = source
            MediaDiagnosticLogger.success(
                attempt, source, decoded.width, decoded.height, System.currentTimeMillis() - startedAt
            )
        }
        return decoded
    }

    private fun openHttp(attempt: MediaDiagnosticLogger.Attempt, source: String, uri: Uri): HttpURLConnection? {
        val startedAt = System.currentTimeMillis()
        MediaDiagnosticLogger.httpStart(
            attempt, source, uri.host, uri.encodedPath?.take(80)
        )
        try {
            (java.net.URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                useCaches = true
                requestMethod = "GET"
                setRequestProperty("Accept", "image/*")
                connect()
                if (responseCode !in 200..299) {
                    MediaDiagnosticLogger.httpResponse(
                        attempt, source, responseCode, contentType, contentLengthLong,
                        System.currentTimeMillis() - startedAt
                    )
                    disconnect()
                    return null
                }
                MediaDiagnosticLogger.httpResponse(
                    attempt, source, responseCode, contentType, contentLengthLong,
                    System.currentTimeMillis() - startedAt
                )
                return this
            }
        } catch (error: Exception) {
            MediaDiagnosticLogger.httpError(
                attempt, source, error.javaClass.simpleName, error.message, System.currentTimeMillis() - startedAt
            )
            return null
        }
    }

    private suspend fun decodeDriveThumbnail(
        attempt: MediaDiagnosticLogger.Attempt,
        mediaId: String,
        sizePx: Int,
        sessionProvider: AuthenticatedSessionProvider
    ): Bitmap? {
        val startedAt = System.currentTimeMillis()
        val bytes = MediaDriveClient.fetchBytes(
            attempt = attempt,
            mediaId = mediaId,
            variant = MediaDriveClient.VARIANT_THUMB,
            sessionProvider = sessionProvider,
            maxBytes = MAX_THUMBNAIL_BYTES.toLong(),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 15_000
        ) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            MediaDiagnosticLogger.decodeError(
                attempt, "MEDIA_DRIVE", null, "EmptyDecodeBounds", "Drive thumb decoded bounds ${bounds.outWidth}x${bounds.outHeight}"
            )
            return null
        }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > sizePx * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
        if (decoded != null) {
            MediaDiagnosticLogger.success(
                attempt, "MEDIA_DRIVE", decoded.width, decoded.height, System.currentTimeMillis() - startedAt
            )
        } else {
            MediaDiagnosticLogger.decodeError(attempt, "MEDIA_DRIVE", null, "DecodeByteArrayNull", "BitmapFactory returned null for Drive thumb")
        }
        return decoded
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
