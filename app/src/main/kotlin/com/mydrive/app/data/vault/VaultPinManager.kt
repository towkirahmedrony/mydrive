package com.mydrive.app.data.vault

import android.content.Context
import android.util.Base64

/**
 * The Private Vault PIN: enrolment and verification.
 *
 * Security properties:
 *  - the PIN is **never stored**, in any form: only a PBKDF2-HMAC-SHA256 verifier
 *    with a per-vault random salt ([VaultPinCrypto]);
 *  - the verifier is additionally sealed with an Android Keystore AES-GCM key
 *    ([VaultCrypto.sealString]) before it reaches preferences, so copying the app's
 *    data directory does not disclose even the verifier;
 *  - comparison is constant-time;
 *  - repeated failures are rate-limited by [VaultLockPolicy] and the PIN is refused
 *    entirely while locked out;
 *  - nothing here is ever logged, and the device lock-screen PIN/password is never
 *    requested or stored.
 *
 * This class holds only the verifier, its attempt counter, and the non-secret
 * biometric-unlock preference. It deliberately does NOT hold the unlocked-session
 * state — that is [VaultSession] — so authentication and authorisation can be
 * reasoned about separately.
 */
class VaultPinManager(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policy: VaultLockPolicy = VaultLockPolicy()
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val crypto = VaultCrypto(context.applicationContext)

    sealed class VerifyResult {
        data class Success(val verifiedAt: Long) : VerifyResult()

        /** [attemptsRemaining] before a lockout begins. */
        data class Failure(val attemptsRemaining: Int) : VerifyResult()

        /** Too many failures: the PIN is refused until the back-off expires. */
        data class LockedOut(val remainingMillis: Long) : VerifyResult()

        data object NotConfigured : VerifyResult()

        data object InvalidInput : VerifyResult()
    }

    fun isPinConfigured(): Boolean {
        val hasVerifier = !prefs.getString(KEY_VERIFIER, null).isNullOrBlank() &&
            !prefs.getString(KEY_SALT, null).isNullOrBlank()
        if (hasVerifier && !prefs.getBoolean(KEY_CONFIGURED, false)) {
            prefs.edit().putBoolean(KEY_CONFIGURED, true).apply()
        }
        return hasVerifier
    }

    fun isBiometricUnlockEnabled(): Boolean =
        isPinConfigured() && prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        if (!isPinConfigured()) return
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    /**
     * The foreground inactivity timeout for an unlocked vault, in seconds. Mirrors
     * `media_vault_settings.lock_timeout_seconds` but is kept on the device: it is
     * non-sensitive, and the vault must still lock correctly with no network.
     */
    fun lockTimeoutSeconds(): Int =
        prefs.getInt(KEY_LOCK_TIMEOUT_SECONDS, VaultSession.DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(MIN_LOCK_TIMEOUT_SECONDS, MAX_LOCK_TIMEOUT_SECONDS)

    fun setLockTimeoutSeconds(seconds: Int) {
        prefs.edit()
            .putInt(KEY_LOCK_TIMEOUT_SECONDS, seconds.coerceIn(MIN_LOCK_TIMEOUT_SECONDS, MAX_LOCK_TIMEOUT_SECONDS))
            .apply()
    }

    fun isLockedOut(): Boolean = policy.isLocked(clock())

    fun remainingLockMillis(): Long = policy.remainingLockMillis(clock())

    /**
     * Enrols (or replaces) the vault PIN. Replacing it clears the failure state,
     * because a deliberate change by an authenticated user is not an attack.
     *
     * The plaintext PIN never leaves this method: it is hashed, the buffer is
     * wiped by the caller, and only the Keystore-sealed verifier is persisted.
     */
    fun setPin(pin: CharArray): Boolean {
        if (!VaultPinCrypto.isAcceptablePin(pin)) return false
        val salt = VaultPinCrypto.newSalt()
        val verifier = VaultPinCrypto.deriveVerifier(pin, salt)
        val sealedVerifier = crypto.sealString(Base64.encodeToString(verifier, Base64.NO_WRAP))
            ?: return false
        val written = prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_VERIFIER, sealedVerifier)
            .putInt(KEY_ITERATIONS, VaultPinCrypto.DEFAULT_ITERATIONS)
            .putBoolean(KEY_CONFIGURED, true)
            .commit()
        if (!written) return false
        policy.recordSuccess()
        return true
    }

    /** Verifies [pin]. Never logs the PIN or the derived verifier. */
    fun verify(pin: CharArray): VerifyResult {
        val now = clock()
        if (policy.isLocked(now)) return VerifyResult.LockedOut(policy.remainingLockMillis(now))
        if (!isPinConfigured()) return VerifyResult.NotConfigured
        if (pin.isEmpty() || pin.size < VaultPinCrypto.MIN_PIN_LENGTH) {
            return VerifyResult.InvalidInput
        }

        val saltEncoded = prefs.getString(KEY_SALT, null) ?: return VerifyResult.NotConfigured
        val sealedVerifier = prefs.getString(KEY_VERIFIER, null) ?: return VerifyResult.NotConfigured
        val iterations = prefs.getInt(KEY_ITERATIONS, VaultPinCrypto.DEFAULT_ITERATIONS)

        val salt = runCatching { Base64.decode(saltEncoded, Base64.NO_WRAP) }.getOrNull()
            ?: return VerifyResult.NotConfigured
        val storedVerifier = crypto.openString(sealedVerifier)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return VerifyResult.NotConfigured

        val candidate = VaultPinCrypto.deriveVerifier(pin, salt, iterations)
        return if (VaultPinCrypto.constantTimeEquals(candidate, storedVerifier)) {
            policy.recordSuccess()
            VerifyResult.Success(now)
        } else {
            policy.recordFailure(now)
            if (policy.isLocked(now)) {
                VerifyResult.LockedOut(policy.remainingLockMillis(now))
            } else {
                VerifyResult.Failure(policy.attemptsRemaining())
            }
        }
    }

    /**
     * Removes the vault PIN. The vault's encrypted files are NOT deleted here: a
     * forgotten PIN must not silently destroy user media. Callers decide separately
     * whether to keep the vault recoverable.
     */
    fun clearPin() {
        prefs.edit()
            .remove(KEY_SALT)
            .remove(KEY_VERIFIER)
            .remove(KEY_ITERATIONS)
            .remove(KEY_CONFIGURED)
            .remove(KEY_BIOMETRIC_ENABLED)
            .apply()
        policy.recordSuccess()
    }

    private companion object {
        const val PREFS_NAME = "mydrive_vault_credentials"
        const val KEY_SALT = "pin_salt"
        const val KEY_VERIFIER = "pin_verifier_sealed"
        const val KEY_ITERATIONS = "pin_iterations"
        const val KEY_CONFIGURED = "vault_configured"
        const val KEY_BIOMETRIC_ENABLED = "biometric_unlock_enabled"
        const val KEY_LOCK_TIMEOUT_SECONDS = "lock_timeout_seconds"
        const val MIN_LOCK_TIMEOUT_SECONDS = 0
        const val MAX_LOCK_TIMEOUT_SECONDS = 3600
    }
}
