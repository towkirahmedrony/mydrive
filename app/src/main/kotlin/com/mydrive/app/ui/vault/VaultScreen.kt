package com.mydrive.app.ui.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.local.VaultItemEntity
import com.mydrive.app.data.vault.VaultBiometricGate
import com.mydrive.app.data.vault.VaultPinCrypto
import com.mydrive.app.ui.theme.ChipShape
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.MediaShape
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Spacing

/**
 * Private Vault entry point (Settings → Hidden Photos).
 *
 * SECURITY CONTRACT enforced by this composable:
 *   - the locked, authenticating and setup branches render ONLY the neutral
 *     authentication UI. No thumbnail, file name, count or preview of hidden media
 *     can appear on them, because [VaultViewModel.VaultUiState.items] is empty in
 *     every non-[VaultViewModel.Phase.UNLOCKED] phase and the media gallery is not
 *     composed at all until authentication has succeeded;
 *   - the media gallery lives in its own composable that is only reachable from
 *     the UNLOCKED branch, so the repository-backed grid is never instantiated
 *     under an authentication overlay;
 *   - the whole vault flow is shown with `FLAG_SECURE`, so neither live content
 *     nor the recents/app-switcher preview can capture it;
 *   - authentication is delegated to the existing vault layer — this screen never
 *     verifies a PIN or a biometric itself, and never draws a fake fingerprint UI.
 *
 * The vault session is app-scoped, so this screen owns no cryptographic state.
 */
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onBrowsePhotos: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val fragmentActivity = remember(context) { context.findFragmentActivity() }
    val colors = MaterialTheme.colorScheme

    val launchBiometric: () -> Unit = {
        val host = fragmentActivity
        if (host == null) {
            viewModel.onBiometricResult(VaultBiometricGate.Outcome.UNAVAILABLE)
        } else {
            VaultBiometricGate.authenticate(
                activity = host,
                onResult = { outcome -> viewModel.onBiometricResult(outcome) }
            )
        }
    }

    // Lock on real app background (activity stop) and re-check on resume, where the
    // biometric prompt is (re)offered over the neutral locked screen. Deliberately
    // tied to the Activity lifecycle, not the navigation back stack entry, so moving
    // between the vault and its settings screen does not lock the session.
    DisposableEffect(activity, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onScreenShown()
                    if (viewModel.shouldAutoPromptBiometric()) launchBiometric()
                }
                Lifecycle.Event.ON_STOP -> viewModel.onBackgrounded()
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    // Initial entry: the activity is already resumed, so the observer above will not
    // fire for this first pass.
    LaunchedEffect(Unit) {
        viewModel.onScreenShown()
        if (viewModel.shouldAutoPromptBiometric()) launchBiometric()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        when (state.phase) {
            VaultViewModel.Phase.SETUP_PIN -> VaultSetupPinScreen(
                state = state,
                onBack = onBack,
                onCreated = viewModel::setPin
            )

            VaultViewModel.Phase.SETUP_BIOMETRIC -> VaultSetupBiometricScreen(
                state = state,
                onEnable = {
                    val host = fragmentActivity
                    if (host == null) {
                        viewModel.skipBiometricFromSetup()
                        return@VaultSetupBiometricScreen
                    }
                    VaultBiometricGate.authenticate(
                        activity = host,
                        title = "Enable biometric unlock",
                        subtitle = "Confirm your biometric to unlock the Private Vault faster",
                        negativeButton = "Not now",
                        onResult = { outcome ->
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
                onSkip = viewModel::skipBiometricFromSetup
            )

            VaultViewModel.Phase.LOCKED,
            VaultViewModel.Phase.AUTHENTICATING -> VaultLockedScreen(
                state = state,
                onBack = onBack,
                onRequestBiometric = {
                    if (viewModel.prepareBiometricPrompt()) launchBiometric()
                },
                onSubmitPin = viewModel::submitPin
            )

            VaultViewModel.Phase.LOCKING -> VaultTransitionScreen()

            VaultViewModel.Phase.UNLOCKED -> VaultMediaScreen(
                state = state,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
                onLock = viewModel::onLockRequested,
                onBrowsePhotos = onBrowsePhotos
            )
        }
    }
}

/** Neutral screen shown only while decrypted state is being released. */
@Composable
private fun VaultTransitionScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    )
}

// ── first-time setup ─────────────────────────────────────────────────────────

@Composable
private fun VaultSetupPinScreen(
    state: VaultViewModel.VaultUiState,
    onBack: () -> Unit,
    onCreated: (CharArray) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.md)
    ) {
        VaultBackHeader(title = "Private Vault", onBack = onBack)
        Spacer(Modifier.height(Spacing.xl))
        VaultPinCreatePanel(
            subtitle = "This PIN opens Hidden Photos. It is separate from your device " +
                "lock screen and is never stored in plain text.",
            error = state.error,
            onCreated = onCreated
        )
    }
}

@Composable
private fun VaultSetupBiometricScreen(
    state: VaultViewModel.VaultUiState,
    onEnable: () -> Unit,
    onSkip: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        VaultEmblem()
        Spacer(Modifier.height(Spacing.lg))
        Text(
            "Vault PIN created",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Unlock the Private Vault faster with your device biometrics. " +
                "Your Vault PIN stays available as the fallback, and biometric data " +
                "never leaves your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
        if (state.biometricHardwareAvailable) {
            VaultPrimaryButton(text = "Enable biometrics", onClick = onEnable)
            Spacer(Modifier.height(Spacing.sm))
        }
        Text(
            text = "Not now",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .clip(ChipShape)
                .clickable(onClick = onSkip)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        )
    }
}

// ── locked / authenticating ──────────────────────────────────────────────────

@Composable
private fun VaultLockedScreen(
    state: VaultViewModel.VaultUiState,
    onBack: () -> Unit,
    onRequestBiometric: () -> Unit,
    onSubmitPin: (CharArray) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // Digits live only here and only while locked; leaving this composable discards
    // them. `remember` (not `rememberSaveable`) keeps them out of saved state.
    var digits by remember(state.phase) { mutableStateOf("") }
    var pinMode by remember(state.phase) { mutableStateOf(false) }
    val biometricOffered = state.biometricUnlockEnabled && state.biometricHardwareAvailable
    val authenticating = state.phase == VaultViewModel.Phase.AUTHENTICATING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VaultBackHeader(title = "Hidden Photos", onBack = onBack)
        Spacer(Modifier.height(Spacing.xl))
        VaultEmblem()
        Spacer(Modifier.height(Spacing.lg))
        Text(
            "Private Vault",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = if (biometricOffered && !pinMode) {
                "Unlock with your device biometrics to view your hidden photos and videos."
            } else {
                "Enter your Vault PIN to view your hidden photos and videos."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))

        if (biometricOffered && !pinMode) {
            VaultPrimaryButton(
                text = if (authenticating) "Authenticating…" else "Unlock with biometrics",
                onClick = onRequestBiometric,
                enabled = !authenticating
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "Use Vault PIN",
                style = MaterialTheme.typography.labelLarge,
                color = Copper,
                modifier = Modifier
                    .clip(ChipShape)
                    .clickable { pinMode = true }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        } else {
            VaultPinPad(
                length = digits.length,
                error = state.error,
                onDigit = { digit ->
                    if (digits.length < VaultPinCrypto.MAX_PIN_LENGTH) digits += digit
                },
                onBackspace = { digits = digits.dropLast(1) },
                submitLabel = "Unlock",
                submitEnabled = digits.length >= VaultPinCrypto.MIN_PIN_LENGTH,
                onSubmit = {
                    val entered = digits.toCharArray()
                    digits = ""
                    onSubmitPin(entered)
                }
            )
            if (biometricOffered) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "Use biometrics instead",
                    style = MaterialTheme.typography.labelLarge,
                    color = Copper,
                    modifier = Modifier
                        .clip(ChipShape)
                        .clickable { pinMode = false }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            }
        }
        if (state.lockoutRemainingSeconds > 0L) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "Try again in ${state.lockoutRemainingSeconds}s",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        }
    }
}

// ── unlocked media gallery ───────────────────────────────────────────────────

@Composable
private fun VaultMediaScreen(
    state: VaultViewModel.VaultUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onLock: () -> Unit,
    onBrowsePhotos: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = colors.onBackground)
            }
            Spacer(Modifier.width(Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Private Vault",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground
                )
                if (state.items.isNotEmpty()) {
                    Text(
                        "${state.items.size} hidden item${if (state.items.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Vault options", tint = colors.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Vault settings") },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Lock vault") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onLock()
                        }
                    )
                }
            }
        }

        if (state.items.isEmpty()) {
            VaultEmptyState(onBrowsePhotos = onBrowsePhotos)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.md,
                    end = Spacing.md,
                    top = Spacing.xs,
                    bottom = Spacing.lg
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(state.items, key = { it.vaultItemId }) { item ->
                    VaultMediaTile(item)
                }
            }
        }
    }
}

/**
 * A gallery tile for one hidden item.
 *
 * Intentionally does NOT use the shared public thumbnail pipeline: vault tiles
 * render a neutral placeholder and are never backed by a decrypted preview until
 * the private vault preview cache provides one. Nothing here touches MediaStore or
 * writes into a public/shared cache directory.
 */
@Composable
private fun VaultMediaTile(item: VaultItemEntity) {
    val colors = MaterialTheme.colorScheme
    val isVideo = item.originalMimeType.startsWith("video/", ignoreCase = true)
    val label = if (isVideo) "Hidden video" else "Hidden photo"
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MediaShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, MediaShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
        if (isVideo) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Spacing.xxs)
                    .size(14.dp)
            )
        }
    }
}

@Composable
private fun VaultEmptyState(onBrowsePhotos: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            "No hidden photos yet",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            "Photos and videos you hide will appear here, encrypted on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
        VaultPrimaryButton(text = "Browse Photos", onClick = onBrowsePhotos)
    }
}

// ── shared vault chrome ──────────────────────────────────────────────────────

@Composable
internal fun VaultBackHeader(title: String, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        Spacer(Modifier.width(Spacing.md))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onBackground
        )
    }
}

@Composable
private fun VaultEmblem() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(Radius.lg))
                .background(Copper.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = Copper,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun VaultPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = ChipShape,
        color = if (enabled) Copper else colors.surfaceVariant,
        contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm)
        )
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
