package com.mydrive.app.ui.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.ui.components.MediaImage
import kotlinx.coroutines.launch

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_LOCK_THRESHOLD = 1.04f

@Composable
fun ZoomablePhoto(
    item: MediaItem,
    isCurrent: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onSingleTap: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val scale = remember(item.id) { Animatable(1f) }
    val offsetX = remember(item.id) { Animatable(0f) }
    val offsetY = remember(item.id) { Animatable(0f) }
    var containerSize by remember(item.id) { mutableStateOf(IntSize.Zero) }
    var loadFailed by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        scale.snapTo(1f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
        onZoomedChange(false)
    }

    LaunchedEffect(scale.value) {
        onZoomedChange(scale.value > ZOOM_LOCK_THRESHOLD)
    }

    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val sizePx = if (isCurrent) {
        screenWidthPx.coerceIn(720, 1440)
    } else {
        512
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center
    ) {
        if (loadFailed || item.uri.isBlank()) {
            MediaUnavailableState()
        } else {
            MediaImage(
                uri = item.uri,
                seed = item.thumbnailSeed,
                type = item.type,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isCurrent) {
                            Modifier
                                .pointerInput(item.id) {
                                    detectTapGestures(
                                        onTap = { onSingleTap() },
                                        onDoubleTap = { tap ->
                                            scope.launch {
                                                if (scale.value > ZOOM_LOCK_THRESHOLD) {
                                                    launch {
                                                        scale.animateTo(MIN_SCALE, spring())
                                                    }
                                                    launch { offsetX.animateTo(0f, spring()) }
                                                    launch { offsetY.animateTo(0f, spring()) }
                                                } else {
                                                    val target = DOUBLE_TAP_SCALE
                                                    val size = containerSize
                                                    if (size.width > 0 && size.height > 0) {
                                                        val dest = clampedOffset(
                                                            (size.width / 2f - tap.x) * (target - 1f),
                                                            (size.height / 2f - tap.y) * (target - 1f),
                                                            target,
                                                            size
                                                        )
                                                        launch {
                                                            scale.animateTo(target, spring())
                                                        }
                                                        launch {
                                                            offsetX.animateTo(dest.x, spring())
                                                        }
                                                        launch {
                                                            offsetY.animateTo(dest.y, spring())
                                                        }
                                                    } else {
                                                        scale.animateTo(target, spring())
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                .pointerInput(item.id, containerSize) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var zooming = false
                                        do {
                                            val event = awaitPointerEvent()
                                            val pressed = event.changes.filter { it.pressed }
                                            val zoomChange = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            if (pressed.size >= 2) {
                                                zooming = true
                                                val newScale = (scale.value * zoomChange)
                                                    .coerceIn(MIN_SCALE, MAX_SCALE)
                                                scope.launch {
                                                    scale.snapTo(newScale)
                                                    if (newScale > MIN_SCALE && containerSize.width > 0) {
                                                        val dest = clampedOffset(
                                                            offsetX.value + pan.x,
                                                            offsetY.value + pan.y,
                                                            newScale,
                                                            containerSize
                                                        )
                                                        offsetX.snapTo(dest.x)
                                                        offsetY.snapTo(dest.y)
                                                    } else {
                                                        offsetX.snapTo(0f)
                                                        offsetY.snapTo(0f)
                                                    }
                                                }
                                                event.changes.fastForEach {
                                                    if (it.positionChanged()) it.consume()
                                                }
                                            } else if (scale.value > ZOOM_LOCK_THRESHOLD && pressed.size == 1) {
                                                scope.launch {
                                                    val dest = clampedOffset(
                                                        offsetX.value + pan.x,
                                                        offsetY.value + pan.y,
                                                        scale.value,
                                                        containerSize
                                                    )
                                                    offsetX.snapTo(dest.x)
                                                    offsetY.snapTo(dest.y)
                                                }
                                                event.changes.fastForEach {
                                                    if (it.positionChanged()) it.consume()
                                                }
                                            }
                                        } while (event.changes.fastAny { it.pressed })
                                        if (zooming && scale.value <= ZOOM_LOCK_THRESHOLD) {
                                            scope.launch {
                                                scale.animateTo(MIN_SCALE, spring())
                                                offsetX.animateTo(0f, spring())
                                                offsetY.animateTo(0f, spring())
                                            }
                                        }
                                    }
                                }
                        } else {
                            Modifier
                        }
                    )
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = offsetX.value
                        translationY = offsetY.value
                    },
                contentScale = ContentScale.Fit,
                sizePx = sizePx,
                contentDescription = item.filename,
                placeholderBitmap = ThumbnailLoader.peek(item.uri, 256),
                onUnavailable = {
                    loadFailed = true
                    onUnavailable()
                }
            )
        }
    }
}

private fun clampedOffset(
    x: Float,
    y: Float,
    scale: Float,
    size: IntSize
): Offset {
    if (size.width == 0 || size.height == 0 || scale <= 1f) {
        return Offset.Zero
    }
    val maxX = ((scale - 1f) * size.width / 2f).coerceAtLeast(0f)
    val maxY = ((scale - 1f) * size.height / 2f).coerceAtLeast(0f)
    return Offset(
        x = x.coerceIn(-maxX, maxX),
        y = y.coerceIn(-maxY, maxY)
    )
}
