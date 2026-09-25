package com.mydrive.app.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    // Lock as soon as the screen leaves the foreground: hidden media must never be
    // visible in the system's task snapshot.
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
            // Navigating away also ends the unlocked session.
            viewModel.onBackgrounded()
        }
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
                "Hidden Photos",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground
            )
            Spacer(Modifier.weight(1f))
            if (state.unlocked) {
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

        if (!state.unlocked) {
            // ── Locked: authentication only. No vault content is composed. ──────
            LockedContent(
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
                },
                onUnlock = {
                    viewModel.clearPin()
                    pin.forEach(viewModel::appendPinDigit)
                    viewModel.submitPin()
                    pin = ""
                }
            )
        } else {
            UnlockedContent(state = state)
        }
    }
}

@Composable
private fun LockedContent(
    state: VaultViewModel.VaultUiState,
    pin: String,
    confirmPin: String,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onSetPin: () -> Unit,
    onUnlock: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (state.pinConfigured) "Vault locked" else "Set up your vault PIN",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (state.pinConfigured) {
                "Authenticate to view hidden photos and videos."
            } else {
                "Hidden media is encrypted and protected by a vault PIN. " +
                    "This is separate from your device lock screen."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )

        if (!state.pinConfigured) {
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) onPinChange(it) },
                label = { Text("Vault PIN (min 6 digits)") },
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
        } else {
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
        }

        if (state.error != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        }

        Spacer(Modifier.height(Spacing.md))
        val ready = if (state.pinConfigured) {
            pin.isNotEmpty()
        } else {
            pin.length >= 6 && pin == confirmPin
        }
        Button(
            onClick = { if (state.pinConfigured) onUnlock() else onSetPin() },
            enabled = ready,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.pinConfigured) "Unlock" else "Set PIN")
        }

        Spacer(Modifier.height(Spacing.md))
        Text(
            if (state.biometricOffered) {
                "Biometric unlock is available on this device."
            } else {
                "Biometric unlock is not used; the vault PIN is the only unlock method."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

/**
 * Unlocked: the hidden media list.
 *
 * Only reached after a successful unlock. Because no vault items can exist until the
 * hide flow is implemented, this renders the empty state today; it lists metadata
 * only (no thumbnail is generated into shared storage).
 */
@Composable
private fun UnlockedContent(state: VaultViewModel.VaultUiState) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${state.items.size} hidden item${if (state.items.size == 1) "" else "s"}",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.md))
        if (state.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .padding(Spacing.md)
            ) {
                Text(
                    "Nothing is hidden yet. Media you hide will appear here, " +
                        "encrypted on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(bottom = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(state.items, key = { it.vaultItemId }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
        }
    }
}
