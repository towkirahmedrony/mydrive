package com.mydrive.app.ui.trash

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.media.MediaUnavailableState
import com.mydrive.app.ui.media.VideoPlaybackState
import com.mydrive.app.ui.media.ViewerVideoPlayer
import com.mydrive.app.ui.media.ZoomablePhoto
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.IvoryMuted
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusIdle
import com.mydrive.app.ui.util.formatPlaybackMs
import com.mydrive.app.ui.util.formatTrashExpiry
import kotlinx.coroutines.launch

@Composable
fun TrashViewerScreen(
    viewModel: TrashViewerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }

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

    val confirmationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onSystemConfirmationResult(result.resultCode == Activity.RESULT_OK)
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

    val current = state.items.getOrNull(pagerState.currentPage) ?: state.items.first()
    val isVideo = current.type == MediaType.VIDEO
    val pagerScope = rememberCoroutineScope()
    fun onItemRemoved(nextIndex: Int?) {
        if (nextIndex == null) {
            onBack()
        } else {
            pagerScope.launch { pagerState.scrollToPage(nextIndex) }
        }
    }

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty() && pagerState.currentPage > state.items.lastIndex) {
            pagerState.scrollToPage(state.items.lastIndex)
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
            if (item.type == MediaType.VIDEO) {
                ViewerVideoPlayer(
                    item = item,
                    active = active,
                    onTap = { chromeVisible = !chromeVisible },
                    onState = { playback -> if (active) videoState = playback },
                    seekRequestMs = if (active) seekRequestMs else null,
                    seekNonce = if (active) seekNonce else 0,
                    playRequest = if (active) playRequest else null,
                    playNonce = if (active) playNonce else 0,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ZoomablePhoto(
                    item = item,
                    isCurrent = active,
                    onZoomedChange = { isZoomed -> if (active) zoomed = isZoomed },
                    onSingleTap = { chromeVisible = !chromeVisible },
                    onUnavailable = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.78f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Trash",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ivory
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${pagerState.currentPage + 1} / ${state.items.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ivory
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = current.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ivory,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val expiry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    formatTrashExpiry(current.dateExpiresMillis)
                } else {
                    null
                }
                Text(
                    text = buildString {
                        append(if (isVideo) "Video in Trash" else "Photo in Trash")
                        if (expiry != null) {
                            append(" · ")
                            append(expiry)
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = IvoryMuted
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) {
                if (isVideo) {
                    TrashVideoBar(
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ViewerAction(Icons.Outlined.Restore, "Restore") {
                        viewModel.restore(current.id, ::onItemRemoved)
                    }
                    ViewerAction(Icons.Outlined.DeleteForever, "Delete") {
                        viewModel.requestPermanentDelete(current.id)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }

    val pendingDelete = sheet as? TrashViewerSheet.PermanentDelete
    state.items.firstOrNull { it.id == pendingDelete?.itemId }?.let { item ->
        TrashActionSheet(
            title = "Delete permanently?",
            body = "This item will be permanently removed from this device and cannot be recovered.",
            confirmLabel = "Delete permanently",
            preview = item,
            onDismiss = viewModel::dismissSheet,
            onConfirm = {
                viewModel.confirmPermanentDelete(::onItemRemoved)
            }
        )
    }
}

@Composable
private fun TrashVideoBar(
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
                .clickable(onClick = onPlayPause),
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
                onScrub(((value * duration).toInt()).coerceIn(0, duration.coerceAtLeast(0)))
            },
            onValueChangeFinished = { onScrubFinished(position) },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.sm),
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

@Composable
private fun ViewerAction(
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
        Icon(icon, contentDescription = label, tint = Ivory, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = IvoryMuted)
    }
}

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
