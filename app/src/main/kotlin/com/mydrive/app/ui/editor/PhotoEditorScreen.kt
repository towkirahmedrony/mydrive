package com.mydrive.app.ui.editor

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Spacing
import kotlin.math.roundToInt

@Composable
fun PhotoEditorScreen(
    viewModel: PhotoEditorViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit = { onBack() }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by remember { mutableStateOf(false) }
    // Filled in by the preview stage so Save can bake overlays at the right
    // scale even though the Save button lives in the top bar.
    var renderContext by remember { mutableStateOf(EditorOverlayRenderContext.Default) }

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

    fun requestClose() {
        if (state.isEdited) showDiscardDialog = true else onBack()
    }

    BackHandler(onBack = { requestClose() })

    LaunchedEffect(state.saveMessage) {
        val message = state.saveMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSaveMessage()
    }

    LaunchedEffect(state.savedMediaId) {
        val savedId = state.savedMediaId ?: return@LaunchedEffect
        viewModel.consumeSaved()
        onSaved(savedId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                title = state.filename.ifBlank { "Edit" },
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                canReset = state.isEdited,
                saving = state.isSaving,
                onClose = { requestClose() },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onReset = viewModel::reset,
                onSave = { viewModel.savePreview(renderContext) }
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.isLoading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Copper)
                            if (state.loadingFromCloud) {
                                Spacer(Modifier.height(Spacing.md))
                                Text(
                                    text = "Downloading from My Drive…",
                                    color = Ivory.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    state.loadFailed -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(Spacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = state.loadErrorMessage
                                    ?: "This photo could not be opened for editing.",
                                color = Ivory,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(Spacing.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                EditorChip("Retry", Icons.Outlined.Refresh, viewModel::retryLoad)
                                EditorChip("Back", Icons.AutoMirrored.Outlined.ArrowBack, onBack)
                            }
                        }
                    }
                    else -> {
                        EditorPreviewStage(
                            state = state,
                            viewModel = viewModel,
                            onRenderContext = { renderContext = it }
                        )
                        if (state.isRendering) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(Spacing.md)
                                    .size(18.dp),
                                color = Copper,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
            EditorBottomPanel(state = state, viewModel = viewModel)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard edits?") },
            text = { Text("Your current edits stay in this session only until you save a copy to Photos.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) {
                    Text("Discard", color = Copper)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing")
                }
            }
        )
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    saving: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Ink.copy(alpha = 0.86f), Color.Transparent))
            )
            .statusBarsPadding()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Cancel" }) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Cancel", tint = Ivory)
        }
        Text(
            text = title,
            color = Ivory,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.Outlined.Undo,
                contentDescription = "Undo",
                tint = if (canUndo) Ivory else Ivory.copy(alpha = 0.35f)
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                Icons.Outlined.Redo,
                contentDescription = "Redo",
                tint = if (canRedo) Ivory else Ivory.copy(alpha = 0.35f)
            )
        }
        IconButton(onClick = onReset, enabled = canReset) {
            Icon(
                Icons.Outlined.RestartAlt,
                contentDescription = "Reset",
                tint = if (canReset) Ivory else Ivory.copy(alpha = 0.35f)
            )
        }
        IconButton(onClick = onSave, enabled = !saving) {
            Icon(Icons.Outlined.Save, contentDescription = "Save", tint = Copper)
        }
    }
}

@Composable
private fun EditorPreviewStage(
    state: PhotoEditorUiState,
    viewModel: PhotoEditorViewModel,
    onRenderContext: (EditorOverlayRenderContext) -> Unit
) {
    val preview = state.preview ?: return
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        val imageWidth = preview.width.toFloat().coerceAtLeast(1f)
        val imageHeight = preview.height.toFloat().coerceAtLeast(1f)
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val scale = minOf(maxW / imageWidth, maxH / imageHeight)
        val drawWidth = imageWidth * scale
        val drawHeight = imageHeight * scale
        val density = LocalDensity.current
        val drawWidthDp = with(density) { drawWidth.toDp() }
        val drawHeightDp = with(density) { drawHeight.toDp() }
        // Export renders at the source resolution, so the overlay sizes must be
        // scaled from this on-screen box before the overlays are baked in.
        LaunchedEffect(drawWidth, density, preview) {
            onRenderContext(
                EditorOverlayRenderContext(
                    displayWidthPx = drawWidth,
                    density = density.density,
                    fontScale = density.fontScale
                )
            )
        }
        val displayCrop = remember(
            state.recipe.crop,
            state.recipe.rotationDegrees,
            state.recipe.flipHorizontal,
            state.recipe.flipVertical
        ) {
            EditorCropMath.mapRectToDisplay(
                state.recipe.crop,
                state.recipe.rotationDegrees,
                state.recipe.flipHorizontal,
                state.recipe.flipVertical
            )
        }
        val displayAspect = imageWidth / imageHeight

        Box(
            modifier = Modifier
                .size(drawWidthDp, drawHeightDp)
                .clip(RoundedCornerShape(Radius.md))
                .background(Ink)
        ) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Editor preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (state.selectedTool != EditorTool.CROP && state.eyedropperEnabled) {
                ColorEyedropperOverlay(
                    preview = preview,
                    sampledColor = state.sampledColor,
                    onSample = { x, y -> viewModel.sampleDisplayedColor(x, y) },
                    onFinish = viewModel::finishEyedropper
                )
            }
            if (state.selectedTool != EditorTool.CROP) DrawingLayer(
                strokes = state.recipe.strokes,
                enabled = state.selectedTool == EditorTool.DRAW && !state.eyedropperEnabled,
                onBegin = viewModel::beginStroke,
                onMove = viewModel::appendStroke,
                onEnd = viewModel::endStroke
            )
            if (state.selectedTool != EditorTool.CROP) OverlayLayer(
                texts = state.recipe.texts,
                stickers = state.recipe.stickers,
                selectedTextId = state.selectedTextId,
                selectedStickerId = state.selectedStickerId,
                selectedTool = state.selectedTool,
                onSelectText = viewModel::selectText,
                onSelectSticker = viewModel::selectSticker,
                onBeginGesture = viewModel::beginOverlayGesture,
                onMoveText = viewModel::moveText,
                onTransformText = viewModel::transformText,
                onMoveSticker = viewModel::moveSticker,
                onTransformSticker = viewModel::transformSticker,
                onEndGesture = viewModel::endOverlayGesture
            )
            if (state.selectedTool == EditorTool.CROP) {
                EditorCropOverlay(
                    crop = displayCrop,
                    aspect = state.cropAspect,
                    imageAspect = displayAspect,
                    onChange = viewModel::updateDisplayCrop,
                    onChangeFinished = viewModel::commitLiveChange
                )
                CropAspectBadge(
                    preset = state.cropAspect,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun CropAspectBadge(
    preset: CropAspectPreset,
    modifier: Modifier = Modifier
) {
    val free = preset == CropAspectPreset.FREE
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Crop,
            contentDescription = null,
            tint = if (free) Copper else Ivory,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (free) "Free crop - drag corners or edges" else "Locked ${preset.label}",
            color = Ivory,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * The photo-color picker. While the finger is down a loupe sits just above the
 * sampling point, showing the magnified image, a centre crosshair and the
 * sampled color; the color is applied to the active Draw brush or Text overlay
 * live, and [onFinish] commits it exactly once on release.
 */
@Composable
private fun ColorEyedropperOverlay(
    preview: Bitmap,
    sampledColor: Long?,
    onSample: (Float, Float) -> Unit,
    onFinish: () -> Unit
) {
    var pointer by remember { mutableStateOf<Offset?>(null) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(preview) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val widthPx = size.width.coerceAtLeast(1).toFloat()
                    val heightPx = size.height.coerceAtLeast(1).toFloat()
                    fun report(position: Offset) {
                        onSample(
                            (position.x / widthPx).coerceIn(0f, 1f),
                            (position.y / heightPx).coerceIn(0f, 1f)
                        )
                    }
                    pointer = down.position
                    report(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                pointer = change.position
                                report(change.position)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    pointer = null
                    onFinish()
                }
            }
    ) {
        val position = pointer ?: return@BoxWithConstraints
        ColorMagnifier(
            preview = preview,
            pointer = position,
            containerWidthPx = constraints.maxWidth.toFloat(),
            containerHeightPx = constraints.maxHeight.toFloat(),
            sampledColor = sampledColor
        )
    }
}

@Composable
private fun ColorMagnifier(
    preview: Bitmap,
    pointer: Offset,
    containerWidthPx: Float,
    containerHeightPx: Float,
    sampledColor: Long?
) {
    val density = LocalDensity.current
    val loupeSize = 104.dp
    val loupePx = with(density) { loupeSize.toPx() }
    val marginPx = with(density) { 18.dp.toPx() }
    val containerWidthDp = with(density) { containerWidthPx.toDp() }
    val containerHeightDp = with(density) { containerHeightPx.toDp() }
    // The magnifier shows real image pixels, so the image is laid out undistorted
    // at the displayed size and scaled about the exact sampled point.
    val zoom = 5f
    val nx = (pointer.x / containerWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val ny = (pointer.y / containerHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)

    var left = pointer.x - loupePx / 2f
    var top = pointer.y - loupePx - marginPx
    if (top < 0f) top = pointer.y + marginPx
    left = left.coerceIn(0f, (containerWidthPx - loupePx).coerceAtLeast(0f))
    top = top.coerceIn(0f, (containerHeightPx - loupePx).coerceAtLeast(0f))

    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(loupeSize)
            .clip(CircleShape)
            .background(Color.Black)
            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
    ) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .size(containerWidthDp, containerHeightDp)
                .align(Alignment.Center)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(nx, ny)
                    scaleX = zoom
                    scaleY = zoom
                    translationX = containerWidthPx * (0.5f - nx)
                    translationY = containerHeightPx * (0.5f - ny)
                }
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val arm = 9.dp.toPx()
            val crosshair = Color.White.copy(alpha = 0.92f)
            drawLine(crosshair, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 1.5f.dp.toPx())
            drawLine(crosshair, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 1.5f.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = 4.dp.toPx(), center = center)
            drawCircle(Color.White, radius = 4.dp.toPx(), center = center, style = Stroke(width = 1.5f.dp.toPx()))
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (sampledColor != null) Color(sampledColor) else Color.Transparent)
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

@Composable
private fun DrawingLayer(
    strokes: List<EditorDrawStroke>,
    enabled: Boolean,
    onBegin: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onEnd: () -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (enabled) {
                    Modifier.pointerInput(enabled) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                onBegin(offset.x / size.width, offset.y / size.height)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                onMove(change.position.x / size.width, change.position.y / size.height)
                            },
                            onDragEnd = { onEnd() },
                            onDragCancel = { onEnd() }
                        )
                    }
                } else Modifier
            )
    ) {
        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val path = Path()
            val first = stroke.points.first()
            path.moveTo(first.x * size.width, first.y * size.height)
            stroke.points.drop(1).forEach { point ->
                path.lineTo(point.x * size.width, point.y * size.height)
            }
            drawPath(
                path = path,
                color = Color(stroke.color),
                style = Stroke(
                    width = stroke.width * size.minDimension,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

@Composable
private fun OverlayLayer(
    texts: List<EditorTextOverlay>,
    stickers: List<EditorStickerOverlay>,
    selectedTextId: String?,
    selectedStickerId: String?,
    selectedTool: EditorTool,
    onSelectText: (String?) -> Unit,
    onSelectSticker: (String?) -> Unit,
    onBeginGesture: () -> Unit,
    onMoveText: (String, Float, Float) -> Unit,
    onTransformText: (String, Float, Float) -> Unit,
    onMoveSticker: (String, Float, Float) -> Unit,
    onTransformSticker: (String, Float, Float) -> Unit,
    onEndGesture: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        texts.forEach { overlay ->
            OverlayItem(
                x = overlay.x,
                y = overlay.y,
                scale = overlay.scale,
                rotation = overlay.rotation,
                selected = overlay.id == selectedTextId && selectedTool == EditorTool.TEXT,
                parentWidth = widthPx,
                parentHeight = heightPx,
                onSelect = { onSelectText(overlay.id) },
                onBegin = onBeginGesture,
                onMove = { x, y -> onMoveText(overlay.id, x, y) },
                onTransform = { scale, rotation -> onTransformText(overlay.id, scale, rotation) },
                onEnd = onEndGesture
            ) {
                Text(
                    text = overlay.text,
                    color = Color(overlay.color),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        stickers.forEach { overlay ->
            val kind = EditorStickerKind.fromId(overlay.stickerId)
            OverlayItem(
                x = overlay.x,
                y = overlay.y,
                scale = overlay.scale,
                rotation = overlay.rotation,
                selected = overlay.id == selectedStickerId && selectedTool == EditorTool.STICKERS,
                parentWidth = widthPx,
                parentHeight = heightPx,
                onSelect = { onSelectSticker(overlay.id) },
                onBegin = onBeginGesture,
                onMove = { x, y -> onMoveSticker(overlay.id, x, y) },
                onTransform = { scale, rotation -> onTransformSticker(overlay.id, scale, rotation) },
                onEnd = onEndGesture
            ) {
                Image(
                    painter = painterResource(id = stickerDrawable(kind)),
                    contentDescription = kind.label,
                    modifier = Modifier.size(72.dp)
                )
            }
        }
    }
}

@Composable
private fun OverlayItem(
    x: Float,
    y: Float,
    scale: Float,
    rotation: Float,
    selected: Boolean,
    parentWidth: Float,
    parentHeight: Float,
    onSelect: () -> Unit,
    onBegin: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onTransform: (Float, Float) -> Unit,
    onEnd: () -> Unit,
    content: @Composable () -> Unit
) {
    val currentX = rememberUpdatedState(x)
    val currentY = rememberUpdatedState(y)
    val currentScale = rememberUpdatedState(scale)
    val currentRotation = rememberUpdatedState(rotation)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (x * parentWidth).roundToInt(),
                    (y * parentHeight).roundToInt()
                )
            }
            .graphicsLayer {
                translationX = -size.width / 2f
                translationY = -size.height / 2f
                this.scaleX = scale
                this.scaleY = scale
                this.rotationZ = rotation
            }
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) Copper else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(6.dp)
            .pointerInput(parentWidth, parentHeight) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onSelect()
                    onBegin()
                    var nextX = currentX.value
                    var nextY = currentY.value
                    var nextScale = currentScale.value
                    var nextRotation = currentRotation.value
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        nextX = ((nextX * parentWidth + pan.x) / parentWidth).coerceIn(0.05f, 0.95f)
                        nextY = ((nextY * parentHeight + pan.y) / parentHeight).coerceIn(0.05f, 0.95f)
                        nextScale = (nextScale * event.calculateZoom()).coerceIn(0.4f, 4f)
                        nextRotation += event.calculateRotation()
                        onMove(nextX, nextY)
                        onTransform(nextScale, nextRotation)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    onEnd()
                }
            }
    ) {
        content()
    }
}

@Composable
private fun EditorBottomPanel(
    state: PhotoEditorUiState,
    viewModel: PhotoEditorViewModel
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))
            )
            .navigationBarsPadding()
            .padding(top = Spacing.xs, bottom = Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (state.selectedTool == EditorTool.TEXT && imeVisible) {
                        Modifier.heightIn(min = 120.dp, max = 240.dp)
                    } else {
                        Modifier.height(220.dp)
                    }
                )
                .padding(horizontal = Spacing.md)
        ) {
            when (state.selectedTool) {
                EditorTool.CROP -> CropPanel(state = state, viewModel = viewModel)
                EditorTool.ADJUST -> AdjustPanel(state = state, viewModel = viewModel)
                EditorTool.FILTERS -> FiltersPanel(state = state, viewModel = viewModel)
                EditorTool.DRAW -> DrawPanel(state = state, viewModel = viewModel)
                EditorTool.TEXT -> TextPanel(state = state, viewModel = viewModel)
                EditorTool.STICKERS -> StickersPanel(state = state, viewModel = viewModel)
            }
        }
        if (!imeVisible) {
            Spacer(Modifier.height(Spacing.xs))
            ToolRail(
                selected = state.selectedTool,
                onSelect = viewModel::selectTool
            )
        }
    }
}

@Composable
private fun ToolRail(
    selected: EditorTool,
    onSelect: (EditorTool) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        EditorTool.entries.forEach { tool ->
            val active = tool == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.md))
                    .background(if (active) Copper.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(tool) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    .semantics { contentDescription = tool.label },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = toolIcon(tool),
                    contentDescription = tool.label,
                    tint = if (active) Copper else Ivory,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tool.label,
                    color = if (active) Copper else Ivory,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun CropPanel(state: PhotoEditorUiState, viewModel: PhotoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val cropActions = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(cropActions),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            EditorChip("Rotate left", Icons.Outlined.Rotate90DegreesCcw) { viewModel.rotateLeft() }
            EditorChip("Rotate right", Icons.Outlined.Rotate90DegreesCw) { viewModel.rotateRight() }
            EditorChip("Flip H", Icons.Outlined.Flip) { viewModel.flipHorizontal() }
            EditorChip("Flip V", Icons.Outlined.SwapVert) { viewModel.flipVertical() }
            EditorChip("Reset crop", Icons.Outlined.RestartAlt) { viewModel.resetCrop() }
        }
        val ratios = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(ratios),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            CropAspectPreset.entries.forEach { preset ->
                val active = state.cropAspect == preset
                Text(
                    text = preset.label,
                    color = if (active) Ink else Ivory,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (active) Copper else Color.White.copy(alpha = 0.08f))
                        .clickable { viewModel.setCropAspect(preset) }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                )
            }
        }
        Text(
            text = if (state.cropAspect == CropAspectPreset.FREE) {
                "Free crop: drag any corner or edge to resize without a fixed ratio."
            } else {
                "Locked to ${state.cropAspect.label}: drag a corner or edge to resize."
            },
            color = Ivory.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private enum class AdjustControl(val label: String) {
    BRIGHTNESS("Brightness"),
    CONTRAST("Contrast"),
    SATURATION("Saturation"),
    WARMTH("Warmth"),
    SHARPNESS("Sharpness"),
    VIGNETTE("Vignette")
}

@Composable
private fun AdjustPanel(state: PhotoEditorUiState, viewModel: PhotoEditorViewModel) {
    val adjustments = state.recipe.adjustments
    var selected by remember { mutableStateOf(AdjustControl.BRIGHTNESS) }
    val value = when (selected) {
        AdjustControl.BRIGHTNESS -> adjustments.brightness
        AdjustControl.CONTRAST -> adjustments.contrast
        AdjustControl.SATURATION -> adjustments.saturation
        AdjustControl.WARMTH -> adjustments.warmth
        AdjustControl.SHARPNESS -> adjustments.sharpness
        AdjustControl.VIGNETTE -> adjustments.vignette
    }
    val range = when (selected) {
        AdjustControl.SHARPNESS, AdjustControl.VIGNETTE -> 0f..1f
        else -> -1f..1f
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Adjust", color = Ivory, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = viewModel::resetAdjustments) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = Copper, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset", color = Copper)
            }
        }
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AdjustControl.entries.forEach { control ->
                val active = control == selected
                Text(
                    text = control.label,
                    color = if (active) Ink else Ivory,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (active) Copper else Color.White.copy(alpha = 0.08f))
                        .clickable { selected = control }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                )
            }
        }
        EditorSlider(
            label = selected.label,
            value = value,
            valueRange = range,
            onChange = { next ->
                viewModel.updateAdjustment { current ->
                    when (selected) {
                        AdjustControl.BRIGHTNESS -> current.copy(brightness = next)
                        AdjustControl.CONTRAST -> current.copy(contrast = next)
                        AdjustControl.SATURATION -> current.copy(saturation = next)
                        AdjustControl.WARMTH -> current.copy(warmth = next)
                        AdjustControl.SHARPNESS -> current.copy(sharpness = next)
                        AdjustControl.VIGNETTE -> current.copy(vignette = next)
                    }
                }
            },
            onChangeFinished = viewModel::commitLiveChange
        )
    }
}

@Composable
private fun FiltersPanel(state: PhotoEditorUiState, viewModel: PhotoEditorViewModel) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        EditorFilter.entries.forEach { filter ->
            val selected = state.recipe.filter == filter
            val thumb = state.filterThumbs[filter]
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(if (selected) Copper.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                    .border(
                        width = 1.dp,
                        color = if (selected) Copper else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(Radius.md)
                    )
                    .clickable { viewModel.selectFilter(filter) }
                    .padding(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumb != null && !thumb.isRecycled) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = filter.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Copper,
                            strokeWidth = 2.dp
                        )
                    }
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = Ivory, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = filter.displayName,
                    color = if (selected) Copper else Ivory,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DrawPanel(state: PhotoEditorUiState, viewModel: PhotoEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        EditorSlider("Brush size", state.brushSize, 0.006f..0.08f, viewModel::setBrushSize)
        EditorColorSelector(
            selected = state.brushColor,
            eyedropperEnabled = state.eyedropperEnabled,
            onSelect = viewModel::setBrushColor,
            onToggleEyedropper = viewModel::toggleEyedropper
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EditorChip("Undo stroke", Icons.Outlined.Undo) { viewModel.undoStroke() }
            EditorChip("Clear", Icons.Outlined.DeleteSweep) { viewModel.clearStrokes() }
        }
    }
}

@Composable
private fun TextPanel(state: PhotoEditorUiState, viewModel: PhotoEditorViewModel) {
    val selected = state.recipe.texts.firstOrNull { it.id == state.selectedTextId }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(selected?.id) {
        if (selected != null) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EditorChip("Add text", Icons.Outlined.Add, viewModel::addText)
            if (selected != null) {
                EditorChip("Delete", Icons.Outlined.Delete) { viewModel.removeText(selected.id) }
            }
        }
        if (selected != null) {
            OutlinedTextField(
                value = selected.text,
                onValueChange = { viewModel.updateText(selected.id, it) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Copper,
                    cursorColor = Copper,
                    focusedTextColor = Ivory,
                    unfocusedTextColor = Ivory
                )
            )
            EditorColorSelector(
                selected = selected.color,
                eyedropperEnabled = state.eyedropperEnabled,
                onSelect = { viewModel.setTextColor(selected.id, it) },
                onToggleEyedropper = viewModel::toggleEyedropper
            )
        } else {
            Text(
                "Add text, then drag to move or pinch to scale.",
                color = Ivory.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StickersPanel(state: PhotoEditorUiState, viewModel: PhotoEditorViewModel) {
    var category by remember { mutableStateOf(EditorStickerCategory.SHAPES) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            EditorStickerCategory.entries.forEach { item ->
                val active = item == category
                Text(
                    text = item.label,
                    color = if (active) Ink else Ivory,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (active) Copper else Color.White.copy(alpha = 0.08f))
                        .clickable { category = item }
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                )
            }
            if (state.selectedStickerId != null) {
                EditorChip("Delete", Icons.Outlined.Delete) {
                    viewModel.removeSticker(state.selectedStickerId)
                }
            }
        }
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            EditorStickerKind.entries.filter { it.category == category }.forEach { kind ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { viewModel.addSticker(kind) }
                        .padding(Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = stickerDrawable(kind)),
                        contentDescription = kind.label,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(kind.label, color = Ivory, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EditorChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = Ivory, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Ivory, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EditorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Ivory, style = MaterialTheme.typography.labelMedium)
            Text(
                text = "%.2f".format(value),
                color = Ivory.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = { onChangeFinished?.invoke() },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Copper,
                activeTrackColor = Copper,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f)
            )
        )
    }
}

private fun toolIcon(tool: EditorTool): ImageVector = when (tool) {
    EditorTool.CROP -> Icons.Outlined.Crop
    EditorTool.ADJUST -> Icons.Outlined.Tune
    EditorTool.FILTERS -> Icons.Outlined.FilterVintage
    EditorTool.DRAW -> Icons.Outlined.Gesture
    EditorTool.TEXT -> Icons.Outlined.TextFields
    EditorTool.STICKERS -> Icons.Outlined.EmojiEmotions
}

private fun stickerDrawable(kind: EditorStickerKind): Int = EditorExportRenderer.stickerDrawableId(kind)
