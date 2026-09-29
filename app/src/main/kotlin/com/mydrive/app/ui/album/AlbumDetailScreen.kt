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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.MediaGrid
import com.mydrive.app.ui.components.SearchField
import com.mydrive.app.ui.selection.MediaSelectionBottomBar
import com.mydrive.app.ui.selection.MediaSelectionEffects
import com.mydrive.app.ui.selection.MediaSelectionProgress
import com.mydrive.app.ui.selection.MediaSelectionSheets
import com.mydrive.app.ui.selection.MediaSelectionSnackbarHost
import com.mydrive.app.ui.selection.MediaSelectionTopBar
import com.mydrive.app.ui.selection.SelectionActionButton
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
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selecting = state.selectionMode
    val visibleItems = remember(state.groups) { state.groups.flatMap { it.items } }

    MediaSelectionEffects(viewModel.selection, snackbarHostState)

    LaunchedEffect(state.albumDeleted) {
        if (state.albumDeleted) onBack()
    }
    LaunchedEffect(state.userMessage) {
        val message = state.userMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeUserMessage()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selecting) {
                MediaSelectionTopBar(
                    selectedCount = state.selectedIds.size,
                    onClose = viewModel.selection::clearSelection,
                    onSelectAll = viewModel.selection::selectAll
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleHeaderButton(
                        onClick = onBack,
                        contentDescription = "Back"
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
                    CircleHeaderButton(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) viewModel.setQuery("")
                        },
                        contentDescription = if (searchOpen) "Close search" else "Search album"
                    ) {
                        Icon(
                            if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search album",
                            tint = colors.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Box {
                        CircleHeaderButton(
                            onClick = { menuOpen = true },
                            contentDescription = "Album options"
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "Album options",
                                tint = colors.onSurface
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.isUserAlbum) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.openRename()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add photos/videos") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.openAddMedia()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete album") },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.openDelete()
                                    }
                                )
                            }
                            if (selecting.not() && state.groups.isNotEmpty() && state.isUserAlbum) {
                                DropdownMenuItem(
                                    text = { Text("Organize") },
                                    onClick = {
                                        menuOpen = false
                                        visibleItems.firstOrNull()?.id?.let { viewModel.selection.onItemLongClick(it) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            MediaSelectionProgress(state.bulkProgress)
            if (searchOpen && !selecting) {
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
                onMediaClick = { id -> viewModel.selection.onItemClick(id, onMediaClick) },
                emptyTitle = if (state.query.isNotBlank()) "No matches" else title,
                emptyMessage = if (state.query.isNotBlank()) {
                    "No matches for that name."
                } else {
                    "Add photos and videos to this album. They will stay in Photos."
                },
                emptyActionLabel = if (state.query.isBlank() && state.isUserAlbum) "Add photos/videos" else null,
                onEmptyAction = if (state.query.isBlank() && state.isUserAlbum) viewModel::openAddMedia else null,
                contentPadding = PaddingValues(bottom = if (selecting) 112.dp else Spacing.lg),
                isLoadingMore = state.isLoadingMore,
                hasNextPage = state.hasNextPage,
                onLoadMore = viewModel::loadMore,
                selectedIds = state.selectedIds,
                selectionMode = selecting,
                onMediaLongClick = viewModel.selection::onItemLongClick
            )
        }
        if (selecting) {
            MediaSelectionBottomBar(
                enabled = state.selectedIds.isNotEmpty() && !state.bulkProgress.inProgress,
                onDelete = viewModel.selection::requestDeleteSelected,
                onMove = {
                    if (state.isUserAlbum && state.destinationAlbums.isNotEmpty()) {
                        viewModel.requestMoveToAlbum()
                    } else {
                        viewModel.selection.requestMoveSelected()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                extraAction = if (state.isUserAlbum) {
                    {
                        if (state.selectedIds.size == 1) {
                            SelectionActionButton(
                                icon = Icons.Outlined.Image,
                                label = "Cover",
                                enabled = !state.bulkProgress.inProgress,
                                onClick = {
                                    state.selectedIds.firstOrNull()?.let(viewModel::setCover)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        SelectionActionButton(
                            icon = Icons.Outlined.PlaylistRemove,
                            label = "Remove",
                            enabled = state.selectedIds.isNotEmpty() && !state.bulkProgress.inProgress,
                            onClick = viewModel::requestRemoveSelected,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    null
                }
            )
        }
        MediaSelectionSnackbarHost(
            snackbarHostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    MediaSelectionSheets(viewModel.selection, visibleItems)

    when (state.sheet) {
        AlbumSheet.RENAME -> AlbumNameDialog(
            title = "Rename album",
            confirmLabel = "Rename",
            value = state.renameValue,
            error = state.renameError,
            onValueChange = viewModel::setRenameValue,
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::dismissSheet
        )
        AlbumSheet.DELETE -> DeleteAlbumDialog(
            albumName = title,
            onConfirm = viewModel::confirmDeleteAlbum,
            onDismiss = viewModel::dismissSheet
        )
        AlbumSheet.REMOVE_CONFIRM -> RemoveFromAlbumDialog(
            count = state.selectedIds.size,
            onConfirm = viewModel::confirmRemoveSelected,
            onDismiss = viewModel::dismissSheet
        )
        AlbumSheet.ADD_MEDIA -> AddMediaSheet(
            items = state.pickerItems,
            selectedIds = state.pickerSelectedIds,
            query = state.pickerQuery,
            onQueryChange = viewModel::setPickerQuery,
            onToggle = viewModel::togglePickerItem,
            onConfirm = viewModel::confirmAddMedia,
            onDismiss = viewModel::dismissSheet
        )
        AlbumSheet.MOVE_TO_ALBUM -> MoveToAlbumSheet(
            albums = state.destinationAlbums,
            onSelect = viewModel::confirmMoveToAlbum,
            onDismiss = viewModel::dismissSheet
        )
        AlbumSheet.HIDDEN -> Unit
    }
}

@Composable
private fun CircleHeaderButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
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
        content()
    }
}
