package com.mydrive.app.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object EditorPreviewPipeline {

    suspend fun render(source: Bitmap, recipe: PhotoEditorRecipe): Bitmap = withContext(Dispatchers.Default) {
        renderSync(source, recipe)
    }

    fun renderSync(source: Bitmap, recipe: PhotoEditorRecipe): Bitmap {
        val cropped = crop(source, recipe.crop)
        val transformed = transform(cropped, recipe.rotationDegrees, recipe.flipHorizontal, recipe.flipVertical)
        if (cropped !== source && cropped !== transformed && !cropped.isRecycled) {
            cropped.recycle()
        }
        val colorAdjusted = applyColor(transformed, recipe.adjustments, recipe.filter)
        if (transformed !== source && transformed !== colorAdjusted && !transformed.isRecycled) {
            transformed.recycle()
        }
        val sharpened = applySharpness(colorAdjusted, recipe.adjustments.sharpness)
        if (colorAdjusted !== source && colorAdjusted !== sharpened && !colorAdjusted.isRecycled) {
            colorAdjusted.recycle()
        }
        val result = applyVignette(sharpened, recipe.adjustments.vignette)
        return if (result === source) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            result
        }
    }

    private fun crop(source: Bitmap, crop: NormalizedRect): Bitmap {
        if (crop.isFull) return source
        val rect = crop.coerced()
        val left = (rect.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (rect.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = (rect.right * source.width).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (rect.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun transform(source: Bitmap, rotationDegrees: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation == 0 && !flipH && !flipV) return source
        val matrix = Matrix()
        if (flipH || flipV) {
            matrix.preScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        }
        if (rotation != 0) {
            matrix.postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyColor(source: Bitmap, adjustments: EditorAdjustments, filter: EditorFilter): Bitmap {
        val matrix = EditorColorMath.combined(adjustments, filter)
        if (EditorColorMath.isIdentity(matrix)) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun applySharpness(source: Bitmap, amount: Float): Bitmap {
        if (abs(amount) < 0.01f) return source
        val strength = amount.coerceIn(0f, 1f)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val dest = IntArray(pixels.size)
        val center = 1f + 4f * strength
        val neighbor = -strength
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val original = pixels[index]
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    dest[index] = original
                    continue
                }
                val left = pixels[index - 1]
                val right = pixels[index + 1]
                val up = pixels[index - width]
                val down = pixels[index + width]
                dest[index] = Color.argb(
                    Color.alpha(original),
                    clampColor(Color.red(original) * center + Color.red(left) * neighbor + Color.red(right) * neighbor + Color.red(up) * neighbor + Color.red(down) * neighbor),
                    clampColor(Color.green(original) * center + Color.green(left) * neighbor + Color.green(right) * neighbor + Color.green(up) * neighbor + Color.green(down) * neighbor),
                    clampColor(Color.blue(original) * center + Color.blue(left) * neighbor + Color.blue(right) * neighbor + Color.blue(up) * neighbor + Color.blue(down) * neighbor)
                )
            }
        }
        output.setPixels(dest, 0, width, 0, 0, width, height)
        return output
    }

    private fun applyVignette(source: Bitmap, amount: Float): Bitmap {
        if (abs(amount) < 0.01f) return source
        val strength = amount.coerceIn(0f, 1f)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val cx = source.width / 2f
        val cy = source.height / 2f
        val radius = hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val inner = radius * (0.42f + 0.28f * (1f - strength))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((180 * strength).roundToInt(), 0, 0, 0)),
                floatArrayOf(0f, (inner / radius).coerceIn(0.2f, 0.85f), 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(Rect(0, 0, source.width, source.height), paint)
        return output
    }

    private fun clampColor(value: Float): Int = min(255, max(0, value.roundToInt()))
}
