package com.mydrive.app.ui.media

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import com.mydrive.app.data.media.FullImageLoader
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.MyDriveApp
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.ui.util.thumbnailBrush
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_LOCK_THRESHOLD = 1.04f
private const val FLING_LOOKAHEAD_SECONDS = 0.15f

internal fun viewerFullResTargetPx(context: Context): Int {
    val configuration = context.resources.configuration
    val density = context.resources.displayMetrics.density
    val longestDp = maxOf(
        configuration.screenWidthDp,
        configuration.screenHeightDp
    ).coerceAtLeast(1)
    return (longestDp * density * 1.3f).toInt().coerceIn(1080, 2880)
}

@Composable
fun ZoomablePhoto(
    item: MediaItem,
    isCurrent: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onSingleTap: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnZoomedChange by rememberUpdatedState(onZoomedChange)
    val latestOnSingleTap by rememberUpdatedState(onSingleTap)
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)

    val scale = remember(item.id) { Animatable(1f) }
    val offsetX = remember(item.id) { Animatable(0f) }
    val offsetY = remember(item.id) { Animatable(0f) }
    var containerSize by remember(item.id) { mutableStateOf(IntSize.Zero) }
    var loadFailed by remember(item.id) { mutableStateOf(false) }

    val fullTargetPx = if (isCurrent) viewerFullResTargetPx(context) else 0
    val placeholderPx = 720

    LaunchedEffect(item.id) {
        scale.snapTo(1f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }

    LaunchedEffect(item.id) {
        snapshotFlow { scale.value > ZOOM_LOCK_THRESHOLD }
            .distinctUntilChanged()
            .collect { zoomed -> latestOnZoomedChange(zoomed) }
    }

    var bitmap by remember(item.id) {
        mutableStateOf(
            FullImageLoader.peek(item.displayUri)
                ?: ThumbnailLoader.peek(item.displayUri, placeholderPx)
                ?: ThumbnailLoader.peek(item.displayUri, 256)
        )
    }

    LaunchedEffect(item.id, placeholderPx) {
        if (item.displayUri.isBlank() && item.remoteMediaId.isNullOrBlank()) {
            loadFailed = true
            latestOnUnavailable()
            return@LaunchedEffect
        }
        if (bitmap == null) {
            val app = context.applicationContext as? MyDriveApp
            val loaded = ThumbnailLoader.load(
                context = context,
                uriString = item.displayUri,
                sizePx = placeholderPx,
                fallbackMediaId = item.remoteMediaId,
                sessionProvider = app?.sessionProvider
            )
            if (loaded != null) {
                bitmap = loaded
            }
        }
    }

    LaunchedEffect(item.id, fullTargetPx) {
        if ((item.displayUri.isBlank() && item.remoteMediaId.isNullOrBlank()) || fullTargetPx <= 0) return@LaunchedEffect
        val app = context.applicationContext as? MyDriveApp
        val full = FullImageLoader.load(
            context = context,
            uriString = item.displayUri,
            maxDimPx = fullTargetPx,
            fallbackMediaId = item.remoteMediaId,
            sessionProvider = app?.sessionProvider
        )
        if (full != null) {
            bitmap = full
        } else if (bitmap == null) {
            loadFailed = true
            latestOnUnavailable()
        }
    }

    val gestureModifier = if (isCurrent) {
        Modifier
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { latestOnSingleTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            val animSpec = spring<Float>(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                            if (scale.value > ZOOM_LOCK_THRESHOLD) {
                                launch { scale.animateTo(MIN_SCALE, animSpec) }
                                launch { offsetX.animateTo(0f, animSpec) }
                                launch { offsetY.animateTo(0f, animSpec) }
                            } else {
                                val target = DOUBLE_TAP_SCALE
                                val size = containerSize
                                val dest = if (size.width > 0 && size.height > 0) {
                                    val ratio = target / scale.value.coerceAtLeast(0.01f)
                                    val focus = Offset(tap.x, tap.y)
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    // Math corrected for center scaling origin
                                    val next = Offset(
                                        offsetX.value * ratio + (focus.x - center.x) * (1f - ratio),
                                        offsetY.value * ratio + (focus.y - center.y) * (1f - ratio)
                                    )
                                    clampedOffset(next.x, next.y, target, size)
                                } else {
                                    Offset.Zero
                                }
                                launch { scale.animateTo(target, animSpec) }
                                launch { offsetX.animateTo(dest.x, animSpec) }
                                launch { offsetY.animateTo(dest.y, animSpec) }
                            }
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    var zooming = false
                    var panning = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pressed.size >= 2) {
                            zooming = true
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val currentScale = scale.value
                            val newScale = (currentScale * zoomChange)
                                .coerceIn(MIN_SCALE, MAX_SCALE)
                            val ratio = if (currentScale > 0.01f) newScale / currentScale else 1f
                            scope.launch {
                                scale.snapTo(newScale)
                                val next = if (ratio != 1f) {
                                    // Math corrected for center scaling origin
                                    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                    Offset(
                                        offsetX.value * ratio + (centroid.x - center.x) * (1f - ratio),
                                        offsetY.value * ratio + (centroid.y - center.y) * (1f - ratio)
                                    )
                                } else {
                                    Offset(
                                        offsetX.value + pan.x,
                                        offsetY.value + pan.y
                                    )
                                }
                                val dest = clampedOffset(
                                    next.x, next.y, newScale, containerSize
                                )
                                offsetX.snapTo(dest.x)
                                offsetY.snapTo(dest.y)
                            }
                            event.changes.fastForEach {
                                if (it.positionChanged()) it.consume()
                            }
                        } else if (scale.value > ZOOM_LOCK_THRESHOLD && pressed.size == 1) {
                            panning = true
                            event.changes.fastForEach { change ->
                                if (change.positionChanged()) {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                }
                            }
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
                        val animSpec = spring<Float>(
                            dampingRatio = 0.85f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                        scope.launch { scale.animateTo(MIN_SCALE, animSpec) }
                        scope.launch { offsetX.animateTo(0f, animSpec) }
                        scope.launch { offsetY.animateTo(0f, animSpec) }
                    } else if (panning && scale.value > MIN_SCALE) {
                        val velocity = tracker.calculateVelocity()
                        val lookahead = FLING_LOOKAHEAD_SECONDS
                        val target = clampedOffset(
                            offsetX.value + velocity.x * lookahead,
                            offsetY.value + velocity.y * lookahead,
                            scale.value,
                            containerSize
                        )
                        val animSpec = spring<Float>(
                            dampingRatio = 0.9f,
                            stiffness = Spring.StiffnessLow
                        )
                        scope.launch { offsetX.animateTo(target.x, animSpec) }
                        scope.launch { offsetY.animateTo(target.y, animSpec) }
                    }
                }
            }
    } else {
        Modifier
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
            val current = bitmap
            if (current != null && !current.isRecycled) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = item.filename,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(gestureModifier)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                            translationX = offsetX.value
                            translationY = offsetY.value
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(thumbnailBrush(item.thumbnailSeed, item.type))
                )
            }
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
