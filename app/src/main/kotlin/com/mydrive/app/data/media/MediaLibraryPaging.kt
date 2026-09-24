package com.mydrive.app.data.media

import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.remote.dto.MediaAssetRow
import kotlinx.serialization.Serializable
import java.time.Instant

data class MediaPageCursor(
    val createdAt: String,
    val id: String
)

data class MediaAssetsPage(
    val rows: List<MediaAssetRow>,
    val nextCursor: MediaPageCursor?,
    val hasNextPage: Boolean
)

/**
 * A deterministic position on the `media_assets.updated_at` timeline.
 *
 * `updated_at` alone can never be a cursor: a single transaction stamps several
 * rows with the exact same instant, so filtering by `> updated_at` would skip
 * every row of that batch after the first, and filtering by `>= updated_at`
 * would replay them forever. The position is therefore the pair
 * (`updated_at`, `id`), which is unique and totally ordered, and it is only ever
 * advanced past rows that were actually read and processed.
 */
@Serializable
data class MediaSyncCursor(
    val updatedAt: String,
    val id: String
) {
    /** [updatedAt] as epoch microseconds, or `null` when it is not parseable. */
    val epochMicros: Long? get() = parseEpochMicros(updatedAt)

    companion object {
        /**
         * Orders two positions by timestamp first, then by id.
         *
         * Timestamps are compared as *instants*, not as strings: the backend may
         * report the same instant with different fractional-second precision
         * (`.5`, `.500000`, or none at all), and microsecond-resolution
         * timestamps must not be truncated to milliseconds or two rows written
         * microseconds apart would look equal and the second one would be
         * skipped. When a value cannot be parsed the comparison falls back to the
         * raw text, which keeps the order total and deterministic.
         */
        fun compare(left: MediaSyncCursor, right: MediaSyncCursor): Int {
            val leftMicros = left.epochMicros
            val rightMicros = right.epochMicros
            if (leftMicros != null && rightMicros != null) {
                val byTime = leftMicros.compareTo(rightMicros)
                if (byTime != 0) return byTime
            } else {
                val byText = left.updatedAt.compareTo(right.updatedAt)
                if (byText != 0) return byText
            }
            return left.id.compareTo(right.id)
        }

        fun parseEpochMicros(raw: String?): Long? {
            val value = raw?.takeIf { it.isNotBlank() } ?: return null
            return try {
                val instant = Instant.parse(value)
                instant.epochSecond * MICROS_PER_SECOND + instant.nano / NANOS_PER_MICRO
            } catch (_: Exception) {
                null
            }
        }

        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MICRO = 1_000L
    }
}

/**
 * One page of incrementally changed rows, in `updated_at` + `id` ascending order.
 *
 * [nextCursor] is the position of the last row of a full page and is fed back as
 * the next request's cursor, so a large batch of changes is walked without
 * offsets, without repeats and without gaps.
 */
data class MediaSyncPage(
    val rows: List<MediaAssetRow>,
    val nextCursor: MediaSyncCursor?,
    val hasNextPage: Boolean
)

data class MediaAlbumStats(
    val cloudOnlyCount: Int = 0,
    val cover: MediaAssetRow? = null
)

object MediaLibraryPaging {
    const val PAGE_SIZE = 80

    /**
     * Upper bound on the pages one incremental refresh reads (~40 x 80 rows).
     *
     * Reaching it is not a failure and skips nothing: the rows read so far are a
     * contiguous prefix of the change timeline, so the cursor advances to the last
     * of them and the next refresh resumes from exactly that position.
     */
    const val MAX_INCREMENTAL_PAGES = 40

    // `updated_at` is part of the listing because it is the synchronization
    // cursor's source: the initial load seeds the cursor from the rows it read,
    // and an incremental page carries the position it ended at.
    const val LISTING_COLUMNS = "id,owner_id,local_media_id,file_name,mime_type,file_size,width,height,duration_ms,storage_url,thumbnail_url,storage_asset_id,client_upload_id,status,user_hidden_at,deleted_at,uploaded_at,created_at,updated_at,drive_archived_at,primary_cleanup_status,primary_deleted_at"

    fun cursorOf(row: MediaAssetRow): MediaPageCursor? {
        val createdAt = row.createdAt?.takeIf { it.isNotBlank() }
            ?: row.uploadedAt?.takeIf { it.isNotBlank() }
            ?: return null
        if (row.id.isBlank()) return null
        return MediaPageCursor(createdAt = createdAt, id = row.id)
    }

    fun hasNextPage(fetchedCount: Int, pageSize: Int = PAGE_SIZE): Boolean =
        fetchedCount >= pageSize

    fun nextCursor(rows: List<MediaAssetRow>, pageSize: Int = PAGE_SIZE): MediaPageCursor? {
        if (!hasNextPage(rows.size, pageSize)) return null
        return rows.lastOrNull()?.let(::cursorOf)
    }

    /** The synchronization position of a single row, or `null` when it has none. */
    fun syncCursorOf(row: MediaAssetRow): MediaSyncCursor? {
        val updatedAt = row.updatedAt?.takeIf { it.isNotBlank() } ?: return null
        if (row.id.isBlank()) return null
        return MediaSyncCursor(updatedAt = updatedAt, id = row.id)
    }

    /**
     * The next incremental cursor for a page: the last row of a full page.
     *
     * A short page ends the batch, so it advertises no continuation even though
     * its rows still advance the cursor.
     */
    fun nextSyncCursor(rows: List<MediaAssetRow>, pageSize: Int = PAGE_SIZE): MediaSyncCursor? {
        if (!hasNextPage(rows.size, pageSize)) return null
        return rows.lastOrNull()?.let(::syncCursorOf)
    }

    /**
     * The greatest position of [rows], never behind [base].
     *
     * Used both to seed the cursor from a fully processed load and to advance it
     * after an incremental page, so the stored position can only move forward.
     */
    fun latestSyncCursor(base: MediaSyncCursor?, rows: List<MediaAssetRow>): MediaSyncCursor? {
        var best = base
        for (row in rows) {
            val candidate = syncCursorOf(row) ?: continue
            if (best == null || MediaSyncCursor.compare(candidate, best) > 0) best = candidate
        }
        return best
    }

    /**
     * Whether [candidate] may become the stored position.
     *
     * The single rule behind [MediaSyncCursorStore.advanceTo]: a position is
     * written only when it is strictly newer than the stored one, so a slow or
     * older response can never rewind synchronization and make the next refresh
     * re-read — or worse, re-order — what was already applied.
     */
    fun shouldAdvance(current: MediaSyncCursor?, candidate: MediaSyncCursor?): Boolean {
        if (candidate == null) return false
        if (current == null) return true
        return MediaSyncCursor.compare(candidate, current) > 0
    }

    /**
     * Merges an incremental page into the loaded catalog, keyed by the stable
     * remote id: an incoming row carries the newest server state for its id, so
     * it replaces the loaded one, and unknown ids are appended. Existing order is
     * preserved, and no id can appear twice.
     */
    fun upsertRows(
        existing: List<MediaAssetRow>,
        incoming: List<MediaAssetRow>
    ): List<MediaAssetRow> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.distinctBy { it.id }
        val replacements = incoming.associateBy { it.id }
        val seen = HashSet<String>(existing.size + incoming.size)
        val merged = ArrayList<MediaAssetRow>(existing.size + incoming.size)
        for (row in existing) {
            if (!seen.add(row.id)) continue
            merged += replacements[row.id] ?: row
        }
        for (row in incoming) {
            if (seen.add(row.id)) merged += row
        }
        return merged
    }

    /**
     * The catalog's own visibility rule, mirroring the server-side filter
     * (`status = 'READY' AND user_hidden_at IS NULL`).
     *
     * It is applied here rather than in the incremental query so that a row
     * leaving the library — trashed, restored, or moved on by the server
     * lifecycle — is still delivered and can update local state, while the set of
     * rows the library actually shows stays exactly what it was.
     */
    fun isVisibleInCatalog(row: MediaAssetRow): Boolean =
        row.status == "READY" && !row.isHiddenFromLibrary

    /**
     * An authoritative server tombstone. Only this — never absence from a
     * response — may remove a record from the local catalog.
     */
    fun isRemoteTombstone(row: MediaAssetRow): Boolean = row.status == "DELETED"

    fun mergeRows(
        existing: List<MediaAssetRow>,
        incoming: List<MediaAssetRow>
    ): List<MediaAssetRow> {
        if (existing.isEmpty()) return incoming.distinctBy { it.id }
        if (incoming.isEmpty()) return existing
        val seen = HashSet<String>(existing.size + incoming.size)
        val merged = ArrayList<MediaAssetRow>(existing.size + incoming.size)
        for (row in existing) {
            if (seen.add(row.id)) merged += row
        }
        for (row in incoming) {
            if (seen.add(row.id)) merged += row
        }
        return merged
    }

    fun libraryIdentity(item: MediaItem): String =
        item.remoteMediaId?.takeIf { it.isNotBlank() } ?: "local:${item.id}"

    fun mergeLibraryItems(
        existing: List<MediaItem>,
        incoming: List<MediaItem>
    ): List<MediaItem> {
        if (existing.isEmpty()) {
            return incoming.distinctBy(::libraryIdentity).sortedByDescending { it.capturedAtMillis }
        }
        if (incoming.isEmpty()) return existing
        val seen = HashSet<String>(existing.size + incoming.size)
        val merged = ArrayList<MediaItem>(existing.size + incoming.size)
        for (item in existing) {
            if (seen.add(libraryIdentity(item))) merged += item
        }
        for (item in incoming) {
            if (seen.add(libraryIdentity(item))) merged += item
        }
        return merged.sortedByDescending { it.capturedAtMillis }
    }

    fun needsDriveArchiveLookup(row: MediaAssetRow): Boolean =
        row.status == "READY" &&
            row.storageUrl.isNullOrBlank() &&
            row.thumbnailUrl.isNullOrBlank() &&
            row.driveArchivedAt.isNullOrBlank()

    fun groupChronologically(
        items: List<MediaItem>,
        labelFor: (Long) -> String
    ): List<Pair<String, List<MediaItem>>> {
        if (items.isEmpty()) return emptyList()
        val ordered = items.sortedByDescending { it.capturedAtMillis }
        val groups = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in ordered) {
            val label = labelFor(item.capturedAtMillis)
            groups.getOrPut(label) { ArrayList() }.add(item)
        }
        return groups.map { it.key to it.value.toList() }
    }
}
