package com.mydrive.app.data.media

import com.mydrive.app.data.remote.dto.MediaAssetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rules behind "does the cloud already hold this media?".
 *
 * This is the decision that stops an already-uploaded photo from being uploaded
 * again, so the cases that could go wrong — a tombstone counted as a backup, a
 * reused MediaStore id counted as the same file, an upload that never finished
 * counted as done — are covered here.
 */
class CloudBackupMatchTest {

    private fun row(
        id: String,
        localMediaId: Long? = null,
        clientUploadId: String? = null,
        fileSize: Long? = null,
        status: String = "READY"
    ) = MediaAssetRow(
        id = id,
        localMediaId = localMediaId,
        clientUploadId = clientUploadId,
        fileSize = fileSize,
        status = status,
        storageUrl = "https://res.cloudinary.com/demo/image/upload/v1/$id.jpg"
    )

    private fun candidate(localMediaId: Long, size: Long = 1024L, clientUploadId: String? = null) =
        CloudBackupCandidate(localMediaId = localMediaId, clientUploadId = clientUploadId, fileSize = size)

    // ── the same media must be recognised across installs ─────────────────────

    @Test
    fun `a ready row for the local media id proves the backup`() {
        val index = CloudBackupMatch.index(
            rows = listOf(row("m1", localMediaId = 100, fileSize = 1024L)),
            candidates = listOf(candidate(100))
        )
        assertTrue(index.hasAnyRecordFor(candidate(100)))
        assertEquals("m1", index.rowFor(candidate(100))?.id)
    }

    @Test
    fun `a row from an earlier install still proves the backup`() {
        // 1100 rows across 8 device ids with 266 distinct local ids is the observed
        // production shape; a match on local_media_id must not depend on device_id,
        // which is exactly why the same photo kept being re-uploaded.
        val rows = (1..4).map { index -> row("m$index", localMediaId = 100, fileSize = 1024L) }
        val index = CloudBackupMatch.index(rows, listOf(candidate(100)))
        assertTrue(index.rowFor(candidate(100)) != null)
    }

    @Test
    fun `the client upload identity alone is enough`() {
        // Matched before the local id, because a UUID minted for one upload is that
        // upload and nothing else.
        val index = CloudBackupMatch.index(
            rows = listOf(row("m1", localMediaId = null, clientUploadId = "cu-1")),
            candidates = listOf(candidate(localMediaId = 0L, clientUploadId = "cu-1"))
        )
        assertEquals("m1", index.rowFor(candidate(0L, clientUploadId = "cu-1"))?.id)
    }

    // ── what must NOT count as a backup ───────────────────────────────────────

    @Test
    fun `a tombstone proves nothing`() {
        val index = CloudBackupMatch.index(
            rows = listOf(row("m1", localMediaId = 100, fileSize = 1024L, status = "DELETED")),
            candidates = listOf(candidate(100))
        )
        assertNull(index.rowFor(candidate(100)))
        // A tombstone is not a pending upload either: the cloud copy is gone, so the
        // media is eligible again.
        assertFalse(index.hasAnyRecordFor(candidate(100)))
    }

    @Test
    fun `an unfinished upload is reported as pending and does not count as a backup`() {
        val index = CloudBackupMatch.index(
            rows = listOf(row("m1", localMediaId = 100, fileSize = 1024L, status = "UPLOADING")),
            candidates = listOf(candidate(100))
        )
        assertNull(index.rowFor(candidate(100)))
        assertTrue(index.hasAnyRecordFor(candidate(100)))
        assertEquals("UPLOADING", index.statusOf(candidate(100)))
    }

    @Test
    fun `a reused mediastore id with a different size is treated as changed content`() {
        // The only case in which a new upload is legitimate, and the only change
        // signal this schema carries (`sha256_hash` is unpopulated in production).
        val index = CloudBackupMatch.index(
            rows = listOf(row("m1", localMediaId = 100, fileSize = 500L)),
            candidates = listOf(candidate(100, size = 1024L))
        )
        assertNull(index.rowFor(candidate(100)))
    }

    @Test
    fun `unknown sizes cannot prove a change`() {
        assertTrue(CloudBackupMatch.sizeAgrees(row("m1", fileSize = null), localFileSize = 1024L))
        assertTrue(CloudBackupMatch.sizeAgrees(row("m1", fileSize = 1024L), localFileSize = 0L))
    }

    // ── the answer must be about the media that was asked about ───────────────

    @Test
    fun `a row for another local id is not an answer`() {
        val index = CloudBackupMatch.index(
            rows = listOf(row("m1", localMediaId = 999, fileSize = 1024L)),
            candidates = listOf(candidate(100))
        )
        assertNull(index.rowFor(candidate(100)))
        assertFalse(index.hasAnyRecordFor(candidate(100)))
    }

    @Test
    fun `a hidden media in my drive trash is still backed up`() {
        // Trash only sets `user_hidden_at`; the row is still READY, so re-uploading
        // would duplicate the cloud record.
        val trashed = row("m1", localMediaId = 100, fileSize = 1024L).copy(userHiddenAt = "2026-09-26T00:00:00Z")
        val index = CloudBackupMatch.index(listOf(trashed), listOf(candidate(100)))
        assertEquals("m1", index.rowFor(candidate(100))?.id)
    }

    @Test
    fun `an empty answer is a real answer and no candidate is proven`() {
        val index = CloudBackupMatch.index(rows = emptyList(), candidates = listOf(candidate(100)))
        assertNull(index.rowFor(candidate(100)))
        assertFalse(index.hasAnyRecordFor(candidate(100)))
    }
}
