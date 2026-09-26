package com.mydrive.app.data.media

import com.mydrive.app.data.local.TrashedIdentity
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

    /**
     * Whether a cloud row belongs to a media the user moved to Trash.
     *
     * Matched on every identity the media has — the `media_assets` id, the
     * MediaStore `_ID` it was deleted under, and the queue's upload identity — so the
     * answer does not depend on the local copy still being discoverable. This is the
     * guard that keeps a deleted media's cloud rows out of Photos/Albums even when
     * its Android Trash entry has expired, when a scan is partial, or when the row
     * came from a different install.
     */
    fun matchesTrashedIdentity(
        remoteMediaId: String?,
        localMediaId: Long?,
        clientUploadId: String?,
        trashed: Collection<TrashedIdentity>
    ): Boolean {
        if (trashed.isEmpty()) return false
        val remote = remoteMediaId?.takeIf { it.isNotBlank() }
        val local = localMediaId?.takeIf { it > 0L }
        val client = clientUploadId?.takeIf { it.isNotBlank() }
        return trashed.any { identity ->
            (remote != null && remote in identity.remoteMediaIds) ||
                (local != null && identity.localMediaId == local) ||
                (client != null && identity.clientUploadId == client)
        }
    }

    /**
     * Whether a composed gallery item is a media the user moved to Trash.
     *
     * Deliberately broader than the local hidden set: a cloud-only tile carries a
     * `cloud-…` id rather than the local one, so it is recognised through its remote
     * id instead. A trashed media must never be composed as active, whichever of
     * those handles the tile happens to carry.
     */
    fun isTrashedItem(
        item: MediaItem,
        hiddenLocalIds: Set<String>,
        trashed: Collection<TrashedIdentity>
    ): Boolean {
        if (item.id in hiddenLocalIds) return true
        if (trashed.isEmpty()) return false
        if (trashed.containsKey(item.id)) return true
        val remote = item.remoteMediaId?.takeIf { it.isNotBlank() }
        val local = item.mediaStoreId.takeIf { it > 0L }
        return trashed.values.any { identity ->
            (remote != null && remote in identity.remoteMediaIds) ||
                (local != null && identity.localMediaId == local)
        }
    }

    /**
     * Drops every item that must not be shown as active.
     *
     * The gallery has several publish paths — composition, the interactive overlay,
     * the cold-start hydration of the persisted catalog — and this is the one place
     * they all funnel through, so the invariant holds for all of them rather than
     * for the one path that happened to be fixed.
     */
    fun withoutTrashed(
        items: List<MediaItem>,
        hiddenLocalIds: Set<String>,
        trashed: Collection<TrashedIdentity>
    ): List<MediaItem> {
        if (hiddenLocalIds.isEmpty() && trashed.isEmpty()) return items
        return items.filterNot { isTrashedItem(it, hiddenLocalIds, trashed) }
    }
}
