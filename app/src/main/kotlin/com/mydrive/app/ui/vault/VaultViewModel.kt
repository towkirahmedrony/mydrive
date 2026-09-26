package com.mydrive.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.local.VaultDao
import com.mydrive.app.data.local.VaultItemEntity
import com.mydrive.app.data.vault.VaultBiometricGate
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
 * State for the Private Vault.
 *
 * Security contract this ViewModel exists to enforce:
 *  - [VaultUiState.items] is **only** ever populated in [Phase.UNLOCKED]. Every
 *    other phase — including [Phase.LOCKED] and [Phase.AUTHENTICATING], which is
 *    what is on screen while the system biometric prompt is up — carries an empty
 *    list, and the row is read from [VaultDao] only after [VaultSession] reports
 *    unlocked. The locked screen therefore has no thumbnail, file name, count or
 *    preview it *could* render, even for a frame;
 *  - a lock ([onBackgrounded], [onLockRequested], timeout) tears down first
 *    ([Phase.LOCKING]), purges any decrypted transient preview, then publishes
 *    [Phase.LOCKED] — the teardown order matters, so the media never lingers;
 *  - authentication is delegated to the existing vault layer ([VaultPinManager]
 *    for the PIN, [VaultBiometricGate]/`BiometricPrompt` for biometrics, and
 *    [VaultSession] for authorisation). Nothing is re-implemented here and no
 *    biometric data is ever read.
 *
 * Configured vs empty stays independent of whether any media is hidden:
 *  - not configured           → PIN enrolment
 *  - PIN just created         → optional biometric enrolment
 *  - configured + locked      → biometric and/or PIN
 *  - authenticating           → locked background, no media
 *  - configured + unlocked    → hidden media gallery
 */
class VaultViewModel(
    private val vaultDao: VaultDao,
    private val pinManager: VaultPinManager,
    private val session: VaultSession,
    private val biometricAvailable: () -> Boolean,
    /** Purges any decrypted transient preview files held while unlocked. */
    private val clearPreviews: () -> Unit = {}
) : ViewModel() {

    enum class Phase {
        /** Vault has never been configured. */
        SETUP_PIN,

        /** PIN just saved; optional biometric enrolment. */
        SETUP_BIOMETRIC,

        /** Configured and locked: authentication required. No media is loaded. */
        LOCKED,

        /** The system biometric prompt is up over the locked background. */
        AUTHENTICATING,

        /** Authenticated session: the media gallery may be composed. */
        UNLOCKED,

        /** Transient teardown state while decrypted state is released. */
        LOCKING
    }

    data class VaultUiState(
        val phase: Phase = Phase.SETUP_PIN,
        /** True once a vault PIN exists; independent of whether media is hidden. */
        val pinConfigured: Boolean = false,
        val biometricHardwareAvailable: Boolean = false,
        val biometricUnlockEnabled: Boolean = false,
        val lockTimeoutSeconds: Int = VaultSession.DEFAULT_TIMEOUT_SECONDS,
        val error: String? = null,
        val lockoutRemainingSeconds: Long = 0L,
        /** Never populated outside [Phase.UNLOCKED]. */
        val items: List<VaultItemEntity> = emptyList()
    ) {
        val isUnlocked: Boolean get() = phase == Phase.UNLOCKED
    }

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    /** True once the automatic prompt has been offered for this lock cycle. */
    private var biometricPromptRequested = false

    /** True while the system prompt is up, so backgrounding does not double-lock. */
    private var biometricPromptActive = false

    init {
        session.timeoutSeconds = pinManager.lockTimeoutSeconds()
    }

    /**
     * Called when the screen appears. Never loads media unless the session is
     * already unlocked per the existing lock policy.
     */
    fun onScreenShown() {
        session.timeoutSeconds = pinManager.lockTimeoutSeconds()
        session.onForegrounded()
        val current = _uiState.value
        when {
            current.phase == Phase.SETUP_BIOMETRIC && pinManager.isPinConfigured() -> refreshConfig()
            session.isUnlocked() && pinManager.isPinConfigured() -> loadItems()
            else -> publishLocked()
        }
    }

    // ── setup ────────────────────────────────────────────────────────────────

    /** Enrols the first vault PIN. Does not leave the vault permanently unlocked. */
    fun setPin(pin: CharArray) {
        if (!VaultPinCrypto.isAcceptablePin(pin)) {
            _uiState.value = _uiState.value.copy(
                error = "Use at least ${VaultPinCrypto.MIN_PIN_LENGTH} digits"
            )
            pin.fill('\u0000')
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { pinManager.setPin(pin) }
            pin.fill('\u0000')
            if (!ok) {
                _uiState.value = _uiState.value.copy(error = "Could not save the Vault PIN")
                return@launch
            }
            if (biometricAvailable()) {
                _uiState.value = _uiState.value.copy(
                    phase = Phase.SETUP_BIOMETRIC,
                    pinConfigured = true,
                    biometricHardwareAvailable = true,
                    biometricUnlockEnabled = false,
                    error = null,
                    items = emptyList()
                )
            } else {
                pinManager.setBiometricUnlockEnabled(false)
                unlockAndLoad()
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

    // ── authentication ───────────────────────────────────────────────────────

    /**
     * Verifies the entered PIN and, on success, unlocks the session and loads the
     * gallery. The PIN is passed as a CharArray and wiped afterwards; it is never
     * stored in state, never logged and never placed in a saved-state bundle.
     */
    fun submitPin(pin: CharArray) {
        if (pin.isEmpty()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { pinManager.verify(pin) }
            pin.fill('\u0000')
            when (result) {
                is VaultPinManager.VerifyResult.Success -> unlockAndLoad()
                is VaultPinManager.VerifyResult.Failure ->
                    _uiState.value = lockedState().copy(
                        error = "Incorrect PIN. ${result.attemptsRemaining} attempt(s) left."
                    )
                is VaultPinManager.VerifyResult.LockedOut ->
                    _uiState.value = lockedState().copy(
                        error = "Too many attempts. Try again later.",
                        lockoutRemainingSeconds = result.remainingMillis / 1000L
                    )
                VaultPinManager.VerifyResult.NotConfigured ->
                    _uiState.value = _uiState.value.copy(
                        phase = Phase.SETUP_PIN,
                        pinConfigured = false,
                        error = "No Vault PIN is set"
                    )
                VaultPinManager.VerifyResult.InvalidInput ->
                    _uiState.value = _uiState.value.copy(error = "Enter your Vault PIN")
            }
        }
    }

    /**
     * Offers the automatic prompt once per lock cycle, and only when the vault is
     * genuinely locked. Returns true when the caller should show the system prompt;
     * the state moves to [Phase.AUTHENTICATING], which still renders the locked
     * background and never media.
     */
    fun shouldAutoPromptBiometric(): Boolean {
        val state = _uiState.value
        if (biometricPromptRequested) return false
        if (state.phase != Phase.LOCKED) return false
        if (!state.biometricUnlockEnabled || !state.biometricHardwareAvailable) return false
        if (pinManager.isLockedOut()) return false
        beginAuthenticating()
        return true
    }

    /** Explicit "Unlock with biometrics" tap; always allowed while locked. */
    fun prepareBiometricPrompt(): Boolean {
        val state = _uiState.value
        if (state.phase == Phase.UNLOCKED || state.phase == Phase.AUTHENTICATING) return false
        if (!state.biometricUnlockEnabled || !state.biometricHardwareAvailable) return false
        if (pinManager.isLockedOut()) return false
        beginAuthenticating()
        return true
    }

    private fun beginAuthenticating() {
        biometricPromptRequested = true
        biometricPromptActive = true
        _uiState.value = _uiState.value.copy(phase = Phase.AUTHENTICATING, error = null)
    }

    /**
     * Called by the UI after the system prompt reports an outcome. The app only
     * ever learns success/failure — never biometric data.
     */
    fun onBiometricResult(outcome: VaultBiometricGate.Outcome) {
        biometricPromptActive = false
        biometricPromptRequested = true
        when (outcome) {
            VaultBiometricGate.Outcome.AUTHENTICATED ->
                if (pinManager.isPinConfigured()) unlockAndLoad() else publishLocked()
            VaultBiometricGate.Outcome.LOCKED_OUT ->
                _uiState.value = lockedState().copy(
                    error = "Biometric unlock is temporarily unavailable. Enter your Vault PIN.",
                    lockoutRemainingSeconds = pinManager.remainingLockMillis() / 1000L
                )
            VaultBiometricGate.Outcome.CANCELLED -> _uiState.value = lockedState()
            VaultBiometricGate.Outcome.UNAVAILABLE ->
                _uiState.value = lockedState().copy(
                    error = "Biometric unlock is unavailable. Enter your Vault PIN.",
                    biometricHardwareAvailable = biometricAvailable()
                )
            VaultBiometricGate.Outcome.UNSUPPORTED,
            VaultBiometricGate.Outcome.ERROR -> _uiState.value = lockedState()
        }
    }

    // ── settings ─────────────────────────────────────────────────────────────

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        if (!session.isUnlocked() || !pinManager.isPinConfigured()) return
        if (enabled && !biometricAvailable()) return
        pinManager.setBiometricUnlockEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            biometricHardwareAvailable = biometricAvailable()
        )
    }

    fun setLockTimeoutSeconds(seconds: Int) {
        pinManager.setLockTimeoutSeconds(seconds)
        session.timeoutSeconds = pinManager.lockTimeoutSeconds()
        _uiState.value = _uiState.value.copy(lockTimeoutSeconds = pinManager.lockTimeoutSeconds())
    }

    /** Re-reads configuration after returning from the separate settings screen. */
    fun refreshConfig() {
        val current = _uiState.value
        val configured = pinManager.isPinConfigured()
        session.timeoutSeconds = pinManager.lockTimeoutSeconds()
        val phase = when {
            current.phase == Phase.SETUP_BIOMETRIC && configured -> Phase.SETUP_BIOMETRIC
            session.isUnlocked() && configured -> Phase.UNLOCKED
            configured -> Phase.LOCKED
            else -> Phase.SETUP_PIN
        }
        _uiState.value = current.copy(
            phase = phase,
            pinConfigured = configured,
            biometricHardwareAvailable = biometricAvailable(),
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            lockTimeoutSeconds = pinManager.lockTimeoutSeconds(),
            items = if (session.isUnlocked() && configured) current.items else emptyList()
        )
    }

    // ── locking ──────────────────────────────────────────────────────────────

    /**
     * The app left the foreground. Drops the session and the loaded items
     * immediately, so nothing vaulted stays in memory or on screen. The teardown
     * runs before the state is published: clear → lock → LOCKED.
     */
    fun onBackgrounded() {
        if (biometricPromptActive) return
        clearPreviews()
        session.onBackgrounded()
        publishLocked()
    }

    /**
     * Explicit "Lock vault". Locks and suppresses the automatic prompt until the
     * next foreground cycle, so tapping Lock does not immediately re-open the
     * biometric prompt the user just dismissed.
     */
    fun onLockRequested() {
        clearPreviews()
        session.lock()
        publishLocked()
        biometricPromptRequested = true
    }

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
                    pinConfigured = true,
                    biometricHardwareAvailable = biometricAvailable(),
                    biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
                    lockTimeoutSeconds = pinManager.lockTimeoutSeconds(),
                    items = items,
                    error = null
                )
            } else {
                lockedState()
            }
        }
    }

    /**
     * Releases decrypted/session state, then publishes the locked state. The
     * media list is emptied in the same step so the UI cannot render a stale
     * frame behind the authentication surface.
     */
    private fun publishLocked() {
        biometricPromptActive = false
        biometricPromptRequested = false
        _uiState.value = lockedState()
    }

    private fun lockedState(): VaultUiState {
        val configured = pinManager.isPinConfigured()
        return _uiState.value.copy(
            phase = if (configured) Phase.LOCKED else Phase.SETUP_PIN,
            pinConfigured = configured,
            biometricHardwareAvailable = biometricAvailable(),
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            lockTimeoutSeconds = pinManager.lockTimeoutSeconds(),
            items = emptyList()
        )
    }

    private fun initialState(): VaultUiState {
        val configured = pinManager.isPinConfigured()
        return VaultUiState(
            phase = if (configured) Phase.LOCKED else Phase.SETUP_PIN,
            pinConfigured = configured,
            biometricHardwareAvailable = biometricAvailable(),
            biometricUnlockEnabled = pinManager.isBiometricUnlockEnabled(),
            lockTimeoutSeconds = pinManager.lockTimeoutSeconds()
        )
    }

    /**
     * The vault screen left the back stack: release decrypted state and lock the
     * session, so returning to Hidden Photos always starts from the locked screen.
     */
    override fun onCleared() {
        clearPreviews()
        session.lock()
        super.onCleared()
    }

    companion object {
        fun factory(
            vaultDao: VaultDao,
            pinManager: VaultPinManager,
            session: VaultSession,
            biometricAvailable: () -> Boolean,
            clearPreviews: () -> Unit = {}
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VaultViewModel(
                        vaultDao,
                        pinManager,
                        session,
                        biometricAvailable,
                        clearPreviews
                    ) as T
                }
            }
    }
}
