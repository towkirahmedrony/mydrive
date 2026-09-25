package com.mydrive.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.local.VaultDao
import com.mydrive.app.data.local.VaultItemEntity
import com.mydrive.app.data.vault.VaultPinCrypto
import com.mydrive.app.data.vault.VaultPinManager
import com.mydrive.app.data.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the Private Vault screen.
 *
 * The security rule this ViewModel exists to enforce: [VaultUiState.items] is **only
 * ever populated while the vault session is unlocked**, and it is cleared the moment
 * the vault locks. The screen therefore has nothing to render before
 * authentication — the locked state cannot leak a thumbnail, a file name or a count.
 *
 * Authentication reuses the existing vault layer ([VaultPinManager] for the PIN,
 * [VaultSession] for the unlocked state); nothing is re-implemented here.
 *
 * Configured vs empty is independent of whether any media is hidden:
 *  - not configured → PIN enrolment
 *  - configured + locked → biometric and/or PIN
 *  - configured + unlocked + no items → empty Private Vault
 *  - configured + unlocked + items → hidden media grid
 */
class VaultViewModel(
    private val vaultDao: VaultDao,
    private val pinManager: VaultPinManager,
    private val session: VaultSession,
    private val biometricAvailable: () -> Boolean
) : ViewModel() {

    enum class Phase {
        /** Vault has never been configured. */
        SETUP_PIN,

        /** PIN just saved; optional biometric enrolment. */
        SETUP_BIOMETRIC,

        /** Configured and locked: authentication required. */
        LOCKED,

        /** Authenticated session. */
        UNLOCKED
    }

    data class VaultUiState(
        val phase: Phase = Phase.SETUP_PIN,
        val unlocked: Boolean = false,
        /** True once a vault PIN exists; independent of whether media is hidden. */
        val pinConfigured: Boolean = false,
        val biometricHardwareAvailable: Boolean = false,
        val biometricUnlockEnabled: Boolean = false,
        val pinLength: Int = 0,
        val error: String? = null,
        val lockoutRemainingSeconds: Long = 0L,
        val items: List<VaultItemEntity> = emptyList()
    )

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var biometricPromptRequested = false
    private var biometricPromptActive = false

    /** Called when the screen appears. Never loads media while locked. */
    fun onScreenShown() {
        session.onForegrounded()
        if (session.isUnlocked()) {
            loadItems()
        } else if (_uiState.value.phase == Phase.SETUP_BIOMETRIC && pinManager.isPinConfigured()) {
            return
        } else {
            publishLocked()
        }
    }

    fun refreshConfiguration() {
        val configured = pinManager.isPinConfigured()
        val biometricHw = biometricAvailable()
        val biometricOn = pinManager.isBiometricUnlockEnabled()
        val current = _uiState.value
        val phase = when {
            current.phase == Phase.SETUP_BIOMETRIC && configured -> Phase.SETUP_BIOMETRIC
            session.isUnlocked() && configured -> Phase.UNLOCKED
            configured -> Phase.LOCKED
            else -> Phase.SETUP_PIN
        }
        _uiState.value = current.copy(
            phase = phase,
            unlocked = session.isUnlocked() && configured,
            pinConfigured = configured,
            biometricHardwareAvailable = biometricHw,
            biometricUnlockEnabled = biometricOn,
            items = if (session.isUnlocked() && configured) current.items else emptyList()
        )
    }

    fun shouldPromptBiometric(): Boolean {
        val state = _uiState.value
        if (biometricPromptRequested) return false
        if (state.phase != Phase.LOCKED) return false
        if (!state.biometricUnlockEnabled) return false
        if (!state.biometricHardwareAvailable) return false
        if (pinManager.isLockedOut()) return false
        biometricPromptRequested = true
        biometricPromptActive = true
        return true
    }

    fun onBiometricPromptActive() {
        biometricPromptActive = true
    }

    fun onBiometricPromptFinished() {
        biometricPromptActive = false
    }

    fun onPinChanged(length: Int) {
        _uiState.value = _uiState.value.copy(pinLength = length, error = null)
    }

    fun appendPinDigit(digit: Char) {
        if (!digit.isDigit()) return
        val current = pinBuffer
        if (current.length >= VaultPinCrypto.MAX_PIN_LENGTH) return
        pinBuffer = current + digit
        onPinChanged(pinBuffer.length)
    }

    fun deletePinDigit() {
        if (pinBuffer.isEmpty()) return
        pinBuffer = pinBuffer.dropLast(1)
        onPinChanged(pinBuffer.length)
    }

    fun clearPin() {
        pinBuffer = ""
        onPinChanged(0)
    }

    /** Enrols a vault PIN on first run. Does not leave the vault permanently unlocked. */
    fun setPin() {
        val pin = pinBuffer
        if (!VaultPinCrypto.isAcceptablePin(pin.toCharArray())) {
            _uiState.value = _uiState.value.copy(
                error = "Use at least ${VaultPinCrypto.MIN_PIN_LENGTH} digits"
            )
            return
        }
        val chars = pin.toCharArray()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { pinManager.setPin(chars) }
            chars.fill('\u0000')
            clearPin()
            if (ok) {
                val offerBiometric = biometricAvailable()
                if (offerBiometric) {
                    _uiState.value = _uiState.value.copy(
                        phase = Phase.SETUP_BIOMETRIC,
                        pinConfigured = true,
                        biometricHardwareAvailable = true,
                        biometricUnlockEnabled = false,
                        error = null,
                        unlocked = false,
                        items = emptyList()
                    )
                } else {
                    pinManager.setBiometricUnlockEnabled(false)
                    unlockAndLoad()
                }
            } else {
                _uiState.value = _uiState.value.copy(error = "Could not save the vault PIN")
            }
        }
    }

    fun enableBiometricFromSetup() {
        if (!pinManager.isPinConfigured()) return
        pinManager.setBiometricUnlockEnabled(true)
        unlockAndLoad()
    }

    fun skipBiometricFromSetup() {
        if (!pinManager.isPinConfigured()) return
        pinManager.setBiometricUnlockEnabled(false)
        unlockAndLoad()
    }

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        if (!session.isUnlocked() || !pinManager.isPinConfigured()) return
        if (enabled && !biometricAvailable()) return
        pinManager.setBiometricUnlockEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            biometricHardwareAvailable = biometricAvailable()
        )
    }

    /**
     * Verifies the entered PIN and, on success, unlocks the session and loads items.
     *
     * The PIN is passed as a CharArray and wiped afterwards; it is never stored in
     * state, never logged and never placed in a saved-state bundle.
     */
    fun submitPin() {
        if (pinBuffer.isEmpty()) return
        val chars = pinBuffer.toCharArray()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { pinManager.verify(chars) }
            chars.fill('\u0000')
            clearPin()
            when (result) {
                is VaultPinManager.VerifyResult.Success -> unlockAndLoad()
                is VaultPinManager.VerifyResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        error = "Incorrect PIN. ${result.attemptsRemaining} attempt(s) left."
                    )
                }
                is VaultPinManager.VerifyResult.LockedOut -> {
                    _uiState.value = _uiState.value.copy(
                        error = "Too many attempts. Try again later.",
                        lockoutRemainingSeconds = result.remainingMillis / 1000L
                    )
                }
                VaultPinManager.VerifyResult.NotConfigured ->
                    _uiState.value = _uiState.value.copy(
                        phase = Phase.SETUP_PIN,
                        pinConfigured = false,
                        error = "No vault PIN is set"
                    )
                VaultPinManager.VerifyResult.InvalidInput ->
                    _uiState.value = _uiState.value.copy(error = "Enter your vault PIN")
            }
        }
    }

    /**
     * Called by the UI after a successful biometric result. The UI only ever passes a
     * success/failure outcome — the app never sees biometric data.
     */
    fun onBiometricAuthenticated() {
        if (!pinManager.isPinConfigured()) return
        unlockAndLoad()
    }

    fun onBiometricUnavailable() {
        biometricPromptRequested = true
        _uiState.value = _uiState.value.copy(
            error = null,
            biometricHardwareAvailable = false
        )
    }

    fun onBiometricCancelled() {
        biometricPromptRequested = true
    }

    fun onBiometricLockout() {
        biometricPromptRequested = true
        _uiState.value = _uiState.value.copy(
            error = "Biometric unavailable. Enter your vault PIN."
        )
    }

    /**
     * The app left the foreground (or the user tapped Lock): drop the session and the
     * loaded items immediately, so nothing vaulted stays in memory or on screen.
     */
    fun onBackgrounded() {
        if (biometricPromptActive) return
        session.onBackgrounded()
        clearPin()
        biometricPromptRequested = false
        publishLocked()
    }

    fun onLockRequested() = onBackgrounded()

    private fun unlockAndLoad() {
        session.unlock()
        loadItems()
    }

    private fun loadItems() {
        if (!session.isUnlocked() || !pinManager.isPinConfigured()) {
            publishLocked()
            return
        }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { vaultDao.readyItems() }.getOrDefault(emptyList())
            }
            _uiState.value = if (session.isUnlocked()) {
                _uiState.value.copy(
                    phase = Phase.UNLOCKED,
                    unlocked = true,
                    pinConfigured = true,
                    biometricHardwareAvailable = biometricAvailable(),
                    biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
                    items = items,
                    error = null
                )
            } else {
                lockedState()
            }
        }
    }

    private fun publishLocked() {
        _uiState.value = lockedState()
    }

    private fun lockedState(): VaultUiState {
        val configured = pinManager.isPinConfigured()
        return _uiState.value.copy(
            phase = if (configured) Phase.LOCKED else Phase.SETUP_PIN,
            unlocked = false,
            pinConfigured = configured,
            biometricHardwareAvailable = biometricAvailable(),
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            items = emptyList()
        )
    }

    private fun initialState(): VaultUiState {
        val configured = pinManager.isPinConfigured()
        return VaultUiState(
            phase = if (configured) Phase.LOCKED else Phase.SETUP_PIN,
            unlocked = false,
            pinConfigured = configured,
            biometricHardwareAvailable = biometricAvailable(),
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled()
        )
    }

    override fun onCleared() {
        clearPin()
        super.onCleared()
    }

    /** Held only between keystrokes and wiped after verification. */
    private var pinBuffer: String = ""

    companion object {
        fun factory(
            vaultDao: VaultDao,
            pinManager: VaultPinManager,
            session: VaultSession,
            biometricAvailable: () -> Boolean
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VaultViewModel(vaultDao, pinManager, session, biometricAvailable) as T
                }
            }
    }
}
