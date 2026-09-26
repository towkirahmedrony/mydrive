package com.mydrive.app.data.media

import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.remote.dto.MediaAssetRow

/**
 * The decisions behind the Delete/Restore lifecycle, as pure functions.
 *
 * These are the parts that decide whether a photo disappears from Photos/Albums,
 * whether a repeated delete is a no-op, and whether a cloud write is believed —
 * so they are kept free of Android and network dependencies and are covered by
 * unit tests rather than only by reading the call sites.
 */
object LibraryVisibilityRules {

    /**
     * The rows of one media that are not yet in the requested visibility state.
     *
     * An empty result means the media is already in that state, which makes both a
     * repeated Move-to-Trash and a repeated Restore idempotent: nothing is written,
     * and the operation is reported as successful rather than as a second failure.
     */
    fun rowsNeedingChange(rows: List<MediaAssetRow>, hidden: Boolean): List<MediaAssetRow> =
        rows.filter { it.isHiddenFromLibrary != hidden }

    /**
     * Whether the visibility request is confirmed by the rows that were read back.
     *
     * A row that no longer exists cannot be in the wrong state, so a tombstoned or
     * purged row does not veto the result; a row that is still in the old state does.
     */
    fun isConfirmed(
        rowsById: Map<String, MediaAssetRow>,
        requestedIds: Collection<String>,
        hidden: Boolean
    ): Boolean = requestedIds.all { id ->
        rowsById[id]?.let { row -> row.isHiddenFromLibrary == hidden } ?: true
    }

    /**
     * The MediaStore ids of media that are locally hidden (in device Trash).
     *
     * A media can be represented by several cloud rows — production holds more than
     * one `media_assets` row per `local_media_id` — and this is what lets a local
     * hide suppress *all* of them, instead of hiding one and letting its siblings
     * compose the photo back into Photos/Albums under a different id.
     */
    fun hiddenMediaStoreIds(hiddenLocalIds: Set<String>, items: List<MediaItem>): Set<Long> =
        items.asSequence()
            .filter { it.id in hiddenLocalIds }
            .mapNotNull { it.mediaStoreId.takeIf { mediaStoreId -> mediaStoreId > 0L } }
            .toSet()

    /**
     * Whether a cloud row must stay out of the library because the media it belongs
     * to is locally hidden.
     *
     * A row with no `local_media_id`, or one whose media has no local counterpart,
     * is not suppressed by a local hide: those are cloud-only items, and only the
     * cloud itself can say whether they are in Trash.
     */
    fun isSuppressedByLocalHide(row: MediaAssetRow, hiddenMediaStoreIds: Set<Long>): Boolean {
        if (hiddenMediaStoreIds.isEmpty()) return false
        val localMediaId = row.localMediaId?.takeIf { it > 0L } ?: return false
        return localMediaId in hiddenMediaStoreIds
    }
}
