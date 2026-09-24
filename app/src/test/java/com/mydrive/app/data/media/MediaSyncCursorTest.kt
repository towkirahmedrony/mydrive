package com.mydrive.app.data.media

import com.mydrive.app.data.remote.dto.MediaAssetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incremental synchronization contract: a deterministic (`updated_at`, `id`)
 * position that can be paged through without skipping or repeating a row, even
 * when many rows share one timestamp and even when the same instant is written
 * with different fractional precision.
 */
class MediaSyncCursorTest {

    // ── Deterministic ordering ────────────────────────────────────────────────

    @Test
    fun `newer timestamps order after older ones`() {
        val older = MediaSyncCursor("2026-09-24T09:00:00.000000+00:00", "r-9")
        val newer = MediaSyncCursor("2026-09-24T10:00:00.000000+00:00", "r-1")

        assertTrue(MediaSyncCursor.compare(newer, older) > 0)
        assertTrue(MediaSyncCursor.compare(older, newer) < 0)
        assertEquals(0, MediaSyncCursor.compare(older, older))
    }

    @Test
    fun `equal timestamps are ordered by id`() {
        val timestamp = "2026-09-24T10:00:00.000000+00:00"
        val first = MediaSyncCursor(timestamp, "aaa")
        val second = MediaSyncCursor(timestamp, "bbb")

        assertTrue(MediaSyncCursor.compare(first, second) < 0)
        assertTrue(MediaSyncCursor.compare(second, first) > 0)
        assertEquals(0, MediaSyncCursor.compare(first, MediaSyncCursor(timestamp, "aaa")))
    }

    @Test
    fun `fractional precision is compared as an instant not as text`() {
        // The very same instant, written with and without fractional seconds.
        val short = MediaSyncCursor("2026-09-24T10:00:00+00:00", "r-1")
        val padded = MediaSyncCursor("2026-09-24T10:00:00.000000+00:00", "r-2")
        assertEquals(0, MediaSyncCursor.compare(short, padded))

        // ... and one microsecond after it still orders after both.
        val micros = MediaSyncCursor("2026-09-24T10:00:00.000001+00:00", "r-0")
        assertTrue(MediaSyncCursor.compare(micros, short) > 0)
        assertTrue(MediaSyncCursor.compare(micros, padded) > 0)
    }

    @Test
    fun `sub millisecond precision is not truncated`() {
        val earlier = MediaSyncCursor("2026-09-24T10:00:00.000100+00:00", "r-1")
        val later = MediaSyncCursor("2026-09-24T10:00:00.000900+00:00", "r-2")

        // Truncating to milliseconds would make these two look identical.
        assertTrue(
            MediaSyncCursor.parseEpochMicros(later.updatedAt)!! >
                MediaSyncCursor.parseEpochMicros(earlier.updatedAt)!!
        )
        assertTrue(MediaSyncCursor.compare(later, earlier) > 0)
    }

    @Test
    fun `an unparseable timestamp still orders deterministically`() {
        val malformed = MediaSyncCursor("not-a-timestamp", "r-1")
        assertNull(malformed.epochMicros)

        assertTrue(MediaSyncCursor.compare(MediaSyncCursor("not-a-timestamp", "r-2"), malformed) > 0)
        assertTrue(MediaSyncCursor.compare(malformed, MediaSyncCursor("not-a-timestamp", "r-2")) < 0)
        assertEquals(0, MediaSyncCursor.compare(malformed, MediaSyncCursor("not-a-timestamp", "r-1")))
    }

    // ── Seeding and advancing the cursor ──────────────────────────────────────

    @Test
    fun `the seeded position is the newest synchronized row`() {
        val rows = listOf(
            row("m-1", "2026-09-24T09:00:00.000000+00:00"),
            row("m-2", "2026-09-24T10:00:00.000000+00:00"),
            row("m-3", "2026-09-24T08:00:00.000000+00:00")
        )

        val seeded = MediaLibraryPaging.latestSyncCursor(null, rows)!!

        assertEquals("2026-09-24T10:00:00.000000+00:00", seeded.updatedAt)
        assertEquals("m-2", seeded.id)
    }

    @Test
    fun `a batch sharing one timestamp seeds the greatest id`() {
        val timestamp = "2026-09-24T10:00:00.000000+00:00"

        val seeded = MediaLibraryPaging.latestSyncCursor(
            null,
            listOf(row("b", timestamp), row("c", timestamp), row("a", timestamp))
        )!!

        assertEquals("c", seeded.id)
    }

    @Test
    fun `an older page cannot rewind the stored position`() {
        val current = MediaSyncCursor("2026-09-24T10:00:00.000000+00:00", "r-5")

        assertEquals(
            current,
            MediaLibraryPaging.latestSyncCursor(current, listOf(row("m-1", "2026-09-24T07:00:00.000000+00:00")))
        )
    }

    @Test
    fun `rows without a usable timestamp can never move the cursor`() {
        assertNull(MediaLibraryPaging.latestSyncCursor(null, listOf(MediaAssetRow(id = "m-1", status = "READY"))))
        assertNull(MediaLibraryPaging.syncCursorOf(MediaAssetRow(id = "m-1", status = "READY")))
        assertNull(MediaLibraryPaging.syncCursorOf(MediaAssetRow(id = "m-1", status = "READY", updatedAt = "   ")))
        assertNull(
            MediaLibraryPaging.syncCursorOf(
                MediaAssetRow(id = "", status = "READY", updatedAt = "2026-09-24T10:00:00+00:00")
            )
        )
    }

    @Test
    fun `the cursor only moves forward`() {
        val current = MediaSyncCursor("2026-09-24T10:00:00.000000+00:00", "r-5")

        // Nothing to advance to, or an unchanged position: keep what we have.
        assertFalse(MediaLibraryPaging.shouldAdvance(current, null))
        assertFalse(MediaLibraryPaging.shouldAdvance(current, current))
        assertFalse(
            MediaLibraryPaging.shouldAdvance(
                current,
                MediaSyncCursor("2026-09-24T09:00:00.000000+00:00", "r-9")
            )
        )
        // A newer timestamp, or the same instant with a greater id, is progress.
        assertTrue(
            MediaLibraryPaging.shouldAdvance(
                current,
                MediaSyncCursor("2026-09-24T11:00:00.000000+00:00", "r-1")
            )
        )
        assertTrue(MediaLibraryPaging.shouldAdvance(current, MediaSyncCursor(current.updatedAt, "r-6")))
        assertTrue(MediaLibraryPaging.shouldAdvance(null, current))
    }

    // ── Pagination completeness ───────────────────────────────────────────────

    @Test
    fun `incremental pagination visits every row exactly once in order`() {
        val rows = mixedTimestampRows()

        val visited = drain(rows, pageSize = 4)

        assertEquals(rows.map { it.id }, visited)
        assertEquals(visited.size, visited.distinct().size)
        assertEquals(rows.size, visited.size)
    }

    @Test
    fun `a batch sharing one timestamp survives a page boundary`() {
        val timestamp = "2026-09-24T10:00:00.123456+00:00"
        val rows = (1..6).map { index -> row("r-%02d".format(index), timestamp) }

        val visited = drain(rows, pageSize = 2)

        assertEquals(rows.map { it.id }, visited)
        assertEquals(6, visited.size)
    }

    @Test
    fun `a short page ends the batch and a full page continues it`() {
        val full = (1..4).map { row("r-%02d".format(it), "2026-09-24T10:00:00.000000+00:00") }

        assertEquals("r-04", MediaLibraryPaging.nextSyncCursor(full, pageSize = 4)?.id)
        assertNull(MediaLibraryPaging.nextSyncCursor(full.take(3), pageSize = 4))
    }

    // ── Storage of the incremental result ─────────────────────────────────────

    @Test
    fun `an incremental row replaces the loaded one without duplicating it`() {
        val existing = listOf(
            row("m-1", "2026-09-24T09:00:00.000000+00:00", fileName = "old.jpg"),
            row("m-2", "2026-09-24T08:00:00.000000+00:00")
        )
        val changed = row("m-1", "2026-09-24T10:00:00.000000+00:00", fileName = "new.jpg")

        val merged = MediaLibraryPaging.upsertRows(existing, listOf(changed))

        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.id == "m-1" })
        assertEquals("new.jpg", merged.first { it.id == "m-1" }.fileName)
        assertEquals("m-2", merged.first { it.id == "m-2" }.id)
    }

    @Test
    fun `unknown rows are appended and an empty page changes nothing`() {
        val existing = listOf(row("m-1", "2026-09-24T09:00:00.000000+00:00"))

        assertEquals(existing, MediaLibraryPaging.upsertRows(existing, emptyList()))

        val appended = MediaLibraryPaging.upsertRows(
            existing,
            listOf(row("m-2", "2026-09-24T10:00:00.000000+00:00"))
        )
        assertEquals(listOf("m-1", "m-2"), appended.map { it.id })
    }

    @Test
    fun `applying the same page twice stays stable`() {
        val page = listOf(
            row("m-1", "2026-09-24T10:00:00.000000+00:00"),
            row("m-2", "2026-09-24T10:00:00.000001+00:00")
        )

        val once = MediaLibraryPaging.upsertRows(emptyList(), page)
        val twice = MediaLibraryPaging.upsertRows(once, page)

        assertEquals(2, twice.size)
        assertEquals(listOf("m-1", "m-2"), twice.map { it.id })
    }

    // ── Catalog rules the incremental response must not relax ─────────────────

    @Test
    fun `the catalog rule mirrors the server side filter`() {
        assertTrue(MediaLibraryPaging.isVisibleInCatalog(row("ready")))
        assertFalse(
            MediaLibraryPaging.isVisibleInCatalog(
                row("hidden", userHiddenAt = "2026-09-24T10:00:00+00:00")
            )
        )
        assertFalse(MediaLibraryPaging.isVisibleInCatalog(row("deleted", status = "DELETED")))
        assertFalse(MediaLibraryPaging.isVisibleInCatalog(row("uploading", status = "UPLOADING")))
        assertFalse(MediaLibraryPaging.isVisibleInCatalog(row("failed", status = "FAILED")))
    }

    @Test
    fun `only an authoritative DELETED row is a tombstone`() {
        assertTrue(MediaLibraryPaging.isRemoteTombstone(row("d", status = "DELETED")))

        // Trash is visibility, not deletion; an unfinished upload is neither.
        assertFalse(MediaLibraryPaging.isRemoteTombstone(row("trashed", userHiddenAt = "2026-09-24T10:00:00+00:00")))
        assertFalse(MediaLibraryPaging.isRemoteTombstone(row("ready")))
        assertFalse(MediaLibraryPaging.isRemoteTombstone(row("uploading", status = "UPLOADING")))
        assertFalse(MediaLibraryPaging.isRemoteTombstone(row("failed", status = "FAILED")))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Rows in the order the server returns them for the incremental query:
     * ascending (`updated_at`, `id`), including a five-row batch that shares one
     * timestamp and the same instant written with two different precisions.
     */
    private fun mixedTimestampRows(): List<MediaAssetRow> = listOf(
        row("r-07", "2026-09-24T09:59:59.500000+00:00"),
        row("r-06", "2026-09-24T10:00:00+00:00"),
        row("r-08", "2026-09-24T10:00:00.000000+00:00"),
        row("r-01", "2026-09-24T10:00:00.000001+00:00"),
        row("r-02", "2026-09-24T10:00:00.000001+00:00"),
        row("r-03", "2026-09-24T10:00:00.000001+00:00"),
        row("r-04", "2026-09-24T10:00:00.000001+00:00"),
        row("r-05", "2026-09-24T10:00:00.000001+00:00")
    )

    /** Walks the incremental query to exhaustion the way the repository does. */
    private fun drain(rows: List<MediaAssetRow>, pageSize: Int): List<String> {
        val visited = ArrayList<String>()
        var cursor: MediaSyncCursor? = null
        var guard = 0
        while (guard++ <= rows.size + 1) {
            val page = serverPage(rows, cursor, pageSize)
            if (page.isEmpty()) break
            visited += page.map { it.id }
            cursor = MediaLibraryPaging.nextSyncCursor(page, pageSize)
                ?: MediaLibraryPaging.latestSyncCursor(cursor, page)
        }
        return visited
    }

    /** The server side of `applySyncCursor`: ordered `> (updated_at, id)`. */
    private fun serverPage(
        rows: List<MediaAssetRow>,
        cursor: MediaSyncCursor?,
        pageSize: Int
    ): List<MediaAssetRow> {
        val ordered = rows.sortedWith { left, right ->
            MediaSyncCursor.compare(positionOf(left), positionOf(right))
        }
        val after = if (cursor == null) {
            ordered
        } else {
            ordered.filter { MediaSyncCursor.compare(positionOf(it), cursor) > 0 }
        }
        return after.take(pageSize)
    }

    private fun positionOf(row: MediaAssetRow): MediaSyncCursor =
        requireNotNull(MediaLibraryPaging.syncCursorOf(row)) { "row ${row.id} has no updated_at" }

    private fun row(
        id: String,
        updatedAt: String = "2026-09-24T10:00:00.000000+00:00",
        status: String = "READY",
        userHiddenAt: String? = null,
        fileName: String = "$id.jpg"
    ) = MediaAssetRow(
        id = id,
        status = status,
        userHiddenAt = userHiddenAt,
        updatedAt = updatedAt,
        fileName = fileName,
        storageUrl = "https://cdn/$id"
    )
}
