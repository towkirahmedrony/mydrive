package com.mydrive.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mydrive.app.data.model.TelegramConnectionState
import com.mydrive.app.data.model.TelegramSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TelegramCredentials(
    val botToken: String,
    val chatId: String
)

class TelegramSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<TelegramSettings> = _settings.asStateFlow()

    @Synchronized
    fun saveConfiguration(botToken: String?, chatId: String, enabled: Boolean) {
        val cleanedToken = botToken?.trim().orEmpty()
        val cleanedChatId = chatId.trim()
        val hasExistingToken = hasStoredToken()
        val tokenConfigured = cleanedToken.isNotBlank() || hasExistingToken
        val state = stateFor(
            enabled = enabled,
            tokenConfigured = tokenConfigured,
            chatId = cleanedChatId,
            fallback = TelegramConnectionState.NOT_TESTED
        )

        prefs.edit().apply {
            putBoolean(KEY_ENABLED, enabled)
            putString(KEY_CHAT_ID, encrypt(cleanedChatId))
            putString(KEY_CONNECTION_STATE, state.name)
            putString(KEY_CONNECTION_MESSAGE, "")
            if (cleanedToken.isNotBlank()) {
                putString(KEY_BOT_TOKEN, encrypt(cleanedToken))
            }
        }.apply()
        _settings.value = readSettings()
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val current = readSettings()
        val state = stateFor(
            enabled = enabled,
            tokenConfigured = current.tokenConfigured,
            chatId = current.chatId,
            fallback = current.connectionState
        )
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_CONNECTION_STATE, state.name)
            .putString(KEY_CONNECTION_MESSAGE, "")
            .apply()
        _settings.value = readSettings()
    }

    @Synchronized
    fun markTesting() {
        _settings.update {
            it.copy(
                connectionState = TelegramConnectionState.TESTING,
                connectionMessage = "Testing connection..."
            )
        }
    }

    @Synchronized
    fun markConnected() {
        prefs.edit()
            .putString(KEY_CONNECTION_STATE, TelegramConnectionState.CONNECTED.name)
            .putString(KEY_CONNECTION_MESSAGE, "Telegram connection verified.")
            .apply()
        _settings.value = readSettings()
    }

    @Synchronized
    fun markNotTested() {
        prefs.edit()
            .putString(KEY_CONNECTION_STATE, TelegramConnectionState.NOT_TESTED.name)
            .putString(KEY_CONNECTION_MESSAGE, "Configuration has not been verified.")
            .apply()
        _settings.value = readSettings()
    }

    @Synchronized
    fun markConnectionFailed(message: String) {
        prefs.edit()
            .putString(KEY_CONNECTION_STATE, TelegramConnectionState.FAILED.name)
            .putString(KEY_CONNECTION_MESSAGE, message)
            .apply()
        _settings.update {
            it.copy(
                connectionState = TelegramConnectionState.FAILED,
                connectionMessage = message
            )
        }
    }

    @Synchronized
    fun credentials(): TelegramCredentials? {
        val token = decrypt(prefs.getString(KEY_BOT_TOKEN, null)).orEmpty()
        val chatId = decrypt(prefs.getString(KEY_CHAT_ID, null)).orEmpty()
        if (token.isBlank() || chatId.isBlank()) return null
        return TelegramCredentials(botToken = token, chatId = chatId)
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _settings.value = readSettings()
    }

    private fun readSettings(): TelegramSettings {
        val tokenConfigured = hasStoredToken()
        val chatId = decrypt(prefs.getString(KEY_CHAT_ID, null)).orEmpty()
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val storedState = prefs.getString(KEY_CONNECTION_STATE, null)
            ?.let { runCatching { TelegramConnectionState.valueOf(it) }.getOrNull() }
        val storedMessage = prefs.getString(KEY_CONNECTION_MESSAGE, "").orEmpty()
        val state = stateFor(
            enabled = enabled,
            tokenConfigured = tokenConfigured,
            chatId = chatId,
            fallback = storedState ?: TelegramConnectionState.NOT_CONFIGURED
        )
        return TelegramSettings(
            enabled = enabled,
            tokenConfigured = tokenConfigured,
            botTokenMasked = if (tokenConfigured) MASKED_TOKEN else "",
            chatId = chatId,
            connectionState = state,
            connectionMessage = messageFor(state, storedMessage)
        )
    }

    private fun stateFor(
        enabled: Boolean,
        tokenConfigured: Boolean,
        chatId: String,
        fallback: TelegramConnectionState
    ): TelegramConnectionState {
        if (!tokenConfigured && chatId.isBlank()) return TelegramConnectionState.NOT_CONFIGURED
        if (enabled && (!tokenConfigured || !isValidChatId(chatId))) return TelegramConnectionState.INCOMPLETE
        return when (fallback) {
            TelegramConnectionState.CONNECTED,
            TelegramConnectionState.FAILED,
            TelegramConnectionState.NOT_TESTED -> fallback
            TelegramConnectionState.TESTING -> TelegramConnectionState.NOT_TESTED
            TelegramConnectionState.NOT_CONFIGURED,
            TelegramConnectionState.INCOMPLETE -> TelegramConnectionState.NOT_TESTED
        }
    }

    private fun messageFor(state: TelegramConnectionState, storedMessage: String): String {
        if (storedMessage.isNotBlank()) return storedMessage
        return when (state) {
            TelegramConnectionState.NOT_CONFIGURED -> "Add a bot token and chat ID."
            TelegramConnectionState.INCOMPLETE -> "Configuration is incomplete."
            TelegramConnectionState.NOT_TESTED -> "Configuration has not been verified."
            TelegramConnectionState.TESTING -> "Testing connection..."
            TelegramConnectionState.CONNECTED -> "Telegram connection verified."
            TelegramConnectionState.FAILED -> "Connection failed."
        }
    }

    private fun hasStoredToken(): Boolean = !prefs.getString(KEY_BOT_TOKEN, null).isNullOrBlank()

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, cipherText)
            .joinToString(separator = PART_SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val parts = value.split(PART_SEPARATOR)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "telegram_settings"
        private const val KEY_ALIAS = "mydrive_telegram_settings_key"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_CONNECTION_STATE = "connection_state"
        private const val KEY_CONNECTION_MESSAGE = "connection_message"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PART_SEPARATOR = ":"
        private const val MASKED_TOKEN = "**********"

        fun isValidChatId(chatId: String): Boolean {
            val value = chatId.trim()
            return value.matches(Regex("-?\\d{5,20}")) || value.matches(Regex("@[A-Za-z0-9_]{5,32}"))
        }
    }
}
