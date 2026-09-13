package com.mydrive.app.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.components.AppCard
import com.mydrive.app.ui.components.BackupStateChip
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.SecondaryActionButton
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Graphite
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.IvoryMuted
import com.mydrive.app.ui.theme.Mist
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusConnected
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.theme.Stroke
import com.mydrive.app.ui.util.formatDateTime
import com.mydrive.app.ui.util.formatDuration
import com.mydrive.app.ui.util.formatFileSize
import com.mydrive.app.ui.util.thumbnailBrush

@Composable
fun MediaDetailsScreen(
    viewModel: MediaDetailsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item = state.item

    if (item == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink)
        ) {
            TopBar(onBack = onBack)
            EmptyState(title = "Media not found", message = "This item is no longer available.")
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
    ) {
        TopBar(onBack = onBack)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink)
                .padding(horizontal = Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (item.type == MediaType.VIDEO) 16f / 9f else 4f / 3f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.lg))
                    .background(thumbnailBrush(item.thumbnailSeed, item.type)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(androidx.compose.ui.graphics.Color.Transparent, Ink.copy(alpha = 0.35f))
                            )
                        )
                )
                if (item.type == MediaType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Ink.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Play", tint = Ivory, modifier = Modifier.size(32.dp))
                    }
                    if (item.durationSeconds != null) {
                        Text(
                            text = formatDuration(item.durationSeconds),
                            style = MaterialTheme.typography.labelMedium,
                            color = Ivory,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(Spacing.sm)
                                .clip(CircleShape)
                                .background(Ink.copy(alpha = 0.7f))
                                .padding(horizontal = Spacing.sm, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(item.filename, style = MaterialTheme.typography.titleLarge, color = Ivory)
            Spacer(Modifier.height(Spacing.xs))
            BackupStateChip(item.backupState)
            Spacer(Modifier.height(Spacing.lg))

            AppCard {
                InfoRow("Filename", item.filename)
                Spacer(Modifier.height(Spacing.sm))
                InfoRow("File size", formatFileSize(item.fileSizeBytes))
                Spacer(Modifier.height(Spacing.sm))
                InfoRow("Date", formatDateTime(item.capturedAtMillis))
                Spacer(Modifier.height(Spacing.sm))
                InfoRow("Resolution", item.resolution)
                Spacer(Modifier.height(Spacing.sm))
                InfoRow("Media type", if (item.type == MediaType.VIDEO) "Video" else "Photo")
                Spacer(Modifier.height(Spacing.sm))
                InfoRow("Device", item.device)
            }

            Spacer(Modifier.height(Spacing.lg))
            Text("Backup Status", style = MaterialTheme.typography.titleMedium, color = Ivory)
            Spacer(Modifier.height(Spacing.sm))
            AppCard {
                DestinationRow(
                    name = "Backup",
                    completed = item.backupCompleted,
                    processingState = item.backupState.takeIf { !item.backupCompleted }
                )
                Spacer(Modifier.height(Spacing.md))
                DestinationRow(
                    name = "Telegram",
                    completed = item.telegramCompleted,
                    processingState = item.backupState.takeIf {
                        item.backupCompleted && !item.telegramCompleted
                    }
                )
                if (item.backupState != BackupState.COMPLETED && item.backupState != BackupState.FAILED && item.backupState != BackupState.WAITING) {
                    Spacer(Modifier.height(Spacing.md))
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = Copper,
                        trackColor = Ink.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                SecondaryActionButton(
                    text = "Share",
                    onClick = {},
                    icon = Icons.Outlined.Share,
                    modifier = Modifier.weight(1f)
                )
                SecondaryActionButton(
                    text = if (item.isFavorite) "Favorited" else "Favorite",
                    onClick = viewModel::toggleFavorite,
                    icon = if (item.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    modifier = Modifier.weight(1f)
                )
            }
            if (item.backupState == BackupState.FAILED) {
                Spacer(Modifier.height(Spacing.sm))
                SecondaryActionButton(
                    text = "Retry",
                    onClick = viewModel::retryBackup,
                    icon = Icons.Outlined.Refresh,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
        Spacer(Modifier.weight(1f))
        CircleIcon(Icons.Outlined.MoreVert, "More") {}
    }
}

@Composable
private fun CircleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Graphite)
            .border(1.dp, Stroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ivory, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Mist, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Ivory)
    }
}

@Composable
private fun DestinationRow(
    name: String,
    completed: Boolean,
    processingState: BackupState?
) {
    val (label, color) = when {
        completed -> "✓ Backup completed" to StatusConnected
        processingState == BackupState.FAILED -> "Failed" to StatusAttention
        processingState == BackupState.WAITING -> "Waiting" to Mist
        processingState == BackupState.UPLOADING -> "Uploading" to StatusSyncing
        processingState == BackupState.PROCESSING -> "Processing" to StatusSyncing
        processingState == BackupState.SENDING_TELEGRAM -> "Telegram sync" to StatusSyncing
        else -> "Pending" to IvoryMuted
    }
    val displayLabel = if (name == "Telegram" && completed) "✓ Synced" else label
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = Ivory, modifier = Modifier.weight(1f))
        Text(displayLabel, style = MaterialTheme.typography.labelLarge, color = if (completed) Sage else color)
    }
}
