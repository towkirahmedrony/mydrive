package com.mydrive.app.data.media

import com.mydrive.app.BuildConfig
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.LogLevel
import com.mydrive.app.debug.MediaDiagnosticLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal object MediaDriveClient {
    const val VARIANT_THUMB = "thumb"
    const val VARIANT_ORIGINAL = "original"

    /** An authenticated media-drive URL a media player can open on its own. */
    data class AuthorizedStream(val url: String, val headers: Map<String, String>)

    fun streamUrl(baseUrl: String, mediaId: String, variant: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/functions/v1/media-drive?media_id=$mediaId&variant=$variant"
    }

    /**
     * Builds a GET request the platform player can issue itself, so playback
     * is driven by byte ranges instead of a download into app memory. Only ids
     * travel in the URL; the session token stays in the request headers.
     */
    suspend fun authorizedStream(
        mediaId: String,
        variant: String,
        sessionProvider: AuthenticatedSessionProvider
    ): AuthorizedStream? {
        val accessToken = when (val prepared = sessionProvider.prepare(forceRefresh = false)) {
            is PreparedAuth.Available -> prepared.accessToken
            is PreparedAuth.SignedOut -> {
                MediaDiagnosticLogger.log(
                    LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                    "media-drive stream skipped: no session",
                    mapOf("media_id" to mediaId, "variant" to variant, "error_type" to "AUTHENTICATION_FAILURE")
                )
                return null
            }
            is PreparedAuth.NetworkError -> {
                MediaDiagnosticLogger.log(
                    LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                    "media-drive stream skipped: network error preparing session",
                    mapOf("media_id" to mediaId, "variant" to variant, "error_type" to "NETWORK_ERROR")
                )
                return null
            }
        }
        return AuthorizedStream(
            url = streamUrl(BuildConfig.SUPABASE_URL, mediaId, variant),
            headers = mapOf(
                "apikey" to BuildConfig.SUPABASE_ANON_KEY,
                "Authorization" to "Bearer $accessToken"
            )
        )
    }

    suspend fun fetchBytes(
        attempt: MediaDiagnosticLogger.Attempt? = null,
        mediaId: String,
        variant: String,
        sessionProvider: AuthenticatedSessionProvider,
        maxBytes: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): ByteArray? {
        if (attempt != null) {
            MediaDiagnosticLogger.log(
                LogLevel.INFO, LogCategory.DRIVE, "MEDIA_DRIVE_START",
                "media-drive request started variant=$variant",
                mapOf("media_id" to mediaId, "variant" to variant, "request_id" to attempt.requestId)
            )
        }
        val accessToken = when (val prepared = sessionProvider.prepare(forceRefresh = false)) {
            is PreparedAuth.Available -> prepared.accessToken
            is PreparedAuth.SignedOut -> {
                MediaDiagnosticLogger.log(
                    LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                    "media-drive skipped: authentication failure (no session)",
                    mapOf(
                        "media_id" to mediaId, "variant" to variant,
                        "error_type" to "AUTHENTICATION_FAILURE",
                        "request_id" to attempt?.requestId
                    )
                )
                return null
            }
            is PreparedAuth.NetworkError -> {
                MediaDiagnosticLogger.log(
                    LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                    "media-drive skipped: network error preparing session",
                    mapOf(
                        "media_id" to mediaId, "variant" to variant,
                        "error_type" to "NETWORK_ERROR",
                        "request_id" to attempt?.requestId
                    )
                )
                return null
            }
        }
        val startedAt = System.currentTimeMillis()
        val connection = try {
            (URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/media-drive")
                .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    outputStream.use { output ->
                        output.write(
                            buildJsonObject {
                                put("media_id", mediaId)
                                put("variant", variant)
                            }.toString().toByteArray(Charsets.UTF_8)
                        )
                    }
                }
        } catch (error: Exception) {
            if (attempt != null) {
                MediaDiagnosticLogger.httpError(
                    attempt, "MEDIA_DRIVE", error.javaClass.simpleName, error.message,
                    System.currentTimeMillis() - startedAt
                )
            }
            MediaDiagnosticLogger.log(
                LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                "media-drive request could not be sent",
                mapOf(
                    "media_id" to mediaId, "variant" to variant,
                    "error_type" to classify(error),
                    "exception_type" to error.javaClass.simpleName,
                    "request_id" to attempt?.requestId
                )
            )
            return null
        }
        return try {
            val status = connection.responseCode
            val contentType = connection.contentType
            val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
            if (attempt != null) {
                MediaDiagnosticLogger.httpResponse(
                    attempt, "MEDIA_DRIVE", status, contentType, contentLength,
                    System.currentTimeMillis() - startedAt
                )
            }
            if (status !in 200..299) {
                val errorType = classifyDriveStatus(status)
                MediaDiagnosticLogger.log(
                    LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                    "media-drive rejected the request http=$status",
                    mapOf(
                        "media_id" to mediaId, "variant" to variant,
                        "http_status" to status.toString(),
                        "error_type" to errorType,
                        "request_id" to attempt?.requestId
                    )
                )
                null
            } else {
                val bytes = connection.inputStream.use { it.readBounded(maxBytes) }.takeIf { it.isNotEmpty() }
                if (bytes == null && attempt != null) {
                    MediaDiagnosticLogger.log(
                        LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                        "media-drive answered 2xx but the body was empty or exceeded the byte budget",
                        mapOf(
                            "media_id" to mediaId, "variant" to variant,
                            "error_type" to "EMPTY_BODY_OR_TOO_LARGE",
                            "request_id" to attempt.requestId
                        )
                    )
                }
                bytes
            }
        } catch (error: Exception) {
            if (attempt != null) {
                MediaDiagnosticLogger.httpError(
                    attempt, "MEDIA_DRIVE", error.javaClass.simpleName, error.message,
                    System.currentTimeMillis() - startedAt
                )
            }
            MediaDiagnosticLogger.log(
                LogLevel.WARNING, LogCategory.DRIVE, "MEDIA_DRIVE_ERROR",
                "media-drive response could not be read",
                mapOf(
                    "media_id" to mediaId, "variant" to variant,
                    "error_type" to classify(error),
                    "exception_type" to error.javaClass.simpleName,
                    "request_id" to attempt?.requestId
                )
            )
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Distinguishes auth/authorization/not-found/metadata/server failure classes. */
    private fun classifyDriveStatus(status: Int): String = when (status) {
        401 -> "AUTHENTICATION_FAILURE"
        403 -> "AUTHORIZATION_FAILURE"
        404 -> "MEDIA_NOT_FOUND_OR_DRIVE_FILE_NOT_FOUND"
        400, 422 -> "INVALID_METADATA"
        408 -> "TIMEOUT"
        in 500..599 -> "SERVER_ERROR"
        else -> "HTTP_$status"
    }

    private fun classify(error: Exception): String {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.net.SocketTimeoutException || current is java.net.ConnectTimeoutException) {
                return "TIMEOUT"
            }
            if (current is java.net.UnknownHostException) return "NETWORK_ERROR"
            current = current.cause
        }
        return "TRANSPORT_FAILURE"
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
}
