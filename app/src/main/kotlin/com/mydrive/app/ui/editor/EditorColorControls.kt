package com.mydrive.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Spacing

@Composable
fun EditorColorSelector(
    selected: Long,
    eyedropperEnabled: Boolean,
    onSelect: (Long) -> Unit,
    onToggleEyedropper: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(EditorPalette.colors, key = { it }) { color ->
                ColorDot(
                    color = color,
                    selected = EditorPalette.argb(selected) == color,
                    onClick = { onSelect(color) }
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = Icons.Outlined.Palette,
                contentDescription = "Custom color",
                tint = Ivory,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { showPicker = true }
                    .padding(5.dp)
            )
            Icon(
                imageVector = Icons.Outlined.Colorize,
                contentDescription = "Pick from photo",
                tint = if (eyedropperEnabled) Ink else Ivory,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (eyedropperEnabled) Copper else Color.White.copy(alpha = 0.1f))
                    .clickable(onClick = onToggleEyedropper)
                    .padding(5.dp)
            )
        }
    }
    if (showPicker) {
        EditorHsvPickerDialog(
            initial = selected,
            onDismiss = { showPicker = false },
            onConfirm = { color ->
                onSelect(color)
                showPicker = false
            }
        )
    }
}

@Composable
private fun ColorDot(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Copper else Color.White.copy(alpha = 0.28f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun EditorHsvPickerDialog(
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val start = EditorPalette.colorToHsv(initial)
    var hue by remember { mutableFloatStateOf(start.hue) }
    var saturation by remember { mutableFloatStateOf(start.saturation) }
    var value by remember { mutableFloatStateOf(start.value) }
    val current = EditorPalette.hsvToColor(hue, saturation, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SaturationValueBox(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { s, v ->
                        saturation = s
                        value = v
                    }
                )
                Text("Hue", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = Copper,
                        activeTrackColor = Copper,
                        inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(Color(current))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(Radius.sm))
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Selected", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current) }) { Text("Apply", color = Copper) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit
) {
    val hueColor = Color(EditorPalette.hsvToColor(hue, 1f, 1f))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(Radius.md))
            .pointerInput(hue) {
                fun emit(offset: Offset) {
                    val s = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onChange(s, v)
                }
                detectTapGestures(onTap = { emit(it) })
            }
            .pointerInput(hue) {
                fun emit(offset: Offset) {
                    val s = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onChange(s, v)
                }
                detectDragGestures(
                    onDragStart = { emit(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        emit(change.position)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = saturation * size.width
            val cy = (1f - value) * size.height
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.Black, radius = 8.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}
