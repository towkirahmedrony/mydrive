package com.mydrive.app.ui.editor

data class NormalizedRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isFull: Boolean get() = left <= 0.0001f && top <= 0.0001f && right >= 0.9999f && bottom >= 0.9999f

    fun coerced(minSize: Float = MIN_SIZE): NormalizedRect {
        val maxL = (1f - minSize).coerceAtLeast(0f)
        val l = left.coerceIn(0f, maxL)
        val t = top.coerceIn(0f, maxL)
        val r = right.coerceIn((l + minSize).coerceAtMost(1f), 1f)
        val b = bottom.coerceIn((t + minSize).coerceAtMost(1f), 1f)
        return NormalizedRect(l, t, r, b)
    }

    companion object {
        const val MIN_SIZE = 0.12f
        val Full = NormalizedRect()
    }
}

data class EditorAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sharpness: Float = 0f,
    val vignette: Float = 0f
) {
    val isIdentity: Boolean
        get() = brightness == 0f &&
            contrast == 0f &&
            saturation == 0f &&
            warmth == 0f &&
            sharpness == 0f &&
            vignette == 0f
}

enum class EditorFilter(val displayName: String) {
    ORIGINAL("Original"),
    MONO("Mono"),
    FADE("Fade"),
    WARM_GLOW("Warm Glow"),
    COOL_MIST("Cool Mist"),
    VINTAGE("Vintage"),
    DRAMATIC("Dramatic"),
    SOFT("Soft")
}

enum class EditorTool(val label: String) {
    CROP("Crop"),
    ADJUST("Adjust"),
    FILTERS("Filters"),
    DRAW("Draw"),
    TEXT("Text"),
    STICKERS("Stickers")
}

data class EditorTextOverlay(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val color: Long = 0xFFFFFFFF
)

data class EditorStickerOverlay(
    val id: String,
    val stickerId: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f
)

data class EditorDrawPoint(
    val x: Float,
    val y: Float
)

data class EditorDrawStroke(
    val id: String,
    val points: List<EditorDrawPoint>,
    val color: Long,
    val width: Float
)

data class PhotoEditorRecipe(
    val sourceUri: String,
    val mediaId: String,
    val crop: NormalizedRect = NormalizedRect.Full,
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val adjustments: EditorAdjustments = EditorAdjustments(),
    val filter: EditorFilter = EditorFilter.ORIGINAL,
    val texts: List<EditorTextOverlay> = emptyList(),
    val stickers: List<EditorStickerOverlay> = emptyList(),
    val strokes: List<EditorDrawStroke> = emptyList()
) {
    val isIdentity: Boolean
        get() = crop.isFull &&
            rotationDegrees % 360 == 0 &&
            !flipHorizontal &&
            !flipVertical &&
            adjustments.isIdentity &&
            filter == EditorFilter.ORIGINAL &&
            texts.isEmpty() &&
            stickers.isEmpty() &&
            strokes.isEmpty()

    fun withRotation(delta: Int): PhotoEditorRecipe {
        val next = ((rotationDegrees + delta) % 360 + 360) % 360
        return copy(rotationDegrees = next)
    }
}

enum class EditorStickerKind(
    val id: String,
    val label: String,
    val drawableName: String
) {
    HEART("heart", "Heart", "editor_sticker_heart"),
    STAR("star", "Star", "editor_sticker_star"),
    SUN("sun", "Sun", "editor_sticker_sun"),
    SMILE("smile", "Smile", "editor_sticker_smile"),
    LEAF("leaf", "Leaf", "editor_sticker_leaf"),
    SPARK("spark", "Spark", "editor_sticker_spark");

    companion object {
        fun fromId(id: String): EditorStickerKind =
            entries.firstOrNull { it.id == id } ?: HEART
    }
}
