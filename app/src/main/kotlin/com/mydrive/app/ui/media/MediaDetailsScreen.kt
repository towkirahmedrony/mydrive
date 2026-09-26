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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.isActive
import com.mydrive.app.ui.components.AppCard
import com.mydrive.app.ui.components.BackupStateChip
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.MediaImage
import com.mydrive.app.ui.components.SecondaryActionButton
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.IvoryMuted
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusConnected
import com.mydrive.app.ui.theme.StatusIdle
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.util.formatDateTime
import com.mydrive.app.ui.util.formatDuration
import com.mydrive.app.ui.util.formatFileSize
import com.mydrive.app.data.model.cacheVersion

@Composable
fun MediaDetailsScreen(
    viewModel: MediaDetailsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item = state.item
    val context = LocalContext.current

    if (item == null) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            TopBar(onBack = onBack)
            EmptyState(title = "Media not found", message = "This item is no longer available.")
        }
        return
    }

    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
    ) {
        TopBar(onBack = onBack)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(horizontal = Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (item.type == MediaType.VIDEO) 16f / 9f else 4f / 3f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.lg)),
                contentAlignment = Alignment.Center
            ) {
                MediaImage(
                    uri = item.displayUri,
                    seed = item.thumbnailSeed,
                    type = item.type,
                    fallbackMediaId = item.remoteMediaId,
                    previewUri = item.thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    sizePx = 720,
                    version = item.cacheVersion
                )
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
            Text(item.filename, style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
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
                if (item.durationSeconds != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    InfoRow("Duration", formatDuration(item.durationSeconds))
                }
                Spacer(Modifier.height(Spacing.sm))
                InfoRow("Device", item.device.ifBlank { "This device" })
                if (item.albumName.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    InfoRow("Album", item.albumName)
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            Text("Backup Status", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
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
                if (item.backupState.isActive) {
                    Spacer(Modifier.height(Spacing.md))
                    LinearProgressIndicator(
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
                    onClick = { shareMedia(context, item) },
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
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
        processingState == BackupState.CANCELLED -> "Cancelled" to IvoryMuted
        processingState == BackupState.PAUSED -> "Paused" to IvoryMuted
        processingState == BackupState.WAITING -> "Waiting" to StatusIdle
        processingState == BackupState.PREPARING -> "Preparing" to StatusSyncing
        processingState == BackupState.UPLOADING -> "Uploading" to StatusSyncing
        processingState == BackupState.PROCESSING -> "Processing" to StatusSyncing
        processingState == BackupState.SENDING_TELEGRAM -> "Telegram sync" to StatusSyncing
        processingState == BackupState.UPLOADING_TO_CLOUDINARY -> "Cloudinary upload" to StatusSyncing
        processingState == BackupState.FINALIZING_SUPABASE -> "Finalizing backup" to StatusSyncing
        name == "Telegram" -> "Not synced yet" to IvoryMuted
        else -> "Backup not started" to IvoryMuted
    }
    val displayLabel = if (name == "Telegram" && completed) "✓ Synced" else label
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Text(displayLabel, style = MaterialTheme.typography.labelLarge, color = if (completed) Sage else color)
    }
}

private fun shareMedia(context: android.content.Context, item: MediaItem) {
    if (item.uri.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType.ifBlank { if (item.type == MediaType.VIDEO) "video/*" else "image/*" }
            putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, item.filename))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share this item", Toast.LENGTH_SHORT).show()
    }
}
