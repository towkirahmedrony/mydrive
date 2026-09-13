package com.mydrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.ui.gallery.MediaGroup
import com.mydrive.app.ui.theme.CardShape
import com.mydrive.app.ui.theme.MediaShape
import com.mydrive.app.ui.theme.Spacing

private data class GridEntry(
    val key: String,
    val span: Int,
    val groupLabel: String? = null,
    val item: MediaItem? = null
)

@Composable
fun MediaGrid(
    groups: List<MediaGroup>,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyTitle: String = "No media yet",
    emptyMessage: String = "Your photos and videos will appear here.",
    contentPadding: PaddingValues = PaddingValues(bottom = Spacing.lg),
    header: (@Composable () -> Unit)? = null,
    showSkeleton: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val gridState = rememberLazyGridState()
    val entries = remember(groups) {
        buildList {
            groups.forEach { group ->
                add(GridEntry(key = "g-${group.label}", span = 3, groupLabel = group.label))
                group.items.forEach { item ->
                    add(GridEntry(key = item.id, span = 1, item = item))
                }
            }
        }
    }

    if (groups.isEmpty() && header == null && !showSkeleton) {
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
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (header != null) {
            item(key = "header", span = { GridItemSpan(3) }, contentType = "header") {
                header()
            }
        }
        if (showSkeleton && groups.isEmpty()) {
            items(12, key = { "sk-$it" }, contentType = { "skeleton" }) {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(MediaShape)
                        .background(colors.surfaceVariant)
                )
            }
        } else if (groups.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(3) }, contentType = "empty") {
                EmptyState(
                    title = emptyTitle,
                    message = emptyMessage,
                    icon = Icons.Outlined.PhotoLibrary
                )
            }
        } else {
            items(
                items = entries,
                key = { it.key },
                span = { GridItemSpan(it.span) },
                contentType = { entry -> if (entry.item != null) "media" else "label" }
            ) { entry ->
                val item = entry.item
                if (item != null) {
                    MediaThumb(item = item, onClick = { onMediaClick(item.id) })
                } else {
                    Text(
                        text = entry.groupLabel.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = Spacing.md,
                            end = Spacing.md,
                            top = Spacing.md,
                            bottom = Spacing.xs
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumCard(
    album: AlbumFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
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
        ) {
            MediaImage(
                uri = album.coverUri,
                seed = album.coverSeed,
                type = album.coverType,
                modifier = Modifier.fillMaxSize(),
                sizePx = 256,
                contentDescription = album.name
            )
        }
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onBackground,
            modifier = Modifier.padding(top = Spacing.xs),
            maxLines = 1
        )
        Text(
            text = "DEBUG: ${album.mediaCount} ${if (album.mediaCount == 1) "item" else "items"}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Red
        )
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
