package com.mydrive.app.ui.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.vault.VaultBiometricGate
import com.mydrive.app.ui.components.SettingsGroup
import com.mydrive.app.ui.components.SettingsRow
import com.mydrive.app.ui.components.SettingsSwitchRow
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Spacing

/**
 * Dedicated Vault Settings screen — the secondary surface for security and
 * privacy configuration, reached from the vault's overflow menu. Keeping these
 * controls out of the gallery is what makes the media screen feel like a private
 * photo vault rather than a mixed dashboard.
 *
 * Nothing here stores new credential material: the PIN change delegates to the
 * existing [com.mydrive.app.data.vault.VaultPinManager], and enabling biometrics
 * still uses the system BiometricPrompt.
 */
@Composable
fun VaultSettingsScreen(
    viewModel: VaultSettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val fragmentActivity = remember(context) { context.findFragmentActivity() }

    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md)
    ) {
        VaultBackHeader(title = "Vault Settings", onBack = onBack)
        Spacer(Modifier.height(Spacing.lg))

        if (state.editingPin) {
            VaultPinCreatePanel(
                title = "Change Vault PIN",
                subtitle = "Choose a new PIN for the Private Vault. Your media stays encrypted.",
                error = state.error,
                onCreated = viewModel::changePin,
                onCancel = viewModel::cancelChangePin
            )
        } else {
            VaultSettingsContent(
                state = state,
                viewModel = viewModel,
                fragmentActivity = fragmentActivity
            )
        }
    }
}

@Composable
private fun VaultSettingsContent(
    state: VaultSettingsViewModel.SettingsUiState,
    viewModel: VaultSettingsViewModel,
    fragmentActivity: FragmentActivity?
) {
    val colors = MaterialTheme.colorScheme

    state.message?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary
        )
        Spacer(Modifier.height(Spacing.sm))
    }
    if (state.error != null) {
        Text(
            text = state.error,
            style = MaterialTheme.typography.bodySmall,
            color = colors.error
        )
        Spacer(Modifier.height(Spacing.sm))
    }

    SettingsGroup(title = "Security") {
        SettingsRow(
            title = "Change Vault PIN",
            subtitle = "Set a new 6–12 digit PIN",
            leading = Icons.Outlined.Lock,
            onClick = viewModel::beginChangePin,
            trailing = {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant
                )
            }
        )
        HorizontalDivider(color = colors.outlineVariant)
        if (state.biometricHardwareAvailable) {
            SettingsSwitchRow(
                title = "Biometric unlock",
                subtitle = "Use the device biometric prompt. The Vault PIN stays as the fallback.",
                checked = state.biometricUnlockEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        viewModel.setBiometricEnabled(false)
                    } else {
                        val host = fragmentActivity
                        if (host != null) {
                            VaultBiometricGate.authenticate(
                                activity = host,
                                title = "Enable biometric unlock",
                                subtitle = "Confirm your biometric to unlock the Private Vault",
                                negativeButton = "Cancel",
                                onResult = { outcome ->
                                    if (outcome == VaultBiometricGate.Outcome.AUTHENTICATED) {
                                        viewModel.setBiometricEnabled(true)
                                    }
                                }
                            )
                        }
                    }
                }
            )
        } else {
            SettingsRow(
                title = "Biometric unlock",
                subtitle = "No supported biometric is enrolled on this device. Use your Vault PIN.",
                leading = Icons.Outlined.Fingerprint
            )
        }
    }

    Spacer(Modifier.height(Spacing.lg))

    SettingsGroup(title = "Lock automatically") {
        val options = VaultSettingsViewModel.LOCK_TIMEOUT_OPTIONS
        options.forEachIndexed { index, option ->
            SettingsRow(
                title = option.label,
                onClick = { viewModel.setLockTimeoutSeconds(option.seconds) },
                trailing = {
                    if (state.lockTimeoutSeconds == option.seconds) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = "Selected",
                            tint = Copper
                        )
                    }
                }
            )
            if (index != options.lastIndex) {
                HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }

    Spacer(Modifier.height(Spacing.lg))

    SettingsGroup(title = "Privacy") {
        SettingsRow(
            title = "Secure screen",
            subtitle = "Screenshots and recent-app previews are blocked while the Vault is open.",
            leading = Icons.Outlined.VisibilityOff,
            trailing = {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Always on",
                    tint = Copper
                )
            }
        )
    }

    Spacer(Modifier.height(Spacing.lg))
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
