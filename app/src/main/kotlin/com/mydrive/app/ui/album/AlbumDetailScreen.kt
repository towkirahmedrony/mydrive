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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.MediaGrid
import com.mydrive.app.ui.theme.Graphite
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Mist
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.Stroke

@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    onBack: () -> Unit,
    onMediaClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val title = state.album?.name ?: "Album"
    val count = state.album?.mediaCount ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
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
                    .background(Graphite)
                    .border(1.dp, Stroke, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Ivory)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.md)
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Ivory)
                Text(
                    "$count ${if (count == 1) "item" else "items"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mist
                )
            }
        }
        MediaGrid(
            groups = state.groups,
            onMediaClick = onMediaClick,
            emptyTitle = "Empty album",
            emptyMessage = "Media in this album will appear here.",
            contentPadding = PaddingValues(bottom = Spacing.lg)
        )
    }
}
