package com.mydrive.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import com.mydrive.app.R
import kotlin.math.min

/**
 * Where the on-screen overlay sizes come from.
 *
 * The editor previews at the photo's displayed size but exports at the source
 * resolution, so the Draw strokes (normalized), the Text labels (fixed 22sp) and
 * the Sticker bitmaps (fixed 72dp) all have to be scaled by the ratio between the
 * exported bitmap and the on-screen image box. [displayWidthPx] is that box's
 * width in pixels; [density] and [fontScale] convert the fixed sp/dp sizes.
 */
data class EditorOverlayRenderContext(
    val displayWidthPx: Float,
    val density: Float,
    val fontScale: Float
) {
    companion object {
        val Default = EditorOverlayRenderContext(displayWidthPx = 0f, density = 1f, fontScale = 1f)
    }
}

/**
 * Bakes the non-destructive editor recipe's Draw strokes, Text labels and
 * Stickers into the exported bitmap. The base image (crop / rotation / flips /
 * color / sharpness / vignette) comes from [EditorPreviewPipeline]; this only
 * adds the layers the preview draws on top of it, so a saved photo is exactly
 * what the user saw.
 */
object EditorExportRenderer {

    /** Matches the 22.sp used by the on-screen Text overlays. */
    private const val TEXT_SP = 22f

    /** Matches the 72.dp used by the on-screen Sticker overlays. */
    private const val STICKER_DP = 72f

    fun renderOverlays(
        context: Context,
        base: Bitmap,
        recipe: PhotoEditorRecipe,
        displayWidthPx: Float,
        density: Float,
        fontScale: Float
    ): Bitmap {
        val output = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val width = output.width.toFloat()
        val height = output.height.toFloat()
        val minDim = min(width, height)
        val scale = if (displayWidthPx > 0f) width / displayWidthPx else 1f
        val safeDensity = if (density > 0f) density else 1f
        val safeFontScale = if (fontScale > 0f) fontScale else 1f

        // Draw strokes first: the preview paints the Drawing layer beneath Text
        // and Stickers.
        recipe.strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val strokeColor = stroke.color.toInt()
            if (stroke.points.size == 1) {
                val point = stroke.points.first()
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = strokeColor
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(point.x * width, point.y * height, (stroke.width * minDim) / 2f, dot)
                return@forEach
            }
            val path = Path()
            val first = stroke.points.first()
            path.moveTo(first.x * width, first.y * height)
            stroke.points.drop(1).forEach { point -> path.lineTo(point.x * width, point.y * height) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = strokeColor
                style = Paint.Style.STROKE
                strokeWidth = stroke.width * minDim
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, paint)
        }

        recipe.stickers.forEach { sticker ->
            val size = STICKER_DP * safeDensity * scale * sticker.scale
            if (size < 1f) return@forEach
            val drawable = runCatching {
                AppCompatResources.getDrawable(context, stickerDrawableId(EditorStickerKind.fromId(sticker.stickerId)))
            }.getOrNull() ?: return@forEach
            val edge = size.toInt().coerceAtLeast(1)
            val stickerBitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, stickerBitmap.width, stickerBitmap.height)
            drawable.draw(Canvas(stickerBitmap))
            canvas.save()
            canvas.translate(sticker.x * width, sticker.y * height)
            canvas.rotate(sticker.rotation)
            canvas.drawBitmap(stickerBitmap, -stickerBitmap.width / 2f, -stickerBitmap.height / 2f, null)
            canvas.restore()
            stickerBitmap.recycle()
        }

        recipe.texts.forEach { text ->
            if (text.text.isBlank()) return@forEach
            val sizePx = TEXT_SP * safeDensity * safeFontScale * scale * text.scale
            if (sizePx < 1f) return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = text.color.toInt()
                textSize = sizePx
                typeface = Typeface.DEFAULT_BOLD
            }
            val textWidth = paint.measureText(text.text)
            val metrics = paint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            canvas.save()
            canvas.translate(text.x * width, text.y * height)
            canvas.rotate(text.rotation)
            canvas.drawText(text.text, -textWidth / 2f, -textHeight / 2f - metrics.ascent, paint)
            canvas.restore()
        }

        return output
    }

    fun stickerDrawableId(kind: EditorStickerKind): Int = when (kind) {
        EditorStickerKind.HEART -> R.drawable.editor_sticker_heart
        EditorStickerKind.STAR -> R.drawable.editor_sticker_star
        EditorStickerKind.SPARK -> R.drawable.editor_sticker_spark
        EditorStickerKind.CIRCLE -> R.drawable.editor_sticker_circle
        EditorStickerKind.DIAMOND -> R.drawable.editor_sticker_diamond
        EditorStickerKind.TRIANGLE -> R.drawable.editor_sticker_triangle
        EditorStickerKind.HEXAGON -> R.drawable.editor_sticker_hexagon
        EditorStickerKind.SUN -> R.drawable.editor_sticker_sun
        EditorStickerKind.LEAF -> R.drawable.editor_sticker_leaf
        EditorStickerKind.MOON -> R.drawable.editor_sticker_moon
        EditorStickerKind.CLOUD -> R.drawable.editor_sticker_cloud
        EditorStickerKind.FLOWER -> R.drawable.editor_sticker_flower
        EditorStickerKind.DROP -> R.drawable.editor_sticker_drop
        EditorStickerKind.SMILE -> R.drawable.editor_sticker_smile
        EditorStickerKind.WINK -> R.drawable.editor_sticker_wink
        EditorStickerKind.LAUGH -> R.drawable.editor_sticker_laugh
        EditorStickerKind.COOL -> R.drawable.editor_sticker_cool
        EditorStickerKind.HEART_EYES -> R.drawable.editor_sticker_heart_eyes
        EditorStickerKind.CHECK -> R.drawable.editor_sticker_check
        EditorStickerKind.ARROW -> R.drawable.editor_sticker_arrow
        EditorStickerKind.PIN -> R.drawable.editor_sticker_pin
        EditorStickerKind.BADGE -> R.drawable.editor_sticker_badge
        EditorStickerKind.BURST -> R.drawable.editor_sticker_burst
        EditorStickerKind.FRAME -> R.drawable.editor_sticker_frame
    }
}
