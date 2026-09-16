package com.mydrive.app.data.remote

import android.content.Context
import com.mydrive.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
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
    private val network: NetworkMonitor
) {

    private val appContext = context.applicationContext

    suspend fun finalize(request: MediaFinalizeRequest): FinalizeResult =
        withContext(Dispatchers.IO) {
            if (!network.isOnline()) return@withContext FinalizeResult.NetworkUnavailable
            if (supabaseClient == null) return@withContext FinalizeResult.Misconfigured

            val session = supabaseClient.auth.currentSessionOrNull()
                ?: return@withContext FinalizeResult.Unauthorized
            val accessToken = session.accessToken
                ?: return@withContext FinalizeResult.Unauthorized

            val projectRef = extractProjectRef(BuildConfig.SUPABASE_URL)
            val edgeFunctionUrl =
                "https://$projectRef.supabase.co/functions/v1/finalize-media"
            val body = buildRequestBody(request)

            val connection = try {
                (URL(edgeFunctionUrl).openConnection() as HttpURLConnection)
            } catch (_: Exception) {
                return@withContext FinalizeResult.NetworkUnavailable
            }

            return@withContext try {
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

                when (status) {
                    200 -> parseSuccess(responseBody)
                    401 -> FinalizeResult.Unauthorized
                    400, 403, 409 -> FinalizeResult.Rejected(extractError(responseBody))
                    500 -> FinalizeResult.Misconfigured
                    else -> FinalizeResult.Error("Finalization failed: $status")
                }
            } catch (_: SocketTimeoutException) {
                FinalizeResult.Timeout
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                FinalizeResult.NetworkUnavailable
            } finally {
                connection.disconnect()
            }
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