package com.mydrive.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.local.VaultDao
import com.mydrive.app.data.local.VaultItemEntity
import com.mydrive.app.data.vault.VaultBiometricGate
import com.mydrive.app.data.vault.VaultPinManager
import com.mydrive.app.data.vault.VaultPinCrypto
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
 */
class VaultViewModel(
    private val vaultDao: VaultDao,
    private val pinManager: VaultPinManager,
    private val session: VaultSession,
    private val biometricAvailable: () -> Boolean = { false }
) : ViewModel() {

    data class VaultUiState(
        val unlocked: Boolean = false,
        /** True once a vault PIN exists; false means the first-run enrolment screen. */
        val pinConfigured: Boolean = false,
        /** Reported only so the UI can explain the fallback; never a biometric result. */
        val biometricOffered: Boolean = false,
        val pinLength: Int = 0,
        val error: String? = null,
        val lockoutRemainingSeconds: Long = 0L,
        /** Empty while locked — by construction, not by UI filtering. */
        val items: List<VaultItemEntity> = emptyList()
    )

    private val _uiState = MutableStateFlow(
        VaultUiState(
            unlocked = false,
            pinConfigured = pinManager.isPinConfigured(),
            biometricOffered = biometricAvailable()
        )
    )
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    /** Called when the screen appears. Never loads media while locked. */
    fun onScreenShown() {
        session.onForegrounded()
        if (session.isUnlocked()) loadItems() else publishLocked()
    }

    /** Freshly re-read configuration, e.g. after enrolling a PIN. */
    fun refreshConfiguration() {
        _uiState.value = _uiState.value.copy(
            pinConfigured = pinManager.isPinConfigured(),
            biometricOffered = biometricAvailable()
        )
    }

    // ── PIN entry (the fallback path, and the only path until biometric is wired) ──

    fun onPinChanged(length: Int) {
        _uiState.value = _uiState.value.copy(pinLength = length, error = null)
    }

    fun appendPinDigit(digit: Char) {
        val current = pinBuffer
        if (current.length >= MAX_PIN_LENGTH) return
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

    /** Enrols a vault PIN on first run. */
    fun setPin() {
        val pin = pinBuffer
        if (pin.length < VaultPinCrypto.MIN_PIN_LENGTH) {
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
                refreshConfiguration()
                _uiState.value = _uiState.value.copy(error = null)
            } else {
                _uiState.value = _uiState.value.copy(error = "Could not save the vault PIN")
            }
        }
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
                    _uiState.value = _uiState.value.copy(error = "No vault PIN is set")
                VaultPinManager.VerifyResult.InvalidInput ->
                    _uiState.value = _uiState.value.copy(error = "Enter your vault PIN")
            }
        }
    }

    /**
     * Called by the UI after a successful biometric result. The UI only ever passes a
     * success/failure outcome — the app never sees biometric data.
     */
    fun onBiometricAuthenticated() = unlockAndLoad()

    fun onBiometricUnavailable(reason: String) {
        _uiState.value = _uiState.value.copy(error = reason, biometricOffered = false)
    }

    /**
     * The app left the foreground (or the user tapped Lock): drop the session and the
     * loaded items immediately, so nothing vaulted stays in memory or on screen.
     */
    fun onBackgrounded() {
        session.onBackgrounded()
        clearPin()
        publishLocked()
    }

    fun onLockRequested() = onBackgrounded()

    private fun unlockAndLoad() {
        session.unlock()
        loadItems()
    }

    private fun loadItems() {
        if (!session.isUnlocked()) {
            publishLocked()
            return
        }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { vaultDao.readyItems() }.getOrDefault(emptyList())
            }
            // Re-check: if the session locked while loading, publish nothing.
            _uiState.value = if (session.isUnlocked()) {
                _uiState.value.copy(unlocked = true, items = items, error = null)
            } else {
                _uiState.value.copy(unlocked = false, items = emptyList())
            }
        }
    }

    private fun publishLocked() {
        _uiState.value = _uiState.value.copy(unlocked = false, items = emptyList())
    }

    override fun onCleared() {
        clearPin()
        super.onCleared()
    }

    /** Held only between keystrokes and wiped after verification. */
    private var pinBuffer: String = ""

    companion object {
        private const val MAX_PIN_LENGTH = 12

        fun factory(
            vaultDao: VaultDao,
            pinManager: VaultPinManager,
            session: VaultSession,
            biometricAvailable: () -> Boolean = { false }
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VaultViewModel(vaultDao, pinManager, session, biometricAvailable) as T
                }
            }
    }
}
