package com.mydrive.app.ui.albums

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.AlbumCard
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.SearchField
import com.mydrive.app.ui.permission.MediaPermissionScreen
import com.mydrive.app.ui.theme.CardShape
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.util.formatFileSize

@Composable
fun AlbumsScreen(
    viewModel: AlbumsViewModel,
    onAlbumClick: (String) -> Unit,
    onTrashClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

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
            onAllowAccess = {
                permissionLauncher.launch(viewModel.permissionPermissions())
            },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Albums",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButtonCircle(
                icon = if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                contentDescription = if (searchOpen) "Close search" else "Search albums"
            ) {
                searchOpen = !searchOpen
                if (!searchOpen) viewModel.setQuery("")
            }
        }

        if (state.isLoading && state.albums.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Copper,
                trackColor = colors.surfaceVariant
            )
        }

        if (searchOpen) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = "Search albums",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.xs,
                bottom = Spacing.sm
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            when {
                state.isLoading && state.albums.isEmpty() && state.query.isBlank() -> {
                    item(key = "empty-loading", span = { GridItemSpan(2) }) {
                        EmptyState(
                            title = "No albums found",
                            message = "Albums from your device will appear here.",
                            icon = Icons.Outlined.PhotoLibrary
                        )
                    }
                }
                state.errorMessage != null && state.albums.isEmpty() -> {
                    item(key = "empty-error", span = { GridItemSpan(2) }) {
                        EmptyState(
                            title = "Couldn't load albums",
                            message = state.errorMessage ?: "Please try again.",
                            icon = Icons.Outlined.PhotoLibrary
                        )
                    }
                }
                state.albums.isEmpty() -> {
                    item(key = "empty-albums", span = { GridItemSpan(2) }) {
                        EmptyState(
                            title = "No albums found",
                            message = if (state.query.isNotBlank()) {
                                "No matches for that name."
                            } else {
                                "Albums from your device will appear here."
                            },
                            icon = Icons.Outlined.PhotoLibrary
                        )
                    }
                }
                else -> {
                    items(state.albums, key = { it.id }) { album ->
                        AlbumCard(
                            album = album,
                            onClick = { onAlbumClick(album.id) }
                        )
                    }
                }
            }
        }
        if (state.query.isBlank()) {
            TrashAlbumEntry(
                count = state.trashCount,
                sizeBytes = state.trashSizeBytes,
                onClick = onTrashClick,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }
    }
}

@Composable
private fun TrashAlbumEntry(
    count: Int,
    sizeBytes: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val subtitle = buildString {
        append(if (count == 1) "1 item" else "$count items")
        if (sizeBytes > 0L) {
            append(" · ")
            append(formatFileSize(sizeBytes))
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.md)
        ) {
            Text(
                text = "Trash",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IconButtonCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.onSurface, modifier = Modifier.size(20.dp))
    }
}
