package com.mydrive.app.data.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric capability and prompt for the Private Vault.
 *
 * Uses AndroidX [BiometricPrompt] so the OS performs verification. The app only
 * learns success or failure — no biometric templates, images or samples are
 * collected or stored. Facial recognition is never implemented in-app.
 *
 * Device lock-screen PIN/password/pattern is never requested: authenticators are
 * restricted to BIOMETRIC_STRONG, and setDeviceCredentialAllowed is not used.
 */
object VaultBiometricGate {

    /**
     * True when the device has a strong biometric enrolled that the vault can
     * actually prompt for. Weak sensors and unenrolled hardware return false so
     * the UI never offers a misleading biometric option.
     */
    fun canOfferBiometric(context: Context): Boolean {
        val manager = BiometricManager.from(context.applicationContext)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        return when (manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

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
     * Shows the system biometric prompt. [activity] must be a [FragmentActivity]
     * (MainActivity is). Device credentials are not accepted as a substitute.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Hidden Photos",
        subtitle: String = "Use biometrics to open your Private Vault",
        negativeButton: String = "Use vault PIN",
        onResult: (Outcome) -> Unit
    ) {
        if (!canOfferBiometric(activity)) {
            onResult(Outcome.UNAVAILABLE)
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButton)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(Outcome.AUTHENTICATED)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED -> Outcome.CANCELLED
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> Outcome.LOCKED_OUT
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> Outcome.UNAVAILABLE
                            else -> Outcome.ERROR
                        }
                    )
                }

                override fun onAuthenticationFailed() {
                    // A failed attempt that did not end the prompt. Stay silent;
                    // Android keeps the dialog open for another try.
                }
            }
        )
        prompt.authenticate(promptInfo)
    }

    /**
     * The outcome vocabulary the vault authentication UI uses. Kept here so the PIN
     * path and the biometric path share one contract: any non-[AUTHENTICATED]
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
