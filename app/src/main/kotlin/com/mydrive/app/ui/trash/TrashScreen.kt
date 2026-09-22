package com.mydrive.app.ui.trash

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.MediaImage
import com.mydrive.app.ui.components.MediaThumb
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.MediaShape
import com.mydrive.app.ui.theme.SheetShape
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
    onMediaClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var menuExpanded by remember { mutableStateOf(false) }

    val confirmationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onSystemConfirmationResult(result.resultCode == Activity.RESULT_OK)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(confirmation) {
        confirmation?.let { request ->
            confirmationLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }

    LaunchedEffect(userMessage) {
        val message = userMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeUserMessage()
    }

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderIcon(
                    icon = if (state.selectionMode) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = if (state.selectionMode) "Cancel selection" else "Back",
                    onClick = { if (state.selectionMode) viewModel.clearSelection() else onBack() }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.md)
                ) {
                    Text(
                        text = if (state.selectionMode) "${state.selectedCount} selected" else "Trash",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.onBackground
                    )
                    if (!state.selectionMode) {
                        Text(
                            text = trashCountLabel(state.items.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                if (state.selectionMode) {
                    HeaderIcon(Icons.Outlined.SelectAll, "Select all") { viewModel.selectAll() }
                    Spacer(Modifier.width(Spacing.xs))
                    HeaderIcon(Icons.Outlined.Restore, "Restore selected") { viewModel.restoreSelected() }
                    Spacer(Modifier.width(Spacing.xs))
                    HeaderIcon(Icons.Outlined.DeleteForever, "Delete selected") { viewModel.requestPermanentDeleteSelected() }
                } else if (state.items.isNotEmpty()) {
                    Box {
                        HeaderIcon(Icons.Outlined.MoreVert, "More") { menuExpanded = true }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Empty Trash") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.requestEmptyTrash()
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }

            if (state.progress.inProgress) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Copper,
                    trackColor = colors.surfaceVariant
                )
            }

            when {
                state.items.isEmpty() -> {
                    EmptyState(
                        title = "Trash is empty",
                        message = "Photos and videos you move to Trash will appear here.",
                        icon = Icons.Outlined.DeleteForever,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    TrashGrid(
                        items = state.items,
                        selectedIds = state.selectedIds,
                        onClick = { id -> viewModel.onItemClick(id, onMediaClick) },
                        onLongClick = viewModel::onItemLongClick
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(Spacing.md)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = colors.inverseSurface,
                contentColor = colors.inverseOnSurface,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }

    when (val current = sheet) {
        is TrashSheet.PermanentDelete -> {
            val count = current.ids.size
            TrashActionSheet(
                title = if (count == 1) "Delete permanently?" else "Delete $count items permanently?",
                body = if (count == 1) {
                    "This item will be permanently removed from this device and cannot be recovered."
                } else {
                    "These items will be permanently removed from this device and cannot be recovered."
                },
                confirmLabel = "Delete permanently",
                preview = state.items.firstOrNull { it.id == current.ids.firstOrNull() },
                onDismiss = viewModel::dismissSheet,
                onConfirm = viewModel::confirmPermanentDelete
            )
        }
        is TrashSheet.EmptyTrash -> {
            TrashActionSheet(
                title = "Empty Trash?",
                body = "${current.count} ${if (current.count == 1) "item" else "items"} will be permanently removed from this device and cannot be recovered.",
                confirmLabel = "Empty Trash",
                preview = state.items.firstOrNull(),
                onDismiss = viewModel::dismissSheet,
                onConfirm = viewModel::confirmPermanentDelete
            )
        }
        TrashSheet.Hidden -> Unit
    }
}

@Composable
private fun TrashGrid(
    items: List<MediaItem>,
    selectedIds: Set<String>,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit
) {
    val gridState = rememberLazyGridState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 840.dp -> 5
            maxWidth >= 600.dp -> 4
            else -> 3
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items, key = { it.id }) { item ->
                MediaThumb(
                    item = item,
                    onClick = { onClick(item.id) },
                    onLongClick = { onLongClick(item.id) },
                    selected = item.id in selectedIds,
                    showStatusOverlays = false
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashActionSheet(
    title: String,
    body: String,
    confirmLabel: String,
    preview: MediaItem?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = Spacing.md)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            )
            if (preview != null) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(MediaShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    MediaImage(
                        uri = preview.uri,
                        seed = preview.thumbnailSeed,
                        type = preview.type,
                        modifier = Modifier.fillMaxSize(),
                        sizePx = 144,
                        contentDescription = preview.filename
                    )
                }
                Spacer(Modifier.height(Spacing.md))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusAttention,
                        contentColor = Ivory
                    )
                ) {
                    Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
private fun HeaderIcon(
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

private fun trashCountLabel(count: Int): String =
    if (count == 1) "1 item" else "$count items"
