package com.mydrive.app.data.vault

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Vault PIN cryptography and lock policy.
 *
 * Kept free of Android APIs on purpose so the security rules are unit-testable on
 * the JVM. The Android-facing pieces (preferences, Keystore, BiometricPrompt) sit
 * in [VaultPinManager] and the vault UI, and delegate here.
 *
 * What is deliberately absent: the device lock-screen PIN/password (never
 * requested, never stored), any biometric data (Android verifies biometrics and
 * only reports success/failure), and any reversible encoding of the PIN.
 */
object VaultPinCrypto {

    /**
     * Derives the stored verifier for [pin].
     *
     * PBKDF2-HMAC-SHA256 with a per-vault random salt and a high iteration count.
     * The PIN itself is never stored or logged — only this one-way verifier.
     */
    fun deriveVerifier(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        keyLengthBits: Int = DEFAULT_KEY_BITS
    ): ByteArray {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        val spec = PBEKeySpec(pin, salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** A fresh random salt for a vault PIN. */
    fun newSalt(bytes: Int = SALT_BYTES): ByteArray =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }

    /**
     * Constant-time comparison, so a wrong PIN cannot be brute-forced by timing.
     */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a, b)
    }

    /** Minimum PIN length enforced at enrolment. */
    const val MIN_PIN_LENGTH = 6
    const val SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 120_000
    const val DEFAULT_KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
}

/**
 * Vault PIN brute-force protection and inactivity lock policy.
 *
 * Pure state machine driven by an explicit clock so tests are deterministic.
 * Failures back off progressively and then lock the PIN entirely until the
 * back-off expires; the counter resets on a successful unlock.
 */
class VaultLockPolicy(
    private val maxAttemptsBeforeLock: Int = DEFAULT_MAX_ATTEMPTS,
    private val lockMillis: Long = DEFAULT_LOCK_MILLIS,
    private val attemptWindowMillis: Long = ATTEMPT_WINDOW_MILLIS
) {

    private var failures = 0
    private var lockedUntil = 0L
    private var firstFailureAt = 0L

    /** True while the PIN is refused because of repeated failures. */
    fun isLocked(now: Long): Boolean = now < lockedUntil

    fun remainingLockMillis(now: Long): Long = (lockedUntil - now).coerceAtLeast(0L)

    fun failureCount(): Int = failures

    fun attemptsRemaining(): Int = (maxAttemptsBeforeLock - failures).coerceAtLeast(0)

    /** Records a wrong PIN; returns true when this failure triggered a lockout. */
    fun recordFailure(now: Long): Boolean {
        // A long gap means a new attempt session rather than a continuing attack.
        if (firstFailureAt > 0L && now - firstFailureAt > attemptWindowMillis) {
            failures = 0
            firstFailureAt = now
        } else if (firstFailureAt == 0L) {
            firstFailureAt = now
        }
        failures += 1
        if (failures >= maxAttemptsBeforeLock) {
            lockedUntil = now + lockMillis
            return true
        }
        return false
    }

    /** Records a correct PIN: clears the failure state. */
    fun recordSuccess() {
        failures = 0
        lockedUntil = 0L
        firstFailureAt = 0L
    }

    /**
     * Whether an unlocked session must be considered expired.
     *
     * [timeoutSeconds] of 0 means "lock as soon as the app leaves the foreground",
     * which the caller enforces separately via [VaultSession.onBackgrounded].
     */
    fun isExpired(lastActiveAt: Long, now: Long, timeoutSeconds: Int): Boolean {
        if (timeoutSeconds <= 0) return false
        return now - lastActiveAt >= timeoutSeconds * 1000L
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_LOCK_MILLIS = 5 * 60 * 1000L
        const val ATTEMPT_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
