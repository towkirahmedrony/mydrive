package com.mydrive.app.data.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Biometric capability check for the Private Vault.
 *
 * IMPORTANT — why the biometric *prompt* is not implemented here:
 *
 * The platform class `android.hardware.biometrics.BiometricPrompt` cannot be used by
 * a normal app. Its public-looking constructors are hidden in the SDK (the compiler
 * reports "Cannot access 'constructor(): BiometricPrompt': it is package-private"),
 * its nested `PromptInfo` is not resolvable, and constants such as
 * `BIOMETRIC_ERROR_NEGATIVE_BUTTON` are `@SystemApi`. Verified against compileSdk 36
 * in CI run #111, which failed on exactly those three errors.
 *
 * Calling BiometricPrompt from an app therefore requires the first-party
 * `androidx.biometric:biometric` artifact. That is a NEW dependency, and this project
 * pins dependencies deliberately, so it was NOT added: the decision is flagged for
 * review rather than taken silently. Until it is added, the vault authenticates with
 * its own PIN, which is fully implemented ([VaultPinManager]) and independent of any
 * biometric capability.
 *
 * The button order is unchanged: biometric first when available, vault PIN as the
 * fallback, and the device lock-screen PIN/password is never requested.
 *
 * What this object does provide today is the honest capability answer the UI needs to
 * decide whether to show a biometric option at all, so adding the dependency later is
 * a local change rather than a redesign.
 */
object VaultBiometricGate {

    /**
     * Whether an in-app biometric prompt can be offered once `androidx.biometric` is
     * available. Returns false today because the platform API is unusable from an app
     * (see the class comment) — [isHardwarePresent] reports on the device itself.
     */
    fun canOfferBiometric(context: Context): Boolean = false

    /** Coarse hardware check: does the device have any biometric sensor at all? */
    fun isHardwarePresent(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.hasSystemFeature(PackageManager.FEATURE_FACE) ||
                pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
        } else {
            false
        }
    }

    /**
     * The outcome vocabulary the vault authentication UI uses. Kept here so the PIN
     * path and the future biometric path share one contract: any non-[AUTHENTICATED]
     * value means the vault stays locked and the PIN remains available.
     *
     * No biometric data is ever read, stored or transmitted — Android performs the
     * verification and reports success/failure only.
     */
    enum class Outcome {
        AUTHENTICATED,

        /** Biometric cannot be used here: the vault PIN is the only option. */
        UNAVAILABLE,
        LOCKED_OUT,
        CANCELLED,
        UNSUPPORTED,
        ERROR
    }
}
