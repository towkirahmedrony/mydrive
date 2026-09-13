package com.mydrive.app.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.MediaGrid
import com.mydrive.app.ui.components.SearchField
import com.mydrive.app.ui.theme.Spacing

@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    onBack: () -> Unit,
    onMediaClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val title = state.album?.name ?: "Album"
    val count = state.album?.mediaCount ?: 0
    val colors = MaterialTheme.colorScheme
    var searchOpen by rememberSaveable { mutableStateOf(false) }

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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant)
                    .border(1.dp, colors.outlineVariant, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onSurface
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.md)
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                Text(
                    "$count ${if (count == 1) "item" else "items"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant)
                    .border(1.dp, colors.outlineVariant, CircleShape)
                    .clickable {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.setQuery("")
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search album",
                    tint = colors.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (searchOpen) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = "Search in album",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        }
        MediaGrid(
            groups = state.groups,
            onMediaClick = onMediaClick,
            emptyTitle = if (state.query.isNotBlank()) "No matches" else "Empty album",
            emptyMessage = if (state.query.isNotBlank()) {
                "No matches for that name."
            } else {
                "Media in this album will appear here."
            },
            contentPadding = PaddingValues(bottom = Spacing.lg)
        )
    }
}
