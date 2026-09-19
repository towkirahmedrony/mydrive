package com.mydrive.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.HorizontalDivider
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
import com.mydrive.app.data.model.TelegramConnectionState
import com.mydrive.app.ui.components.SettingsGroup
import com.mydrive.app.ui.components.SettingsRow
import com.mydrive.app.ui.components.SettingsSwitchRow
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenTelegram: () -> Unit,
    onOpenDeveloperConsole: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(
            start = Spacing.md,
            end = Spacing.md,
            top = Spacing.md,
            bottom = Spacing.lg
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
        }

        item {
            SettingsGroup(title = "Account") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Copper),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            state.profile.name.take(1).ifBlank { "A" },
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onPrimary
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column {
                        Text(state.profile.name, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                        Text(state.profile.email, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                SettingsRow(title = "Profile", subtitle = state.profile.name)
                HorizontalDivider(color = colors.outlineVariant)
                SettingsRow(title = "Email", subtitle = state.profile.email)
                HorizontalDivider(color = colors.outlineVariant)
                SettingsRow(title = "Account status", subtitle = state.profile.accountStatus)
                HorizontalDivider(color = colors.outlineVariant)
                SettingsRow(
                    title = "Logout",
                    leading = Icons.AutoMirrored.Outlined.Logout,
                    onClick = viewModel::logout
                )
            }
        }

        item {
            SettingsGroup(title = "Backup") {
                SettingsSwitchRow(
                    title = "Automatic backup",
                    subtitle = "Keep new media backed up in the background",
                    checked = state.preferences.automaticBackup,
                    onCheckedChange = viewModel::setAutomaticBackup
                )
                HorizontalDivider(color = colors.outlineVariant)
                SettingsSwitchRow(
                    title = "Backup photos",
                    checked = state.preferences.backupPhotos,
                    onCheckedChange = viewModel::setBackupPhotos
                )
                HorizontalDivider(color = colors.outlineVariant)
                SettingsSwitchRow(
                    title = "Backup videos",
                    checked = state.preferences.backupVideos,
                    onCheckedChange = viewModel::setBackupVideos
                )
                HorizontalDivider(color = colors.outlineVariant)
                SettingsSwitchRow(
                    title = "Wi-Fi only",
                    subtitle = "Avoid using mobile data for uploads",
                    checked = state.preferences.wifiOnly,
                    onCheckedChange = viewModel::setWifiOnly
                )
                HorizontalDivider(color = colors.outlineVariant)
                SettingsSwitchRow(
                    title = "Upload while charging",
                    checked = state.preferences.uploadWhileCharging,
                    onCheckedChange = viewModel::setUploadWhileCharging
                )
            }
        }

        item {
            SettingsGroup(title = "Telegram") {
                SettingsRow(
                    title = "Telegram Backup",
                    subtitle = state.telegram.settingsLabel(),
                    onClick = onOpenTelegram,
                    trailing = {
                        Text(
                            state.telegram.settingsLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.telegram.connected) Sage else colors.onSurfaceVariant
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant
                        )
                    }
                )
            }
        }

        item {
            SettingsGroup(title = "Other") {
                SettingsRow(
                    title = "Notifications",
                    leading = Icons.Outlined.NotificationsNone,
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                )
                HorizontalDivider(color = colors.outlineVariant)
                SettingsRow(
                    title = "Privacy Policy",
                    leading = Icons.Outlined.Policy,
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                )
                HorizontalDivider(color = colors.outlineVariant)
                SettingsRow(
                    title = "Terms",
                    leading = Icons.Outlined.Description,
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                )
                HorizontalDivider(color = colors.outlineVariant)
                SettingsRow(
                    title = "About Albums",
                    leading = Icons.Outlined.Info,
                    onClick = viewModel::onAboutTapped,
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                )
            }
        }

        item {
            SettingsGroup(title = "Developer") {
                SettingsRow(
                    title = "Developer Logs",
                    subtitle = "Inspect local backup and upload diagnostics",
                    leading = Icons.Outlined.BugReport,
                    onClick = onOpenDeveloperConsole,
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                )
            }
        }
    }
}

private fun com.mydrive.app.data.model.TelegramSettings.settingsLabel(): String = when {
    !enabled -> "Off"
    connectionState == TelegramConnectionState.CONNECTED -> "Connected"
    connectionState == TelegramConnectionState.INCOMPLETE -> "Incomplete"
    tokenConfigured && chatId.isNotBlank() -> "Configured"
    else -> "Not configured"
}
