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

@Composable
fun EditorCropOverlay(
    crop: NormalizedRect,
    aspect: CropAspectPreset,
    imageAspect: Float,
    onChange: (NormalizedRect) -> Unit,
    onChangeFinished: () -> Unit
) {
    val density = LocalDensity.current
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
                        val handlePad = with(density) { 28.dp.toPx() } / min(size.width, size.height).coerceAtLeast(1f)
                        val nx = (offset.x / size.width).coerceIn(0f, 1f)
                        val ny = (offset.y / size.height).coerceIn(0f, 1f)
                        activeHandle = EditorCropMath.hitTest(cropState.value, nx, ny, handlePad)
                    },
                    onDrag = { change, dragAmount ->
                        val handle = activeHandle ?: return@detectDragGestures
                        change.consume()
                        val dx = dragAmount.x / size.width.coerceAtLeast(1f)
                        val dy = dragAmount.y / size.height.coerceAtLeast(1f)
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
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(cropRect)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.55f))
        drawRect(
            color = Color.White.copy(alpha = 0.95f),
            topLeft = Offset(left, top),
            size = Size(cropRect.width, cropRect.height),
            style = Stroke(width = 2.dp.toPx())
        )
        val thirdX = cropRect.width / 3f
        val thirdY = cropRect.height / 3f
        val grid = Color.White.copy(alpha = 0.28f)
        drawLine(grid, Offset(left + thirdX, top), Offset(left + thirdX, bottom), 1.dp.toPx())
        drawLine(grid, Offset(left + thirdX * 2f, top), Offset(left + thirdX * 2f, bottom), 1.dp.toPx())
        drawLine(grid, Offset(left, top + thirdY), Offset(right, top + thirdY), 1.dp.toPx())
        drawLine(grid, Offset(left, top + thirdY * 2f), Offset(right, top + thirdY * 2f), 1.dp.toPx())

        val handle = 18.dp.toPx()
        val stroke = 4.dp.toPx()
        val corners = listOf(
            Offset(left, top) to listOf(Offset(1f, 0f), Offset(0f, 1f)),
            Offset(right, top) to listOf(Offset(-1f, 0f), Offset(0f, 1f)),
            Offset(left, bottom) to listOf(Offset(1f, 0f), Offset(0f, -1f)),
            Offset(right, bottom) to listOf(Offset(-1f, 0f), Offset(0f, -1f))
        )
        corners.forEach { (origin, dirs) ->
            dirs.forEach { dir ->
                drawLine(
                    color = Copper,
                    start = origin,
                    end = Offset(origin.x + dir.x * handle, origin.y + dir.y * handle),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }
        val edge = 14.dp.toPx()
        val midY = (top + bottom) / 2f
        val midX = (left + right) / 2f
        drawLine(Copper, Offset(left, midY - edge / 2f), Offset(left, midY + edge / 2f), stroke, StrokeCap.Round)
        drawLine(Copper, Offset(right, midY - edge / 2f), Offset(right, midY + edge / 2f), stroke, StrokeCap.Round)
        drawLine(Copper, Offset(midX - edge / 2f, top), Offset(midX + edge / 2f, top), stroke, StrokeCap.Round)
        drawLine(Copper, Offset(midX - edge / 2f, bottom), Offset(midX + edge / 2f, bottom), stroke, StrokeCap.Round)
    }
}
