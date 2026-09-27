package com.mydrive.app.ui.selection

import com.mydrive.app.data.model.MediaItem

/**
 * Shared gallery multi-select state, keyed by stable media/catalog ids.
 *
 * Photos and Albums both use this. Selection never uses adapter/grid indexes, so
 * it survives recomposition, scrolling and pagination. Vault, Trash and invalid
 * records are not eligible: those collections are either a different screen or
 * already excluded from the composed library, and [isEligible] is the last guard.
 */
data class MediaSelectionState(
    val mode: Boolean = false,
    val selectedIds: Set<String> = emptySet()
) {
    val count: Int get() = selectedIds.size
    val isEmpty: Boolean get() = selectedIds.isEmpty()

    fun contains(id: String): Boolean = id in selectedIds
}

enum class BulkActionKind {
    DELETE,
    MOVE
}

data class BulkActionProgress(
    val inProgress: Boolean = false,
    val kind: BulkActionKind? = null,
    val processed: Int = 0,
    val total: Int = 0
) {
    val label: String?
        get() {
            if (!inProgress || kind == null || total <= 0) return null
            val verb = when (kind) {
                BulkActionKind.DELETE -> "Deleting"
                BulkActionKind.MOVE -> "Moving"
            }
            return "$verb $total ${if (total == 1) "item" else "items"}…"
        }
}

data class BulkItemCounts(
    val succeeded: Int = 0,
    val failed: Int = 0
) {
    val total: Int get() = succeeded + failed
}

object MediaSelection {

    fun isEligible(item: MediaItem): Boolean {
        if (item.id.isBlank()) return false
        if (item.isTrashed) return false
        if (item.hiddenFromLibrary) return false
        return true
    }

    /**
     * Active, eligible media on the current screen, in display order, de-duplicated
     * by stable id. Vault, Trash, hidden and blank records never appear here.
     */
    fun eligibleIds(items: List<MediaItem>): List<String> {
        if (items.isEmpty()) return emptyList()
        val seen = HashSet<String>(items.size)
        val ids = ArrayList<String>(items.size)
        for (item in items) {
            if (!isEligible(item)) continue
            if (seen.add(item.id)) ids.add(item.id)
        }
        return ids
    }

    fun eligibleIdSet(items: List<MediaItem>): Set<String> = eligibleIds(items).toSet()

    fun enter(id: String, eligibleIds: Set<String>): MediaSelectionState {
        if (id !in eligibleIds) return MediaSelectionState()
        return MediaSelectionState(mode = true, selectedIds = setOf(id))
    }

    fun toggle(
        state: MediaSelectionState,
        id: String,
        eligibleIds: Set<String>
    ): MediaSelectionState {
        if (!state.mode) {
            return enter(id, eligibleIds)
        }
        if (id !in eligibleIds && id !in state.selectedIds) return state
        val next = if (id in state.selectedIds) {
            state.selectedIds - id
        } else if (id in eligibleIds) {
            state.selectedIds + id
        } else {
            state.selectedIds
        }
        if (next.isEmpty()) return MediaSelectionState()
        return MediaSelectionState(mode = true, selectedIds = next)
    }

    fun selectAll(eligibleIds: Collection<String>): MediaSelectionState {
        val ids = eligibleIds.toSet()
        if (ids.isEmpty()) return MediaSelectionState()
        return MediaSelectionState(mode = true, selectedIds = ids)
    }

    /**
     * Drops ids that are no longer on this screen. Exits when nothing selected
     * remains — including after the last selected item is trashed or moved.
     */
    fun intersectVisible(
        state: MediaSelectionState,
        visibleEligibleIds: Set<String>
    ): MediaSelectionState {
        if (!state.mode) return MediaSelectionState()
        if (state.selectedIds.isEmpty()) return MediaSelectionState()
        val next = LinkedHashSet<String>(state.selectedIds.size)
        for (id in state.selectedIds) {
            if (id in visibleEligibleIds) next.add(id)
        }
        if (next.isEmpty()) return MediaSelectionState()
        if (next.size == state.selectedIds.size) return state
        return MediaSelectionState(mode = true, selectedIds = next)
    }

    fun minus(state: MediaSelectionState, ids: Collection<String>): MediaSelectionState {
        if (!state.mode || ids.isEmpty()) return state
        val next = state.selectedIds - ids.toSet()
        if (next.isEmpty()) return MediaSelectionState()
        return state.copy(selectedIds = next)
    }

    fun exit(): MediaSelectionState = MediaSelectionState()

    fun countLabel(count: Int): String =
        if (count == 1) "1 selected" else "$count selected"

    fun trashConfirmTitle(count: Int): String =
        if (count == 1) "Move 1 item to Trash?" else "Move $count items to Trash?"

    fun trashConfirmBody(count: Int): String =
        if (count == 1) {
            "This item will move to Trash. You can restore it until it is permanently deleted."
        } else {
            "These $count items will move to Trash. You can restore them until they are permanently deleted."
        }

    fun trashResultMessage(counts: BulkItemCounts): String {
        if (counts.failed == 0) {
            return if (counts.succeeded == 1) "Moved to Trash" else "${counts.succeeded} items moved to Trash"
        }
        if (counts.succeeded == 0) {
            return if (counts.failed == 1) {
                "This item could not be moved to Trash."
            } else {
                "${counts.failed} items could not be moved to Trash."
            }
        }
        return "${counts.succeeded} deleted, ${counts.failed} failed"
    }

    fun moveResultMessage(counts: BulkItemCounts): String {
        if (counts.failed == 0) {
            return if (counts.succeeded == 1) "Moved successfully" else "${counts.succeeded} items moved"
        }
        if (counts.succeeded == 0) {
            return if (counts.failed == 1) "Move failed" else "${counts.failed} items could not be moved"
        }
        return "${counts.succeeded} moved, ${counts.failed} failed"
    }
}
