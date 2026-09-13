package com.mydrive.app.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.MediaGrid
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Spacing

@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onMediaClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.headlineMedium,
            color = Ivory,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        )
        MediaGrid(
            groups = state.groups,
            onMediaClick = onMediaClick,
            emptyTitle = "No favorites yet",
            emptyMessage = "Your favorite photos and videos will appear here.",
            contentPadding = PaddingValues(bottom = Spacing.lg)
        )
    }
}
