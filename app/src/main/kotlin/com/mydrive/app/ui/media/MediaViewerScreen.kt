package com.mydrive.app.ui.media

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import com.mydrive.app.ui.theme.Spacing
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
    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current

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
    var showDetails by remember { mutableStateOf(false) }
    var videoState by remember { mutableStateOf(VideoPlaybackState()) }
    var seekRequestMs by remember { mutableStateOf<Int?>(null) }
    var seekNonce by remember { mutableIntStateOf(0) }
    var playRequest by remember { mutableStateOf<Boolean?>(null) }
    var playNonce by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableIntStateOf(0) }

    val current = state.items.getOrNull(pagerState.currentPage) ?: state.items.first()
    val isVideo = current.type == MediaType.VIDEO
    val context = LocalContext.current
    val prefetchScope = rememberCoroutineScope()

    // Warm the ORIGINAL full-resolution decodes for the neighboring pages so
    // swiping lands on an already-sharp image. Requests go to FullImageLoader's
    // LRU cache, shared with the page composables via viewerFullResTargetPx.
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

    LaunchedEffect(showDetails) {
        if (showDetails && isVideo && videoState.playing) {
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
                            listOf(Color.Transparent, Ink.copy(alpha = 0.86f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.md, vertical = Spacing.md)
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
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (isVideo) Spacing.sm else 0.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ViewerAction(
                        icon = if (current.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        label = "Favorite",
                        tint = if (current.isFavorite) Copper else Ivory,
                        contentDescription = if (current.isFavorite) "Remove favorite" else "Add favorite"
                    ) { viewModel.toggleFavorite(current.id) }
                    ViewerAction(Icons.Outlined.Info, "Details") { showDetails = true }
                }
            }
        }
    }

    if (showDetails) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            MediaDetailsSheet(item = current)
        }
    }
}

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

@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    tint: Color = Ivory,
    contentDescription: String = label,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = StatusIdle,
            modifier = Modifier.padding(top = 4.dp)
        )
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
