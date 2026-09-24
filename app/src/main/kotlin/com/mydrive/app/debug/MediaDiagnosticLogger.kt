package com.mydrive.app.debug

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Point-to-point diagnostic tracing for media loading, built on the existing
 * [DeveloperLogger] pipeline — this adds no second logging framework.
 *
 * Every event uses one structured `event` tag (THUMB_START, THUMB_HTTP_START,
 * MEDIA_LOAD_SUMMARY, …) and carries a short correlation id plus the safe media
 * identity (mediaId / localMediaId) in metadata, so a whole attempt can be
 * followed in the Developer Console by filtering on the id or the event tag.
 *
 * Flood control: a normal successful load logs only the compact
 * MEDIA_LOAD_SUMMARY. The verbose per-step trace (THUMB_RESOLVE, THUMB_HTTP_*)
 * is emitted for the first [TRACE_BUDGET] load attempts per process and always
 * for failures. Scrolling a large grid therefore costs one log line per item,
 * not dozens.
 *
 * Nothing logged here contains tokens, signed URLs, image bytes or full user
 * identities; URLs are reduced to host + redacted path and user ids are masked.
 */
object MediaDiagnosticLogger {

    const val TRACE_BUDGET = 40

    private val budget = AtomicLong(TRACE_BUDGET.toLong())
    private val requestIdCounter = AtomicLong(0L)
    private val lastFailureByMedia = ConcurrentHashMap<String, String>()

    fun newRequestId(variant: String): String {
        val prefix = if (variant == Variant.ORIGINAL.name) "OR" else "TH"
        val suffix = java.lang.Long.toHexString(requestIdCounter.incrementAndGet()).uppercase()
        return "$prefix-${suffix.takeLast(4)}"
    }

    enum class Variant { THUMBNAIL, ORIGINAL }

    data class Attempt(
        val variant: Variant,
        val requestId: String,
        val mediaId: String?,
        val localMediaId: String?,
        val mimeType: String?,
        val fileName: String?,
        val startedAtMs: Long = System.currentTimeMillis(),
        // Availability inputs, filled by the caller when known.
        var localAvailable: Boolean? = null,
        var cloudAvailable: Boolean? = null,
        var selectedSource: String = "NONE",
        var cacheResult: String = "MISS",
        var networkResult: String = "NOT_ATTEMPTED",
        var decodeResult: String = "NOT_ATTEMPTED",
        var finalResult: String = "",
        var failureStage: String = "NONE",
        var failureReason: String = "NONE"
    )

    fun attempt(
        variant: Variant,
        mediaId: String?,
        localMediaId: String? = null,
        mimeType: String? = null,
        fileName: String? = null
    ): Attempt = Attempt(
        variant = variant,
        requestId = newRequestId(if (variant == Variant.ORIGINAL) "ORIGINAL" else "THUMB"),
        mediaId = mediaId,
        localMediaId = localMediaId,
        mimeType = mimeType,
        fileName = fileName?.take(64)
    )

    fun shouldTraceVerbose(attempt: Attempt): Boolean {
        if (budget.getAndDecrement() > 0) return true
        return false
    }

    fun baseMetadata(attempt: Attempt): Map<String, String?> = mapOf(
        "request_id" to attempt.requestId,
        "variant" to attempt.variant.name,
        "media_id" to attempt.mediaId,
        "local_media_id" to attempt.localMediaId,
        "mime_type" to attempt.mimeType,
        "file_name" to attempt.fileName
    )

    fun availabilityStart(
        mediaId: String?,
        localMediaId: String?,
        status: String?,
        storageProvider: String?,
        hasStorageUrl: Boolean,
        hasThumbnailUrl: Boolean,
        storageAssetIdPresent: Boolean,
        driveArchivedAtPresent: Boolean,
        deletedAtPresent: Boolean,
        userHiddenAtPresent: Boolean,
        localFileExists: Boolean?,
        localUriPresent: Boolean
    ) {
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "MEDIA_AVAILABILITY_START",
            message = "Availability decision started",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "status" to status,
                "storage_provider" to storageProvider,
                "has_storage_url" to hasStorageUrl.toString(),
                "has_thumbnail_url" to hasThumbnailUrl.toString(),
                "storage_asset_id_present" to storageAssetIdPresent.toString(),
                "drive_archived_at_present" to driveArchivedAtPresent.toString(),
                "deleted_at_present" to deletedAtPresent.toString(),
                "user_hidden_at_present" to userHiddenAtPresent.toString(),
                "local_file_exists" to localFileExists?.toString(),
                "local_uri_present" to localUriPresent.toString()
            )
        )
    }

    fun availabilityResult(
        attempt: Attempt,
        available: Boolean,
        reason: String,
        selectedSource: String
    ) {
        attempt.selectedSource = selectedSource
        if (!available) {
            attempt.failureStage = "AVAILABILITY"
            attempt.failureReason = reason
        }
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "MEDIA_AVAILABILITY_RESULT",
            message = if (available) "Media considered available" else "Media considered unavailable: $reason",
            localMediaId = attempt.localMediaId,
            metadata = baseMetadata(attempt) + mapOf(
                "available" to available.toString(),
                "reason" to reason,
                "selected_source" to selectedSource
            )
        )
    }

    fun cloudMetadata(
        mediaId: String?,
        localMediaId: String?,
        ownerIdPresent: Boolean,
        status: String?,
        storageProvider: String?,
        storageUrlPresent: Boolean,
        thumbnailUrlPresent: Boolean,
        storageAssetIdPresent: Boolean,
        driveArchivedAtPresent: Boolean,
        deletedAtPresent: Boolean,
        userHiddenAtPresent: Boolean,
        primaryCleaned: Boolean
    ) {
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "CLOUD_MEDIA_METADATA",
            message = "Cloud media metadata used by the resolver",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "owner_id_present" to ownerIdPresent.toString(),
                "status" to status,
                "storage_provider" to storageProvider,
                "storage_url_present" to storageUrlPresent.toString(),
                "thumbnail_url_present" to thumbnailUrlPresent.toString(),
                "storage_asset_id_present" to storageAssetIdPresent.toString(),
                "drive_archived_at_present" to driveArchivedAtPresent.toString(),
                "deleted_at_present" to deletedAtPresent.toString(),
                "user_hidden_at_present" to userHiddenAtPresent.toString(),
                "primary_cleaned" to primaryCleaned.toString()
            )
        )
    }

    fun cloudDriveCheck(
        mediaId: String?,
        localMediaId: String?,
        driveArchivedAtPresent: Boolean,
        storageUrlPresent: Boolean,
        thumbnailUrlPresent: Boolean
    ) {
        DeveloperLogger.info(
            category = LogCategory.DRIVE,
            event = "CLOUD_DRIVE_CHECK",
            message = "Drive-only candidate inspected",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "drive_archived_at_present" to driveArchivedAtPresent.toString(),
                "storage_url_present" to storageUrlPresent.toString(),
                "thumbnail_url_present" to thumbnailUrlPresent.toString()
            )
        )
    }

    fun resolve(attempt: Attempt, step: String, result: String, extra: Map<String, String?> = emptyMap()) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_RESOLVE" else "THUMB_RESOLVE"
        DeveloperLogger.info(
            category = LogCategory.CACHE,
            event = event,
            message = "resolve step=$step result=$result",
            localMediaId = attempt.localMediaId,
            metadata = baseMetadata(attempt) + mapOf("step" to step, "result" to result) + extra
        )
    }

    fun cacheLookup(attempt: Attempt, cacheType: String, key: String, result: String) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_CACHE_LOOKUP" else "CACHE_LOOKUP"
        DeveloperLogger.info(
            category = LogCategory.CACHE,
            event = event,
            message = "cache lookup $cacheType result=$result",
            localMediaId = attempt.localMediaId,
            metadata = baseMetadata(attempt) + mapOf(
                "cache_type" to cacheType,
                "key" to key,
                "result" to result
            )
        )
    }

    fun httpStart(attempt: Attempt, source: String, urlHost: String?, urlPathSafe: String?) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_HTTP_START" else "THUMB_HTTP_START"
        attempt.networkResult = "ATTEMPTED"
        log(
            LogLevel.INFO,
            LogCategory.NETWORK,
            event,
            "HTTP request started source=$source url_host=$urlHost",
            baseMetadata(attempt) + mapOf(
                "source" to source,
                "url_host" to urlHost,
                "url_path_safe" to urlPathSafe
            )
        )
    }

    fun httpResponse(
        attempt: Attempt,
        source: String,
        httpStatus: Int,
        contentType: String?,
        contentLength: Long?,
        elapsedMs: Long
    ) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_HTTP_RESULT" else "THUMB_HTTP_RESULT"
        attempt.networkResult = "HTTP_$httpStatus"
        DeveloperLogger.network(
            category = LogCategory.NETWORK,
            event = event,
            message = "HTTP $httpStatus source=$source",
            method = "GET",
            urlPath = "",
            status = httpStatus,
            durationMs = elapsedMs,
            localMediaId = attempt.localMediaId,
            extra = baseMetadata(attempt) + mapOf(
                "source" to source,
                "content_type" to contentType,
                "content_length" to contentLength?.toString()
            )
        )
    }

    fun httpError(attempt: Attempt, source: String, exceptionType: String, message: String?, elapsedMs: Long) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_HTTP_ERROR" else "THUMB_HTTP_ERROR"
        attempt.networkResult = "ERROR"
        attempt.failureStage = "NETWORK"
        attempt.failureReason = exceptionType
        DeveloperLogger.warn(
            category = LogCategory.NETWORK,
            event = event,
            message = "HTTP failure source=$source",
            localMediaId = attempt.localMediaId,
            throwable = null,
            metadata = baseMetadata(attempt) + mapOf(
                "source" to source,
                "exception_type" to exceptionType,
                "message" to (message?.take(200)),
                "elapsed_ms" to elapsedMs.toString()
            )
        )
    }

    fun decodeError(attempt: Attempt, source: String, contentType: String?, exceptionType: String, message: String?) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_DECODE_ERROR" else "THUMB_DECODE_ERROR"
        attempt.decodeResult = "FAILURE"
        attempt.failureStage = "DECODE"
        attempt.failureReason = exceptionType
        val category = if (attempt.variant == Variant.ORIGINAL) LogCategory.ORIGINAL else LogCategory.THUMBNAIL
        DeveloperLogger.warn(
            category = category,
            event = event,
            message = "decode failure source=$source",
            localMediaId = attempt.localMediaId,
            metadata = baseMetadata(attempt) + mapOf(
                "source" to source,
                "content_type" to contentType,
                "exception_type" to exceptionType,
                "message" to (message?.take(200))
            )
        )
    }

    fun success(attempt: Attempt, source: String, width: Int, height: Int, elapsedMs: Long) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_SUCCESS" else "THUMB_SUCCESS"
        attempt.selectedSource = source
        attempt.decodeResult = "SUCCESS"
        val category = if (attempt.variant == Variant.ORIGINAL) LogCategory.ORIGINAL else LogCategory.THUMBNAIL
        DeveloperLogger.info(
            category = category,
            event = event,
            message = "loaded source=$source ${width}x$height",
            localMediaId = attempt.localMediaId,
            durationMs = elapsedMs,
            metadata = baseMetadata(attempt) + mapOf(
                "source" to source,
                "width" to width.toString(),
                "height" to height.toString(),
                "elapsed_ms" to elapsedMs.toString()
            )
        )
    }

    fun error(attempt: Attempt, stage: String, reason: String, detail: String?) {
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_ERROR" else "THUMB_ERROR"
        attempt.failureStage = stage
        attempt.failureReason = reason
        val category = if (attempt.variant == Variant.ORIGINAL) LogCategory.ORIGINAL else LogCategory.THUMBNAIL
        DeveloperLogger.warn(
            category = category,
            event = event,
            message = "load failed stage=$stage reason=$reason${detail?.let { " detail=$it" } ?: ""}",
            localMediaId = attempt.localMediaId,
            metadata = baseMetadata(attempt) + mapOf(
                "stage" to stage,
                "reason" to reason,
                "detail" to detail?.take(300)
            )
        )
    }

    fun cancelled(attempt: Attempt, reason: String) {
        attempt.finalResult = "CANCELLED"
        val event = if (attempt.variant == Variant.ORIGINAL) "ORIGINAL_CANCELLED" else "THUMB_CANCELLED"
        DeveloperLogger.info(
            category = if (attempt.variant == Variant.ORIGINAL) LogCategory.ORIGINAL else LogCategory.THUMBNAIL,
            event = event,
            message = "load cancelled reason=$reason",
            localMediaId = attempt.localMediaId,
            metadata = baseMetadata(attempt) + mapOf("reason" to reason)
        )
    }

    fun deduplicated(mediaId: String?, localMediaId: String?, variant: Variant, key: String) {
        val event = if (variant == Variant.ORIGINAL) "ORIGINAL_DEDUPLICATED" else "THUMB_DEDUPLICATED"
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = event,
            message = "concurrent load deduplicated onto an in-flight request",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "cache_key" to key
            )
        )
    }

    fun replaced(mediaId: String?, localMediaId: String?, variant: Variant) {
        val event = if (variant == Variant.ORIGINAL) "ORIGINAL_REQUEST_REPLACED" else "THUMB_REQUEST_REPLACED"
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = event,
            message = "in-flight load superseded by a newer request",
            localMediaId = localMediaId,
            metadata = mapOf("media_id" to mediaId)
        )
    }

    fun unavailableUi(
        mediaId: String?,
        localMediaId: String?,
        variant: Variant,
        reason: String,
        availabilityState: String,
        resolverState: String,
        lastResolverSource: String,
        lastResolverError: String?
    ) {
        rememberFailure(mediaId ?: localMediaId ?: "?", reason)
        DeveloperLogger.warn(
            category = LogCategory.UI,
            event = "MEDIA_UNAVAILABLE_UI",
            message = "Media unavailable UI emitted: $reason",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "variant" to variant.name,
                "reason" to reason,
                "availability_state" to availabilityState,
                "resolver_state" to resolverState,
                "last_resolver_source" to lastResolverSource,
                "last_resolver_error" to (lastResolverError?.take(200))
            )
        )
    }

    fun cloudAuthState(sessionAvailable: Boolean, userIdPresent: Boolean, tokenAvailable: Boolean) {
        DeveloperLogger.info(
            category = LogCategory.AUTH,
            event = "CLOUD_AUTH_STATE",
            message = "cloud media auth state",
            metadata = mapOf(
                "session_available" to sessionAvailable.toString(),
                "user_id_present" to userIdPresent.toString(),
                "token_available" to tokenAvailable.toString()
            )
        )
    }

    fun cloudAuthRace(mediaId: String?, localMediaId: String?, reason: String) {
        DeveloperLogger.warn(
            category = LogCategory.AUTH,
            event = "CLOUD_AUTH_RACE",
            message = "cloud load attempted before the session was ready: $reason",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "reason" to reason
            )
        )
    }

    fun localThumbStart(mediaId: String?, localMediaId: String?, contentUri: String?, mimeType: String?) {
        DeveloperLogger.info(
            category = LogCategory.MEDIASTORE,
            event = "LOCAL_THUMB_START",
            message = "MediaStore thumbnail load started",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "content_uri" to safeUri(contentUri),
                "mime_type" to mimeType
            )
        )
    }

    fun localThumbResult(
        mediaId: String?,
        localMediaId: String?,
        success: Boolean,
        width: Int?,
        height: Int?,
        elapsedMs: Long,
        exceptionType: String? = null,
        message: String? = null
    ) {
        if (success) {
            DeveloperLogger.info(
                category = LogCategory.MEDIASTORE,
                event = "LOCAL_THUMB_RESULT",
                message = "MediaStore thumbnail loaded",
                localMediaId = localMediaId,
                durationMs = elapsedMs,
                metadata = mapOf(
                    "media_id" to mediaId,
                    "result" to "SUCCESS",
                    "width" to width?.toString(),
                    "height" to height?.toString(),
                    "elapsed_ms" to elapsedMs.toString()
                )
            )
        } else {
            DeveloperLogger.warn(
                category = LogCategory.MEDIASTORE,
                event = "LOCAL_THUMB_ERROR",
                message = "MediaStore thumbnail failed",
                localMediaId = localMediaId,
                metadata = mapOf(
                    "media_id" to mediaId,
                    "result" to "FAILURE",
                    "exception_type" to exceptionType,
                    "message" to (message?.take(200)),
                    "elapsed_ms" to elapsedMs.toString()
                )
            )
        }
    }

    fun localOriginalComparison(
        mediaId: String?,
        localMediaId: String?,
        thumbnailResult: String,
        originalResult: String
    ) {
        if (thumbnailResult == "FAILURE" && originalResult != "FAILURE") {
            rememberFailure(mediaId ?: localMediaId ?: "?", "LOCAL_THUMB_FAILED_BUT_ORIGINAL_OK")
        }
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "LOCAL_ORIGINAL_COMPARISON",
            message = "thumbnail=$thumbnailResult original=$originalResult",
            localMediaId = localMediaId,
            metadata = mapOf(
                "media_id" to mediaId,
                "thumbnail_result" to thumbnailResult,
                "original_result" to originalResult
            )
        )
    }

    fun summary(attempt: Attempt) {
        attempt.finalResult = when {
            attempt.finalResult.isNotEmpty() -> attempt.finalResult
            attempt.failureStage == "NONE" -> "SUCCESS"
            else -> "FAILURE"
        }
        val elapsedMs = System.currentTimeMillis() - attempt.startedAtMs
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "MEDIA_LOAD_SUMMARY",
            message = "load summary variant=${attempt.variant.name} final=${attempt.finalResult} " +
                "stage=${attempt.failureStage} reason=${attempt.failureReason}",
            localMediaId = attempt.localMediaId,
            durationMs = elapsedMs,
            metadata = baseMetadata(attempt) + mapOf(
                "local_available" to attempt.localAvailable?.toString(),
                "cloud_available" to attempt.cloudAvailable?.toString(),
                "selected_source" to attempt.selectedSource,
                "cache_result" to attempt.cacheResult,
                "network_result" to attempt.networkResult,
                "decode_result" to attempt.decodeResult,
                "final_result" to attempt.finalResult,
                "failure_stage" to attempt.failureStage,
                "failure_reason" to attempt.failureReason,
                "elapsed_ms" to elapsedMs.toString()
            )
        )
    }

    fun rememberFailure(mediaKey: String, reason: String) {
        lastFailureByMedia[mediaKey] = reason
    }

    fun lastFailure(mediaKey: String): String? = lastFailureByMedia[mediaKey]

    /**
     * Safe URI for logs: scheme + host + first path segment only, no query and
     * no full path (a MediaStore path segment is not sensitive, a signed URL
     * query would be).
     */
    fun safeUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        return try {
            val parsed = java.net.URI(uri)
            val host = parsed.host
            val path = parsed.path?.split('/')?.firstOrNull { it.isNotBlank() }
            buildString {
                append(parsed.scheme ?: "unknown")
                append("://")
                if (host != null) append(host)
                if (path != null) append("/…/", path.take(24))
            }
        } catch (_: Exception) {
            uri.take(24) + "…"
        }
    }

    /** Compact, safe logging helper used by the trace points below. */
    fun log(level: LogLevel, category: LogCategory, event: String, message: String, metadata: Map<String, String?>) {
        when (level) {
            LogLevel.ERROR, LogLevel.FATAL -> DeveloperLogger.error(category, event, message, metadata = metadata)
            LogLevel.WARNING -> DeveloperLogger.warn(category, event, message, metadata = metadata)
            LogLevel.VERBOSE, LogLevel.DEBUG -> DeveloperLogger.log(
                LogLevel.DEBUG, category, event, message, metadata = metadata
            )
            LogLevel.INFO -> DeveloperLogger.info(category, event, message, metadata = metadata)
        }
    }
}
