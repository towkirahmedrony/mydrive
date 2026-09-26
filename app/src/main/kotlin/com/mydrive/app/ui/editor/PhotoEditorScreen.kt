package com.mydrive.app.ui.editor

import android.app.Activity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Redo
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import com.mydrive.app.R
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Spacing
import kotlin.math.roundToInt

@Composable
fun PhotoEditorScreen(
    viewModel: PhotoEditorViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by remember { mutableStateOf(false) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                onSave = viewModel::savePreview
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Copper
                        )
                    }
                    state.loadFailed -> {
                        Text(
                            text = "This photo could not be opened for editing.",
                            color = Ivory,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(Spacing.lg),
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        EditorPreviewStage(
                            state = state,
                            viewModel = viewModel
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
                .padding(bottom = 188.dp)
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
            text = { Text("Your current edits stay in this session only until you save a temporary preview.") },
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
    viewModel: PhotoEditorViewModel
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
            DrawingLayer(
                strokes = state.recipe.strokes,
                enabled = state.selectedTool == EditorTool.DRAW,
                onBegin = viewModel::beginStroke,
                onMove = viewModel::appendStroke,
                onEnd = viewModel::endStroke
            )
            OverlayLayer(
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
        }
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
                .height(188.dp)
                .padding(horizontal = Spacing.md)
        ) {
            when (state.selectedTool) {
                EditorTool.CROP -> CropPanel(state = state, viewModel = viewModel)
                EditorTool.ADJUST -> AdjustPanel(state = state, viewModel = viewModel)
                EditorTool.FILTERS -> FiltersPanel(state = state, viewModel = viewModel)
                EditorTool.DRAW -> DrawPanel(state = state, viewModel = viewModel)
                EditorTool.TEXT -> TextPanel(state = state, viewModel = viewModel)
                EditorTool.STICKERS -> StickersPanel(viewModel = viewModel)
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        ToolRail(
            selected = state.selectedTool,
            onSelect = viewModel::selectTool
        )
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
        EditorSlider(
            label = "Crop width",
            value = state.recipe.crop.width,
            valueRange = NormalizedRect.MIN_SIZE..1f,
            onChange = { width ->
                val current = state.recipe.crop
                val left = current.left.coerceAtMost(1f - width)
                viewModel.updateCrop(current.copy(left = left, right = left + width))
            },
            onChangeFinished = viewModel::commitLiveChange
        )
        EditorSlider(
            label = "Crop height",
            value = state.recipe.crop.height,
            valueRange = NormalizedRect.MIN_SIZE..1f,
            onChange = { height ->
                val current = state.recipe.crop
                val top = current.top.coerceAtMost(1f - height)
                viewModel.updateCrop(current.copy(top = top, bottom = top + height))
            },
            onChangeFinished = viewModel::commitLiveChange
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
                        .background(filterSwatch(filter)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        EditorSlider("Brush size", state.brushSize, 0.006f..0.08f, viewModel::setBrushSize)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(0xFFFFFFFF, 0xFFD4A574, 0xFFD97858, 0xFF8FADA0, 0xFF0B0C0E).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(
                            width = if (state.brushColor == color) 2.dp else 1.dp,
                            color = if (state.brushColor == color) Copper else Color.White.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .clickable { viewModel.setBrushColor(color) }
                )
            }
            Spacer(Modifier.weight(1f))
            EditorChip("Undo stroke", Icons.Outlined.Undo) { viewModel.undoStroke() }
            EditorChip("Clear", Icons.Outlined.DeleteSweep) { viewModel.clearStrokes() }
        }
    }
}

@Composable
private fun TextPanel(state: PhotoEditorUiState, viewModel: PhotoEditorViewModel) {
    val selected = state.recipe.texts.firstOrNull { it.id == state.selectedTextId }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EditorChip("Add text", Icons.Outlined.Add, viewModel::addText)
        }
        if (selected != null) {
            OutlinedTextField(
                value = selected.text,
                onValueChange = { viewModel.updateText(selected.id, it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Copper,
                    cursorColor = Copper,
                    focusedTextColor = Ivory,
                    unfocusedTextColor = Ivory
                )
            )
        } else {
            Text("Add text, then drag to move or pinch to scale.", color = Ivory.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StickersPanel(viewModel: PhotoEditorViewModel) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        EditorStickerKind.entries.forEach { kind ->
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

private fun filterSwatch(filter: EditorFilter): Color = when (filter) {
    EditorFilter.ORIGINAL -> Color(0xFFC9C3B8)
    EditorFilter.MONO -> Color(0xFF8B909A)
    EditorFilter.FADE -> Color(0xFFB7AFA3)
    EditorFilter.WARM_GLOW -> Color(0xFFD4A574)
    EditorFilter.COOL_MIST -> Color(0xFF8AA4C4)
    EditorFilter.VINTAGE -> Color(0xFFC48A5A)
    EditorFilter.DRAMATIC -> Color(0xFF5E636C)
    EditorFilter.SOFT -> Color(0xFFEDE7DC)
}

private fun stickerDrawable(kind: EditorStickerKind): Int = when (kind) {
    EditorStickerKind.HEART -> R.drawable.editor_sticker_heart
    EditorStickerKind.STAR -> R.drawable.editor_sticker_star
    EditorStickerKind.SUN -> R.drawable.editor_sticker_sun
    EditorStickerKind.SMILE -> R.drawable.editor_sticker_smile
    EditorStickerKind.LEAF -> R.drawable.editor_sticker_leaf
    EditorStickerKind.SPARK -> R.drawable.editor_sticker_spark
}
