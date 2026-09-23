package com.mydrive.app.ui.gallery

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.MediaGrid
import com.mydrive.app.ui.components.SearchField
import com.mydrive.app.ui.permission.MediaPermissionScreen
import com.mydrive.app.ui.theme.ChipShape
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Spacing

@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onMediaClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
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

    if ((state.needsPermission || state.permissionDenied) && !state.hasMedia && !state.isLoading) {
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
                text = "Photos",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButtonCircle(
                icon = if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                contentDescription = if (searchOpen) "Close search" else "Search"
            ) {
                searchOpen = !searchOpen
                if (!searchOpen) viewModel.setQuery("")
            }
        }

        if ((state.isLoading || state.isRefreshing) && state.hasMedia) {
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
                placeholder = "Search photos and videos",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        }

        FilterRow(
            selected = state.filter,
            onSelect = viewModel::setFilter,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )

        when {
            state.isLoading && !state.hasMedia -> {
                MediaGrid(
                    groups = emptyList(),
                    onMediaClick = onMediaClick,
                    emptyTitle = "No photos or videos yet",
                    emptyMessage = "Your photos and videos will appear here.",
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                    showSkeleton = true
                )
            }
            state.errorMessage != null && !state.hasMedia -> {
                EmptyState(
                    title = "Couldn't load media",
                    message = state.errorMessage ?: "Please try again."
                )
            }
            else -> {
                val emptyTitle = when (state.filter) {
                    GalleryFilter.FAVORITES -> "No favorites yet"
                    GalleryFilter.PHOTOS -> "No photos yet"
                    GalleryFilter.VIDEOS -> "No videos yet"
                    GalleryFilter.ALL -> "No photos or videos yet"
                }
                val emptyMessage = when {
                    state.filter == GalleryFilter.FAVORITES -> "Your favorite photos and videos will appear here."
                    state.query.isNotBlank() -> "No matches for that name."
                    else -> "Your photos and videos will appear here."
                }
                MediaGrid(
                    groups = state.groups,
                    onMediaClick = onMediaClick,
                    emptyTitle = emptyTitle,
                    emptyMessage = emptyMessage,
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                    isLoadingMore = state.isLoadingMore,
                    hasNextPage = state.hasNextPage,
                    onLoadMore = viewModel::loadMore
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: GalleryFilter,
    onSelect: (GalleryFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        GalleryFilter.entries.forEach { filter ->
            val active = filter == selected
            val label = when (filter) {
                GalleryFilter.ALL -> "All"
                GalleryFilter.PHOTOS -> "Photos"
                GalleryFilter.VIDEOS -> "Videos"
                GalleryFilter.FAVORITES -> "Favorites"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) colors.onPrimary else colors.onSurface,
                modifier = Modifier
                    .clip(ChipShape)
                    .background(if (active) Copper else colors.surfaceVariant)
                    .border(1.dp, if (active) Copper else colors.outlineVariant, ChipShape)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    .semantics { contentDescription = label }
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
