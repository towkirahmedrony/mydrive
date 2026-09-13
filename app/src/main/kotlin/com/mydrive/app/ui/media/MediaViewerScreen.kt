package com.mydrive.app.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Graphite
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Mist
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.theme.Stroke
import com.mydrive.app.ui.util.formatDuration
import com.mydrive.app.ui.util.thumbnailBrush

@Composable
fun MediaViewerScreen(
    viewModel: MediaViewerViewModel,
    onBack: () -> Unit,
    onOpenDetails: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink),
            contentAlignment = Alignment.Center
        ) {
            Text("Media not found", color = Ivory)
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = state.initialIndex,
        pageCount = { state.items.size }
    )
    LaunchedEffect(state.initialIndex) {
        if (pagerState.currentPage != state.initialIndex && state.initialIndex in state.items.indices) {
            pagerState.scrollToPage(state.initialIndex)
        }
    }

    val current = state.items.getOrNull(pagerState.currentPage) ?: state.items.first()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = state.items[page]
            ViewerPage(item = item)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(listOf(Ink.copy(alpha = 0.72f), Color.Transparent))
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                Spacer(Modifier.weight(1f))
                CircleIcon(Icons.Outlined.Info, "Details") { onOpenDetails(current.id) }
                Spacer(Modifier.size(Spacing.xs))
                CircleIcon(Icons.Outlined.MoreVert, "More") {}
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.86f)))
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            if (current.type == MediaType.VIDEO) {
                VideoControls(item = current)
            }
            Text(current.filename, style = MaterialTheme.typography.titleSmall, color = Ivory, maxLines = 1)
            Text(
                backupStatusLabel(current),
                style = MaterialTheme.typography.labelMedium,
                color = backupStatusColor(current),
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ViewerAction(
                    icon = if (current.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    label = "Favorite",
                    tint = if (current.isFavorite) Copper else Ivory
                ) { viewModel.toggleFavorite(current.id) }
                ViewerAction(Icons.Outlined.Share, "Share") {}
                ViewerAction(Icons.Outlined.Info, "Info") { onOpenDetails(current.id) }
            }
        }
    }
}

@Composable
private fun ViewerPage(item: MediaItem) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(thumbnailBrush(item.thumbnailSeed, item.type)),
        contentAlignment = Alignment.Center
    ) {
        if (item.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Play",
                    tint = Ivory,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

@Composable
private fun VideoControls(item: MediaItem) {
    var playing by remember(item.id) { mutableStateOf(false) }
    var progress by remember(item.id) { mutableFloatStateOf(0.22f) }
    val duration = item.durationSeconds ?: 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Copper)
                .clickable { playing = !playing },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Ink
            )
        }
        Slider(
            value = progress,
            onValueChange = { progress = it },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.sm),
            colors = SliderDefaults.colors(
                thumbColor = Copper,
                activeTrackColor = Copper,
                inactiveTrackColor = Mist.copy(alpha = 0.35f)
            )
        )
        Text(
            text = "${formatDuration((duration * progress).toInt())} / ${formatDuration(duration)}",
            style = MaterialTheme.typography.labelSmall,
            color = Ivory
        )
    }
}

@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = Ivory,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Mist, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CircleIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Graphite.copy(alpha = 0.72f))
            .border(1.dp, Stroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ivory, modifier = Modifier.size(20.dp))
    }
}

private fun backupStatusLabel(item: MediaItem): String = when (item.backupState) {
    BackupState.COMPLETED -> "✓ Backed up"
    BackupState.UPLOADING -> "Uploading"
    BackupState.PROCESSING -> "Processing"
    BackupState.SENDING_TELEGRAM -> "Telegram sync"
    BackupState.WAITING -> "Waiting"
    BackupState.FAILED -> "Backup failed"
}

private fun backupStatusColor(item: MediaItem) = when (item.backupState) {
    BackupState.COMPLETED -> Sage
    BackupState.FAILED -> StatusAttention
    BackupState.WAITING -> Mist
    else -> StatusSyncing
}
