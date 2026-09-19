package com.mydrive.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.local.TelegramSettingsStore
import com.mydrive.app.data.auth.AuthState
import com.mydrive.app.data.model.BackupPreferences
import com.mydrive.app.data.model.TelegramConnectionState
import com.mydrive.app.data.model.TelegramSettings
import com.mydrive.app.data.model.UserProfile
import com.mydrive.app.data.repository.AuthRepository
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.debug.DeveloperModeStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val profile: UserProfile,
    val preferences: BackupPreferences,
    val telegram: TelegramSettings
)

data class TelegramSetupFormState(
    val botToken: String = "",
    val chatId: String = "",
    val enabled: Boolean = false,
    val tokenVisible: Boolean = false
) {
    val chatIdValid: Boolean
        get() = chatId.isBlank() || TelegramSettingsStore.isValidChatId(chatId)
}

class SettingsViewModel(
    private val repository: MediaRepository,
    private val authRepository: AuthRepository,
    private val developerModeStore: DeveloperModeStore? = null
) : ViewModel() {
    private var telegramTestJob: Job? = null
    private var aboutTapCount = 0
    private var lastAboutTapAt = 0L

    private val _telegramForm = MutableStateFlow(
        TelegramSetupFormState(
            chatId = repository.telegram.value.chatId,
            enabled = repository.telegram.value.enabled
        )
    )
    val telegramForm: StateFlow<TelegramSetupFormState> = _telegramForm.asStateFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.state,
        repository.preferences,
        repository.telegram,
        developerModeStore?.enabled ?: MutableStateFlow(false)
    ) { authState, preferences, telegram, developerEnabled ->
        SettingsUiState(
            profile = when (authState) {
                is AuthState.Authenticated -> authState.profile.toUserProfile()
                is AuthState.Suspended -> authState.profile.toUserProfile()
                else -> UserProfile()
            },
            preferences = preferences,
            telegram = telegram
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            profile = when (val authState = authRepository.state.value) {
                is AuthState.Authenticated -> authState.profile.toUserProfile()
                is AuthState.Suspended -> authState.profile.toUserProfile()
                else -> UserProfile()
            },
            preferences = repository.preferences.value,
            telegram = repository.telegram.value
        )
    )

    init {
        viewModelScope.launch {
            repository.telegram.collect { settings ->
                _telegramForm.update {
                    it.copy(
                        chatId = settings.chatId,
                        enabled = settings.enabled
                    )
                }
            }
        }
    }

    fun setAutomaticBackup(value: Boolean) {
        repository.updatePreferences { it.copy(automaticBackup = value) }
    }

    fun setBackupPhotos(value: Boolean) {
        repository.updatePreferences { it.copy(backupPhotos = value) }
    }

    fun setBackupVideos(value: Boolean) {
        repository.updatePreferences { it.copy(backupVideos = value) }
    }

    fun setWifiOnly(value: Boolean) {
        repository.updatePreferences { it.copy(wifiOnly = value) }
    }

    fun setUploadWhileCharging(value: Boolean) {
        repository.updatePreferences { it.copy(uploadWhileCharging = value) }
    }

    fun updateTelegramBotToken(value: String) {
        _telegramForm.update { it.copy(botToken = value) }
    }

    fun updateTelegramChatId(value: String) {
        _telegramForm.update { it.copy(chatId = value) }
    }

    fun setTelegramTokenVisible(value: Boolean) {
        _telegramForm.update { it.copy(tokenVisible = value) }
    }

    fun setTelegramBackupEnabled(value: Boolean) {
        val form = _telegramForm.value.copy(enabled = value)
        val currentTelegram = uiState.value.telegram
        _telegramForm.value = form
        if (form.botToken.isBlank() && form.chatId.trim() == currentTelegram.chatId) {
            repository.setTelegramBackupEnabled(value)
        } else {
            repository.saveTelegramConfiguration(
                botToken = form.botToken.takeIf { it.isNotBlank() },
                chatId = form.chatId,
                enabled = value
            )
            _telegramForm.update { it.copy(botToken = "", tokenVisible = false) }
        }
    }

    fun saveTelegramConfiguration() {
        val form = _telegramForm.value
        repository.saveTelegramConfiguration(
            botToken = form.botToken.takeIf { it.isNotBlank() },
            chatId = form.chatId,
            enabled = form.enabled
        )
        _telegramForm.update { it.copy(botToken = "", tokenVisible = false) }
    }

    fun testTelegramConnection() {
        if (telegramTestJob?.isActive == true) return

        val form = _telegramForm.value
        val currentTelegram = uiState.value.telegram
        val hasToken = form.botToken.isNotBlank() || currentTelegram.tokenConfigured
        val chatId = form.chatId.trim()
        if (!hasToken) {
            repository.markTelegramConnectionFailed("Bot Token is required.")
            return
        }
        if (chatId.isBlank()) {
            repository.markTelegramConnectionFailed("Chat ID is required.")
            return
        }
        if (!TelegramSettingsStore.isValidChatId(chatId)) {
            repository.markTelegramConnectionFailed("Invalid Chat ID.")
            return
        }
        if (currentTelegram.connectionState == TelegramConnectionState.TESTING) return

        saveTelegramConfiguration()
        telegramTestJob = viewModelScope.launch {
            repository.testTelegramConnection()
        }
    }

    fun clearTelegramConfiguration() {
        repository.clearTelegramConfiguration()
        _telegramForm.value = TelegramSetupFormState()
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun onAboutTapped() {
        val now = System.currentTimeMillis()
        if (now - lastAboutTapAt > 2_000L) aboutTapCount = 0
        lastAboutTapAt = now
        aboutTapCount += 1
        if (aboutTapCount >= 7) {
            aboutTapCount = 0
            developerModeStore?.enable()
        }
    }

    companion object {
        fun factory(
            repository: MediaRepository,
            authRepository: AuthRepository,
            developerModeStore: DeveloperModeStore? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(repository, authRepository, developerModeStore) as T
                }
            }
    }
}
