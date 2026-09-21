package com.mydrive.app.ui.media

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.media.FullImageLoader
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.IvoryMuted
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusIdle
import com.mydrive.app.ui.util.formatPlaybackMs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    viewModel: MediaViewerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val operation by viewModel.pendingOperation.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(darkTheme) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !darkTheme
            controller?.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    BackHandler(onBack = onBack)

    if (state.items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            MediaUnavailableState()
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(Spacing.md)
            ) {
                CircleIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            }
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = state.initialIndex.coerceIn(0, state.items.lastIndex),
        pageCount = { state.items.size }
    )
    var zoomed by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var videoState by remember { mutableStateOf(VideoPlaybackState()) }
    var seekRequestMs by remember { mutableStateOf<Int?>(null) }
    var seekNonce by remember { mutableIntStateOf(0) }
    var playRequest by remember { mutableStateOf<Boolean?>(null) }
    var playNonce by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableIntStateOf(0) }

    // Rename dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var renameTargetItem by remember { mutableStateOf<MediaItem?>(null) }

    val current = state.items.getOrNull(pagerState.currentPage) ?: state.items.first()
    val isVideo = current.type == MediaType.VIDEO
    val prefetchScope = rememberCoroutineScope()

    // SAF launcher for Copy/Move
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val pendingOp = operation
            val itemId = when (pendingOp) {
                is MediaOperation.CopyTo -> pendingOp.itemId
                is MediaOperation.MoveTo -> pendingOp.itemId
                else -> null
            }
            if (itemId != null) {
                when (pendingOp) {
                    is MediaOperation.CopyTo -> viewModel.copyToDestination(itemId, treeUri)
                    is MediaOperation.MoveTo -> viewModel.moveToDestination(itemId, treeUri)
                    else -> {}
                }
            } else {
                viewModel.dismissOperation()
            }
        } else {
            viewModel.dismissOperation()
        }
    }

    // Launch SAF when copy/move is pending
    LaunchedEffect(operation) {
        when (operation) {
            is MediaOperation.CopyTo, is MediaOperation.MoveTo -> {
                safLauncher.launch(null)
            }
            else -> {}
        }
    }

    // Warm the ORIGINAL full-resolution decodes for neighboring pages
    LaunchedEffect(current.id, state.items, context) {
        val index = state.items.indexOfFirst { it.id == current.id }
        if (index < 0) return@LaunchedEffect
        val target = viewerFullResTargetPx(context)
        listOfNotNull(
            state.items.getOrNull(index + 1),
            state.items.getOrNull(index - 1)
        )
            .filter { it.type == MediaType.PHOTO && it.uri.isNotBlank() }
            .forEach { neighbor ->
                prefetchScope.launch {
                    FullImageLoader.load(context, neighbor.uri, target)
                }
            }
    }

    LaunchedEffect(pagerState.currentPage, current.id) {
        zoomed = false
        videoState = VideoPlaybackState(
            durationMs = current.durationMillis?.toInt()?.coerceAtLeast(0) ?: 0
        )
        seekRequestMs = null
        playRequest = null
        seekNonce = 0
        playNonce = 0
        scrubbing = false
    }

    LaunchedEffect(operation) {
        if (operation is MediaOperation.Details && isVideo && videoState.playing) {
            playRequest = false
            playNonce += 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { page -> state.items.getOrNull(page)?.id ?: page }
        ) { page ->
            val item = state.items[page]
            val active = page == pagerState.currentPage
            ViewerPage(
                item = item,
                active = active,
                onZoomedChange = { isZoomed ->
                    if (active) zoomed = isZoomed
                },
                onTap = { chromeVisible = !chromeVisible },
                onVideoState = { playback ->
                    if (active) videoState = playback
                },
                seekRequestMs = if (active) seekRequestMs else null,
                seekNonce = if (active) seekNonce else 0,
                playRequest = if (active) playRequest else null,
                playNonce = if (active) playNonce else 0
            )
        }

        // Top bar: Back + page counter
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Ink.copy(alpha = 0.72f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${pagerState.currentPage + 1} / ${state.items.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ivory,
                        modifier = Modifier.semantics {
                            contentDescription = "Item ${pagerState.currentPage + 1} of ${state.items.size}"
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(40.dp))
                }
            }
        }

        // Bottom bar: Video controls (if video) + Action bar
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) {
                if (isVideo) {
                    VideoPlaybackBar(
                        state = videoState,
                        scrubbing = scrubbing,
                        scrubPosition = scrubPosition,
                        onScrub = { value ->
                            scrubbing = true
                            scrubPosition = value
                        },
                        onScrubFinished = { value ->
                            scrubbing = false
                            seekRequestMs = value
                            seekNonce += 1
                        },
                        onPlayPause = {
                            playRequest = !(videoState.playing)
                            playNonce += 1
                        }
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }

                // Primary action bar: Share | Edit | Delete | More
                ViewerActionBar(
                    item = current,
                    onShare = { viewModel.shareMedia(current) },
                    onEdit = { viewModel.editMedia(current) },
                    onDelete = { viewModel.requestDelete(current.id) },
                    onMore = { viewModel.requestMoreMenu(current.id) }
                )
            }
        }
    }

    // Keep dialog composition in dedicated functions. This avoids a Kotlin 2.1
    // Compose compiler inference issue with nested nullable smart casts here.
    val pendingDelete = operation as? MediaOperation.DeleteConfirm
    state.items.firstOrNull { it.id == pendingDelete?.itemId }?.let { deleteItem ->
        DeleteConfirmationDialog(
            item = deleteItem,
            viewModel = viewModel,
            onBack = onBack
        )
    }

    renameTargetItem?.takeIf { showRenameDialog }?.let { renameItem ->
        RenameDialog(
            item = renameItem,
            value = renameValue,
            onValueChange = { renameValue = it },
            onRename = { newName ->
                if (newName.isNotBlank() && newName != renameItem.filename) {
                    viewModel.confirmRename(renameItem.id, newName)
                }
                showRenameDialog = false
                renameTargetItem = null
            },
            onDismiss = {
                showRenameDialog = false
                renameTargetItem = null
            }
        )
    }

    // More menu bottom sheet
    val moreState = operation
    if (moreState is MediaOperation.MoreMenu) {
        val moreItem = state.items.firstOrNull { it.id == moreState.itemId }
        if (moreItem != null) {
            MoreMenuSheet(
                item = moreItem,
                onDismiss = { viewModel.dismissOperation() },
                onCopy = {
                    viewModel.dismissOperation()
                    viewModel.requestCopy(moreItem.id)
                },
                onMove = {
                    viewModel.dismissOperation()
                    viewModel.requestMove(moreItem.id)
                },
                onRename = {
                    viewModel.dismissOperation()
                    renameTargetItem = moreItem
                    renameValue = moreItem.filename
                    showRenameDialog = true
                },
                onRotateLeft = {
                    viewModel.dismissOperation()
                    viewModel.rotateLeft(moreItem.id)
                },
                onRotateRight = {
                    viewModel.dismissOperation()
                    viewModel.rotateRight(moreItem.id)
                },
                onSetWallpaper = {
                    viewModel.dismissOperation()
                    viewModel.setAsWallpaper(moreItem)
                },
                onDetails = {
                    viewModel.dismissOperation()
                    viewModel.requestDetails(moreItem.id)
                },
                onOpenWith = {
                    viewModel.dismissOperation()
                    viewModel.openWith(moreItem)
                },
                onToggleFavorite = {
                    viewModel.dismissOperation()
                    viewModel.toggleFavorite(moreItem.id)
                }
            )
        }
    }

    // Details bottom sheet
    val detailsState = operation
    if (detailsState is MediaOperation.Details) {
        val detailItem = state.items.firstOrNull { it.id == detailsState.itemId }
        if (detailItem != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissOperation() },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                MediaDetailsSheet(item = detailItem)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteConfirmationDialog(
    item: MediaItem,
    viewModel: MediaViewerViewModel,
    onBack: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissOperation() },
        title = {
            Text(
                text = "Delete ${if (item.type == MediaType.VIDEO) "video" else "photo"}?",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = "\"${item.filename}\" will be ${if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "moved to trash" else "permanently deleted"}.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.confirmDelete(item.id) { nextIndex ->
                        if (nextIndex == null) onBack()
                    }
                }
            ) {
                Text("Delete", color = StatusAttention)
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissOperation() }) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialog(
    item: MediaItem,
    value: String,
    onValueChange: (String) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Rename", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                Text(
                    text = "Enter a new filename:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Copper,
                        cursorColor = Copper
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(value) }) {
                Text("Rename", color = Copper)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ── Primary action bar ──────────────────────────────────────────────

@Composable
private fun ViewerActionBar(
    item: MediaItem,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ViewerBarAction(
            icon = Icons.Outlined.Share,
            label = "Share",
            onClick = onShare
        )
        ViewerBarAction(
            icon = Icons.Outlined.Edit,
            label = "Edit",
            onClick = onEdit
        )
        ViewerBarAction(
            icon = Icons.Outlined.Delete,
            label = "Delete",
            onClick = onDelete
        )
        ViewerBarAction(
            icon = Icons.Outlined.MoreVert,
            label = "More",
            onClick = onMore
        )
    }
}

@Composable
private fun ViewerBarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics { contentDescription = label }
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Ivory,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = IvoryMuted
        )
    }
}

// ── More menu bottom sheet ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreMenuSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onSetWallpaper: () -> Unit,
    onDetails: () -> Unit,
    onOpenWith: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.lg)
        ) {
            // Header
            Text(
                text = item.filename,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            )
            Spacer(Modifier.height(Spacing.xs))

            // File actions
            MoreMenuItem(
                icon = Icons.Outlined.ContentCopy,
                label = "Copy"
            ) { onCopy() }
            MoreMenuItem(
                icon = Icons.Outlined.FileOpen,
                label = "Move"
            ) { onMove() }
            MoreMenuItem(
                icon = Icons.Outlined.DriveFileRenameOutline,
                label = "Rename"
            ) { onRename() }

            // Divider
            Spacer(Modifier.height(Spacing.xs))

            // Image actions (photos only)
            if (item.type == MediaType.PHOTO) {
                MoreMenuItem(
                    icon = Icons.Outlined.RotateLeft,
                    label = "Rotate left"
                ) { onRotateLeft() }
                MoreMenuItem(
                    icon = Icons.Outlined.RotateRight,
                    label = "Rotate right"
                ) { onRotateRight() }
            }

            MoreMenuItem(
                icon = Icons.Outlined.Wallpaper,
                label = "Set as wallpaper"
            ) { onSetWallpaper() }

            Spacer(Modifier.height(Spacing.xs))

            MoreMenuItem(
                icon = Icons.Outlined.Info,
                label = "Details"
            ) { onDetails() }
            MoreMenuItem(
                icon = Icons.Outlined.OpenInNew,
                label = "Open with"
            ) { onOpenWith() }

            Spacer(Modifier.height(Spacing.xs))

            // Favorite toggle
            MoreMenuItem(
                icon = if (item.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                label = if (item.isFavorite) "Remove favorite" else "Add to favorites",
                tint = if (item.isFavorite) Copper else MaterialTheme.colorScheme.onSurface
            ) { onToggleFavorite() }
        }
    }
}

@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}

// ── Viewer page composable ──────────────────────────────────────────

@Composable
private fun ViewerPage(
    item: MediaItem,
    active: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    onVideoState: (VideoPlaybackState) -> Unit,
    seekRequestMs: Int?,
    seekNonce: Int,
    playRequest: Boolean?,
    playNonce: Int
) {
    if (item.type == MediaType.VIDEO) {
        ViewerVideoPlayer(
            item = item,
            active = active,
            onTap = onTap,
            onState = onVideoState,
            seekRequestMs = seekRequestMs,
            seekNonce = seekNonce,
            playRequest = playRequest,
            playNonce = playNonce,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        ZoomablePhoto(
            item = item,
            isCurrent = active,
            onZoomedChange = onZoomedChange,
            onSingleTap = onTap,
            onUnavailable = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── Video playback bar ──────────────────────────────────────────────

@Composable
private fun VideoPlaybackBar(
    state: VideoPlaybackState,
    scrubbing: Boolean,
    scrubPosition: Int,
    onScrub: (Int) -> Unit,
    onScrubFinished: (Int) -> Unit,
    onPlayPause: () -> Unit
) {
    val duration = state.durationMs.coerceAtLeast(0)
    val position = if (scrubbing) scrubPosition else state.positionMs.coerceIn(0, duration.coerceAtLeast(0))
    val sliderValue = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Copper)
                .clickable(onClick = onPlayPause)
                .semantics {
                    contentDescription = if (state.playing) "Pause" else "Play"
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (state.playing) "Pause" else "Play",
                tint = Ink
            )
        }
        Slider(
            value = sliderValue.coerceIn(0f, 1f),
            onValueChange = { value ->
                val next = ((value * duration).toInt()).coerceIn(0, duration.coerceAtLeast(0))
                onScrub(next)
            },
            onValueChangeFinished = { onScrubFinished(position) },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.sm)
                .semantics { contentDescription = "Seek" },
            enabled = duration > 0 && !state.error,
            colors = SliderDefaults.colors(
                thumbColor = Copper,
                activeTrackColor = Copper,
                inactiveTrackColor = StatusIdle.copy(alpha = 0.35f)
            )
        )
        Text(
            text = "${formatPlaybackMs(position)} / ${formatPlaybackMs(duration)}",
            style = MaterialTheme.typography.labelSmall,
            color = Ivory
        )
    }
}

// ── Circle icon button ──────────────────────────────────────────────

@Composable
private fun CircleIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.55f))
            .border(1.dp, Ivory.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ivory, modifier = Modifier.size(20.dp))
    }
}
