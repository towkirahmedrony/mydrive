package com.mydrive.app.ui.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class EditorHsv(
    val hue: Float,
    val saturation: Float,
    val value: Float
)

object EditorPalette {
    val colors: List<Long> = listOf(
        0xFFFFFFFF,
        0xFFF5F5F5,
        0xFFE0E0E0,
        0xFFBDBDBD,
        0xFF9E9E9E,
        0xFF757575,
        0xFF616161,
        0xFF424242,
        0xFF212121,
        0xFF000000,
        0xFFFFCDD2,
        0xFFEF9A9A,
        0xFFF44336,
        0xFFD32F2F,
        0xFFB71C1C,
        0xFFF8BBD0,
        0xFFF48FB1,
        0xFFE91E63,
        0xFFC2185B,
        0xFFE1BEE7,
        0xFFCE93D8,
        0xFF9C27B0,
        0xFF6A1B9A,
        0xFFC5CAE9,
        0xFF9FA8DA,
        0xFF3F51B5,
        0xFF1A237E,
        0xFFBBDEFB,
        0xFF90CAF9,
        0xFF2196F3,
        0xFF1565C0,
        0xFF0D47A1,
        0xFFB2EBF2,
        0xFF4DD0E1,
        0xFF00ACC1,
        0xFF006064,
        0xFFC8E6C9,
        0xFFA5D6A7,
        0xFF4CAF50,
        0xFF2E7D32,
        0xFF1B5E20,
        0xFFFFF9C4,
        0xFFFFF59D,
        0xFFFFEB3B,
        0xFFFBC02D,
        0xFFFFE0B2,
        0xFFFFCC80,
        0xFFFF9800,
        0xFFEF6C00,
        0xFFE65100,
        0xFFD7CCC8,
        0xFFA1887F,
        0xFF795548,
        0xFF4E342E,
        0xFFD4A574,
        0xFFD97858,
        0xFF8FADA0,
        0xFF8AA4C4
    )

    fun argb(color: Long): Long = color or 0xFF000000L

    fun hsvToColor(hue: Float, saturation: Float, value: Float): Long {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rp, gp, bp) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rp + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((gp + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((bp + m) * 255f).roundToInt().coerceIn(0, 255)
        return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    fun colorToHsv(color: Long): EditorHsv {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val saturation = if (max == 0f) 0f else delta / max
        return EditorHsv(
            hue = ((hue % 360f) + 360f) % 360f,
            saturation = saturation.coerceIn(0f, 1f),
            value = max.coerceIn(0f, 1f)
        )
    }

    fun sampleNormalized(width: Int, height: Int, x: Float, y: Float, pixelAt: (Int, Int) -> Int): Long {
        if (width <= 0 || height <= 0) return 0xFFFFFFFF
        val px = (x * (width - 1)).roundToInt().coerceIn(0, width - 1)
        val py = (y * (height - 1)).roundToInt().coerceIn(0, height - 1)
        return argb(pixelAt(px, py).toLong() and 0xFFFFFFFFL)
    }
}
