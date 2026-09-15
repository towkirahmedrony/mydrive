package com.mydrive.app.data.remote

import android.content.Context
import android.net.Uri
import com.mydrive.app.data.local.TelegramCredentials
import java.io.FileNotFoundException
import java.io.IOException
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

data class TelegramPhoto(
    val uri: String,
    val filename: String,
    val mimeType: String,
    val declaredSizeBytes: Long,
    val width: Int,
    val height: Int
)

sealed class TelegramUploadResult {
    data class Success(val messageId: Long) : TelegramUploadResult()
    data object InvalidCredentials : TelegramUploadResult()
    data object ChatUnavailable : TelegramUploadResult()
    data object NetworkUnavailable : TelegramUploadResult()
    data object Timeout : TelegramUploadResult()
    data object TooLarge : TelegramUploadResult()
    data object UnsupportedMedia : TelegramUploadResult()
    data object MediaUnavailable : TelegramUploadResult()
    data object Rejected : TelegramUploadResult()
    data object TelegramUnavailable : TelegramUploadResult()
    data object UnexpectedResponse : TelegramUploadResult()

    val configurationRejected: Boolean
        get() = this == InvalidCredentials || this == ChatUnavailable

    fun message(): String = when (this) {
        is Success -> "Backed up to Telegram."
        InvalidCredentials -> "Telegram rejected the saved bot token. Please verify your configuration again."
        ChatUnavailable -> "Telegram could not access the configured chat. Check the chat ID and bot permissions."
        NetworkUnavailable -> "No internet connection. This photo will stay in the queue."
        Timeout -> "Telegram took too long to respond. Try again."
        TooLarge -> "This photo is too large for Telegram's photo upload limit."
        UnsupportedMedia -> "This file type is not supported for Telegram photo backup."
        MediaUnavailable -> "This photo is no longer available on this device."
        Rejected -> "Telegram rejected this photo."
        TelegramUnavailable -> "Telegram is temporarily unavailable. Try again later."
        UnexpectedResponse -> "Telegram returned an unexpected response."
    }
}

class TelegramUploadService(
    context: Context,
    private val network: NetworkMonitor
) {

    private val appContext = context.applicationContext

    suspend fun uploadPhoto(
        credentials: TelegramCredentials,
        photo: TelegramPhoto
    ): TelegramUploadResult = withContext(Dispatchers.IO) {
        if (credentials.botToken.isBlank() || credentials.chatId.isBlank()) {
            return@withContext TelegramUploadResult.InvalidCredentials
        }
        if (photo.uri.isBlank()) return@withContext TelegramUploadResult.MediaUnavailable
        if (!photo.mimeType.startsWith("image/", ignoreCase = true)) {
            return@withContext TelegramUploadResult.UnsupportedMedia
        }
        if (photo.declaredSizeBytes > MAX_PHOTO_BYTES) {
            return@withContext TelegramUploadResult.TooLarge
        }
        if (photo.width > 0 && photo.height > 0 &&
            photo.width.toLong() + photo.height.toLong() > MAX_DIMENSION_SUM
        ) {
            return@withContext TelegramUploadResult.TooLarge
        }
        if (!network.isOnline()) return@withContext TelegramUploadResult.NetworkUnavailable
        upload(credentials, Uri.parse(photo.uri), photo)
    }

    private fun upload(
        credentials: TelegramCredentials,
        uri: Uri,
        photo: TelegramPhoto
    ): TelegramUploadResult {
        val source = openSource(uri) ?: return TelegramUploadResult.MediaUnavailable
        source.use { input ->
            val boundary = "MyDriveBoundary${UUID.randomUUID()}"
            val connection = try {
                (URL("$API_BASE/bot${credentials.botToken}/$METHOD")
                    .openConnection() as HttpURLConnection)
            } catch (_: Exception) {
                return TelegramUploadResult.NetworkUnavailable
            }
            return try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.useCaches = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )
                connection.setChunkedStreamingMode(CHUNK_SIZE)

                connection.outputStream.use { output ->
                    writeTextField(output, boundary, "chat_id", credentials.chatId)
                    val caption = safeCaption(photo.filename)
                    if (caption.isNotBlank()) {
                        writeTextField(output, boundary, "caption", caption)
                    }
                    writeFileHeader(
                        output = output,
                        boundary = boundary,
                        fieldName = "photo",
                        filename = safeFilename(photo),
                        contentType = photo.mimeType
                    )
                    copyPhoto(input, output)
                    writeEnd(output, boundary)
                }

                val status = connection.responseCode
                val body = readBody(connection, status)
                parse(status, body)
            } catch (_: PhotoTooLargeException) {
                TelegramUploadResult.TooLarge
            } catch (_: SocketTimeoutException) {
                TelegramUploadResult.Timeout
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: FileNotFoundException) {
                TelegramUploadResult.MediaUnavailable
            } catch (_: SecurityException) {
                TelegramUploadResult.MediaUnavailable
            } catch (_: IOException) {
                TelegramUploadResult.NetworkUnavailable
            } catch (_: Exception) {
                TelegramUploadResult.UnexpectedResponse
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

    private fun copyPhoto(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(CHUNK_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_PHOTO_BYTES) throw PhotoTooLargeException()
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
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n".toByteArray(Charsets.UTF_8))
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

    private fun parse(status: Int, body: String): TelegramUploadResult {
        if (body.isBlank()) {
            return if (status in 200..299) {
                TelegramUploadResult.UnexpectedResponse
            } else {
                mapStatus(status)
            }
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return TelegramUploadResult.UnexpectedResponse
        val ok = root.boolean("ok")
        val description = root.string("description").orEmpty()
        val errorCode = root.int("error_code") ?: status
        if (ok) {
            val messageId = root["result"]?.jsonObject?.get("message_id")?.jsonPrimitive?.longOrNull
            return if (messageId != null && messageId > 0L) {
                TelegramUploadResult.Success(messageId)
            } else {
                TelegramUploadResult.UnexpectedResponse
            }
        }
        return mapError(errorCode, description, status)
    }

    private fun mapError(errorCode: Int, description: String, status: Int): TelegramUploadResult {
        val normalized = description.lowercase()
        return when {
            status == 0 -> TelegramUploadResult.NetworkUnavailable
            errorCode == HTTP_UNAUTHORIZED -> TelegramUploadResult.InvalidCredentials
            errorCode == HTTP_FORBIDDEN && "token" in normalized -> TelegramUploadResult.InvalidCredentials
            errorCode == HTTP_FORBIDDEN -> TelegramUploadResult.ChatUnavailable
            errorCode == HTTP_BAD_REQUEST &&
                ("chat not found" in normalized || "chat_id is empty" in normalized) ->
                TelegramUploadResult.ChatUnavailable
            errorCode == HTTP_BAD_REQUEST &&
                ("too large" in normalized || "too big" in normalized) -> TelegramUploadResult.TooLarge
            errorCode == HTTP_BAD_REQUEST && "photo" in normalized -> TelegramUploadResult.UnsupportedMedia
            errorCode == HTTP_REQUEST_ENTITY_TOO_LARGE -> TelegramUploadResult.TooLarge
            errorCode == HTTP_TOO_MANY_REQUESTS -> TelegramUploadResult.TelegramUnavailable
            status >= HTTP_SERVER_ERROR -> TelegramUploadResult.TelegramUnavailable
            errorCode in HTTP_BAD_REQUEST..HTTP_UNAUTHORIZED ->
                TelegramUploadResult.Rejected
            else -> TelegramUploadResult.TelegramUnavailable
        }
    }

    private fun mapStatus(status: Int): TelegramUploadResult = when {
        status == HTTP_UNAUTHORIZED -> TelegramUploadResult.InvalidCredentials
        status == HTTP_FORBIDDEN -> TelegramUploadResult.ChatUnavailable
        status == HTTP_REQUEST_ENTITY_TOO_LARGE -> TelegramUploadResult.TooLarge
        status == HTTP_TOO_MANY_REQUESTS -> TelegramUploadResult.TelegramUnavailable
        status in HTTP_BAD_REQUEST..HTTP_UNAUTHORIZED -> TelegramUploadResult.Rejected
        else -> TelegramUploadResult.TelegramUnavailable
    }

    private fun safeFilename(photo: TelegramPhoto): String {
        val sanitized = photo.filename.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_FILENAME_LENGTH)
        if (sanitized.isNotBlank() && sanitized.contains('.')) return sanitized
        val base = sanitized.ifBlank { "photo" }
        return "$base${extensionFor(photo.mimeType)}"
    }

    private fun safeCaption(filename: String): String = filename
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(MAX_CAPTION_LENGTH)

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/heic", "image/heif" -> ".heic"
        "image/gif" -> ".gif"
        else -> ".jpg"
    }

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.longOrNull?.toInt()

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.content

    private class PhotoTooLargeException : Exception()

    companion object {
        private const val API_BASE = "https://api.telegram.org"
        private const val METHOD = "sendPhoto"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val CHUNK_SIZE = 64 * 1024
        private const val MAX_PHOTO_BYTES = 10L * 1024L * 1024L
        private const val MAX_DIMENSION_SUM = 10_000L
        private const val MAX_FILENAME_LENGTH = 120
        private const val MAX_CAPTION_LENGTH = 1024
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_REQUEST_ENTITY_TOO_LARGE = 413
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private val json = Json { ignoreUnknownKeys = true }
    }
}
