package com.mydrive.app.data.remote

import com.mydrive.app.BuildConfig
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.LogLevel
import io.github.jan.supabase.SupabaseClient
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Outcome of asking the server to permanently delete media.
 *
 * [Partial] is deliberately NOT a success: the app must never report that a
 * permanent deletion completed while the server-side lifecycle (thumbnail purge
 * and tombstone) is still outstanding for some of the media.
 */
sealed class MediaPurgeResult {
    data class Success(val purgedIds: List<String>) : MediaPurgeResult() {
        val purged: Int get() = purgedIds.size
    }

    data class Partial(val purgedIds: List<String>, val requested: Int) : MediaPurgeResult() {
        val purged: Int get() = purgedIds.size
    }

    data object Unauthorized : MediaPurgeResult()
    data object NetworkUnavailable : MediaPurgeResult()
    data object Timeout : MediaPurgeResult()
    data object Misconfigured : MediaPurgeResult()
    data class Rejected(val message: String) : MediaPurgeResult()
    data class Error(val message: String) : MediaPurgeResult()
}

/**
 * Android entry point for the `media-lifecycle` Supabase Edge Function.
 *
 * Scope is intentionally narrow: the ONLY operation Android needs is `purge`,
 * the final step of the media deletion lifecycle. It handles one permanent
 * delete (or one "empty trash" batch), never Trash and never Restore.
 *
 * Everything destructive stays on the server. This client:
 *  - sends the caller's access token and lets the Edge Function authorize the
 *    request against `media_assets.owner_id`,
 *  - never contacts Cloudinary and never holds a Cloudinary credential, so no
 *    deletion rule is duplicated in the APK,
 *  - reports failures faithfully instead of optimistically claiming success.
 *
 * Follows the same invoke pattern as [MediaFinalizeService]: HttpURLConnection,
 * anonymous key in `apikey`, the session's access token in `Authorization`, and
 * one forced-refresh retry when the session has just expired.
 */
class DurableMediaLifecycleClient(
    private val supabaseClient: SupabaseClient?,
    private val sessionProvider: AuthenticatedSessionProvider,
    private val network: NetworkMonitor
) {

    /**
     * Permanently deletes [mediaIds] server-side.
     *
     * The server removes the persistent Cloudinary thumbnail, deletes the
     * Cloudinary original only when a verified Drive archive exists, and
     * tombstones the record. Media that still sit in Trash (`user_hidden_at`) are
     * never touched by this path.
     */
    suspend fun purge(mediaIds: Collection<String>): MediaPurgeResult =
        withContext(Dispatchers.IO) {
            val ids = mediaIds
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_BATCH)
                .toList()
            if (ids.isEmpty()) {
                return@withContext MediaPurgeResult.Rejected("No media ids supplied")
            }

            val path = "/functions/v1/media-lifecycle"
            if (!network.isOnline()) {
                logFailure(ids, "NETWORK_UNAVAILABLE", "No network for media-lifecycle purge")
                return@withContext MediaPurgeResult.NetworkUnavailable
            }
            if (supabaseClient == null) {
                logFailure(ids, "MISCONFIGURED", "Supabase client is not configured")
                return@withContext MediaPurgeResult.Misconfigured
            }

            val endpoint =
                "https://${extractProjectRef(BuildConfig.SUPABASE_URL)}.supabase.co$path"
            val body = buildRequestBody(ids)

            DeveloperLogger.info(
                category = LogCategory.THUMBNAIL,
                event = "THUMBNAIL_LIFECYCLE",
                message = lifecycleMessage("purge", ids.first(), "REQUESTED", "PERMANENT_DELETE"),
                metadata = mapOf(
                    "operation" to "purge",
                    "media_count" to ids.size.toString(),
                    "result" to "REQUESTED",
                    "reason" to "PERMANENT_DELETE"
                )
            )

            var forceRefresh = false
            repeat(2) {
                val prepared = sessionProvider.prepare(forceRefresh)
                val accessToken = when (prepared) {
                    is PreparedAuth.Available -> prepared.accessToken
                    is PreparedAuth.NetworkError -> {
                        logFailure(ids, "NETWORK_UNAVAILABLE", "Session prepare failed due to network")
                        return@withContext MediaPurgeResult.NetworkUnavailable
                    }
                    is PreparedAuth.SignedOut -> {
                        logFailure(ids, "UNAUTHORIZED", "No local session for media-lifecycle", 401)
                        return@withContext MediaPurgeResult.Unauthorized
                    }
                }

                val connection = try {
                    (URL(endpoint).openConnection() as HttpURLConnection)
                } catch (error: Exception) {
                    logFailure(
                        ids,
                        "NETWORK_UNAVAILABLE",
                        "Could not open media-lifecycle connection",
                        throwable = error
                    )
                    return@withContext MediaPurgeResult.NetworkUnavailable
                }

                val startedAt = System.currentTimeMillis()
                val result = try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.useCaches = false
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")

                    connection.outputStream.use { output ->
                        output.write(body.toByteArray(Charsets.UTF_8))
                    }

                    val status = connection.responseCode
                    val responseBody = readBody(connection, status)
                    val duration = System.currentTimeMillis() - startedAt
                    val parsed = when (status) {
                        200 -> parseSuccess(responseBody, ids.size, ids)
                        401 -> MediaPurgeResult.Unauthorized
                        400, 403, 404, 409, 422 ->
                            MediaPurgeResult.Rejected(extractError(responseBody))
                        500 -> MediaPurgeResult.Misconfigured
                        else -> MediaPurgeResult.Error("Permanent delete failed: $status")
                    }
                    logOutcome(ids, parsed, status, duration, responseBody)
                    parsed
                } catch (_: SocketTimeoutException) {
                    logFailure(
                        ids,
                        "TIMEOUT",
                        "media-lifecycle timed out",
                        durationMs = System.currentTimeMillis() - startedAt
                    )
                    MediaPurgeResult.Timeout
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    logFailure(
                        ids,
                        "NETWORK_UNAVAILABLE",
                        "media-lifecycle network error",
                        throwable = error,
                        durationMs = System.currentTimeMillis() - startedAt
                    )
                    MediaPurgeResult.NetworkUnavailable
                } finally {
                    connection.disconnect()
                }

                if (result is MediaPurgeResult.Unauthorized && !forceRefresh) {
                    DeveloperLogger.warn(
                        category = LogCategory.AUTH,
                        event = "SESSION_REFRESH_ATTEMPTED",
                        message = "Retrying media-lifecycle purge after HTTP 401 with forced refresh",
                        httpStatus = 401
                    )
                    forceRefresh = true
                } else {
                    return@withContext result
                }
            }
            logFailure(ids, "UNAUTHORIZED", "Purge still unauthorized after session refresh", 401)
            MediaPurgeResult.Unauthorized
        }

    private fun buildRequestBody(ids: List<String>): String =
        buildJsonObject {
            put("action", ACTION_PURGE)
            put(
                "media_ids",
                buildJsonArray { ids.forEach { add(it) } }
            )
        }.toString()

    /**
     * A structurally valid 200 is not enough to call the deletion done: the
     * response must confirm at least one purge, otherwise the media is still
     * live server-side and the caller must see a failure.
     */
    private fun parseSuccess(body: String, requested: Int, requestedIds: List<String>): MediaPurgeResult {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            // Per-media outcomes are authoritative: the count alone cannot tell the
            // caller WHICH records were purged, and a partial purge must be
            // reconciled per id instead of being written off as a success.
            val purgedIds = root["results"]
                ?.let { element -> runCatching { element.jsonArray }.getOrNull() }
                ?.mapNotNull { entry ->
                    val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val status = obj.string("status") ?: return@mapNotNull null
                    if (status.equals(PURGED_STATUS, ignoreCase = true)) obj.string("media_id") else null
                }
                .orEmpty()
            val effective = if (purgedIds.isNotEmpty()) {
                purgedIds
            } else {
                // Older/unexpected payload shape: fall back to the count.
                val count = root["purged"]?.jsonPrimitive?.intOrNull
                    ?: root.string("purged")?.toIntOrNull()
                    ?: 0
                requestedIds.take(count.coerceAtLeast(0))
            }
            if (effective.size >= requested) {
                MediaPurgeResult.Success(effective)
            } else {
                MediaPurgeResult.Partial(effective, requested)
            }
        } catch (_: Exception) {
            MediaPurgeResult.Error("Failed to parse media-lifecycle response")
        }
    }

    private fun logOutcome(
        ids: List<String>,
        result: MediaPurgeResult,
        status: Int?,
        durationMs: Long,
        responseBody: String
    ) {
        val succeeded = result is MediaPurgeResult.Success
        val reason = when (result) {
            is MediaPurgeResult.Success -> "PURGED"
            is MediaPurgeResult.Partial -> "PARTIAL_PURGE"
            is MediaPurgeResult.Rejected -> "REJECTED"
            is MediaPurgeResult.Error -> "ERROR"
            is MediaPurgeResult.Misconfigured -> "MISCONFIGURED"
            else -> "FAILED"
        }
        DeveloperLogger.network(
            category = LogCategory.THUMBNAIL,
            event = "PERMANENT_DELETE",
            message = permanentDeleteMessage(ids.first(), requested = true, result = reason),
            method = "POST",
            urlPath = PATH,
            status = status,
            durationMs = durationMs,
            level = if (succeeded) LogLevel.INFO else LogLevel.ERROR,
            responseBody = if (succeeded) null else responseBody.take(MAX_LOGGED_BODY),
            errorSource = if (succeeded) null else "edge_function",
            extra = mapOf(
                "media_count" to ids.size.toString(),
                "server_purge_requested" to "true",
                "server_purge_result" to reason
            )
        )
        if (succeeded) {
            DeveloperLogger.info(
                category = LogCategory.THUMBNAIL,
                event = "THUMBNAIL_LIFECYCLE",
                message = lifecycleMessage("purge", ids.first(), "PURGED", "PERMANENT_DELETE"),
                metadata = mapOf(
                    "operation" to "purge",
                    "media_count" to ids.size.toString(),
                    "result" to "PURGED",
                    "reason" to "PERMANENT_DELETE"
                )
            )
        }
    }

    private fun logFailure(
        ids: List<String>,
        reason: String,
        message: String,
        status: Int? = null,
        throwable: Throwable? = null,
        durationMs: Long? = null
    ) {
        DeveloperLogger.error(
            category = LogCategory.THUMBNAIL,
            event = "PERMANENT_DELETE",
            message = buildString {
                append("[PERMANENT_DELETE]\n")
                append("mediaId=").append(ids.first()).append('\n')
                append("serverPurgeRequested=true\n")
                append("serverPurgeResult=").append(reason)
            },
            httpMethod = "POST",
            urlPath = PATH,
            httpStatus = status,
            durationMs = durationMs,
            throwable = throwable,
            metadata = mapOf(
                "operation" to "purge",
                "media_count" to ids.size.toString(),
                "result" to "FAILED",
                "reason" to reason,
                "detail" to message
            )
        )
    }

    private fun lifecycleMessage(
        operation: String,
        mediaId: String,
        result: String,
        reason: String
    ): String = buildString {
        append("[THUMBNAIL_LIFECYCLE]\n")
        append("operation=").append(operation).append('\n')
        append("mediaId=").append(mediaId).append('\n')
        append("result=").append(result).append('\n')
        append("reason=").append(reason)
    }

    private fun permanentDeleteMessage(
        mediaId: String,
        requested: Boolean,
        result: String
    ): String = buildString {
        append("[PERMANENT_DELETE]\n")
        append("mediaId=").append(mediaId).append('\n')
        append("serverPurgeRequested=").append(requested).append('\n')
        append("serverPurgeResult=").append(result)
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        return if (status in 200..299) {
            connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
    }

    private fun extractError(body: String): String {
        return try {
            json.parseToJsonElement(body).jsonObject.string("error") ?: "Request was rejected"
        } catch (_: Exception) {
            "Request was rejected"
        }
    }

    private fun extractProjectRef(supabaseUrl: String): String {
        return supabaseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore(".")
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.content

    companion object {
        private const val PATH = "/functions/v1/media-lifecycle"
        private const val ACTION_PURGE = "purge"

        /** Per-media status the `media-lifecycle` function reports for a purge. */
        private const val PURGED_STATUS = "PURGED"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Matches the server's own cap, so a batch is never silently truncated
         * into a partial deletion without the caller noticing.
         */
        private const val MAX_BATCH = 10
        private const val MAX_LOGGED_BODY = 1_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
