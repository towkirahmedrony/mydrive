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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.components.MediaImage
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusIdle
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.util.formatDuration

@Composable
fun MediaViewerScreen(
    viewModel: MediaViewerViewModel,
    onBack: () -> Unit,
    onOpenDetails: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    if (state.items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
            contentAlignment = Alignment.Center
        ) {
            Text("Media not found", color = colors.onBackground)
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
    val context = LocalContext.current

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
            ViewerPage(item = item, background = colors.background)
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
                ViewerAction(Icons.Outlined.Share, "Share") { shareMedia(context, current) }
                ViewerAction(Icons.Outlined.Info, "Info") { onOpenDetails(current.id) }
            }
        }
    }
}

@Composable
private fun ViewerPage(item: MediaItem, background: Color) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .clickable(enabled = item.type == MediaType.VIDEO) {
                openMediaExternally(context, item)
            },
        contentAlignment = Alignment.Center
    ) {
        MediaImage(
            uri = item.uri,
            seed = item.thumbnailSeed,
            type = item.type,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            sizePx = 720,
            contentDescription = item.filename,
            placeholderBitmap = com.mydrive.app.data.media.ThumbnailLoader.peek(item.uri, 256)
        )
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
    val context = LocalContext.current
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
                .clickable {
                    playing = !playing
                    if (playing) openMediaExternally(context, item)
                },
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
                inactiveTrackColor = StatusIdle.copy(alpha = 0.35f)
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = StatusIdle, modifier = Modifier.padding(top = 4.dp))
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
            .background(Ink.copy(alpha = 0.55f))
            .border(1.dp, Ivory.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ivory, modifier = Modifier.size(20.dp))
    }
}

private fun backupStatusLabel(item: MediaItem): String = when (item.backupState) {
    BackupState.COMPLETED -> "✓ Backed up"
    BackupState.NOT_STARTED -> "Backup not started"
    BackupState.UPLOADING -> "Uploading"
    BackupState.PROCESSING -> "Processing"
    BackupState.SENDING_TELEGRAM -> "Telegram sync"
    BackupState.WAITING -> "Waiting"
    BackupState.FAILED -> "Backup failed"
}

private fun backupStatusColor(item: MediaItem) = when (item.backupState) {
    BackupState.COMPLETED -> Sage
    BackupState.FAILED -> StatusAttention
    BackupState.WAITING, BackupState.NOT_STARTED -> StatusIdle
    else -> StatusSyncing
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

private fun openMediaExternally(context: android.content.Context, item: MediaItem) {
    if (item.uri.isBlank()) {
        Toast.makeText(context, "This video can't be opened", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse(item.uri),
                item.mimeType.ifBlank { "video/*" }
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "This video can't be opened", Toast.LENGTH_SHORT).show()
    }
}
