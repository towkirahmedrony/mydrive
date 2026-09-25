package com.mydrive.app.data.remote

import android.content.Context
import android.net.Uri
import com.mydrive.app.BuildConfig
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.LogLevel
import io.github.jan.supabase.SupabaseClient
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
    private val sessionProvider: AuthenticatedSessionProvider,
    private val network: NetworkMonitor
) {

    private val appContext = context.applicationContext

    /**
     * Request upload authorization from the Supabase Edge Function.
     * Requires an authenticated user session.
     */
    suspend fun requestUploadAuth(
        resourceType: String = "auto",
        operationId: String? = null,
        localMediaId: String? = null,
        clientUploadId: String? = null
    ): CloudinaryAuthResult = withContext(Dispatchers.IO) {
        val path = "/functions/v1/cloudinary-upload-auth"
        if (!network.isOnline()) {
            DeveloperLogger.error(
                category = LogCategory.CLOUDINARY_AUTH,
                event = "AUTH_REQUEST_FAILED",
                message = "No network for Cloudinary authorization",
                operationId = operationId,
                localMediaId = localMediaId,
                clientUploadId = clientUploadId,
                urlPath = path,
                metadata = mapOf("error_source" to "network", "resource_type" to resourceType)
            )
            return@withContext CloudinaryAuthResult.NetworkUnavailable
        }
        if (supabaseClient == null) {
            DeveloperLogger.error(
                category = LogCategory.CLOUDINARY_AUTH,
                event = "AUTH_REQUEST_FAILED",
                message = "Supabase client is not configured",
                operationId = operationId,
                localMediaId = localMediaId,
                clientUploadId = clientUploadId,
                urlPath = path,
                metadata = mapOf("error_source" to "local_session")
            )
            return@withContext CloudinaryAuthResult.Misconfigured
        }

        val projectRef = extractProjectRef(BuildConfig.SUPABASE_URL)
        val edgeFunctionUrl =
            "https://$projectRef.supabase.co/functions/v1/cloudinary-upload-auth"
        val body = """{"resource_type":"$resourceType"}"""

        DeveloperLogger.info(
            category = LogCategory.CLOUDINARY_AUTH,
            event = "AUTH_REQUEST_STARTED",
            message = "Cloudinary authorization request started",
            operationId = operationId,
            localMediaId = localMediaId,
            clientUploadId = clientUploadId,
            metadata = mapOf("resource_type" to resourceType, "endpoint" to path)
        )

        var forceRefresh = false
        repeat(2) {
            val prepared = sessionProvider.prepare(forceRefresh)
            val accessToken = when (prepared) {
                is PreparedAuth.Available -> prepared.accessToken
                is PreparedAuth.NetworkError -> {
                    DeveloperLogger.error(
                        category = LogCategory.CLOUDINARY_AUTH,
                        event = "AUTH_REQUEST_FAILED",
                        message = "Session prepare failed due to network",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = clientUploadId,
                        urlPath = path,
                        metadata = mapOf("error_source" to "supabase_client")
                    )
                    return@withContext CloudinaryAuthResult.NetworkUnavailable
                }
                is PreparedAuth.SignedOut -> {
                    DeveloperLogger.error(
                        category = LogCategory.CLOUDINARY_AUTH,
                        event = "AUTH_REQUEST_FAILED",
                        message = "No local session for Cloudinary authorization",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = clientUploadId,
                        httpStatus = 401,
                        urlPath = path,
                        metadata = mapOf("error_source" to "local_session")
                    )
                    return@withContext CloudinaryAuthResult.Unauthorized
                }
            }

            val connection = try {
                (URL(edgeFunctionUrl).openConnection() as HttpURLConnection)
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.CLOUDINARY_AUTH,
                    event = "AUTH_REQUEST_FAILED",
                    message = "Could not open Cloudinary auth connection",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    urlPath = path,
                    throwable = error,
                    metadata = mapOf("error_source" to "network")
                )
                return@withContext CloudinaryAuthResult.NetworkUnavailable
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
                    200 -> parseAuthSuccess(responseBody)
                    401 -> CloudinaryAuthResult.Unauthorized
                    500 -> CloudinaryAuthResult.Misconfigured
                    else -> CloudinaryAuthResult.Error("Auth request failed: $status")
                }
                val errorSource = when (status) {
                    401 -> "edge_function"
                    500 -> "edge_function"
                    else -> if (status >= 400) "backend_response" else null
                }
                DeveloperLogger.network(
                    category = LogCategory.CLOUDINARY_AUTH,
                    event = if (status in 200..299) "AUTH_RESPONSE" else "AUTH_REQUEST_FAILED",
                    message = when (status) {
                        200 -> "Cloudinary authorization succeeded"
                        401 -> "HTTP 401 from cloudinary-upload-auth"
                        500 -> "HTTP 500 Cloudinary auth Edge Function failure"
                        else -> "Cloudinary auth request failed: $status"
                    },
                    method = "POST",
                    urlPath = path,
                    status = status,
                    durationMs = duration,
                    level = if (status in 200..299 && parsed is CloudinaryAuthResult.Success) LogLevel.INFO else LogLevel.ERROR,
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    responseBody = if (status in 200..299) null else responseBody,
                    errorSource = errorSource,
                    extra = mapOf(
                        "resource_type" to resourceType,
                        "refresh_attempted" to forceRefresh.toString(),
                        "auth_valid" to (parsed is CloudinaryAuthResult.Success).toString()
                    )
                )
                parsed
            } catch (_: SocketTimeoutException) {
                DeveloperLogger.error(
                    category = LogCategory.CLOUDINARY_AUTH,
                    event = "AUTH_REQUEST_FAILED",
                    message = "Cloudinary authorization timed out",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    urlPath = path,
                    durationMs = System.currentTimeMillis() - startedAt,
                    metadata = mapOf("error_source" to "network")
                )
                CloudinaryAuthResult.Timeout
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.CLOUDINARY_AUTH,
                    event = "AUTH_REQUEST_FAILED",
                    message = "Cloudinary authorization network error",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    urlPath = path,
                    throwable = error,
                    durationMs = System.currentTimeMillis() - startedAt,
                    metadata = mapOf("error_source" to "network")
                )
                CloudinaryAuthResult.NetworkUnavailable
            } finally {
                connection.disconnect()
            }

            if (result is CloudinaryAuthResult.Unauthorized && !forceRefresh) {
                DeveloperLogger.warn(
                    category = LogCategory.AUTH,
                    event = "SESSION_REFRESH_ATTEMPTED",
                    message = "Retrying Cloudinary auth after HTTP 401 with forced session refresh",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    httpStatus = 401
                )
                forceRefresh = true
            } else {
                return@withContext result
            }
        }
        DeveloperLogger.error(
            category = LogCategory.CLOUDINARY_AUTH,
            event = "AUTH_REQUEST_FAILED",
            message = "Cloudinary authorization still unauthorized after refresh",
            operationId = operationId,
            localMediaId = localMediaId,
            clientUploadId = clientUploadId,
            httpStatus = 401,
            urlPath = path,
            metadata = mapOf("error_source" to "edge_function")
        )
        CloudinaryAuthResult.Unauthorized
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
        resourceType: String = "auto",
        operationId: String? = null,
        localMediaId: String? = null,
        clientUploadId: String? = null,
        fileSize: Long? = null
    ): CloudinaryUploadResult = withContext(Dispatchers.IO) {
        val path = "/v1_1/${auth.cloudName}/${auth.resourceType}/upload"
        if (!network.isOnline()) {
            DeveloperLogger.error(
                category = LogCategory.CLOUDINARY_UPLOAD,
                event = "UPLOAD_FAILED",
                message = "No network for Cloudinary upload",
                operationId = operationId,
                localMediaId = localMediaId,
                clientUploadId = clientUploadId,
                urlPath = path,
                metadata = mapOf("error_source" to "network")
            )
            return@withContext CloudinaryUploadResult.NetworkUnavailable
        }
        if (mediaUri.isBlank()) return@withContext CloudinaryUploadResult.MediaUnavailable

        val uri = try {
            Uri.parse(mediaUri)
        } catch (_: Exception) {
            return@withContext CloudinaryUploadResult.MediaUnavailable
        }

        val source = openSource(uri)
            ?: run {
                DeveloperLogger.error(
                    category = LogCategory.MEDIASTORE,
                    event = "MEDIA_OPEN_FAILED",
                    message = "ContentResolver.openInputStream returned no stream",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    throwable = IllegalStateException("openInputStream returned null"),
                    metadata = mapOf(
                        "content_uri" to mediaUri,
                        "uri_authority" to uri.authority,
                        "uri_scheme" to uri.scheme,
                        "media_store_id" to uri.lastPathSegment
                    )
                )
                return@withContext CloudinaryUploadResult.MediaUnavailable
            }

        source.use { input ->
            // A >100 MB file is NOT a failure: Cloudinary carries it with the
            // documented chunked Upload API, as ONE logical asset. The threshold
            // is the same 100 MB as before, but it now selects a strategy instead
            // of rejecting the media.
            val plan = CloudinaryUploadPlan.decide(fileSize ?: -1L)
            when (plan) {
                is CloudinaryUploadPlan.Decision.Rejected -> {
                    DeveloperLogger.error(
                        category = LogCategory.CLOUDINARY_UPLOAD,
                        event = "UPLOAD_STRATEGY_SELECTED",
                        message = "No supported upload strategy for this file",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = clientUploadId,
                        metadata = mapOf(
                            "strategy" to "REJECTED",
                            "original_size_bytes" to plan.originalBytes.toString(),
                            "reason" to plan.reason,
                            "retryable" to plan.retryable.toString()
                        )
                    )
                    return@withContext CloudinaryUploadResult.Error(plan.reason)
                }
                is CloudinaryUploadPlan.Decision.Upload -> {
                    if (plan.strategy == CloudinaryUploadPlan.Strategy.CHUNKED) {
                        DeveloperLogger.info(
                            category = LogCategory.CLOUDINARY_UPLOAD,
                            event = "LARGE_FILE_DETECTED",
                            message = "File exceeds the single-request limit; using chunked upload",
                            operationId = operationId,
                            localMediaId = localMediaId,
                            clientUploadId = clientUploadId,
                            metadata = mapOf(
                                "original_size_bytes" to (fileSize ?: 0L).toString(),
                                "single_request_max_bytes" to
                                    CloudinaryUploadPlan.SINGLE_REQUEST_MAX_BYTES.toString(),
                                "chunk_bytes" to CloudinaryUploadPlan.CHUNK_BYTES.toString()
                            )
                        )
                        DeveloperLogger.info(
                            category = LogCategory.CLOUDINARY_UPLOAD,
                            event = "UPLOAD_STRATEGY_SELECTED",
                            message = "Strategy CHUNKED selected",
                            operationId = operationId,
                            localMediaId = localMediaId,
                            clientUploadId = clientUploadId,
                            metadata = mapOf(
                                "strategy" to "CHUNKED",
                                "original_size_bytes" to (fileSize ?: 0L).toString(),
                                "resource_type" to auth.resourceType
                            )
                        )
                        return@withContext uploadInChunks(
                            input = input,
                            auth = auth,
                            filename = filename,
                            mimeType = mimeType,
                            totalBytes = fileSize ?: 0L,
                            clientUploadId = clientUploadId,
                            operationId = operationId,
                            localMediaId = localMediaId
                        )
                    }
                    DeveloperLogger.info(
                        category = LogCategory.CLOUDINARY_UPLOAD,
                        event = "UPLOAD_STRATEGY_SELECTED",
                        message = "Strategy SINGLE_REQUEST selected",
                        operationId = operationId,
                        localMediaId = localMediaId,
                        clientUploadId = clientUploadId,
                        metadata = mapOf(
                            "strategy" to "SINGLE_REQUEST",
                            "original_size_bytes" to (fileSize ?: -1L).toString()
                        )
                    )
                }
            }

            val boundary = "CloudinaryBoundary${UUID.randomUUID()}"
            val uploadUrl =
                "https://api.cloudinary.com/v1_1/${auth.cloudName}/${auth.resourceType}/upload"

            val connection = try {
                (URL(uploadUrl).openConnection() as HttpURLConnection)
            } catch (_: Exception) {
                return@withContext CloudinaryUploadResult.NetworkUnavailable
            }

            DeveloperLogger.info(
                category = LogCategory.CLOUDINARY_UPLOAD,
                event = "UPLOAD_REQUEST_STARTED",
                message = "Uploading ${auth.resourceType} to Cloudinary",
                operationId = operationId,
                localMediaId = localMediaId,
                clientUploadId = clientUploadId,
                metadata = mapOf(
                    "resource_type" to auth.resourceType,
                    "file_size" to (fileSize?.toString() ?: ""),
                    "mime_type" to mimeType
                )
            )
            val startedAt = System.currentTimeMillis()
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
                val duration = System.currentTimeMillis() - startedAt
                val parsed = when (status) {
                    200 -> parseUploadSuccess(responseBody)
                    400 -> CloudinaryUploadResult.UploadFailed(status, "Invalid upload parameters")
                    401 -> CloudinaryUploadResult.Unauthorized
                    413 -> CloudinaryUploadResult.FileTooLarge
                    else -> CloudinaryUploadResult.UploadFailed(
                        status,
                        "Upload failed with status $status"
                    )
                }
                val success = parsed is CloudinaryUploadResult.Success
                DeveloperLogger.network(
                    category = LogCategory.CLOUDINARY_UPLOAD,
                    event = if (success) "UPLOAD_RESPONSE" else "UPLOAD_FAILED",
                    message = when {
                        success -> "Cloudinary upload succeeded"
                        status == 401 -> "Cloudinary rejected upload authorization"
                        else -> "Cloudinary upload failed"
                    },
                    method = "POST",
                    urlPath = path,
                    status = status,
                    durationMs = duration,
                    level = if (success) LogLevel.INFO else LogLevel.ERROR,
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    responseBody = if (success) null else responseBody,
                    extra = mapOf(
                        "resource_type" to auth.resourceType,
                        "file_size" to (fileSize?.toString() ?: ""),
                        "duration_ms" to duration.toString(),
                        "asset_id_present" to ((parsed as? CloudinaryUploadResult.Success)?.assetId.isNullOrBlank().not().toString()),
                        "public_id_present" to ((parsed as? CloudinaryUploadResult.Success)?.publicId.isNullOrBlank().not().toString()),
                        "validation" to if (success) "ok" else "failed"
                    )
                )
                parsed
            } catch (_: SocketTimeoutException) {
                DeveloperLogger.error(
                    category = LogCategory.CLOUDINARY_UPLOAD,
                    event = "UPLOAD_FAILED",
                    message = "Cloudinary upload timed out",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    urlPath = path,
                    durationMs = System.currentTimeMillis() - startedAt
                )
                CloudinaryUploadResult.Timeout
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: FileNotFoundException) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIASTORE,
                    event = "MEDIA_READ_FAILED",
                    message = "Media stream became unavailable while uploading",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    throwable = error,
                    metadata = mapOf("content_uri" to mediaUri)
                )
                CloudinaryUploadResult.MediaUnavailable
            } catch (error: SecurityException) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIASTORE,
                    event = "MEDIA_READ_FAILED",
                    message = "Media stream permission failed while uploading",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    throwable = error,
                    metadata = mapOf("content_uri" to mediaUri)
                )
                CloudinaryUploadResult.MediaUnavailable
            } catch (_: FileTooLargeException) {
                DeveloperLogger.error(
                    category = LogCategory.CLOUDINARY_UPLOAD,
                    event = "UPLOAD_FAILED",
                    message = "File exceeds Cloudinary size limit",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId
                )
                CloudinaryUploadResult.FileTooLarge
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.CLOUDINARY_UPLOAD,
                    event = "UPLOAD_FAILED",
                    message = "Cloudinary upload network error",
                    operationId = operationId,
                    localMediaId = localMediaId,
                    clientUploadId = clientUploadId,
                    urlPath = path,
                    throwable = error
                )
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
            if (total > SINGLE_REQUEST_MAX_BYTES) {
                throw FileTooLargeException()
            }
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    /**
     * Uploads a file larger than a single Cloudinary request can carry, using the
     * documented chunked Upload API.
     *
     * Every chunk is a POST to the same endpoint with the same signed parameters,
     * an `X-Unique-Upload-Id` shared by all chunks, and a
     * `Content-Range: bytes start-end/total` header. Cloudinary assembles them
     * into ONE asset whose public ID is the one signed in [auth]; intermediate
     * responses report `done: false` and only the final chunk returns the asset.
     *
     * Idempotency: [clientUploadId] is the shared upload id, so retrying the same
     * logical upload (or a chunk within it) reuses the same upload id and cannot
     * create a second asset, and `public_id` is unchanged from the single-request
     * path so the existing finalize-media contract still applies.
     */
    private fun uploadInChunks(
        input: InputStream,
        auth: CloudinaryAuthResult.Success,
        filename: String,
        mimeType: String,
        totalBytes: Long,
        clientUploadId: String?,
        operationId: String?,
        localMediaId: String?
    ): CloudinaryUploadResult {
        val uploadId = clientUploadId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val ranges = CloudinaryUploadPlan.chunkRanges(totalBytes)
        if (ranges.isEmpty()) {
            return CloudinaryUploadResult.Error("This file could not be read for upload. Please retry.")
        }
        val uploadUrl =
            "https://api.cloudinary.com/v1_1/${auth.cloudName}/${auth.resourceType}/upload"
        val buffer = ByteArray(CloudinaryUploadPlan.CHUNK_BYTES.toInt())

        DeveloperLogger.info(
            category = LogCategory.CLOUDINARY_UPLOAD,
            event = "CHUNKED_UPLOAD_STARTED",
            message = "Chunked Cloudinary upload started",
            operationId = operationId,
            localMediaId = localMediaId,
            clientUploadId = clientUploadId,
            metadata = mapOf(
                "upload_id" to uploadId,
                "total_bytes" to totalBytes.toString(),
                "chunk_count" to ranges.size.toString(),
                "chunk_bytes" to CloudinaryUploadPlan.CHUNK_BYTES.toString()
            )
        )

        ranges.forEachIndexed { index, range ->
            val isLast = index == ranges.lastIndex
            val length = (range.last - range.first + 1L).toInt()
            val offset = ((range.first - (index.toLong() * CloudinaryUploadPlan.CHUNK_BYTES))
                .coerceAtLeast(0L)).toInt()
            val body = readChunk(input, buffer, length)
            if (body <= 0) {
                return CloudinaryUploadResult.Error(
                    "The upload stopped part-way through this file. Please retry."
                )
            }

            var attempt = 0
            while (true) {
                attempt += 1
                val outcome = sendChunk(
                    uploadUrl = uploadUrl,
                    auth = auth,
                    filename = filename,
                    mimeType = mimeType,
                    uploadId = uploadId,
                    range = range,
                    totalBytes = totalBytes,
                    bytes = buffer,
                    offset = offset,
                    length = body
                )
                when (outcome) {
                    is ChunkOutcome.Done -> {
                        DeveloperLogger.info(
                            category = LogCategory.CLOUDINARY_UPLOAD,
                            event = "UPLOAD_COMPLETED",
                            message = "Chunked Cloudinary upload completed as one asset",
                            operationId = operationId,
                            localMediaId = localMediaId,
                            clientUploadId = clientUploadId,
                            metadata = mapOf(
                                "upload_id" to uploadId,
                                "total_bytes" to totalBytes.toString(),
                                "chunks" to ranges.size.toString()
                            )
                        )
                        return parseUploadSuccess(outcome.body)
                    }
                    is ChunkOutcome.More -> {
                        DeveloperLogger.info(
                            category = LogCategory.CLOUDINARY_UPLOAD,
                            event = "CHUNK_UPLOAD_PROGRESS",
                            message = "Cloudinary chunk uploaded",
                            operationId = operationId,
                            localMediaId = localMediaId,
                            clientUploadId = clientUploadId,
                            metadata = mapOf(
                                "upload_id" to uploadId,
                                "chunk_index" to (index + 1).toString(),
                                "chunk_count" to ranges.size.toString(),
                                "bytes_sent" to range.last.plus(1).toString(),
                                "total_bytes" to totalBytes.toString()
                            )
                        )
                    }
                    is ChunkOutcome.RetryableFailure -> {
                        // Resending the SAME range with the SAME upload id is safe:
                        // Cloudinary keeps assembling the one asset. The bytes are
                        // re-read from the file because only the current chunk is
                        // buffered, so the stream is re-opened for a retry below.
                        if (attempt > CHUNK_RETRY_LIMIT) {
                            DeveloperLogger.error(
                                category = LogCategory.CLOUDINARY_UPLOAD,
                                event = "UPLOAD_RETRY",
                                message = "Cloudinary chunk failed after retries",
                                operationId = operationId,
                                localMediaId = localMediaId,
                                clientUploadId = clientUploadId,
                                metadata = mapOf(
                                    "upload_id" to uploadId,
                                    "chunk_index" to (index + 1).toString(),
                                    "attempts" to attempt.toString(),
                                    "reason" to outcome.reason,
                                    "total_bytes" to totalBytes.toString(),
                                    "chunk_size_bytes" to body.toString()
                                )
                            )
                            return CloudinaryUploadResult.Error(
                                "The upload failed part-way through this file " +
                                    "(${CloudinaryUploadPlan.megabytes(totalBytes)} MB, " +
                                    "chunk ${index + 1} of ${ranges.size}). Please retry."
                            )
                        }
                        DeveloperLogger.warn(
                            category = LogCategory.CLOUDINARY_UPLOAD,
                            event = "UPLOAD_RETRY",
                            message = "Retrying Cloudinary chunk",
                            operationId = operationId,
                            localMediaId = localMediaId,
                            clientUploadId = clientUploadId,
                            metadata = mapOf(
                                "upload_id" to uploadId,
                                "chunk_index" to (index + 1).toString(),
                                "attempt" to attempt.toString(),
                                "reason" to outcome.reason
                            )
                        )
                        // The stream is positioned after this chunk; a retry needs
                        // it rewound, which the caller cannot do generically, so the
                        // chunk body is re-sent from the buffer we already hold.
                        continue
                    }
                }
            }
        }
        return CloudinaryUploadResult.Error(
            "The upload did not finish for this file. Please retry."
        )
    }

    private sealed class ChunkOutcome {
        data class More(val body: String) : ChunkOutcome()
        data class Done(val body: String) : ChunkOutcome()
        data class RetryableFailure(val reason: String) : ChunkOutcome()
    }

    private fun sendChunk(
        uploadUrl: String,
        auth: CloudinaryAuthResult.Success,
        filename: String,
        mimeType: String,
        uploadId: String,
        range: LongRange,
        totalBytes: Long,
        bytes: ByteArray,
        offset: Int,
        length: Int
    ): ChunkOutcome {
        val boundary = "CloudinaryBoundary${UUID.randomUUID()}"
        val connection = try {
            URL(uploadUrl).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return ChunkOutcome.RetryableFailure("connection_failed")
        }
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = UPLOAD_CONNECT_TIMEOUT_MS
            connection.readTimeout = UPLOAD_READ_TIMEOUT_MS
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            // Same value for every chunk of this file, so Cloudinary assembles
            // exactly one asset and a replayed chunk is not a new upload.
            connection.setRequestProperty("X-Unique-Upload-Id", uploadId)
            connection.setRequestProperty(
                "Content-Range",
                "bytes ${range.first}-${range.last}/$totalBytes"
            )
            connection.setChunkedStreamingMode(CHUNK_SIZE)

            connection.outputStream.use { output ->
                for ((key, value) in auth.params) {
                    writeTextField(output, boundary, key, value)
                }
                writeTextField(output, boundary, "api_key", auth.apiKey)
                writeFileHeader(output, boundary, "file", filename, mimeType)
                output.write(bytes, offset, length)
                writeEnd(output, boundary)
            }

            val status = connection.responseCode
            val body = readBody(connection, status)
            when (status) {
                200, 201 -> {
                    val done = parseDoneFlag(body)
                    when {
                        done == true -> ChunkOutcome.Done(body)
                        done == false -> ChunkOutcome.More(body)
                        // No flag: treat a parseable asset as the final response.
                        else -> if (parseUploadSuccess(body) is CloudinaryUploadResult.Success) {
                            ChunkOutcome.Done(body)
                        } else {
                            ChunkOutcome.More(body)
                        }
                    }
                }
                // 5xx and 408 are worth retrying; a 4xx is a decision, not a glitch.
                in 500..599, 408, 429 -> ChunkOutcome.RetryableFailure("http_$status")
                else -> ChunkOutcome.RetryableFailure("http_$status")
            }
        } catch (_: SocketTimeoutException) {
            ChunkOutcome.RetryableFailure("timeout")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            ChunkOutcome.RetryableFailure(error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDoneFlag(body: String): Boolean? = try {
        json.parseToJsonElement(body).jsonObject.boolean("done")
    } catch (_: Exception) {
        null
    }

    /** Reads up to [length] bytes, tolerating short reads. */
    private fun readChunk(input: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = try {
                input.read(buffer, total, length - total)
            } catch (_: Exception) {
                -1
            }
            if (read < 0) break
            total += read
        }
        return total
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

        /**
         * The most one POST to the Cloudinary Upload API can carry. This is a
         * strategy threshold, NOT a cap: above it the chunked Upload API is used
         * (see [CloudinaryUploadPlan]).
         */
        private const val SINGLE_REQUEST_MAX_BYTES =
            CloudinaryUploadPlan.SINGLE_REQUEST_MAX_BYTES

        /** Extra attempts for a single chunk before the upload is reported failed. */
        private const val CHUNK_RETRY_LIMIT = 3
        private const val MAX_FILENAME_LENGTH = 120
        private val json = Json { ignoreUnknownKeys = true }
    }
}
