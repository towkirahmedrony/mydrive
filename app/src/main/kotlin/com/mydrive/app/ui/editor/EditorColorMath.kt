package com.mydrive.app.ui.editor

object EditorColorMath {
    private const val LUMA_R = 0.213f
    private const val LUMA_G = 0.715f
    private const val LUMA_B = 0.072f

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    fun brightness(value: Float): FloatArray {
        val offset = value.coerceIn(-1f, 1f) * 255f
        return floatArrayOf(
            1f, 0f, 0f, 0f, offset,
            0f, 1f, 0f, 0f, offset,
            0f, 0f, 1f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }

    fun contrast(value: Float): FloatArray {
        val scale = 1f + value.coerceIn(-1f, 1f)
        val translate = 128f * (1f - scale)
        return floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    }

    fun saturation(value: Float): FloatArray {
        val s = (1f + value.coerceIn(-1f, 1f)).coerceAtLeast(0f)
        val ir = (1f - s) * LUMA_R
        val ig = (1f - s) * LUMA_G
        val ib = (1f - s) * LUMA_B
        return floatArrayOf(
            ir + s, ig, ib, 0f, 0f,
            ir, ig + s, ib, 0f, 0f,
            ir, ig, ib + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    fun warmth(value: Float): FloatArray {
        val w = value.coerceIn(-1f, 1f)
        val r = 1f + 0.32f * w
        val g = 1f + 0.08f * w
        val b = 1f - 0.32f * w
        return floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    fun filterMatrix(filter: EditorFilter): FloatArray = when (filter) {
        EditorFilter.ORIGINAL -> identity()
        EditorFilter.MONO -> saturation(-1f)
        EditorFilter.FADE -> multiply(
            contrast(-0.18f),
            brightness(0.08f)
        )
        EditorFilter.WARM_GLOW -> multiply(
            warmth(0.42f),
            contrast(0.08f)
        )
        EditorFilter.COOL_MIST -> multiply(
            warmth(-0.38f),
            brightness(0.04f)
        )
        EditorFilter.VINTAGE -> multiply(
            multiply(sepia(), contrast(0.12f)),
            warmth(0.18f)
        )
        EditorFilter.DRAMATIC -> multiply(
            contrast(0.38f),
            saturation(-0.12f)
        )
        EditorFilter.SOFT -> multiply(
            contrast(-0.12f),
            brightness(0.06f)
        )
    }

    fun combined(adjustments: EditorAdjustments, filter: EditorFilter): FloatArray {
        var matrix = identity()
        if (adjustments.brightness != 0f) matrix = multiply(brightness(adjustments.brightness), matrix)
        if (adjustments.contrast != 0f) matrix = multiply(contrast(adjustments.contrast), matrix)
        if (adjustments.saturation != 0f) matrix = multiply(saturation(adjustments.saturation), matrix)
        if (adjustments.warmth != 0f) matrix = multiply(warmth(adjustments.warmth), matrix)
        if (filter != EditorFilter.ORIGINAL) matrix = multiply(filterMatrix(filter), matrix)
        return matrix
    }

    fun isIdentity(matrix: FloatArray, epsilon: Float = 0.001f): Boolean {
        val identity = identity()
        if (matrix.size != 20) return false
        for (i in 0 until 20) {
            if (kotlin.math.abs(matrix[i] - identity[i]) > epsilon) return false
        }
        return true
    }

    fun multiply(lhs: FloatArray, rhs: FloatArray): FloatArray {
        val out = FloatArray(20)
        var index = 0
        while (index < 20) {
            for (j in 0..3) {
                out[index + j] =
                    lhs[index] * rhs[j] +
                    lhs[index + 1] * rhs[j + 5] +
                    lhs[index + 2] * rhs[j + 10] +
                    lhs[index + 3] * rhs[j + 15]
            }
            out[index + 4] =
                lhs[index] * rhs[4] +
                lhs[index + 1] * rhs[9] +
                lhs[index + 2] * rhs[14] +
                lhs[index + 3] * rhs[19] +
                lhs[index + 4]
            index += 5
        }
        return out
    }

    private fun sepia(): FloatArray = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
}
