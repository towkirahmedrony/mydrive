package com.mydrive.app.data.vault

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the Private Vault is currently unlocked, for how long, and when it must
 * lock again.
 *
 * Deliberately holds NO decrypted content and NO key material: it is a boolean plus
 * a timestamp. Media is decrypted on demand for display and the transient files are
 * deleted immediately afterwards, so nothing sensitive is kept resident in memory or
 * on disk while the vault is "open".
 *
 * Locking rules implemented here:
 *  - explicit lock (user action),
 *  - inactivity timeout ([timeoutSeconds]),
 *  - app moved to background (locking immediately when the timeout is 0),
 *  - process death (state is in memory only, so a fresh process starts locked).
 */
class VaultSession(
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val unlocked = AtomicBoolean(false)
    private var lastActiveAt: Long = 0L

    /** The configured inactivity timeout, refreshed from vault settings. */
    @Volatile
    var timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS

    fun isUnlocked(): Boolean {
        if (!unlocked.get()) return false
        if (policy.isExpired(lastActiveAt, clock(), timeoutSeconds)) {
            lock()
            return false
        }
        return true
    }

    /** Called after a successful biometric or PIN authentication. */
    fun unlock() {
        lastActiveAt = clock()
        unlocked.set(true)
    }

    /** Any user interaction while the vault is open extends the session. */
    fun touch() {
        if (unlocked.get()) lastActiveAt = clock()
    }

    fun lock() {
        unlocked.set(false)
        lastActiveAt = 0L
    }

    /**
     * The app went to the background: always lock, and release any transient
     * decrypted state the caller is holding.
     */
    fun onBackgrounded() {
        lock()
    }

    /** Re-evaluates the timeout; call when the app returns to the foreground. */
    fun onForegrounded(): Boolean = isUnlocked()

    private val policy = VaultLockPolicy()

    companion object {
        /** Client-side default; `media_vault_settings.lock_timeout_seconds` overrides it. */
        const val DEFAULT_TIMEOUT_SECONDS = 60
    }
}
