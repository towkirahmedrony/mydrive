package com.mydrive.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditorStoreTest {

    private fun recipe(
        rotation: Int = 0,
        flipH: Boolean = false,
        flipV: Boolean = false,
        crop: NormalizedRect = NormalizedRect.Full,
        adjustments: EditorAdjustments = EditorAdjustments(),
        filter: EditorFilter = EditorFilter.ORIGINAL
    ) = PhotoEditorRecipe(
        sourceUri = "content://media/external/images/media/1",
        mediaId = "photo-1",
        crop = crop,
        rotationDegrees = rotation,
        flipHorizontal = flipH,
        flipVertical = flipV,
        adjustments = adjustments,
        filter = filter
    )

    @Test
    fun applyRecordsUndoAndClearsRedo() {
        val store = PhotoEditorStore(recipe())
        store.apply(recipe(rotation = 90))
        store.apply(recipe(rotation = 180))
        assertTrue(store.canUndo)
        assertFalse(store.canRedo)
        assertEquals(180, store.current.rotationDegrees)

        store.undo()
        assertEquals(90, store.current.rotationDegrees)
        assertTrue(store.canRedo)

        store.apply(recipe(rotation = 270))
        assertFalse(store.canRedo)
        assertEquals(270, store.current.rotationDegrees)
    }

    @Test
    fun undoAndRedoWalkRecipeHistoryWithoutBitmapSnapshots() {
        val store = PhotoEditorStore(recipe())
        store.apply(recipe(rotation = 90, flipH = true))
        store.apply(recipe(rotation = 90, flipH = true, flipV = true))

        store.undo()
        assertTrue(store.current.flipHorizontal)
        assertFalse(store.current.flipVertical)

        store.undo()
        assertEquals(store.initial, store.current)

        store.redo()
        assertEquals(90, store.current.rotationDegrees)
        store.redo()
        assertTrue(store.current.flipVertical)
        assertFalse(store.canRedo)
    }

    @Test
    fun resetReturnsToOriginalRecipeAndIsUndoable() {
        val store = PhotoEditorStore(recipe())
        store.apply(recipe(rotation = 90, adjustments = EditorAdjustments(brightness = 0.4f)))
        assertTrue(store.isEdited)

        store.reset()
        assertEquals(store.initial, store.current)
        assertFalse(store.isEdited)
        assertTrue(store.canUndo)

        store.undo()
        assertEquals(90, store.current.rotationDegrees)
        assertEquals(0.4f, store.current.adjustments.brightness)
    }

    @Test
    fun replaceDoesNotPushHistoryUntilCommitFrom() {
        val store = PhotoEditorStore(recipe())
        val before = store.current
        store.replace(recipe(adjustments = EditorAdjustments(contrast = 0.3f)))
        assertFalse(store.canUndo)
        store.commitFrom(before)
        assertTrue(store.canUndo)
        store.undo()
        assertEquals(0f, store.current.adjustments.contrast)
    }
}
