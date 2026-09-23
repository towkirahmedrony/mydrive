package com.mydrive.app.ui.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.isActive
import com.mydrive.app.ui.components.AppCard
import com.mydrive.app.ui.components.BackupStateChip
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.MediaImage
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.components.SecondaryActionButton
import com.mydrive.app.ui.components.SectionHeader
import com.mydrive.app.ui.components.StatCard
import com.mydrive.app.ui.permission.MediaPermissionScreen
import com.mydrive.app.ui.theme.ChipShape
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusConnected
import com.mydrive.app.ui.theme.StatusIdle
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.util.formatFileSize
import com.mydrive.app.ui.util.formatTimeAgo

@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    onMediaClick: (String) -> Unit,
    onOpenTelegramSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionResult()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(force = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state.needsPermission || state.permissionDenied) {
        MediaPermissionScreen(
            denied = state.permissionDenied,
            onAllowAccess = { permissionLauncher.launch(viewModel.permissionPermissions()) },
            onOpenSettings = { openAppSettings(context) }
        )
        return
    }

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
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item(key = "header") {
            Text("Sync", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
        }

        item(key = "status") {
            StatusCard(state)
        }

        if (state.hasMedia) {
            item(key = "summary") {
                SummaryRow(
                    completed = state.completedCount,
                    waiting = state.pendingCount,
                    uploading = state.activeCount,
                    failed = state.failedCount
                )
            }
        }

        item(key = "actions") {
            SyncActions(
                state = state,
                viewModel = viewModel,
                onOpenTelegramSettings = onOpenTelegramSettings
            )
        }

        if (state.active.isNotEmpty()) {
            item(key = "active-header") { SectionHeader(title = "In progress") }
            items(state.active, key = { "active-${it.media.id}" }) { job ->
                SyncJobRow(
                    job = job,
                    onClick = { onMediaClick(job.media.id) },
                    showProgress = true
                )
            }
        }

        if (state.waiting.isNotEmpty()) {
            item(key = "waiting-header") { SectionHeader(title = "Waiting") }
            items(state.waiting, key = { "waiting-${it.media.id}" }) { job ->
                SyncJobRow(
                    job = job,
                    onClick = { onMediaClick(job.media.id) },
                    onClickCancel = { viewModel.cancel(job.media.id) }
                )
            }
        }

        if (state.failed.isNotEmpty()) {
            item(key = "failed-header") { SectionHeader(title = "Needs attention") }
            items(state.failed, key = { "failed-${it.media.id}" }) { job ->
                FailedRow(
                    job = job,
                    onRetry = { viewModel.retry(job.media.id) },
                    onClick = { onMediaClick(job.media.id) }
                )
            }
            item(key = "failed-action") {
                PrimaryActionButton(
                    text = "Retry all",
                    onClick = viewModel::retryFailed,
                    icon = Icons.Outlined.Refresh,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (state.completed.isNotEmpty()) {
            item(key = "completed-header") {
                SectionHeader(title = "Backed up (${state.completedCount})")
            }
            items(state.completed.take(MAX_COMPLETED_ROWS), key = { "completed-${it.media.id}" }) { job ->
                SyncJobRow(job = job, onClick = { onMediaClick(job.media.id) })
            }
        }

        if (!state.hasMedia) {
            item(key = "empty") {
                EmptyState(
                    title = "No media to back up",
                    message = "Photos and videos on this device will appear here.",
                    icon = Icons.Outlined.CloudQueue
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: SyncUiState) {
    val colors = MaterialTheme.colorScheme
    val (statusColor, statusIcon) = statusVisual(state.status)
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.headline,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = state.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(statusColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            }
        }
        if (state.status == SyncStatus.IN_PROGRESS) {
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(ChipShape),
                color = Copper,
                trackColor = colors.background.copy(alpha = 0.4f)
            )
        }
        if (state.lastUpdatedMillis > 0L) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "Last activity ${formatTimeAgo(state.lastUpdatedMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryRow(completed: Int, waiting: Int, uploading: Int, failed: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        StatCard(
            label = "Completed",
            value = completed.toString(),
            accent = StatusConnected,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Waiting",
            value = waiting.toString(),
            accent = StatusSyncing,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Uploading",
            value = uploading.toString(),
            accent = Copper,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Failed",
            value = failed.toString(),
            accent = StatusAttention,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TelegramNotice(
    state: SyncUiState,
    onOpenTelegramSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val message = when {
        !state.telegramEnabled ->
            "Telegram backup is off. Turn it on to upload photos to your Telegram chat."
        !state.telegramConfigured ->
            "Telegram isn't configured yet. Add your bot token and chat ID."
        !state.telegramConnected ->
            "Telegram setup isn't verified. Test the connection before backing up."
        else -> return
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = StatusAttention,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Telegram backup unavailable",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        SecondaryActionButton(
            text = "Open Telegram Setup",
            onClick = onOpenTelegramSettings,
            icon = Icons.Outlined.CloudUpload,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SyncActions(
    state: SyncUiState,
    viewModel: SyncViewModel,
    onOpenTelegramSettings: () -> Unit
) {
    if (!state.hasMedia) {
        SecondaryActionButton(
            text = "Check for new media",
            onClick = { viewModel.refresh(force = true) },
            icon = Icons.Outlined.Refresh,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (!state.telegramReady) {
            TelegramNotice(state = state, onOpenTelegramSettings = onOpenTelegramSettings)
        }
        state.actionNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = StatusAttention
            )
        }
        if (state.hasEligible) {
            PrimaryActionButton(
                text = "Start Backup",
                onClick = viewModel::startBackup,
                icon = Icons.Outlined.CloudUpload,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.pendingVideoCount > 0 && state.eligiblePhotoCount == 0) {
            Text(
                text = "Video backup isn't available yet. Only photos can be uploaded for now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (state.status == SyncStatus.PAUSED || state.status == SyncStatus.WAITING) {
                SecondaryActionButton(
                    text = "Resume",
                    onClick = viewModel::resumeBackup,
                    icon = Icons.Outlined.PlayArrow,
                    modifier = Modifier.weight(1f)
                )
            } else if (state.status == SyncStatus.IN_PROGRESS) {
                SecondaryActionButton(
                    text = "Pause",
                    onClick = viewModel::pauseBackup,
                    icon = Icons.Outlined.Pause,
                    modifier = Modifier.weight(1f)
                )
            }
            SecondaryActionButton(
                text = "Check for new media",
                onClick = { viewModel.refresh(force = true) },
                icon = Icons.Outlined.Refresh,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SyncJobRow(
    job: SyncJob,
    onClick: () -> Unit,
    showProgress: Boolean = false,
    onClickCancel: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JobThumbnail(job)
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (job.media.type == MediaType.VIDEO) {
                        Icons.Outlined.Videocam
                    } else {
                        Icons.Outlined.Image
                    },
                    contentDescription = if (job.media.type == MediaType.VIDEO) "Video" else "Photo",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(Spacing.xxs))
                Text(
                    text = job.media.filename,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = jobSubtitle(job),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            if (showProgress && job.state.isActive) {
                Spacer(Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(ChipShape),
                    color = Copper,
                    trackColor = colors.background.copy(alpha = 0.4f)
                )
            }
        }
        Spacer(Modifier.width(Spacing.xs))
        if (onClickCancel != null) {
            Icon(
                imageVector = Icons.Outlined.Cancel,
                contentDescription = "Cancel",
                tint = colors.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onClickCancel)
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        BackupStateChip(job.state)
    }
}

@Composable
private fun FailedRow(
    job: SyncJob,
    onRetry: () -> Unit,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JobThumbnail(job)
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = job.media.filename,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = safeError(job),
                style = MaterialTheme.typography.bodySmall,
                color = StatusAttention,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        SecondaryActionButton(text = "Retry", onClick = onRetry)
    }
}

@Composable
private fun JobThumbnail(job: SyncJob) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(Radius.sm))
    ) {
        MediaImage(
            uri = job.media.displayUri,
            seed = job.media.thumbnailSeed,
            type = job.media.type,
            fallbackMediaId = job.media.remoteMediaId,
            modifier = Modifier.fillMaxSize(),
            sizePx = 128
        )
        if (job.media.type == MediaType.VIDEO) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(20.dp)
            )
        }
    }
}

private fun jobSubtitle(job: SyncJob): String {
    val parts = mutableListOf(
        if (job.media.type == MediaType.VIDEO) "Video" else "Photo",
        formatFileSize(job.media.fileSizeBytes)
    )
    if (job.updatedAtMillis > 0L) {
        parts += when (job.state) {
            BackupState.WAITING -> "Queued ${formatTimeAgo(job.updatedAtMillis)}"
            BackupState.COMPLETED -> "Backed up ${formatTimeAgo(job.updatedAtMillis)}"
            else -> "${stateLabel(job.state)} ${formatTimeAgo(job.updatedAtMillis)}"
        }
    } else {
        parts += stateLabel(job.state)
    }
    return parts.joinToString(" · ")
}

private fun stateLabel(state: BackupState): String = when (state) {
    BackupState.COMPLETED -> "Completed"
    BackupState.NOT_STARTED -> "Not backed up"
    BackupState.PREPARING -> "Preparing"
    BackupState.UPLOADING -> "Uploading"
    BackupState.PROCESSING -> "Processing"
    BackupState.SENDING_TELEGRAM -> "Telegram sync"
    BackupState.WAITING -> "Waiting"
    BackupState.PAUSED -> "Paused"
    BackupState.FAILED -> "Failed"
    BackupState.CANCELLED -> "Cancelled"
    BackupState.REQUESTING_CLOUDINARY_AUTH -> "Requesting auth"
    BackupState.UPLOADING_TO_CLOUDINARY -> "Cloudinary upload"
    BackupState.CLOUDINARY_COMPLETED -> "Cloudinary done"
    BackupState.FINALIZING_SUPABASE -> "Finalizing backup"
}

private fun safeError(job: SyncJob): String {
    val message = job.errorMessage
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.take(120)
    return message?.takeIf { it.isNotBlank() } ?: "Couldn't back up this item. Tap retry to try again."
}

private fun statusVisual(status: SyncStatus): Pair<Color, ImageVector> = when (status) {
    SyncStatus.NO_MEDIA -> StatusIdle to Icons.Outlined.CloudQueue
    SyncStatus.ALL_BACKED_UP -> StatusConnected to Icons.Outlined.CloudDone
    SyncStatus.READY -> StatusIdle to Icons.Outlined.CloudUpload
    SyncStatus.WAITING -> StatusSyncing to Icons.Outlined.CloudQueue
    SyncStatus.IN_PROGRESS -> StatusSyncing to Icons.Outlined.CloudUpload
    SyncStatus.PAUSED -> StatusIdle to Icons.Outlined.Pause
    SyncStatus.FAILED -> StatusAttention to Icons.Outlined.ErrorOutline
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    context.startActivity(intent)
}

private const val MAX_COMPLETED_ROWS = 50
