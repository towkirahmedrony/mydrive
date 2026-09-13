package com.mydrive.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.model.BackupPreferences
import com.mydrive.app.data.model.TelegramSettings
import com.mydrive.app.data.model.UserProfile
import com.mydrive.app.data.repository.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SettingsUiState(
    val profile: UserProfile,
    val preferences: BackupPreferences,
    val telegram: TelegramSettings
)

class SettingsViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.profile,
        repository.preferences,
        repository.telegram
    ) { profile, preferences, telegram ->
        SettingsUiState(profile, preferences, telegram)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            profile = repository.profile.value,
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

    companion object {
        fun factory(repository: MediaRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(repository) as T
                }
            }
    }
}
