package com.mydrive.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.auth.AuthState
import com.mydrive.app.data.model.BackupPreferences
import com.mydrive.app.data.model.TelegramSettings
import com.mydrive.app.data.model.UserProfile
import com.mydrive.app.data.repository.AuthRepository
import com.mydrive.app.data.repository.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val profile: UserProfile,
    val preferences: BackupPreferences,
    val telegram: TelegramSettings
)

class SettingsViewModel(
    private val repository: MediaRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        authRepository.state,
        repository.preferences,
        repository.telegram
    ) { authState, preferences, telegram ->
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

    fun connectTelegram() {
        repository.connectTelegram()
    }

    fun disconnectTelegram() {
        repository.disconnectTelegram()
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    companion object {
        fun factory(
            repository: MediaRepository,
            authRepository: AuthRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(repository, authRepository) as T
                }
            }
    }
}
