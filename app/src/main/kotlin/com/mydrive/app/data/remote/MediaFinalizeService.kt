package com.mydrive.app.data.remote

import android.content.Context
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Result of finalizing a Cloudinary upload through Supabase.
 */
sealed class FinalizeResult {
    data class Success(val mediaId: String) : FinalizeResult()
    data object Unauthorized : FinalizeResult()
    data object NetworkUnavailable : FinalizeResult()
    data object Timeout : FinalizeResult()
    data object Misconfigured : FinalizeResult()
    data class Rejected(val message: String) : FinalizeResult()
    data class Error(val message: String) : FinalizeResult()
}

/**
 * The minimal Cloudinary upload result needed to create/complete the Supabase
 * media record. `clientUploadId` doubles as the idempotency key so retrying a
 * finalize request never creates a duplicate media record.
 */
data class MediaFinalizeRequest(
    val clientUploadId: String,
    val deviceId: String,
    val localMediaId: Long?,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val assetId: String,
    val publicId: String,
    val secureUrl: String,
    val version: Long,
    val format: String,
    val resourceType: String
)

/**
 * Calls the finalize-media Supabase Edge Function, which verifies ownership
 * and records the Cloudinary asset as a media_assets row. The Cloudinary API
 * Secret never travels over this path.
 */
class MediaFinalizeService(
    private val context: Context,
    private val supabaseClient: SupabaseClient?,
    private val sessionProvider: AuthenticatedSessionProvider,
    private val network: NetworkMonitor
) {

    private val appContext = context.applicationContext

    suspend fun finalize(
        request: MediaFinalizeRequest,
        operationId: String? = null,
        localMediaId: String? = null
    ): FinalizeResult =
        withContext(Dispatchers.IO) {
            val path = "/functions/v1/finalize-media"
            if (!network.isOnline()) {
                DeveloperLogger.error(
                    category = LogCategory.FINALIZE,
                    event = "FINALIZE_FAILED",
                    message = "No network for finalize-media",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = request.clientUploadId,
                    urlPath = path,
                    metadata = mapOf("error_source" to "network")
                )
                return@withContext FinalizeResult.NetworkUnavailable
            }
            if (supabaseClient == null) {
                DeveloperLogger.error(
                    category = LogCategory.FINALIZE,
                    event = "FINALIZE_FAILED",
                    message = "Supabase client is not configured",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = request.clientUploadId,
                    urlPath = path,
                    metadata = mapOf("error_source" to "local_session")
                )
                return@withContext FinalizeResult.Misconfigured
            }

            val projectRef = extractProjectRef(BuildConfig.SUPABASE_URL)
            val edgeFunctionUrl =
                "https://$projectRef.supabase.co/functions/v1/finalize-media"
            val body = buildRequestBody(request)

            DeveloperLogger.info(
                category = LogCategory.FINALIZE,
                event = "FINALIZE_REQUEST_STARTED",
                message = "finalize-media request started",
                operationId = operationId,
                localMediaId = localMediaId,
                clientUploadId = request.clientUploadId,
                metadata = mapOf(
                    "file_name" to request.fileName,
                    "resource_type" to request.resourceType,
                    "has_asset_id" to request.assetId.isNotBlank().toString()
                )
            )

            var forceRefresh = false
            repeat(2) {
                val prepared = sessionProvider.prepare(forceRefresh)
                val accessToken = when (prepared) {
                    is PreparedAuth.Available -> prepared.accessToken
                    is PreparedAuth.NetworkError -> {
                        DeveloperLogger.error(
                            category = LogCategory.FINALIZE,
                            event = "FINALIZE_FAILED",
                            message = "Session prepare failed due to network",
                            operationId = operationId,
                            localMediaId = localMediaId,
                            clientUploadId = request.clientUploadId,
                            urlPath = path,
                            metadata = mapOf("error_source" to "supabase_client")
                        )
                        return@withContext FinalizeResult.NetworkUnavailable
                    }
                    is PreparedAuth.SignedOut -> {
                        DeveloperLogger.error(
                            category = LogCategory.FINALIZE,
                            event = "FINALIZE_FAILED",
                            message = "No local session for finalize-media",
                            operationId = operationId,
                            localMediaId = localMediaId,
                            clientUploadId = request.clientUploadId,
                            httpStatus = 401,
                            urlPath = path,
                            metadata = mapOf("error_source" to "local_session")
                        )
                        return@withContext FinalizeResult.Unauthorized
                    }
                }

                val connection = try {
                    (URL(edgeFunctionUrl).openConnection() as HttpURLConnection)
                } catch (error: Exception) {
                    DeveloperLogger.error(
                        category = LogCategory.FINALIZE,
                        event = "FINALIZE_FAILED",
                        message = "Could not open finalize-media connection",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = request.clientUploadId,
                        urlPath = path,
                        throwable = error,
                        metadata = mapOf("error_source" to "network")
                    )
                    return@withContext FinalizeResult.NetworkUnavailable
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
                        200 -> parseSuccess(responseBody)
                        401 -> FinalizeResult.Unauthorized
                        400, 403, 409 -> FinalizeResult.Rejected(extractError(responseBody))
                        500 -> FinalizeResult.Misconfigured
                        else -> FinalizeResult.Error("Finalization failed: $status")
                    }
                    val success = parsed is FinalizeResult.Success
                    DeveloperLogger.network(
                        category = LogCategory.FINALIZE,
                        event = if (success) "FINALIZE_RESPONSE" else "FINALIZE_FAILED",
                        message = when {
                            success -> "finalize-media succeeded"
                            status == 401 -> "HTTP 401 from finalize-media"
                            status == 500 -> "HTTP 500 finalize-media Edge Function failure"
                            else -> "finalize-media failed: $status"
                        },
                        method = "POST",
                        urlPath = path,
                        status = status,
                        durationMs = duration,
                        level = if (success) LogLevel.INFO else LogLevel.ERROR,
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = request.clientUploadId,
                        responseBody = if (success) null else responseBody,
                        errorSource = when {
                            status == 401 -> "edge_function"
                            status >= 400 -> "backend_response"
                            else -> null
                        },
                        extra = mapOf(
                            "refresh_attempted" to forceRefresh.toString(),
                            "validation" to if (success) "ok" else "failed",
                            "returned_media_id" to ((parsed as? FinalizeResult.Success)?.mediaId ?: "")
                        )
                    )
                    parsed
                } catch (_: SocketTimeoutException) {
                    DeveloperLogger.error(
                        category = LogCategory.FINALIZE,
                        event = "FINALIZE_FAILED",
                        message = "finalize-media timed out",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = request.clientUploadId,
                        urlPath = path,
                        durationMs = System.currentTimeMillis() - startedAt,
                        metadata = mapOf("error_source" to "network")
                    )
                    FinalizeResult.Timeout
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    DeveloperLogger.error(
                        category = LogCategory.FINALIZE,
                        event = "FINALIZE_FAILED",
                        message = "finalize-media network error",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = request.clientUploadId,
                        urlPath = path,
                        throwable = error,
                        durationMs = System.currentTimeMillis() - startedAt,
                        metadata = mapOf("error_source" to "network")
                    )
                    FinalizeResult.NetworkUnavailable
                } finally {
                    connection.disconnect()
                }

                if (result is FinalizeResult.Unauthorized && !forceRefresh) {
                    DeveloperLogger.warn(
                        category = LogCategory.AUTH,
                        event = "SESSION_REFRESH_ATTEMPTED",
                        message = "Retrying finalize-media after HTTP 401 with forced session refresh",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = request.clientUploadId,
                        httpStatus = 401
                    )
                    forceRefresh = true
                } else {
                    return@withContext result
                }
            }
            DeveloperLogger.error(
                category = LogCategory.FINALIZE,
                event = "FINALIZE_FAILED",
                message = "finalize-media still unauthorized after refresh",
                operationId = operationId,
                localMediaId = localMediaId,
                clientUploadId = request.clientUploadId,
                httpStatus = 401,
                urlPath = path,
                metadata = mapOf("error_source" to "edge_function")
            )
            FinalizeResult.Unauthorized
        }

    private fun buildRequestBody(request: MediaFinalizeRequest): String =
        buildJsonObject {
            put("client_upload_id", request.clientUploadId)
            put("device_id", request.deviceId)
            request.localMediaId?.let { put("local_media_id", it) }
            put("file_name", request.fileName)
            put("mime_type", request.mimeType)
            put("file_size", request.fileSize)
            request.width?.let { put("width", it) }
            request.height?.let { put("height", it) }
            request.durationMs?.let { put("duration_ms", it) }
            put("asset_id", request.assetId)
            put("public_id", request.publicId)
            put("secure_url", request.secureUrl)
            put("version", request.version)
            put("format", request.format)
            put("resource_type", request.resourceType)
        }.toString()

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        return if (status in 200..299) {
            connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
    }

    private fun parseSuccess(body: String): FinalizeResult {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val media = root["media"]?.jsonObject
            val mediaId = media?.string("id")
            if (mediaId.isNullOrBlank()) {
                FinalizeResult.Error("Failed to parse finalize response")
            } else {
                FinalizeResult.Success(mediaId)
            }
        } catch (_: Exception) {
            FinalizeResult.Error("Failed to parse finalize response")
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
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}