package com.mydrive.app.data.vault

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Private Vault security rules that can be verified without an Android device.
 *
 * The Android-facing pieces (Keystore, preferences, BiometricPrompt, MediaStore)
 * are covered by their own layers; what is tested here is the cryptography and the
 * lock policy, which is where a mistake would actually weaken the vault.
 */
class VaultPinCryptoTest {

    private fun pin(value: String) = value.toCharArray()

    @Test
    fun `the same pin and salt derive the same verifier`() {
        val salt = ByteArray(16) { 7 }
        assertEquals(
            Base64.getEncoder().encodeToString(VaultPinCrypto.deriveVerifier(pin("246810"), salt, iterations = 1_000)),
            Base64.getEncoder().encodeToString(VaultPinCrypto.deriveVerifier(pin("246810"), salt, iterations = 1_000))
        )
    }

    @Test
    fun `different pins derive different verifiers`() {
        val salt = ByteArray(16) { 7 }
        assertNotEquals(
            VaultPinCrypto.deriveVerifier(pin("246810"), salt, iterations = 1_000).toList(),
            VaultPinCrypto.deriveVerifier(pin("246811"), salt, iterations = 1_000).toList()
        )
    }

    @Test
    fun `the same pin with a different salt derives a different verifier`() {
        // Without a per-vault salt, two users with the same PIN would share a
        // verifier and a precomputed table would work.
        assertNotEquals(
            VaultPinCrypto.deriveVerifier(pin("246810"), ByteArray(16) { 1 }, iterations = 1_000).toList(),
            VaultPinCrypto.deriveVerifier(pin("246810"), ByteArray(16) { 2 }, iterations = 1_000).toList()
        )
    }

    @Test
    fun `a fresh salt is random and correctly sized`() {
        val a = VaultPinCrypto.newSalt()
        val b = VaultPinCrypto.newSalt()
        assertEquals(VaultPinCrypto.SALT_BYTES, a.size)
        assertFalse("two salts must not be identical", a.contentEquals(b))
    }

    @Test
    fun `constant time comparison only matches identical verifiers`() {
        val salt = ByteArray(16) { 3 }
        val verifier = VaultPinCrypto.deriveVerifier(pin("246810"), salt, iterations = 1_000)
        assertTrue(VaultPinCrypto.constantTimeEquals(verifier, verifier.copyOf()))
        assertFalse(VaultPinCrypto.constantTimeEquals(verifier, ByteArray(verifier.size)))
        assertFalse(VaultPinCrypto.constantTimeEquals(null, verifier))
        assertFalse(VaultPinCrypto.constantTimeEquals(verifier, null))
    }

    @Test
    fun `pin policy rejects a too short pin`() {
        assertTrue(VaultPinCrypto.MIN_PIN_LENGTH >= 6)
        assertFalse(VaultPinCrypto.isAcceptablePin("12345".toCharArray()))
        assertTrue(VaultPinCrypto.isAcceptablePin("123456".toCharArray()))
        assertFalse(VaultPinCrypto.isAcceptablePin("12345a".toCharArray()))
        assertFalse(VaultPinCrypto.isAcceptablePin("1234567890123".toCharArray()))
    }

    @Test
    fun `a verifier cannot be reversed to the pin`() {
        val salt = ByteArray(16) { 9 }
        val verifier = VaultPinCrypto.deriveVerifier(pin("246810"), salt, iterations = 1_000)
        val encoded = Base64.getEncoder().encodeToString(verifier)
        assertFalse(encoded.contains("246810"))
        assertEquals(VaultPinCrypto.DEFAULT_KEY_BITS / 8, verifier.size)
    }
}

class VaultLockPolicyTest {

    private val minute = 60_000L

    @Test
    fun `a few wrong pins do not lock the vault`() {
        val policy = VaultLockPolicy()
        repeat(VaultLockPolicy.DEFAULT_MAX_ATTEMPTS - 1) { policy.recordFailure(1_000L) }
        assertFalse(policy.isLocked(1_000L))
        assertEquals(1, policy.attemptsRemaining())
    }

    @Test
    fun `reaching the attempt limit locks the vault`() {
        val policy = VaultLockPolicy()
        val locked = (1..VaultLockPolicy.DEFAULT_MAX_ATTEMPTS).map { policy.recordFailure(1_000L) }
        assertTrue("the final failure must trigger the lockout", locked.last())
        assertTrue(policy.isLocked(1_000L))
        assertEquals(VaultLockPolicy.DEFAULT_LOCK_MILLIS, policy.remainingLockMillis(1_000L))
    }

    @Test
    fun `the lockout expires after the back-off`() {
        val policy = VaultLockPolicy()
        repeat(VaultLockPolicy.DEFAULT_MAX_ATTEMPTS) { policy.recordFailure(1_000L) }
        assertTrue(policy.isLocked(1_000L))
        assertFalse(policy.isLocked(1_000L + VaultLockPolicy.DEFAULT_LOCK_MILLIS))
        assertEquals(0L, policy.remainingLockMillis(1_000L + VaultLockPolicy.DEFAULT_LOCK_MILLIS))
    }

    @Test
    fun `a successful unlock clears the failure state`() {
        val policy = VaultLockPolicy()
        repeat(VaultLockPolicy.DEFAULT_MAX_ATTEMPTS - 1) { policy.recordFailure(1_000L) }
        policy.recordSuccess()
        assertEquals(0, policy.failureCount())
        assertFalse(policy.isLocked(1_000L))
    }

    @Test
    fun `a long gap starts a fresh attempt window instead of accumulating`() {
        val policy = VaultLockPolicy()
        repeat(VaultLockPolicy.DEFAULT_MAX_ATTEMPTS - 1) { policy.recordFailure(1_000L) }
        // Well past the attempt window: the counter restarts.
        policy.recordFailure(1_000L + VaultLockPolicy.ATTEMPT_WINDOW_MILLIS + 1)
        assertEquals(1, policy.failureCount())
    }

    @Test
    fun `timeout expiry follows the configured seconds`() {
        val policy = VaultLockPolicy()
        // 60-second timeout: still open at 30 s, expired at 61 s.
        assertFalse(policy.isExpired(lastActiveAt = 0L, now = 30_000L, timeoutSeconds = 60))
        assertTrue(policy.isExpired(lastActiveAt = 0L, now = 61_000L, timeoutSeconds = 60))
        // 0 seconds means "lock on background", which VaultSession enforces itself.
        assertFalse(policy.isExpired(lastActiveAt = 0L, now = 10_000L, timeoutSeconds = 0))
        // A 10-minute timeout must not expire after 5 minutes.
        assertFalse(policy.isExpired(lastActiveAt = 0L, now = 5 * minute, timeoutSeconds = 600))
        assertTrue(policy.isExpired(lastActiveAt = 0L, now = 11 * minute, timeoutSeconds = 600))
    }
}

class VaultSessionTest {

    private var now = 1_000_000L
    private fun session() = VaultSession(clock = { now })

    @Test
    fun `a new session starts locked`() {
        assertFalse(session().isUnlocked())
    }

    @Test
    fun `unlocking does not persist across a new session instance`() {
        val first = session()
        first.unlock()
        assertTrue(first.isUnlocked())
        assertFalse("a restarted process must start locked", session().isUnlocked())
    }

    @Test
    fun `unlock and lock are reflected`() {
        val session = session()
        session.unlock()
        assertTrue(session.isUnlocked())
        session.lock()
        assertFalse(session.isUnlocked())
    }

    @Test
    fun `the session locks itself after the timeout`() {
        val session = session()
        session.timeoutSeconds = 60
        session.unlock()
        now += 61_000L
        assertFalse("an idle vault must re-lock", session.isUnlocked())
    }

    @Test
    fun `activity extends the session`() {
        val session = session()
        session.timeoutSeconds = 60
        session.unlock()
        now += 50_000L
        assertTrue(session.isUnlocked())
        session.touch()
        now += 50_000L
        assertTrue("touching should keep the vault open", session.isUnlocked())
    }

    @Test
    fun `going to the background always locks`() {
        val session = session()
        session.timeoutSeconds = 600
        session.unlock()
        session.onBackgrounded()
        assertFalse(session.isUnlocked())
        assertFalse("returning to the foreground must not restore access", session.onForegrounded())
    }

    @Test
    fun `a zero timeout locks as soon as the app leaves the foreground`() {
        val session = session()
        session.timeoutSeconds = 0
        session.unlock()
        assertTrue(session.isUnlocked())
        session.onBackgrounded()
        assertFalse(session.isUnlocked())
    }
}
