package com.mydrive.app.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.content
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TelegramApiVerifier(
    private val network: NetworkMonitor
) {

    suspend fun verify(botToken: String, chatId: String): TelegramVerificationResult {
        val cleanedToken = botToken.trim()
        val cleanedChatId = chatId.trim()
        if (cleanedToken.isBlank()) return TelegramVerificationResult.InvalidBotToken
        if (!BOT_TOKEN_FORMAT.matches(cleanedToken)) return TelegramVerificationResult.InvalidBotToken
        if (!isValidChatId(cleanedChatId)) return TelegramVerificationResult.InvalidChatId
        if (!network.isOnline()) return TelegramVerificationResult.NetworkUnavailable

        return withContext(Dispatchers.IO) {
            val tokenResult = request(cleanedToken, "getMe")
            if (!tokenResult.ok) {
                return@withContext tokenResult.toVerificationResult(tokenRequest = true)
            }

            val chatResult = request(
                botToken = cleanedToken,
                method = "getChat",
                query = "chat_id=${URLEncoder.encode(cleanedChatId, Charsets.UTF_8.name())}"
            )
            if (chatResult.ok) {
                TelegramVerificationResult.Success
            } else {
                chatResult.toVerificationResult(tokenRequest = false)
            }
        }
    }

    private fun request(botToken: String, method: String, query: String = ""): TelegramApiResponse {
        val suffix = if (query.isBlank()) "" else "?$query"
        val connection = (URL("$API_BASE/bot$botToken/$method$suffix").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            parseResponse(status, body)
        } catch (_: SocketTimeoutException) {
            TelegramApiResponse(ok = false, statusCode = 0, errorCode = null, description = "timeout")
        } catch (_: IOException) {
            TelegramApiResponse(ok = false, statusCode = 0, errorCode = null, description = "network")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            TelegramApiResponse(ok = false, statusCode = 0, errorCode = null, description = "unavailable")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(status: Int, body: String): TelegramApiResponse {
        if (body.isBlank()) return TelegramApiResponse(ok = false, statusCode = status, errorCode = status, description = "")
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            TelegramApiResponse(
                ok = root.boolean("ok"),
                statusCode = status,
                errorCode = root.int("error_code") ?: status,
                description = root.string("description").orEmpty()
            )
        }.getOrElse {
            TelegramApiResponse(ok = false, statusCode = status, errorCode = status, description = "")
        }
    }

    private fun TelegramApiResponse.toVerificationResult(tokenRequest: Boolean): TelegramVerificationResult {
        val normalizedDescription = description.lowercase()
        if (description == "timeout") return TelegramVerificationResult.TelegramUnavailable
        if (description == "network") return TelegramVerificationResult.NetworkUnavailable
        if (description == "unavailable") return TelegramVerificationResult.TelegramUnavailable
        if (tokenRequest && (statusCode == HTTP_UNAUTHORIZED || errorCode == HTTP_UNAUTHORIZED)) {
            return TelegramVerificationResult.InvalidBotToken
        }
        if (!tokenRequest && errorCode == HTTP_BAD_REQUEST && "chat not found" in normalizedDescription) {
            return TelegramVerificationResult.ChatNotFound
        }
        if (!tokenRequest && errorCode == HTTP_FORBIDDEN) {
            return TelegramVerificationResult.BotCannotAccessChat
        }
        if (statusCode == 0 || statusCode >= HTTP_SERVER_ERROR) {
            return TelegramVerificationResult.TelegramUnavailable
        }
        return if (tokenRequest) {
            TelegramVerificationResult.InvalidBotToken
        } else {
            TelegramVerificationResult.BotCannotAccessChat
        }
    }

    private fun JsonObject.boolean(name: String): Boolean {
        return this[name]?.jsonPrimitive?.booleanOrNull ?: false
    }

    private fun JsonObject.int(name: String): Int? {
        return this[name]?.jsonPrimitive?.intOrNull
    }

    private fun JsonObject.string(name: String): String? {
        return this[name]?.jsonPrimitive?.content
    }

    private data class TelegramApiResponse(
        val ok: Boolean,
        val statusCode: Int,
        val errorCode: Int?,
        val description: String
    )

    companion object {
        private const val API_BASE = "https://api.telegram.org"
        private const val TIMEOUT_MS = 12_000
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_SERVER_ERROR = 500
        private val BOT_TOKEN_FORMAT = Regex("\\d{6,20}:[A-Za-z0-9_-]{20,}")
        private val json = Json { ignoreUnknownKeys = true }

        fun isValidChatId(chatId: String): Boolean {
            val value = chatId.trim()
            return value.matches(Regex("-?\\d{5,20}")) || value.matches(Regex("@[A-Za-z0-9_]{5,32}"))
        }
    }
}

sealed class TelegramVerificationResult {
    data object Success : TelegramVerificationResult()
    data object InvalidBotToken : TelegramVerificationResult()
    data object InvalidChatId : TelegramVerificationResult()
    data object ChatNotFound : TelegramVerificationResult()
    data object BotCannotAccessChat : TelegramVerificationResult()
    data object NetworkUnavailable : TelegramVerificationResult()
    data object TelegramUnavailable : TelegramVerificationResult()

    fun message(): String = when (this) {
        Success -> "Telegram connection verified."
        InvalidBotToken -> "Invalid Bot Token."
        InvalidChatId -> "Invalid Chat ID."
        ChatNotFound -> "Chat ID not found."
        BotCannotAccessChat -> "Bot cannot access this chat."
        NetworkUnavailable -> "Network unavailable."
        TelegramUnavailable -> "Telegram service unavailable."
    }
}
