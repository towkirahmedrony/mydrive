package com.mydrive.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mydrive.app.ui.theme.Copper
import kotlin.math.min

/**
 * The professional crop frame drawn over the photo while the Crop tool is active.
 *
 * The frame is a full rectangle with a dark scrim outside it, a bright double
 * outline, rule-of-thirds guides, and large grab handles — L-shaped brackets on
 * every corner and bars on every edge midpoint. The handles are deliberately
 * chunky and carry a soft dark drop shadow so the crop boundary stays legible
 * against both light and dark photos, and so it is obvious that each side can be
 * dragged. The resize behaviour itself is unchanged: [EditorCropMath.resize]
 * constrains the frame to a preset ratio when one is selected and leaves every
 * edge free when [CropAspectPreset.FREE] is active.
 */
@Composable
fun EditorCropOverlay(
    crop: NormalizedRect,
    aspect: CropAspectPreset,
    imageAspect: Float,
    onChange: (NormalizedRect) -> Unit,
    onChangeFinished: () -> Unit
) {
    val density = LocalDensity.current
    // A generous grab radius so every corner and edge is comfortable to touch.
    val touchPadPx = with(density) { 40.dp.toPx() }
    val cropState = rememberUpdatedState(crop)
    val aspectState = rememberUpdatedState(aspect)
    val imageAspectState = rememberUpdatedState(imageAspect)
    val onChangeState = rememberUpdatedState(onChange)
    val onChangeFinishedState = rememberUpdatedState(onChangeFinished)
    var activeHandle by remember { mutableStateOf<CropHandle?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val minDim = min(size.width, size.height).coerceAtLeast(1).toFloat()
                        val handlePad = touchPadPx / minDim
                        val nx = (offset.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        val ny = (offset.y / size.height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        activeHandle = EditorCropMath.hitTest(cropState.value, nx, ny, handlePad)
                    },
                    onDrag = { change, dragAmount ->
                        val handle = activeHandle ?: return@detectDragGestures
                        change.consume()
                        val dx = dragAmount.x / size.width.coerceAtLeast(1).toFloat()
                        val dy = dragAmount.y / size.height.coerceAtLeast(1).toFloat()
                        val pixelAspect = aspectState.value.pixelAspect(imageAspectState.value)
                        val normRatio = EditorCropMath.normalizedRatio(pixelAspect, imageAspectState.value)
                        onChangeState.value(EditorCropMath.resize(cropState.value, handle, dx, dy, normRatio))
                    },
                    onDragEnd = {
                        activeHandle = null
                        onChangeFinishedState.value()
                    },
                    onDragCancel = {
                        activeHandle = null
                        onChangeFinishedState.value()
                    }
                )
            }
    ) {
        val left = crop.left * size.width
        val top = crop.top * size.height
        val right = crop.right * size.width
        val bottom = crop.bottom * size.height
        val cropRect = Rect(left, top, right, bottom)

        // ── Scrim outside the crop ──────────────────────────────────────────
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(cropRect)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.62f))

        // ── Frame: a dark base stroke under a bright one so the boundary is
        //    readable on any photo. ─────────────────────────────────────────
        val frameStroke = 2.dp.toPx()
        drawRect(
            color = Color.Black.copy(alpha = 0.55f),
            topLeft = Offset(left, top),
            size = Size(cropRect.width, cropRect.height),
            style = Stroke(width = frameStroke + 2.5f.dp.toPx())
        )
        drawRect(
            color = Color.White.copy(alpha = 0.98f),
            topLeft = Offset(left, top),
            size = Size(cropRect.width, cropRect.height),
            style = Stroke(width = frameStroke)
        )

        // ── Rule-of-thirds guides ───────────────────────────────────────────
        val thirdX = cropRect.width / 3f
        val thirdY = cropRect.height / 3f
        val grid = Color.White.copy(alpha = 0.34f)
        val gridStroke = 1.dp.toPx()
        drawLine(grid, Offset(left + thirdX, top), Offset(left + thirdX, bottom), gridStroke)
        drawLine(grid, Offset(left + thirdX * 2f, top), Offset(left + thirdX * 2f, bottom), gridStroke)
        drawLine(grid, Offset(left, top + thirdY), Offset(right, top + thirdY), gridStroke)
        drawLine(grid, Offset(left, top + thirdY * 2f), Offset(right, top + thirdY * 2f), gridStroke)

        // ── Handles: corner brackets + edge bars ────────────────────────────
        val shadow = Color.Black.copy(alpha = 0.5f)
        val shadowWidth = 8.dp.toPx()
        val handleWidth = 5.5.dp.toPx()
        val arm = 30.dp.toPx()
        fun drawHandle(start: Offset, end: Offset) {
            drawLine(shadow, start, end, shadowWidth, StrokeCap.Round)
            drawLine(Color.White, start, end, handleWidth, StrokeCap.Round)
        }

        // Corner brackets (two arms each).
        drawHandle(Offset(left, top), Offset(left + arm, top))
        drawHandle(Offset(left, top), Offset(left, top + arm))

        drawHandle(Offset(right, top), Offset(right - arm, top))
        drawHandle(Offset(right, top), Offset(right, top + arm))

        drawHandle(Offset(left, bottom), Offset(left + arm, bottom))
        drawHandle(Offset(left, bottom), Offset(left, bottom - arm))

        drawHandle(Offset(right, bottom), Offset(right - arm, bottom))
        drawHandle(Offset(right, bottom), Offset(right, bottom - arm))

        // Edge bars at the midpoint of every side.
        val bar = 30.dp.toPx()
        val midX = (left + right) / 2f
        val midY = (top + bottom) / 2f
        drawHandle(Offset(left, midY - bar / 2f), Offset(left, midY + bar / 2f))
        drawHandle(Offset(right, midY - bar / 2f), Offset(right, midY + bar / 2f))
        drawHandle(Offset(midX - bar / 2f, top), Offset(midX + bar / 2f, top))
        drawHandle(Offset(midX - bar / 2f, bottom), Offset(midX + bar / 2f, bottom))

        // ── Copper knobs on the four corners: the unmistakable "drag me"
        //    affordance. ────────────────────────────────────────────────────
        val knobRadius = 9.dp.toPx()
        val knobShadow = 12.dp.toPx()
        listOf(
            Offset(left, top),
            Offset(right, top),
            Offset(left, bottom),
            Offset(right, bottom)
        ).forEach { corner ->
            drawCircle(shadow, radius = knobShadow / 2f, center = corner)
            drawCircle(Copper, radius = knobRadius, center = corner)
            drawCircle(
                Color.White,
                radius = knobRadius,
                center = corner,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // A subtle gradient under the top and bottom edges keeps the frame from
        // dissolving into a bright sky or a white wall.
        val edgeFade = 26.dp.toPx()
        if (cropRect.height > edgeFade * 2f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.24f), Color.Transparent),
                    startY = top,
                    endY = top + edgeFade
                ),
                topLeft = Offset(left, top),
                size = Size(cropRect.width, edgeFade)
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.24f)),
                    startY = bottom - edgeFade,
                    endY = bottom
                ),
                topLeft = Offset(left, bottom - edgeFade),
                size = Size(cropRect.width, edgeFade)
            )
        }
    }
}
