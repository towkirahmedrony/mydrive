package com.mydrive.app.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mydrive.app.data.vault.VaultPinCrypto
import com.mydrive.app.ui.theme.ChipShape
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Spacing

/** Total dots shown for a PIN screen; grows with the entered length. */
@Composable
fun VaultPinDots(
    length: Int,
    modifier: Modifier = Modifier,
    minSlots: Int = VaultPinCrypto.MIN_PIN_LENGTH,
    maxSlots: Int = VaultPinCrypto.MAX_PIN_LENGTH
) {
    val colors = MaterialTheme.colorScheme
    val total = length.coerceAtLeast(minSlots).coerceAtMost(maxSlots)
    Row(
        modifier = modifier.semantics {
            contentDescription = "Vault PIN, $length digits entered"
        },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val filled = index < length
            Box(
                modifier = Modifier
                    .size(if (filled) 12.dp else 10.dp)
                    .clip(CircleShape)
                    .background(if (filled) Copper else colors.surfaceVariant)
                    .border(1.dp, if (filled) Copper else colors.outlineVariant, CircleShape)
            )
        }
    }
}

/**
 * A secure numeric keypad. Digits are held by the caller and never rendered as
 * text; only the count is shown. Nothing is logged or described beyond the count,
 * so the PIN is not exposed through accessibility or the view hierarchy.
 */
@Composable
fun VaultPinPad(
    length: Int,
    error: String?,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String? = null,
    submitEnabled: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    disabled: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Spacing.sm))
        VaultPinDots(length = length)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = error ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9")
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { digit ->
                    PadKey(digit, enabled = !disabled) { onDigit(digit.first()) }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.size(PadKeySize))
            PadKey("0", enabled = !disabled) { onDigit('0') }
            Box(
                modifier = Modifier
                    .size(PadKeySize)
                    .clip(CircleShape)
                    .clickable(enabled = !disabled && length > 0, onClick = onBackspace)
                    .semantics { contentDescription = "Delete last digit" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = null,
                    tint = if (!disabled && length > 0) colors.onBackground else colors.onSurfaceVariant
                )
            }
        }

        if (submitLabel != null && onSubmit != null) {
            Spacer(Modifier.height(Spacing.lg))
            Surface(
                onClick = onSubmit,
                enabled = submitEnabled && !disabled,
                modifier = Modifier.fillMaxWidth(),
                shape = ChipShape,
                color = if (submitEnabled && !disabled) Copper else colors.surfaceVariant,
                contentColor = if (submitEnabled && !disabled) colors.onPrimary else colors.onSurfaceVariant
            ) {
                Text(
                    text = submitLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.sm)
                )
            }
        }
    }
}

private val PadKeySize = 68.dp

@Composable
private fun PadKey(digit: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(PadKeySize)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Digit $digit" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit,
            style = MaterialTheme.typography.headlineMedium,
            color = if (enabled) colors.onBackground else colors.onSurfaceVariant
        )
    }
}

private enum class CreateStage { CREATE, CONFIRM }

/**
 * Two-step PIN creation (enter, then confirm) used by both first-run enrolment
 * and "Change Vault PIN". The digits live in local, non-saveable state so they
 * are not written to a saved-state bundle, and are handed over as a CharArray the
 * caller wipes once stored.
 */
@Composable
fun VaultPinCreatePanel(
    subtitle: String,
    onCreated: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Create a Vault PIN",
    onCancel: (() -> Unit)? = null,
    error: String? = null
) {
    var stage by remember { mutableStateOf(CreateStage.CREATE) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val active = if (stage == CreateStage.CREATE) first else second
    val isConfirm = stage == CreateStage.CONFIRM

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isConfirm) "Confirm your Vault PIN" else title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = if (isConfirm) "Enter the same PIN once more." else subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        VaultPinPad(
            length = active.length,
            error = localError ?: error,
            onDigit = { digit ->
                localError = null
                if (stage == CreateStage.CREATE && first.length < VaultPinCrypto.MAX_PIN_LENGTH) {
                    first += digit
                } else if (stage == CreateStage.CONFIRM && second.length < VaultPinCrypto.MAX_PIN_LENGTH) {
                    second += digit
                }
            },
            onBackspace = {
                localError = null
                if (stage == CreateStage.CREATE) {
                    first = first.dropLast(1)
                } else {
                    second = second.dropLast(1)
                }
            },
            submitLabel = if (isConfirm) "Save PIN" else "Continue",
            submitEnabled = active.length >= VaultPinCrypto.MIN_PIN_LENGTH,
            onSubmit = {
                if (stage == CreateStage.CREATE) {
                    stage = CreateStage.CONFIRM
                    localError = null
                } else if (second == first) {
                    val chars = first.toCharArray()
                    first = ""
                    second = ""
                    stage = CreateStage.CREATE
                    onCreated(chars)
                } else {
                    second = ""
                    localError = "PINs do not match. Try again."
                }
            }
        )
        if (onCancel != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = Copper,
                modifier = Modifier
                    .clip(ChipShape)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}
