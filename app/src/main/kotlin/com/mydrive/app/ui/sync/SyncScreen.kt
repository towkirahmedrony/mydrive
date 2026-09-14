package com.mydrive.app.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.ui.components.BackupStateChip
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.components.ProgressCard
import com.mydrive.app.ui.components.SecondaryActionButton
import com.mydrive.app.ui.components.SectionHeader
import com.mydrive.app.ui.components.stateVisual
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.components.MediaImage
import com.mydrive.app.ui.util.formatFileSize

@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    onMediaClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val headlineColor = when {
        state.failed.isNotEmpty() -> StatusAttention
        state.inProgress.isNotEmpty() || state.waiting.isNotEmpty() -> StatusSyncing
        else -> Sage
    }

    val colors = MaterialTheme.colorScheme
    val empty = state.inProgress.isEmpty() &&
        state.waiting.isEmpty() &&
        state.failed.isEmpty() &&
        state.completed.isEmpty() &&
        state.notStartedCount == 0

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
        item {
            Text("Sync", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Spacer(Modifier.height(Spacing.xxs))
            Text(state.headline, style = MaterialTheme.typography.bodyMedium, color = headlineColor)
        }

        if (state.totalMediaCount > 0) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SummaryValue("Backed up", state.completedCount)
                    SummaryValue("Waiting", state.waiting.size + state.notStartedCount)
                    SummaryValue("Failed", state.failed.size)
                }
            }
        }

        if (state.notStartedCount > 0) {
            item {
                PrimaryActionButton(
                    text = "Start backup",
                    onClick = viewModel::startSync,
                    icon = CloudUpload,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (state.inProgress.isNotEmpty()) {
            item { SectionHeader(title = "In Progress") }
            items(state.inProgress, key = { it.id }) { item ->
                Box(modifier = Modifier.clickable { onMediaClick(item.id) }) {
                    ProgressCard(
                        title = item.filename,
                        filename = item.filename,
                        progress = item.progress,
                        stateLabel = stateVisual(item.backupState).first,
                        fileSize = formatFileSize(item.fileSizeBytes),
                        thumbnail = { MiniThumb(item) }
                    )
                }
            }
        }

        if (state.waiting.isNotEmpty()) {
            item { SectionHeader(title = "Waiting") }
            items(state.waiting, key = { it.id }) { item ->
                SyncRow(item = item, onClick = { onMediaClick(item.id) })
            }
        }

        if (state.completed.isNotEmpty()) {
            item { SectionHeader(title = "Completed") }
            items(state.completed, key = { it.id }) { item ->
                SyncRow(
                    item = item,
                    onClick = { onMediaClick(item.id) }
                )
            }
        }

        if (state.failed.isNotEmpty()) {
            item { SectionHeader(title = "Failed") }
            items(state.failed, key = { it.id }) { item ->
                FailedRow(
                    item = item,
                    onRetry = { viewModel.retry(item.id) },
                    onClick = { onMediaClick(item.id) }
                )
            }
            item {
                Spacer(Modifier.height(Spacing.xs))
                PrimaryActionButton(
                    text = "Retry failed",
                    onClick = viewModel::retryFailed,
                    icon = Icons.Outlined.Refresh,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (empty) {
            item {
                EmptyState(
                    title = if (state.totalMediaCount == 0) "No media found" else "Everything is up to date",
                    message = if (state.totalMediaCount == 0) {
                        "Photos and videos will appear here when available."
                    } else {
                        "New backups will appear here as they start."
                    }
                )
            }
        }
    }
}

@Composable
private fun RowScope.SummaryValue(label: String, value: Int) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.sm)
    ) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniThumb(item: MediaItem) {
    MediaImage(
        uri = item.uri,
        seed = item.thumbnailSeed,
        type = item.type,
        modifier = Modifier.fillMaxSize(),
        sizePx = 160
    )
}

@Composable
private fun SyncRow(
    item: MediaItem,
    onClick: () -> Unit,
    extra: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(Radius.sm))
        ) {
            MiniThumb(item)
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.filename,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                extra ?: formatFileSize(item.fileSizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BackupStateChip(item.backupState)
    }
}

@Composable
private fun FailedRow(
    item: MediaItem,
    onRetry: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(Radius.sm))
        ) {
            MiniThumb(item)
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.filename,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.errorMessage ?: "Upload failed",
                style = MaterialTheme.typography.bodySmall,
                color = StatusAttention
            )
        }
        SecondaryActionButton(text = "Retry", onClick = onRetry)
    }
}
