package com.mydrive.app.ui.editor

class PhotoEditorStore(val initial: PhotoEditorRecipe) {
    var current: PhotoEditorRecipe = initial
        private set

    private val undoStack = ArrayDeque<PhotoEditorRecipe>()
    private val redoStack = ArrayDeque<PhotoEditorRecipe>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isEdited: Boolean get() = current != initial

    fun apply(next: PhotoEditorRecipe) {
        if (next == current) return
        pushUndo(current)
        redoStack.clear()
        current = next
    }

    fun replace(next: PhotoEditorRecipe) {
        current = next
    }

    fun commitFrom(before: PhotoEditorRecipe) {
        if (current == before) return
        pushUndo(before)
        redoStack.clear()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        current = previous
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        current = next
    }

    fun reset() {
        if (current == initial) {
            undoStack.clear()
            redoStack.clear()
            return
        }
        pushUndo(current)
        redoStack.clear()
        current = initial
    }

    private fun pushUndo(recipe: PhotoEditorRecipe) {
        undoStack.addLast(recipe)
        while (undoStack.size > MAX_HISTORY) {
            undoStack.removeFirst()
        }
    }

    companion object {
        const val MAX_HISTORY = 50
    }
}
