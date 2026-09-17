package com.mydrive.app.data.remote

import android.content.Context
import android.net.Uri
import com.mydrive.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Result of requesting Cloudinary upload authorization from the Edge Function.
 */
sealed class CloudinaryAuthResult {
    data class Success(
        val cloudName: String,
        val apiKey: String,
        val timestamp: Long,
        val signature: String,
        val folder: String,
        val resourceType: String,
        val params: Map<String, String>
    ) : CloudinaryAuthResult()

    data object Unauthorized : CloudinaryAuthResult()
    data object Misconfigured : CloudinaryAuthResult()
    data object NetworkUnavailable : CloudinaryAuthResult()
    data object Timeout : CloudinaryAuthResult()
    data class Error(val message: String) : CloudinaryAuthResult()
}

/**
 * Result of uploading a file to Cloudinary.
 */
sealed class CloudinaryUploadResult {
    data class Success(
        val assetId: String,
        val publicId: String,
        val secureUrl: String,
        val version: Long,
        val format: String,
        val resourceType: String,
        val bytes: Long
    ) : CloudinaryUploadResult()

    data object Unauthorized : CloudinaryUploadResult()
    data object NetworkUnavailable : CloudinaryUploadResult()
    data object Timeout : CloudinaryUploadResult()
    data object FileTooLarge : CloudinaryUploadResult()
    data object UnsupportedMedia : CloudinaryUploadResult()
    data object MediaUnavailable : CloudinaryUploadResult()
    data class UploadFailed(val httpStatus: Int, val message: String) : CloudinaryUploadResult()
    data class Error(val message: String) : CloudinaryUploadResult()
}

/**
 * Handles Cloudinary uploads by:
 * 1. Requesting signed upload authorization from the Supabase Edge Function
 * 2. Uploading the file directly from Android to Cloudinary using the signed params
 *
 * The API Secret is NEVER exposed to Android — it stays on the server.
 */
class CloudinaryUploadService(
    private val context: Context,
    private val supabaseClient: SupabaseClient?,
    private val network: NetworkMonitor
) {

    private val appContext = context.applicationContext

    /**
     * Request upload authorization from the Supabase Edge Function.
     * Requires an authenticated user session.
     */
    suspend fun requestUploadAuth(
        resourceType: String = "auto"
    ): CloudinaryAuthResult = withContext(Dispatchers.IO) {
        if (!network.isOnline()) return@withContext CloudinaryAuthResult.NetworkUnavailable
        if (supabaseClient == null) return@withContext CloudinaryAuthResult.Misconfigured

        val session = supabaseClient.auth.currentSessionOrNull()
            ?: return@withContext CloudinaryAuthResult.Unauthorized
        val accessToken = session.accessToken
            ?: return@withContext CloudinaryAuthResult.Unauthorized

        val projectRef = extractProjectRef(BuildConfig.SUPABASE_URL)
        val edgeFunctionUrl =
            "https://$projectRef.supabase.co/functions/v1/cloudinary-upload-auth"

        val body = """{"resource_type":"$resourceType"}"""

        val connection = try {
            (URL(edgeFunctionUrl).openConnection() as HttpURLConnection)
        } catch (_: Exception) {
            return@withContext CloudinaryAuthResult.NetworkUnavailable
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
                200 -> parseAuthSuccess(responseBody)
                401 -> CloudinaryAuthResult.Unauthorized
                500 -> CloudinaryAuthResult.Misconfigured
                else -> CloudinaryAuthResult.Error("Auth request failed: $status")
            }
        } catch (_: SocketTimeoutException) {
            CloudinaryAuthResult.Timeout
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CloudinaryAuthResult.NetworkUnavailable
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Upload a media file directly to Cloudinary using signed authorization.
     * Streams from ContentResolver — never loads the full file into memory.
     */
    suspend fun uploadToCloudinary(
        auth: CloudinaryAuthResult.Success,
        mediaUri: String,
        mimeType: String,
        filename: String,
        resourceType: String = "auto"
    ): CloudinaryUploadResult = withContext(Dispatchers.IO) {
        if (!network.isOnline()) return@withContext CloudinaryUploadResult.NetworkUnavailable
        if (mediaUri.isBlank()) return@withContext CloudinaryUploadResult.MediaUnavailable

        val uri = try {
            Uri.parse(mediaUri)
        } catch (_: Exception) {
            return@withContext CloudinaryUploadResult.MediaUnavailable
        }

        val source = openSource(uri)
            ?: return@withContext CloudinaryUploadResult.MediaUnavailable

        source.use { input ->
            val boundary = "CloudinaryBoundary${UUID.randomUUID()}"
            val uploadUrl =
                "https://api.cloudinary.com/v1_1/${auth.cloudName}/${auth.resourceType}/upload"

            val connection = try {
                (URL(uploadUrl).openConnection() as HttpURLConnection)
            } catch (_: Exception) {
                return@withContext CloudinaryUploadResult.NetworkUnavailable
            }

            return@withContext try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.useCaches = false
                connection.connectTimeout = UPLOAD_CONNECT_TIMEOUT_MS
                connection.readTimeout = UPLOAD_READ_TIMEOUT_MS
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )
                connection.setChunkedStreamingMode(CHUNK_SIZE)

                connection.outputStream.use { output ->
                    // Write signed parameters
                    for ((key, value) in auth.params) {
                        writeTextField(output, boundary, key, value)
                    }
                    writeTextField(output, boundary, "api_key", auth.apiKey)
                    writeTextField(output, boundary, "timestamp", auth.timestamp.toString())
                    writeTextField(output, boundary, "signature", auth.signature)

                    // Write file
                    val safeFilename = sanitizeFilename(filename)
                    writeFileHeader(
                        output = output,
                        boundary = boundary,
                        fieldName = "file",
                        filename = safeFilename,
                        contentType = mimeType
                    )
                    streamFile(input, output)
                    writeEnd(output, boundary)
                }

                val status = connection.responseCode
                val responseBody = readBody(connection, status)

                when (status) {
                    200 -> parseUploadSuccess(responseBody)
                    400 -> CloudinaryUploadResult.UploadFailed(status, "Invalid upload parameters")
                    401 -> CloudinaryUploadResult.Unauthorized
                    413 -> CloudinaryUploadResult.FileTooLarge
                    else -> CloudinaryUploadResult.UploadFailed(
                        status,
                        "Upload failed with status $status"
                    )
                }
            } catch (_: SocketTimeoutException) {
                CloudinaryUploadResult.Timeout
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: FileNotFoundException) {
                CloudinaryUploadResult.MediaUnavailable
            } catch (_: SecurityException) {
                CloudinaryUploadResult.MediaUnavailable
            } catch (_: FileTooLargeException) {
                CloudinaryUploadResult.FileTooLarge
            } catch (_: Exception) {
                CloudinaryUploadResult.NetworkUnavailable
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openSource(uri: Uri): InputStream? = try {
        appContext.contentResolver.openInputStream(uri)
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun streamFile(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(CHUNK_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_FILE_BYTES) {
                throw FileTooLargeException()
            }
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun writeTextField(
        output: OutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"$name\"\r\n".toByteArray(Charsets.UTF_8)
        )
        output.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun writeFileHeader(
        output: OutputStream,
        boundary: String,
        fieldName: String,
        filename: String,
        contentType: String
    ) {
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\"\r\n"
                .toByteArray(Charsets.UTF_8)
        )
        output.write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun writeEnd(output: OutputStream, boundary: String) {
        output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        return if (status in 200..299) {
            connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
    }

    private fun parseAuthSuccess(body: String): CloudinaryAuthResult {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val success = root.boolean("success")
            if (!success) {
                val error = root.string("error") ?: "Unknown error"
                return CloudinaryAuthResult.Error(error)
            }
            val params = mutableMapOf<String, String>()
            root["params"]?.jsonObject?.forEach { (key, value) ->
                params[key] = value.jsonPrimitive.content
            }

            CloudinaryAuthResult.Success(
                cloudName = root.string("cloud_name").orEmpty(),
                apiKey = root.string("api_key").orEmpty(),
                timestamp = root.long("timestamp") ?: 0L,
                signature = root.string("signature").orEmpty(),
                folder = root.string("folder").orEmpty(),
                resourceType = root.string("resource_type").orEmpty(),
                params = params
            ).let { auth ->
                if (auth.cloudName.isBlank() || auth.apiKey.isBlank() || auth.timestamp <= 0L ||
                    auth.signature.isBlank() || auth.resourceType !in setOf("image", "video", "raw")
                ) {
                    CloudinaryAuthResult.Error("Cloudinary authorization response is incomplete")
                } else {
                    auth
                }
            }
        } catch (_: Exception) {
            CloudinaryAuthResult.Error("Failed to parse auth response")
        }
    }

    private fun parseUploadSuccess(body: String): CloudinaryUploadResult {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val error = root.string("error")
            if (error != null) {
                return CloudinaryUploadResult.UploadFailed(400, error)
            }
            val assetId = root.string("asset_id").orEmpty()
            val publicId = root.string("public_id").orEmpty()
            val secureUrl = root.string("secure_url").orEmpty()
            if (assetId.isBlank() || publicId.isBlank() || secureUrl.isBlank()) {
                CloudinaryUploadResult.Error("Cloudinary upload response is incomplete")
            } else {
                CloudinaryUploadResult.Success(
                    assetId = assetId,
                    publicId = publicId,
                    secureUrl = secureUrl,
                    version = root.long("version") ?: 0L,
                    format = root.string("format").orEmpty(),
                    resourceType = root.string("resource_type").orEmpty(),
                    bytes = root.long("bytes") ?: 0L
                )
            }
        } catch (_: Exception) {
            CloudinaryUploadResult.Error("Failed to parse upload response")
        }
    }

    private fun sanitizeFilename(filename: String): String {
        val sanitized = filename.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_FILENAME_LENGTH)
        if (sanitized.isNotBlank() && sanitized.contains('.')) return sanitized
        val base = sanitized.ifBlank { "media" }
        return "$base.jpg"
    }

    private fun extractProjectRef(supabaseUrl: String): String {
        // https://gpiuxcdjmrzcouhjapcs.supabase.co -> gpiuxcdjmrzcouhjapcs
        return supabaseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore(".")
    }

    // JSON helpers
    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.long(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.content

    private class FileTooLargeException : Exception()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val UPLOAD_CONNECT_TIMEOUT_MS = 30_000
        private const val UPLOAD_READ_TIMEOUT_MS = 120_000
        private const val CHUNK_SIZE = 64 * 1024
        private const val MAX_FILE_BYTES = 100L * 1024L * 1024L // 100 MB
        private const val MAX_FILENAME_LENGTH = 120
        private val json = Json { ignoreUnknownKeys = true }
    }
}
