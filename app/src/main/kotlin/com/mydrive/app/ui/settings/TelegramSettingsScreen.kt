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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WifiProtectedSetup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.ConnectionStatus
import com.mydrive.app.data.model.TelegramConnectionState
import com.mydrive.app.ui.components.AppCard
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.components.SecondaryActionButton
import com.mydrive.app.ui.components.SyncStatusChip
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Spacing

@Composable
fun TelegramSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form by viewModel.telegramForm.collectAsStateWithLifecycle()
    val telegram = state.telegram
    val colors = MaterialTheme.colorScheme
    val hasTokenForSave = form.botToken.isNotBlank() || telegram.tokenConfigured
    val chatIdValid = form.chatId.isBlank() || form.chatId.trim().let {
        it.matches(Regex("-?\\d{5,20}")) || it.matches(Regex("@[A-Za-z0-9_]{5,32}"))
    }
    val configurationComplete = hasTokenForSave && chatIdValid && form.chatId.isNotBlank()

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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Telegram Backup", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = if (form.enabled) "Configuration is required before backup is ready." else "Disabled. No Telegram upload will be attempted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = form.enabled,
                    onCheckedChange = viewModel::setTelegramBackupEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onPrimary,
                        checkedTrackColor = Copper,
                        uncheckedThumbColor = colors.onSurface,
                        uncheckedTrackColor = colors.surfaceVariant
                    )
                )
            }
            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Status",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SyncStatusChip(
                    status = telegram.connectionState.toChipStatus(),
                    label = telegram.connectionState.label()
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        AppCard {
            TelegramTokenField(
                value = form.botToken,
                onValueChange = viewModel::updateTelegramBotToken,
                visible = form.tokenVisible,
                onVisibilityChange = viewModel::setTelegramTokenVisible,
                placeholder = if (telegram.tokenConfigured) "Stored token unchanged" else ""
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = form.chatId,
                onValueChange = viewModel::updateTelegramChatId,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Chat ID") },
                singleLine = true,
                isError = !chatIdValid,
                supportingText = {
                    if (!chatIdValid) {
                        Text("Use a numeric ID or @channelusername.")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.saveTelegramConfiguration() }),
                colors = telegramFieldColors()
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        PrimaryActionButton(
            text = "Save Configuration",
            onClick = viewModel::saveTelegramConfiguration,
            icon = Icons.Outlined.Save,
            enabled = chatIdValid && hasTokenForSave,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(Spacing.sm))

        PrimaryActionButton(
            text = "Test Connection",
            onClick = viewModel::testTelegramConnection,
            icon = Icons.Outlined.WifiProtectedSetup,
            enabled = configurationComplete,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(Spacing.sm))

        SecondaryActionButton(
            text = "Clear Configuration",
            onClick = viewModel::clearTelegramConfiguration,
            icon = Icons.Outlined.DeleteOutline,
            modifier = Modifier.fillMaxWidth()
        )

        if (telegram.connectionState == TelegramConnectionState.FAILED) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                "Connection test failed. A real Telegram verification endpoint is not available in this build.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TelegramTokenField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Bot Token") },
        placeholder = {
            if (placeholder.isNotBlank()) {
                Text(placeholder)
            }
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(onClick = { onVisibilityChange(!visible) }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide bot token" else "Show bot token",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = telegramFieldColors()
    )
}

@Composable
private fun telegramFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Copper,
    cursorColor = Copper,
    focusedLabelColor = Copper,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground
)

private fun TelegramConnectionState.label(): String = when (this) {
    TelegramConnectionState.NOT_CONFIGURED -> "Not configured"
    TelegramConnectionState.INCOMPLETE -> "Configuration incomplete"
    TelegramConnectionState.NOT_TESTED -> "Not tested"
    TelegramConnectionState.TESTING -> "Testing..."
    TelegramConnectionState.CONNECTED -> "Connected"
    TelegramConnectionState.FAILED -> "Connection failed"
}

private fun TelegramConnectionState.toChipStatus(): ConnectionStatus = when (this) {
    TelegramConnectionState.CONNECTED -> ConnectionStatus.CONNECTED
    TelegramConnectionState.TESTING -> ConnectionStatus.SYNCING
    TelegramConnectionState.NOT_CONFIGURED,
    TelegramConnectionState.INCOMPLETE,
    TelegramConnectionState.NOT_TESTED,
    TelegramConnectionState.FAILED -> ConnectionStatus.ATTENTION
}
