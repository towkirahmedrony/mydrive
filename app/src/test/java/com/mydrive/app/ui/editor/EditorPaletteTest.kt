package com.mydrive.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPaletteTest {

    @Test
    fun paletteIncludesNeutralsPrimariesAndIntermediates() {
        assertTrue(EditorPalette.colors.size >= 40)
        assertTrue(EditorPalette.colors.contains(0xFFFFFFFF))
        assertTrue(EditorPalette.colors.contains(0xFF000000))
        assertTrue(EditorPalette.colors.contains(0xFFF44336))
        assertTrue(EditorPalette.colors.contains(0xFF2196F3))
        assertTrue(EditorPalette.colors.contains(0xFF4CAF50))
    }

    @Test
    fun hsvRoundTripKeepsPrimaryColors() {
        val red = EditorPalette.hsvToColor(0f, 1f, 1f)
        val hsv = EditorPalette.colorToHsv(red)
        assertEquals(0f, hsv.hue, 1f)
        assertEquals(1f, hsv.saturation, 0.02f)
        assertEquals(1f, hsv.value, 0.02f)
        assertEquals(0xFFFF0000L, red)
    }

    @Test
    fun sampleNormalizedUsesDisplayedCoordinates() {
        val sampled = EditorPalette.sampleNormalized(2, 2, 1f, 0f) { x, y ->
            when {
                x == 1 && y == 0 -> 0xFF112233.toInt()
                else -> 0xFF000000.toInt()
            }
        }
        assertEquals(0xFF112233L, sampled)
    }
}
