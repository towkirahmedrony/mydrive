package com.mydrive.app.ui.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.vault.VaultBiometricGate
import com.mydrive.app.data.vault.VaultPinCrypto
import com.mydrive.app.ui.components.SettingsGroup
import com.mydrive.app.ui.components.SettingsSwitchRow
import com.mydrive.app.ui.theme.Spacing

/**
 * Private Vault screen — the destination behind Settings → Hidden Photos.
 *
 * SECURITY CONTRACT enforced by this composable:
 *   - the locked branch renders ONLY the authentication UI. There is no thumbnail,
 *     file name, count or preview of hidden media on this screen before a successful
 *     unlock, because [VaultViewModel.VaultUiState.items] is empty while locked and
 *     the unlocked content is not composed at all;
 *   - leaving the foreground locks the vault (see the lifecycle observer), so hidden
 *     media cannot appear in the recents/next-launch snapshot;
 *   - authentication is delegated to the existing vault layer — this screen never
 *     verifies a PIN or a biometric itself.
 *
 * The vault session is app-scoped, so this screen owns no cryptographic state.
 */
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onScreenShown()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onBackgrounded()
        }
    }

    LaunchedEffect(state.phase, state.biometricUnlockEnabled, state.biometricHardwareAvailable) {
        if (state.phase != VaultViewModel.Phase.LOCKED) return@LaunchedEffect
        if (!viewModel.shouldPromptBiometric()) return@LaunchedEffect
        val host = activity
        if (host == null) {
            viewModel.onBiometricUnavailable()
            return@LaunchedEffect
        }
        VaultBiometricGate.authenticate(
            activity = host,
            onResult = { outcome ->
                viewModel.onBiometricPromptFinished()
                when (outcome) {
                    VaultBiometricGate.Outcome.AUTHENTICATED -> viewModel.onBiometricAuthenticated()
                    VaultBiometricGate.Outcome.UNAVAILABLE,
                    VaultBiometricGate.Outcome.UNSUPPORTED,
                    VaultBiometricGate.Outcome.ERROR -> viewModel.onBiometricUnavailable()
                    VaultBiometricGate.Outcome.LOCKED_OUT -> viewModel.onBiometricLockout()
                    VaultBiometricGate.Outcome.CANCELLED -> viewModel.onBiometricCancelled()
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant)
                    .border(1.dp, colors.outlineVariant, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onSurface
                )
            }
            Spacer(Modifier.size(Spacing.md))
            Text(
                when (state.phase) {
                    VaultViewModel.Phase.UNLOCKED -> "Private Vault"
                    else -> "Hidden Photos"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground
            )
            Spacer(Modifier.weight(1f))
            if (state.phase == VaultViewModel.Phase.UNLOCKED) {
                IconButton(onClick = { viewModel.onLockRequested() }) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = "Lock vault",
                        tint = colors.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xl))

        when (state.phase) {
            VaultViewModel.Phase.SETUP_PIN -> SetupPinContent(
                state = state,
                pin = pin,
                confirmPin = confirmPin,
                onPinChange = { pin = it },
                onConfirmPinChange = { confirmPin = it },
                onSetPin = {
                    viewModel.clearPin()
                    pin.forEach(viewModel::appendPinDigit)
                    viewModel.setPin()
                    pin = ""
                    confirmPin = ""
                }
            )
            VaultViewModel.Phase.SETUP_BIOMETRIC -> SetupBiometricContent(
                onEnable = {
                    val host = activity
                    if (host == null) {
                        viewModel.skipBiometricFromSetup()
                        return@SetupBiometricContent
                    }
                    viewModel.onBiometricPromptActive()
                    VaultBiometricGate.authenticate(
                        activity = host,
                        title = "Enable biometric unlock",
                        subtitle = "Confirm your biometric to unlock Hidden Photos faster",
                        negativeButton = "Not now",
                        onResult = { outcome ->
                            viewModel.onBiometricPromptFinished()
                            when (outcome) {
                                VaultBiometricGate.Outcome.AUTHENTICATED ->
                                    viewModel.enableBiometricFromSetup()
                                VaultBiometricGate.Outcome.UNAVAILABLE,
                                VaultBiometricGate.Outcome.UNSUPPORTED ->
                                    viewModel.skipBiometricFromSetup()
                                else -> Unit
                            }
                        }
                    )
                },
                onSkip = { viewModel.skipBiometricFromSetup() }
            )
            VaultViewModel.Phase.LOCKED -> UnlockContent(
                state = state,
                pin = pin,
                onPinChange = { pin = it },
                onUnlock = {
                    viewModel.clearPin()
                    pin.forEach(viewModel::appendPinDigit)
                    viewModel.submitPin()
                    pin = ""
                },
                onUsePinInstead = { viewModel.onBiometricCancelled() },
                onRequestBiometric = {
                    val host = activity ?: return@UnlockContent
                    viewModel.onBiometricPromptActive()
                    VaultBiometricGate.authenticate(
                        activity = host,
                        onResult = { outcome ->
                            viewModel.onBiometricPromptFinished()
                            when (outcome) {
                                VaultBiometricGate.Outcome.AUTHENTICATED ->
                                    viewModel.onBiometricAuthenticated()
                                VaultBiometricGate.Outcome.LOCKED_OUT ->
                                    viewModel.onBiometricLockout()
                                VaultBiometricGate.Outcome.CANCELLED ->
                                    viewModel.onBiometricCancelled()
                                else -> viewModel.onBiometricUnavailable()
                            }
                        }
                    )
                }
            )
            VaultViewModel.Phase.UNLOCKED -> UnlockedContent(
                state = state,
                onBiometricToggle = viewModel::setBiometricUnlockEnabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SetupPinContent(
    state: VaultViewModel.VaultUiState,
    pin: String,
    confirmPin: String,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onSetPin: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Set up Vault PIN",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Hidden media is encrypted and protected by a vault PIN. " +
                "This is separate from your device lock screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) onPinChange(it) },
            label = { Text("Vault PIN (min ${VaultPinCrypto.MIN_PIN_LENGTH} digits)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) onConfirmPinChange(it) },
            label = { Text("Confirm vault PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (state.error != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(state.error, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Spacer(Modifier.height(Spacing.md))
        val matching = pin.length >= VaultPinCrypto.MIN_PIN_LENGTH && pin == confirmPin
        Button(
            onClick = onSetPin,
            enabled = matching,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set PIN")
        }
    }
}

@Composable
private fun SetupBiometricContent(
    onEnable: () -> Unit,
    onSkip: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Vault PIN created successfully",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Unlock Hidden Photos faster with biometrics. " +
                "Your vault PIN remains available as a fallback. " +
                "Biometric data stays on the device and is never stored by My Drive.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Biometric Unlock")
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Not Now")
        }
    }
}

@Composable
private fun UnlockContent(
    state: VaultViewModel.VaultUiState,
    pin: String,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onUsePinInstead: () -> Unit,
    onRequestBiometric: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val showBiometricButton = state.biometricUnlockEnabled && state.biometricHardwareAvailable
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Unlock Private Vault",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (showBiometricButton) {
                "Authenticate with biometrics, or enter your vault PIN."
            } else {
                "Enter your vault PIN to view hidden photos and videos."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        if (showBiometricButton) {
            Spacer(Modifier.height(Spacing.md))
            Button(onClick = onRequestBiometric, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock with biometrics")
            }
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(onClick = onUsePinInstead, modifier = Modifier.fillMaxWidth()) {
                Text("Use vault PIN")
            }
        }
        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) onPinChange(it) },
            label = { Text("Vault PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (state.error != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(state.error, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Spacer(Modifier.height(Spacing.md))
        Button(
            onClick = onUnlock,
            enabled = pin.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Unlock")
        }
    }
}

@Composable
private fun UnlockedContent(
    state: VaultViewModel.VaultUiState,
    onBiometricToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        if (state.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .padding(Spacing.md)
            ) {
                Column {
                    Text(
                        "No hidden photos yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "Media you hide will appear here, encrypted on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                "${state.items.size} hidden item${if (state.items.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground
            )
            Spacer(Modifier.height(Spacing.md))
            val rows = state.items.chunked(2)
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    row.forEach { item ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceVariant)
                                .padding(Spacing.sm)
                        ) {
                            Text(
                                item.originalFileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "${item.originalMimeType} · ${item.originalFileSize / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        SettingsGroup(title = "Security") {
            if (state.biometricHardwareAvailable) {
                SettingsSwitchRow(
                    title = "Biometric unlock",
                    subtitle = "Use the device biometric prompt. Vault PIN remains the fallback.",
                    checked = state.biometricUnlockEnabled,
                    onCheckedChange = onBiometricToggle
                )
            } else {
                Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                    Text(
                        "Biometric unlock",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "No supported biometric is enrolled on this device. Use your vault PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
