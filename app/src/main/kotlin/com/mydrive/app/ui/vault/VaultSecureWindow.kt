package com.mydrive.app.ui.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Applies `WindowManager.LayoutParams.FLAG_SECURE` to the host window while the
 * Private Vault flow is on screen, and removes it on exit.
 *
 * This is what keeps vault content out of the Android recents/app-switcher snapshot
 * and blocks screen capture of the vault, without changing screenshot behavior for
 * the rest of My Drive. It is driven from the navigation host (keyed on the route,
 * not per screen) so overlapping transitions cannot leave the flag in the wrong
 * state.
 */
@Composable
fun VaultSecureWindow(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (enabled && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
