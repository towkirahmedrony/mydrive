package com.mydrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.ui.gallery.MediaGroup
import com.mydrive.app.ui.theme.CardShape
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.IvoryMuted
import com.mydrive.app.ui.theme.Mist
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusSyncing
import com.mydrive.app.ui.util.thumbnailBrush

@Composable
fun MediaGrid(
    groups: List<MediaGroup>,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyTitle: String = "No media yet",
    emptyMessage: String = "Your photos and videos will appear here.",
    contentPadding: PaddingValues = PaddingValues(bottom = Spacing.lg),
    header: (@Composable () -> Unit)? = null
) {
    if (groups.isEmpty() && header == null) {
        EmptyState(
            title = emptyTitle,
            message = emptyMessage,
            icon = Icons.Outlined.PhotoLibrary,
            modifier = modifier.fillMaxSize()
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (header != null) {
            item(span = { GridItemSpan(3) }) {
                header()
            }
        }
        if (groups.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                EmptyState(
                    title = emptyTitle,
                    message = emptyMessage,
                    icon = Icons.Outlined.PhotoLibrary
                )
            }
        } else {
            groups.forEach { group ->
                item(span = { GridItemSpan(3) }, key = "g-${group.label}") {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = Mist,
                        modifier = Modifier.padding(
                            start = Spacing.md,
                            end = Spacing.md,
                            top = Spacing.md,
                            bottom = Spacing.xs
                        )
                    )
                }
                items(group.items, key = { it.id }) { item ->
                    MediaThumb(item = item, onClick = { onMediaClick(item.id) })
                }
            }
        }
    }
}

@Composable
fun CompactBackupStatus(
    syncingCount: Int,
    failedCount: Int,
    modifier: Modifier = Modifier
) {
    val (text, color) = when {
        failedCount > 0 && syncingCount > 0 ->
            "$syncingCount items syncing · $failedCount failed" to StatusAttention
        failedCount > 0 ->
            "$failedCount ${if (failedCount == 1) "item" else "items"} failed" to StatusAttention
        syncingCount > 0 ->
            "↑ $syncingCount ${if (syncingCount == 1) "item" else "items"} syncing" to StatusSyncing
        else ->
            "✓ All backed up" to Sage
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.padding(horizontal = Spacing.md)
    )
}

@Composable
fun AlbumCard(
    album: AlbumFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(CardShape)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CardShape)
                .background(thumbnailBrush(album.coverSeed, album.coverType))
        )
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            color = Ivory,
            modifier = Modifier.padding(top = Spacing.xs),
            maxLines = 1
        )
        Text(
            text = "${album.mediaCount} ${if (album.mediaCount == 1) "item" else "items"}",
            style = MaterialTheme.typography.labelSmall,
            color = Mist
        )
    }
}

@Composable
fun AlbumStrip(
    albums: List<AlbumFolder>,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Albums",
            style = MaterialTheme.typography.titleSmall,
            color = IvoryMuted,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            albums.forEach { album ->
                AlbumCard(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    modifier = Modifier.width(112.dp)
                )
            }
        }
    }
}

fun compactBackupLabel(items: List<MediaItem>): Pair<Int, Int> {
    val syncing = items.count {
        it.backupState == BackupState.UPLOADING ||
            it.backupState == BackupState.PROCESSING ||
            it.backupState == BackupState.SENDING_TELEGRAM
    }
    val failed = items.count { it.backupState == BackupState.FAILED }
    return syncing to failed
}
