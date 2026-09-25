package com.mydrive.app.data.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mydrive.app.R
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory

/**
 * Biometric capability check for the Private Vault.
 *
 * Two honest limitations, both deliberate:
 *  - the platform `BiometricPrompt` only exists from API 28, so on API 26-27 the
 *    vault is PIN-only (no deprecated `FingerprintManager` path is added, and no
 *    new dependency is introduced);
 *  - "enrolled" cannot be checked accurately without androidx.biometric, so the
 *    authoritative availability answer comes from the prompt itself
 *    ([VaultAuthActivity] maps `ERROR_NO_BIOMETRICS` and friends), and the caller
 *    falls back to the vault PIN.
 *
 * No biometric data is ever read, stored or transmitted: Android verifies and
 * reports success/failure only.
 */
object VaultBiometricGate {

    /** True when the platform BiometricPrompt is available on this OS version. */
    fun isApiSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Coarse hardware check, used only to decide whether to offer the option. */
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
     * Whether the UI should offer biometric unlock at all. A true result still does
     * not guarantee success — enrollment is confirmed by the prompt.
     */
    fun canOfferBiometric(context: Context): Boolean =
        isApiSupported() && isHardwarePresent(context)
}

/**
 * Hosts the platform BiometricPrompt and returns a coarse result to the caller.
 *
 * Kept out of MainActivity on purpose: MainActivity is a ComponentActivity and the
 * platform prompt requires a FragmentActivity, so changing the app's entry point
 * would be a far larger change than this feature warrants.
 *
 * The activity is `exported=false` and `noHistory=true`: it can only be launched by
 * the app itself, and it never stays in the back stack.
 */
class VaultAuthActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VaultBiometricGate.isApiSupported()) {
            finishWith(Outcome.UNSUPPORTED)
            return
        }
        val signal = CancellationSignal()
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    // A success is a success: no credential material is inspected.
                    log(Outcome.AUTHENTICATED, "biometric_succeeded")
                    finishWith(Outcome.AUTHENTICATED)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    // errString may contain system text; it is never persisted.
                    log(mapError(errorCode), "biometric_error_$errorCode")
                    finishWith(mapError(errorCode))
                }

                override fun onAuthenticationFailed() {
                    // A single non-matching attempt: the prompt stays open, so this is
                    // not treated as a terminal failure and nothing is persisted.
                    DeveloperLogger.info(
                        category = LogCategory.MEDIA,
                        event = "VAULT_BIOMETRIC_ATTEMPT_FAILED",
                        message = "A biometric attempt did not match"
                    )
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.vault_biometric_title))
            .setSubtitle(getString(R.string.vault_biometric_subtitle))
            // Choosing the vault PIN is the fallback, NOT the device credential:
            // device-credential authentication is never enabled, so the user is
            // never asked for their lock-screen PIN/password.
            .setNegativeButtonText(getString(R.string.vault_biometric_cancel))
            .build()

        try {
            prompt.authenticate(info, signal)
        } catch (error: Exception) {
            log(Outcome.ERROR, "prompt_start_${error.javaClass.simpleName}")
            finishWith(Outcome.ERROR)
        }
    }

    private fun mapError(errorCode: Int): Outcome = when (errorCode) {
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
        BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS -> Outcome.UNAVAILABLE

        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> Outcome.LOCKED_OUT

        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> Outcome.CANCELLED

        else -> Outcome.ERROR
    }

    private fun log(outcome: Outcome, reason: String) {
        // Only the outcome and a non-sensitive reason code: no biometric result
        // detail, no system error text, no identifiers.
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "VAULT_BIOMETRIC_RESULT",
            message = "Vault biometric authentication finished",
            metadata = mapOf("outcome" to outcome.name, "reason" to reason)
        )
    }

    private fun finishWith(outcome: Outcome) {
        setResult(
            if (outcome == Outcome.AUTHENTICATED) RESULT_OK else RESULT_CANCELED,
            Intent().putExtra(EXTRA_OUTCOME, outcome.name)
        )
        finish()
    }

    enum class Outcome {
        AUTHENTICATED,

        /** Biometric cannot be used here: the caller should offer the vault PIN. */
        UNAVAILABLE,
        LOCKED_OUT,
        CANCELLED,
        UNSUPPORTED,
        ERROR
    }

    companion object {
        const val EXTRA_OUTCOME = "vault_biometric_outcome"

        /**
         * Launches the prompt. Returns to the caller with the outcome in
         * [EXTRA_OUTCOME]; the vault PIN remains available for every non-success.
         */
        fun intent(context: Context): Intent =
            Intent(context, VaultAuthActivity::class.java)
    }
}
