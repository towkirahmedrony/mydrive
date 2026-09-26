package com.mydrive.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditorRecipeTest {

    private fun base() = PhotoEditorRecipe(
        sourceUri = "content://media/external/images/media/42",
        mediaId = "42"
    )

    @Test
    fun cropStateIsHostOwnedAndCoerced() {
        val crop = NormalizedRect(left = -0.2f, top = 0.1f, right = 1.4f, bottom = 0.15f).coerced()
        assertTrue(crop.left >= 0f)
        assertTrue(crop.right <= 1f)
        assertTrue(crop.width >= NormalizedRect.MIN_SIZE)
        assertTrue(crop.height >= NormalizedRect.MIN_SIZE)

        val recipe = base().copy(crop = crop)
        assertFalse(recipe.crop.isFull)
        assertFalse(recipe.isIdentity)
    }

    @Test
    fun rotationWrapsAndFlipStateIsIndependent() {
        val rotated = base().withRotation(90).withRotation(90).withRotation(180)
        assertEquals(0, rotated.rotationDegrees)

        val flipped = rotated.copy(flipHorizontal = true, flipVertical = true)
        assertTrue(flipped.flipHorizontal)
        assertTrue(flipped.flipVertical)
        assertEquals(0, flipped.rotationDegrees)
        assertFalse(flipped.isIdentity)
    }

    @Test
    fun adjustmentStateChangesAreTrackedInTheRecipe() {
        val adjusted = base().copy(
            adjustments = EditorAdjustments(
                brightness = 0.25f,
                contrast = -0.1f,
                saturation = 0.4f,
                warmth = -0.2f,
                sharpness = 0.5f,
                vignette = 0.3f
            )
        )
        assertFalse(adjusted.adjustments.isIdentity)
        assertEquals(0.25f, adjusted.adjustments.brightness)
        assertEquals(-0.1f, adjusted.adjustments.contrast)
        assertEquals(0.4f, adjusted.adjustments.saturation)
        assertEquals(-0.2f, adjusted.adjustments.warmth)
        assertEquals(0.5f, adjusted.adjustments.sharpness)
        assertEquals(0.3f, adjusted.adjustments.vignette)
        assertFalse(adjusted.isIdentity)
    }

    @Test
    fun overlaysAndStrokesStayInNormalizedEditorState() {
        val recipe = base().copy(
            texts = listOf(EditorTextOverlay(id = "t1", text = "Hello", x = 0.5f, y = 0.4f, scale = 1.2f, rotation = 15f)),
            stickers = listOf(EditorStickerOverlay(id = "s1", stickerId = "heart", x = 0.3f, y = 0.7f)),
            strokes = listOf(
                EditorDrawStroke(
                    id = "d1",
                    points = listOf(EditorDrawPoint(0.1f, 0.2f), EditorDrawPoint(0.4f, 0.5f)),
                    color = 0xFFFFFFFF,
                    width = 0.02f
                )
            )
        )
        assertEquals("Hello", recipe.texts.single().text)
        assertEquals("heart", recipe.stickers.single().stickerId)
        assertEquals(2, recipe.strokes.single().points.size)
        assertTrue(recipe.strokes.single().points.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
