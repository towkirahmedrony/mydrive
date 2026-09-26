package com.mydrive.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EditorCropMathTest {

    @Test
    fun freeformResizeCanChangeAnyEdgeWithoutRatio() {
        val start = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val left = EditorCropMath.resize(start, CropHandle.LEFT, dx = 0.1f, dy = 0f, normRatio = null)
        val top = EditorCropMath.resize(start, CropHandle.TOP, dx = 0f, dy = 0.1f, normRatio = null)
        assertTrue(left.left > start.left)
        assertEquals(start.top, left.top, 0.0001f)
        assertEquals(start.bottom, left.bottom, 0.0001f)
        assertTrue(top.top > start.top)
        assertEquals(start.left, top.left, 0.0001f)
        assertEquals(start.right, top.right, 0.0001f)
    }

    @Test
    fun ratioResizeKeepsAspect() {
        val start = NormalizedRect(0.1f, 0.1f, 0.7f, 0.7f)
        val next = EditorCropMath.resize(start, CropHandle.RIGHT, dx = 0.1f, dy = 0f, normRatio = 1f)
        assertEquals(next.width, next.height, 0.02f)
        assertTrue(next.right <= 1f)
        assertTrue(next.bottom <= 1f)
    }

    @Test
    fun moveKeepsSizeAndStaysInBounds() {
        val start = NormalizedRect(0.2f, 0.2f, 0.5f, 0.4f)
        val moved = EditorCropMath.move(start, 0.9f, 0.9f)
        assertEquals(start.width, moved.width, 0.0001f)
        assertEquals(start.height, moved.height, 0.0001f)
        assertTrue(moved.right <= 1f)
        assertTrue(moved.bottom <= 1f)
    }

    @Test
    fun hitTestFindsCornersEdgesAndInside() {
        val crop = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(CropHandle.TOP_LEFT, EditorCropMath.hitTest(crop, 0.25f, 0.25f, 0.05f))
        assertEquals(CropHandle.RIGHT, EditorCropMath.hitTest(crop, 0.75f, 0.5f, 0.05f))
        assertEquals(CropHandle.INSIDE, EditorCropMath.hitTest(crop, 0.5f, 0.5f, 0.05f))
        assertNull(EditorCropMath.hitTest(crop, 0.02f, 0.02f, 0.05f))
    }

    @Test
    fun sourceDisplayMappingRoundTripsThroughRotationAndFlip() {
        val point = 0.2f to 0.8f
        val display = EditorCropMath.sourceToDisplay(point.first, point.second, 90, true, false)
        val back = EditorCropMath.displayToSource(display.first, display.second, 90, true, false)
        assertEquals(point.first, back.first, 0.0001f)
        assertEquals(point.second, back.second, 0.0001f)
    }

    @Test
    fun cropRectMappingIsIndependentOfPreviewResolution() {
        val crop = NormalizedRect(0.1f, 0.2f, 0.7f, 0.9f)
        val display = EditorCropMath.mapRectToDisplay(crop, 0, false, false)
        val source = EditorCropMath.mapRectToSource(display, 0, false, false)
        assertEquals(crop.left, source.left, 0.0001f)
        assertEquals(crop.top, source.top, 0.0001f)
        assertEquals(crop.right, source.right, 0.0001f)
        assertEquals(crop.bottom, source.bottom, 0.0001f)
    }

    @Test
    fun fitPresetCentersRequestedRatio() {
        val square = EditorCropMath.fitPreset(CropAspectPreset.RATIO_1_1, imageAspect = 16f / 9f)
        assertTrue(abs(square.width / square.height - (9f / 16f)) < 0.02f)
        assertNull(CropAspectPreset.FREE.pixelAspect(1.5f))
        assertEquals(1.5f, CropAspectPreset.ORIGINAL.pixelAspect(1.5f) ?: -1f, 0.0001f)
    }
}
