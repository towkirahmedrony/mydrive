package com.mydrive.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.WifiProtectedSetup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.AppCard
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.components.SecondaryActionButton
import com.mydrive.app.ui.components.SyncStatusChip
import com.mydrive.app.data.model.ConnectionStatus
import com.mydrive.app.ui.theme.Spacing

@Composable
fun TelegramSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val telegram = state.telegram
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
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
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = colors.onSurface)
            }
            Spacer(Modifier.size(Spacing.md))
            Text("Telegram Backup", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
        }

        Spacer(Modifier.height(Spacing.xl))

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Status",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SyncStatusChip(
                    status = if (telegram.connected) ConnectionStatus.CONNECTED else ConnectionStatus.ATTENTION,
                    label = if (telegram.connected) "Connected" else "Not connected"
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                "Telegram is used as your personal backup destination for media.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        AppCard {
            FieldLabel("Bot Token")
            Spacer(Modifier.height(Spacing.xs))
            MaskedField(telegram.botTokenMasked)
            Spacer(Modifier.height(Spacing.md))
            FieldLabel("Chat ID")
            Spacer(Modifier.height(Spacing.xs))
            MaskedField(if (telegram.connected) telegram.chatId else "Not set")
        }

        Spacer(Modifier.height(Spacing.lg))

        if (telegram.connected) {
            SecondaryActionButton(
                text = "Test Connection",
                onClick = {},
                icon = Icons.Outlined.WifiProtectedSetup,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            SecondaryActionButton(
                text = "Disconnect",
                onClick = viewModel::disconnectTelegram,
                icon = Icons.Outlined.LinkOff,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            PrimaryActionButton(
                text = "Connect Telegram",
                onClick = viewModel::connectTelegram,
                icon = Icons.AutoMirrored.Outlined.Send,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MaskedField(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    )
}
