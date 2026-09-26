package com.mydrive.app.data.media

import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.remote.dto.MediaAssetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Delete/Restore lifecycle rules.
 *
 * Every case here corresponds to a failure that was observed in production:
 * a photo that stayed in Photos/Albums after a successful device trash, a repeat
 * delete that reported a failure for a change that had already been applied, and a
 * cloud write that was disbelieved because the RPC's response shape was assumed.
 */
class LibraryVisibilityRulesTest {

    private fun row(
        id: String,
        localMediaId: Long? = null,
        clientUploadId: String? = null,
        status: String = "READY",
        hiddenAt: String? = null
    ) = MediaAssetRow(
        id = id,
        localMediaId = localMediaId,
        clientUploadId = clientUploadId,
        status = status,
        userHiddenAt = hiddenAt,
        storageUrl = "https://res.cloudinary.com/demo/image/upload/v1/$id.jpg"
    )

    private fun item(id: String, mediaStoreId: Long) = MediaItem(
        id = id,
        filename = "$id.jpg",
        type = MediaType.PHOTO,
        fileSizeBytes = 1024L,
        capturedAtMillis = 1L,
        device = "Pixel",
        resolution = "100 x 100",
        thumbnailSeed = id.hashCode(),
        mediaStoreId = mediaStoreId,
        uri = "content://media/external/images/media/$mediaStoreId"
    )

    // ── Test 3 / Test 4: a repeated delete or restore is idempotent ───────────

    @Test
    fun `a media that is already hidden needs no write`() {
        val rows = listOf(
            row("m1", localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z"),
            row("m2", localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z")
        )
        assertTrue(LibraryVisibilityRules.rowsNeedingChange(rows, hidden = true).isEmpty())
    }

    @Test
    fun `a media that is active needs a write for every row, not just one`() {
        // The same photo can have several media_assets rows; hiding one used to
        // leave the siblings READY and the photo back on screen.
        val rows = listOf(
            row("m1", localMediaId = 100),
            row("m2", localMediaId = 100),
            row("m3", localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z")
        )
        assertEquals(listOf("m1", "m2"), LibraryVisibilityRules.rowsNeedingChange(rows, hidden = true).map { it.id })
    }

    @Test
    fun `a restore needs the write for the rows that are still hidden`() {
        val rows = listOf(
            row("m1", localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z"),
            row("m2", localMediaId = 100)
        )
        assertEquals(listOf("m1"), LibraryVisibilityRules.rowsNeedingChange(rows, hidden = false).map { it.id })
    }

    // ── the cloud write is judged by state, not by the RPC's response body ────

    @Test
    fun `a hide is confirmed when every row reads back hidden`() {
        // This is the case the old code reported as a failure: the deployed RPC
        // returns void, so a successful UPDATE arrived with no row count.
        val rows = listOf("m1", "m2").associateWith { row(it, localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z") }
        assertTrue(LibraryVisibilityRules.isConfirmed(rows, listOf("m1", "m2"), hidden = true))
    }

    @Test
    fun `a hide is not confirmed while any row is still active`() {
        val rows = mapOf(
            "m1" to row("m1", localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z"),
            "m2" to row("m2", localMediaId = 100)
        )
        assertFalse(LibraryVisibilityRules.isConfirmed(rows, listOf("m1", "m2"), hidden = true))
    }

    @Test
    fun `a row that no longer exists does not veto the result`() {
        // Deleted while we worked: it cannot be in the wrong visibility state.
        val rows = mapOf("m1" to row("m1", localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z"))
        assertTrue(LibraryVisibilityRules.isConfirmed(rows, listOf("m1", "gone"), hidden = true))
    }

    @Test
    fun `an unconfirmed restore is detected`() {
        val rows = mapOf("m1" to row("m1", localMediaId = 100, hiddenAt = "2026-09-26T00:00:00Z"))
        assertFalse(LibraryVisibilityRules.isConfirmed(rows, listOf("m1"), hidden = false))
    }

    // ── Test 1 / Test 6: a trashed item must not stay an active gallery item ──

    @Test
    fun `a locally hidden media suppresses every cloud row that carries its id`() {
        val hidden = setOf("img-100")
        val hiddenIds = LibraryVisibilityRules.hiddenMediaStoreIds(hidden, listOf(item("img-100", 100)))
        assertEquals(setOf(100L), hiddenIds)
        // The media has sibling cloud rows from older installs; all of them are
        // suppressed, so none can compose the photo back under a different id.
        assertTrue(LibraryVisibilityRules.isSuppressedByLocalHide(row("old-1", localMediaId = 100), hiddenIds))
        assertTrue(LibraryVisibilityRules.isSuppressedByLocalHide(row("old-2", localMediaId = 100), hiddenIds))
    }

    @Test
    fun `a local hide does not suppress other media`() {
        val hiddenIds = LibraryVisibilityRules.hiddenMediaStoreIds(setOf("img-100"), listOf(item("img-100", 100)))
        assertFalse(LibraryVisibilityRules.isSuppressedByLocalHide(row("other", localMediaId = 101), hiddenIds))
    }

    @Test
    fun `a cloud-only row is not suppressed by a local hide`() {
        // No local_media_id, or a media with no local counterpart: only the cloud
        // can say whether it is in Trash.
        val hiddenIds = LibraryVisibilityRules.hiddenMediaStoreIds(setOf("img-100"), listOf(item("img-100", 100)))
        assertFalse(LibraryVisibilityRules.isSuppressedByLocalHide(row("cloud-only", localMediaId = null), hiddenIds))
        assertFalse(LibraryVisibilityRules.isSuppressedByLocalHide(row("zero", localMediaId = 0L), hiddenIds))
    }

    @Test
    fun `an item with an unknown media store id contributes no suppression`() {
        // A fabricated id must never be used to match cloud rows: 0 is not a
        // MediaStore id, and a synthetic cloud id is not a local identity.
        val hiddenIds = LibraryVisibilityRules.hiddenMediaStoreIds(
            setOf("img-0", "cloud-abc"),
            listOf(item("img-0", 0L))
        )
        assertTrue(hiddenIds.isEmpty())
    }

    @Test
    fun `nothing is suppressed when nothing is locally hidden`() {
        assertTrue(
            LibraryVisibilityRules.hiddenMediaStoreIds(emptySet(), listOf(item("img-100", 100))).isEmpty()
        )
    }
}
