package com.mydrive.app.ui.editor

import kotlin.math.abs
import kotlin.math.min

enum class CropHandle {
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    INSIDE
}

enum class CropAspectPreset(val label: String, private val widthToHeight: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", null),
    RATIO_1_1("1:1", 1f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_3_4("3:4", 3f / 4f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_3_2("3:2", 3f / 2f),
    RATIO_2_3("2:3", 2f / 3f);

    fun pixelAspect(imageAspect: Float): Float? = when (this) {
        FREE -> null
        ORIGINAL -> imageAspect.takeIf { it > 0f }
        else -> widthToHeight
    }

    fun displayPixelAspect(sourceAspect: Float, rotationDegrees: Int): Float? {
        val sourceRatio = pixelAspect(sourceAspect) ?: return null
        val rotation = ((rotationDegrees % 360) + 360) % 360
        return if (rotation == 90 || rotation == 270) {
            if (sourceRatio == 0f) null else 1f / sourceRatio
        } else {
            sourceRatio
        }
    }
}

object EditorCropMath {

    fun normalizedRatio(pixelAspect: Float?, imageAspect: Float): Float? {
        if (pixelAspect == null || pixelAspect <= 0f || imageAspect <= 0f) return null
        return pixelAspect / imageAspect
    }

    fun move(crop: NormalizedRect, dx: Float, dy: Float): NormalizedRect {
        var left = crop.left + dx
        var top = crop.top + dy
        var right = crop.right + dx
        var bottom = crop.bottom + dy
        if (left < 0f) {
            right -= left
            left = 0f
        }
        if (top < 0f) {
            bottom -= top
            top = 0f
        }
        if (right > 1f) {
            left -= right - 1f
            right = 1f
        }
        if (bottom > 1f) {
            top -= bottom - 1f
            bottom = 1f
        }
        return NormalizedRect(left, top, right, bottom).coerced()
    }

    fun hitTest(
        crop: NormalizedRect,
        x: Float,
        y: Float,
        handleSize: Float
    ): CropHandle? {
        val pad = handleSize.coerceIn(0.02f, 0.2f)
        val nearLeft = abs(x - crop.left) <= pad
        val nearRight = abs(x - crop.right) <= pad
        val nearTop = abs(y - crop.top) <= pad
        val nearBottom = abs(y - crop.bottom) <= pad
        val inX = x >= crop.left - pad && x <= crop.right + pad
        val inY = y >= crop.top - pad && y <= crop.bottom + pad
        return when {
            nearLeft && nearTop -> CropHandle.TOP_LEFT
            nearRight && nearTop -> CropHandle.TOP_RIGHT
            nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
            nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
            nearLeft && inY -> CropHandle.LEFT
            nearRight && inY -> CropHandle.RIGHT
            nearTop && inX -> CropHandle.TOP
            nearBottom && inX -> CropHandle.BOTTOM
            x >= crop.left && x <= crop.right && y >= crop.top && y <= crop.bottom -> CropHandle.INSIDE
            else -> null
        }
    }

    fun resize(
        crop: NormalizedRect,
        handle: CropHandle,
        dx: Float,
        dy: Float,
        normRatio: Float?,
        minSize: Float = NormalizedRect.MIN_SIZE
    ): NormalizedRect {
        if (handle == CropHandle.INSIDE) return move(crop, dx, dy)
        var left = crop.left
        var top = crop.top
        var right = crop.right
        var bottom = crop.bottom
        when (handle) {
            CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT -> left += dx
            CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT -> right += dx
            else -> Unit
        }
        when (handle) {
            CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT -> top += dy
            CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT -> bottom += dy
            else -> Unit
        }
        val anchorX = when (handle) {
            CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT -> crop.right
            CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT -> crop.left
            else -> (crop.left + crop.right) / 2f
        }
        val anchorY = when (handle) {
            CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT -> crop.bottom
            CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT -> crop.top
            else -> (crop.top + crop.bottom) / 2f
        }
        if (normRatio != null && normRatio > 0f) {
            val width = abs(right - left).coerceAtLeast(minSize)
            val height = abs(bottom - top).coerceAtLeast(minSize)
            val widthDriven = when (handle) {
                CropHandle.LEFT, CropHandle.RIGHT -> true
                CropHandle.TOP, CropHandle.BOTTOM -> false
                else -> abs(dx) * normRatio >= abs(dy)
            }
            if (widthDriven) {
                val nextHeight = width / normRatio
                if (handle == CropHandle.LEFT || handle == CropHandle.RIGHT) {
                    val cy = (crop.top + crop.bottom) / 2f
                    top = cy - nextHeight / 2f
                    bottom = cy + nextHeight / 2f
                } else if (anchorY >= (top + bottom) / 2f) {
                    top = bottom - nextHeight
                } else {
                    bottom = top + nextHeight
                }
            } else {
                val nextWidth = height * normRatio
                if (handle == CropHandle.TOP || handle == CropHandle.BOTTOM) {
                    val cx = (crop.left + crop.right) / 2f
                    left = cx - nextWidth / 2f
                    right = cx + nextWidth / 2f
                } else if (anchorX >= (left + right) / 2f) {
                    left = right - nextWidth
                } else {
                    right = left + nextWidth
                }
            }
        }
        if (left > right) {
            val swap = left
            left = right
            right = swap
        }
        if (top > bottom) {
            val swap = top
            top = bottom
            bottom = swap
        }
        return clampRect(left, top, right, bottom, normRatio, minSize, anchorX, anchorY)
    }

    fun sourceToDisplay(
        x: Float,
        y: Float,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean
    ): Pair<Float, Float> {
        val fx = if (flipHorizontal) 1f - x else x
        val fy = if (flipVertical) 1f - y else y
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> (1f - fy) to fx
            180 -> (1f - fx) to (1f - fy)
            270 -> fy to (1f - fx)
            else -> fx to fy
        }
    }

    fun displayToSource(
        x: Float,
        y: Float,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean
    ): Pair<Float, Float> {
        val (rx, ry) = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
        return (if (flipHorizontal) 1f - rx else rx) to (if (flipVertical) 1f - ry else ry)
    }

    fun mapRectToDisplay(
        crop: NormalizedRect,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean
    ): NormalizedRect = mapRect(crop, rotationDegrees, flipHorizontal, flipVertical, ::sourceToDisplay)

    fun mapRectToSource(
        crop: NormalizedRect,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean
    ): NormalizedRect = mapRect(crop, rotationDegrees, flipHorizontal, flipVertical, ::displayToSource)

    private fun mapRect(
        crop: NormalizedRect,
        rotationDegrees: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        transform: (Float, Float, Int, Boolean, Boolean) -> Pair<Float, Float>
    ): NormalizedRect {
        val points = listOf(
            transform(crop.left, crop.top, rotationDegrees, flipHorizontal, flipVertical),
            transform(crop.right, crop.top, rotationDegrees, flipHorizontal, flipVertical),
            transform(crop.left, crop.bottom, rotationDegrees, flipHorizontal, flipVertical),
            transform(crop.right, crop.bottom, rotationDegrees, flipHorizontal, flipVertical)
        )
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        return NormalizedRect(
            xs.minOrNull() ?: 0f,
            ys.minOrNull() ?: 0f,
            xs.maxOrNull() ?: 1f,
            ys.maxOrNull() ?: 1f
        ).coerced()
    }

    fun fitPreset(
        preset: CropAspectPreset,
        imageAspect: Float,
        minSize: Float = NormalizedRect.MIN_SIZE
    ): NormalizedRect {
        val pixelAspect = preset.pixelAspect(imageAspect) ?: return NormalizedRect.Full
        val normRatio = normalizedRatio(pixelAspect, imageAspect) ?: return NormalizedRect.Full
        val height = min(1f, 1f / normRatio).coerceAtLeast(minSize)
        val width = (normRatio * height).coerceIn(minSize, 1f)
        val left = ((1f - width) / 2f).coerceAtLeast(0f)
        val top = ((1f - height) / 2f).coerceAtLeast(0f)
        return NormalizedRect(left, top, (left + width).coerceAtMost(1f), (top + height).coerceAtMost(1f)).coerced(minSize)
    }

    private fun clampRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        normRatio: Float?,
        minSize: Float,
        anchorX: Float,
        anchorY: Float
    ): NormalizedRect {
        var l = left
        var t = top
        var r = right
        var b = bottom
        if (l < 0f) {
            r -= l
            l = 0f
        }
        if (t < 0f) {
            b -= t
            t = 0f
        }
        if (r > 1f) {
            l -= r - 1f
            r = 1f
        }
        if (b > 1f) {
            t -= b - 1f
            b = 1f
        }
        l = l.coerceIn(0f, 1f - minSize)
        t = t.coerceIn(0f, 1f - minSize)
        r = r.coerceIn(l + minSize, 1f)
        b = b.coerceIn(t + minSize, 1f)
        if (normRatio != null && normRatio > 0f) {
            var width = (r - l).coerceAtLeast(minSize)
            var height = width / normRatio
            if (height < minSize) {
                height = minSize
                width = height * normRatio
            }
            if (width > 1f) {
                width = 1f
                height = width / normRatio
            }
            if (height > 1f) {
                height = 1f
                width = height * normRatio
            }
            width = width.coerceIn(minSize, 1f)
            height = height.coerceIn(minSize, 1f)
            if (anchorX >= 0.5f) {
                r = r.coerceAtMost(1f)
                l = (r - width).coerceAtLeast(0f)
                r = l + width
            } else {
                l = l.coerceAtLeast(0f)
                r = (l + width).coerceAtMost(1f)
                l = r - width
            }
            if (anchorY >= 0.5f) {
                b = b.coerceAtMost(1f)
                t = (b - height).coerceAtLeast(0f)
                b = t + height
            } else {
                t = t.coerceAtLeast(0f)
                b = (t + height).coerceAtMost(1f)
                t = b - height
            }
        }
        return NormalizedRect(l, t, r, b).coerced(minSize)
    }
}
