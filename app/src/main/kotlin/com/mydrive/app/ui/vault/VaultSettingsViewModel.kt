package com.mydrive.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.vault.VaultPinManager
import com.mydrive.app.data.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backing state for the dedicated Vault Settings screen.
 *
 * This is deliberately separate from [VaultViewModel]: the media gallery is a
 * browsing surface and must not grow PIN forms and security toggles. Settings here
 * are read and written through the existing vault layer only — no new credential
 * storage is introduced, and the PIN is still never stored or logged.
 */
class VaultSettingsViewModel(
    private val pinManager: VaultPinManager,
    private val session: VaultSession,
    private val biometricAvailable: () -> Boolean
) : ViewModel() {

    data class SettingsUiState(
        val pinConfigured: Boolean = false,
        val biometricHardwareAvailable: Boolean = false,
        val biometricUnlockEnabled: Boolean = false,
        val lockTimeoutSeconds: Int = VaultSession.DEFAULT_TIMEOUT_SECONDS,
        /** True while the two-step "change PIN" flow is on screen. */
        val editingPin: Boolean = false,
        val message: String? = null,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(load())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun load(): SettingsUiState {
        session.timeoutSeconds = pinManager.lockTimeoutSeconds()
        return SettingsUiState(
            pinConfigured = pinManager.isPinConfigured(),
            biometricHardwareAvailable = biometricAvailable(),
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            lockTimeoutSeconds = pinManager.lockTimeoutSeconds()
        )
    }

    fun onScreenShown() {
        val editingPin = _uiState.value.editingPin
        _uiState.value = load().copy(editingPin = editingPin)
    }

    // ── PIN change ───────────────────────────────────────────────────────────

    fun beginChangePin() {
        _uiState.value = _uiState.value.copy(editingPin = true, error = null, message = null)
    }

    fun cancelChangePin() {
        _uiState.value = _uiState.value.copy(editingPin = false, error = null)
    }

    /**
     * Replaces the Vault PIN from a CharArray, wiping the caller's buffer once the
     * existing [VaultPinManager] has derived and sealed the new verifier.
     */
    fun changePin(pin: CharArray) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { pinManager.setPin(pin) }
            pin.fill('\u0000')
            _uiState.value = if (ok) {
                _uiState.value.copy(
                    editingPin = false,
                    pinConfigured = true,
                    error = null,
                    message = "Vault PIN updated"
                )
            } else {
                _uiState.value.copy(
                    editingPin = false,
                    error = "Could not update the Vault PIN"
                )
            }
        }
    }

    // ── biometric ────────────────────────────────────────────────────────────

    fun setBiometricEnabled(enabled: Boolean) {
        if (enabled && !biometricAvailable()) return
        pinManager.setBiometricUnlockEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            biometricHardwareAvailable = biometricAvailable(),
            error = null,
            message = if (enabled) "Biometric unlock enabled" else "Biometric unlock disabled"
        )
    }

    // ── lock timeout ─────────────────────────────────────────────────────────

    fun setLockTimeoutSeconds(seconds: Int) {
        pinManager.setLockTimeoutSeconds(seconds)
        session.timeoutSeconds = pinManager.lockTimeoutSeconds()
        _uiState.value = _uiState.value.copy(lockTimeoutSeconds = pinManager.lockTimeoutSeconds())
    }

    companion object {
        /**
         * Foreground inactivity options. The vault always locks when the app leaves
         * the foreground regardless of this setting (existing policy), so these only
         * bound how long an actively-idle vault stays open.
         */
        val LOCK_TIMEOUT_OPTIONS = listOf(
            LockTimeoutOption(30, "After 30 seconds"),
            LockTimeoutOption(60, "After 1 minute"),
            LockTimeoutOption(300, "After 5 minutes"),
            LockTimeoutOption(900, "After 15 minutes")
        )

        fun factory(
            pinManager: VaultPinManager,
            session: VaultSession,
            biometricAvailable: () -> Boolean
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VaultSettingsViewModel(pinManager, session, biometricAvailable) as T
                }
            }
    }
}

data class LockTimeoutOption(val seconds: Int, val label: String)
