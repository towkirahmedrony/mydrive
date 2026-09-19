package com.mydrive.app.debug

import android.os.Build
import com.mydrive.app.BuildConfig
import com.mydrive.app.data.auth.AuthState
import com.mydrive.app.data.local.DeviceInfoFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DiagnosticsExporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun buildReport(
        events: List<DeveloperLogEvent>,
        session: SessionSnapshot,
        queue: QueueSnapshot,
        work: WorkManagerSnapshot?,
        counters: DeveloperLogCounters
    ): String {
        val generatedAt = iso(System.currentTimeMillis())
        val payload = buildJsonObject {
            put("app", buildJsonObject {
                put("name", "My Drive")
                put("version_name", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
                put("build_type", BuildConfig.BUILD_TYPE)
            })
            put("device", buildJsonObject {
                put("manufacturer", Build.MANUFACTURER.orEmpty())
                put("brand", DeviceInfoFactory.brand())
                put("model", DeviceInfoFactory.model())
                put("android_version", DeviceInfoFactory.androidVersion())
                put("sdk", Build.VERSION.SDK_INT)
            })
            put("generated_at", generatedAt)
            put("auth", buildJsonObject {
                put("signed_in", session.signedIn)
                put("user_id", session.userIdMasked ?: "")
                put("has_session", session.hasSession)
                put("status", session.statusName)
                put("token_expired", session.tokenExpired)
                session.expiresAtEpochSeconds?.let { put("access_token_expires_at", iso(it * 1000L)) }
            })
            put("queue", buildJsonObject {
                put("pending", queue.pending)
                put("uploading", queue.uploading)
                put("failed", queue.failed)
                put("completed", queue.completed)
                put("paused", queue.paused)
            })
            put("work_manager", buildJsonObject {
                put("unique_name", work?.uniqueName ?: "mydrive-media-upload-queue")
                put("state", work?.state ?: "UNKNOWN")
                put("run_attempt_count", work?.runAttemptCount ?: 0)
                put("running", work?.running ?: false)
                put("last_error", work?.lastError ?: "")
            })
            put("counters", buildJsonObject {
                put("total", counters.total)
                put("errors", counters.errors)
                put("warnings", counters.warnings)
                put("network_errors", counters.networkErrors)
                put("auth_errors", counters.authErrors)
                put("upload_errors", counters.uploadErrors)
                put("workmanager_failures", counters.workManagerFailures)
                put("database_errors", counters.databaseErrors)
                put("backend_errors", counters.backendErrors)
            })
            put("events", buildJsonArray {
                events.forEach { event ->
                    add(eventJson(event))
                }
            })
        }
        return SecretRedactor.text(json.encodeToString(JsonObject(payload)))
    }

    fun formatEventDetails(event: DeveloperLogEvent): String {
        return buildString {
            appendLine("My Drive Developer Console")
            appendLine("timestamp=${iso(event.timestamp)}")
            appendLine("level=${event.level}")
            appendLine("category=${event.category}")
            appendLine("event=${event.event}")
            appendLine("message=${event.message}")
            appendLine("operationId=${event.operationId.orEmpty()}")
            appendLine("localMediaId=${event.localMediaId.orEmpty()}")
            appendLine("clientUploadId=${event.clientUploadId.orEmpty()}")
            appendLine("workerId=${event.workerId.orEmpty()}")
            appendLine("httpMethod=${event.httpMethod.orEmpty()}")
            appendLine("endpoint=${event.urlPath.orEmpty()}")
            appendLine("httpStatus=${event.httpStatus ?: ""}")
            appendLine("durationMs=${event.durationMs ?: ""}")
            appendLine("retryCount=${event.retryCount ?: ""}")
            appendLine("exceptionType=${event.exceptionType.orEmpty()}")
            appendLine("exceptionMessage=${event.exceptionMessage.orEmpty()}")
            if (event.metadata.isNotEmpty()) {
                appendLine("metadata=")
                event.metadata.forEach { (key, value) ->
                    appendLine("  $key=$value")
                }
            }
            if (!event.stackTrace.isNullOrBlank()) {
                appendLine("stackTrace=")
                appendLine(event.stackTrace)
            }
        }.let(SecretRedactor::text)
    }

    fun explanation(event: DeveloperLogEvent): String {
        val status = event.httpStatus
        return when {
            status == 401 -> "Authentication failed. The request was rejected with HTTP 401. Check local session, Supabase client, or Edge Function auth."
            status == 403 -> "The backend refused this request (HTTP 403)."
            status == 500 && event.category == LogCategory.CLOUDINARY_AUTH -> "Cloudinary authorization Edge Function failed to boot or execute (HTTP 500)."
            status != null && status >= 500 -> "Backend or remote service returned HTTP $status."
            status != null && status >= 400 -> "Remote request failed with HTTP $status."
            event.category == LogCategory.WORKMANAGER && (event.level == LogLevel.ERROR || event.level == LogLevel.FATAL) -> "WorkManager stopped or retried this upload worker."
            event.category == LogCategory.ROOM || event.category == LogCategory.DATABASE -> "Local queue / Room state changed or failed."
            event.category == LogCategory.AUTH -> "Session or sign-in state changed."
            event.level == LogLevel.ERROR || event.level == LogLevel.FATAL -> event.message
            else -> event.message
        }
    }

    private fun eventJson(event: DeveloperLogEvent): JsonObject = buildJsonObject {
        put("id", event.id)
        put("timestamp", iso(event.timestamp))
        put("level", event.level.name)
        put("category", event.category.name)
        put("event", event.event)
        put("message", event.message)
        put("operationId", event.operationId ?: "")
        put("localMediaId", event.localMediaId ?: "")
        put("clientUploadId", event.clientUploadId ?: "")
        put("workerId", event.workerId ?: "")
        put("httpMethod", event.httpMethod ?: "")
        put("urlPath", event.urlPath ?: "")
        event.httpStatus?.let { put("httpStatus", it) } ?: put("httpStatus", JsonPrimitive(""))
        event.durationMs?.let { put("durationMs", it) } ?: put("durationMs", JsonPrimitive(""))
        put("exceptionType", event.exceptionType ?: "")
        put("exceptionMessage", event.exceptionMessage ?: "")
        put("stackTrace", event.stackTrace ?: "")
        event.retryCount?.let { put("retryCount", it) } ?: put("retryCount", JsonPrimitive(""))
        put("metadata", buildJsonObject {
            event.metadata.forEach { (key, value) -> put(key, value) }
        })
    }

    private fun iso(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }
}

fun sessionSnapshotOf(
    authState: AuthState,
    expiresAtEpochSeconds: Long?,
    hasSession: Boolean,
    tokenExpired: Boolean
): SessionSnapshot {
    val userId = when (authState) {
        is AuthState.Authenticated -> authState.profile.id
        is AuthState.Suspended -> authState.profile.id
        else -> null
    }
    return SessionSnapshot(
        signedIn = authState is AuthState.Authenticated,
        userIdMasked = SecretRedactor.maskUserId(userId),
        hasSession = hasSession,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        statusName = when (authState) {
            AuthState.Loading -> "LOADING"
            AuthState.Unauthenticated -> "SIGNED_OUT"
            is AuthState.Authenticated -> "AUTHENTICATED"
            is AuthState.Suspended -> "SUSPENDED"
        },
        tokenExpired = tokenExpired
    )
}
