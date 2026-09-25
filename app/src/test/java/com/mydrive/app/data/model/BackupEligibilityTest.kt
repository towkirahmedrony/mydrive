package com.mydrive.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup-run eligibility rule.
 *
 * Regression cover for: after a completed backup, pressing "Start Backup" again
 * re-queued every local media item. The cause was that eligibility was decided
 * only from the install-local upload queue, which is lost on reinstall / cleared
 * data, so every item fell back to NOT_STARTED. Production evidence: 396
 * media_assets rows across 2 device_ids with 153 local_media_ids duplicated, and
 * sha256_hash NULL for every row.
 */
class BackupEligibilityTest {

    private fun item(
        id: String = "img-100",
        backupState: BackupState = BackupState.NOT_STARTED,
        cloudBackedUp: Boolean = false
    ) = MediaItem(
        id = id,
        mediaStoreId = id.substringAfter('-').toLongOrNull() ?: 100L,
        uri = "content://media/external/images/media/${id.substringAfter('-')}",
        filename = "$id.jpg",
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        fileSizeBytes = 1024L,
        backupState = backupState,
        cloudBackedUp = cloudBackedUp
    )

    // ── CASE A / B: first run queues everything, second run queues nothing ────

    @Test
    fun `never-backed-up media is eligible`() {
        assertTrue(isEligibleForBackup(item(), recordState = null))
    }

    @Test
    fun `completed queue record is not eligible`() {
        assertFalse(isEligibleForBackup(item(), recordState = BackupState.COMPLETED))
    }

    @Test
    fun `cloud-backed media is not eligible even with no local queue record`() {
        // The second "Start Backup" on a fresh install: the queue is empty, but the
        // cloud catalog still holds the media, so it must not be queued again.
        val restored = item(cloudBackedUp = true)
        assertFalse(isEligibleForBackup(restored, recordState = null))
    }

    @Test
    fun `cloud-backed media is not eligible even if the queue says not started`() {
        // Belt and braces: an authoritative cloud row wins over a stale/absent
        // local state, whatever the queue claims.
        val restored = item(backupState = BackupState.NOT_STARTED, cloudBackedUp = true)
        assertFalse(isEligibleForBackup(restored, recordState = BackupState.NOT_STARTED))
    }

    // ── CASE C: only genuinely new media is queued ────────────────────────────

    @Test
    fun `only new media is eligible when existing media is cloud-backed`() {
        val existing = (1..243).map { item(id = "img-$it", cloudBackedUp = true) }
        val fresh = (1000..1004).map { item(id = "img-$it") }

        val run = (existing + fresh)
            .filter { isEligibleForBackup(it, recordState = null) }
            .map { it.id }

        assertEquals(5, run.size)
        assertEquals((1000..1004).map { "img-$it" }, run)
    }

    // ── CASE D: completed stays skipped, failed retries ───────────────────────

    @Test
    fun `failed media is eligible for retry`() {
        assertTrue(isEligibleForBackup(item(), recordState = BackupState.FAILED))
    }

    @Test
    fun `cancelled media is eligible for retry`() {
        assertTrue(isEligibleForBackup(item(), recordState = BackupState.CANCELLED))
    }

    @Test
    fun `completed media stays skipped while a sibling failed`() {
        val done = item(id = "img-1", cloudBackedUp = true)
        val failed = item(id = "img-2")

        assertFalse(isEligibleForBackup(done, recordState = BackupState.COMPLETED))
        assertTrue(isEligibleForBackup(failed, recordState = BackupState.FAILED))
    }

    @Test
    fun `a cloud-backed media is not re-queued just because its record failed`() {
        // The cloud row proves the backup completed; a stale local FAILED label
        // must not trigger a duplicate upload.
        val restored = item(cloudBackedUp = true)
        assertFalse(isEligibleForBackup(restored, recordState = BackupState.FAILED))
    }

    // ── CASE E: interrupted runs resume safely ───────────────────────────────

    @Test
    fun `cloudinary-completed but unfinalized media is resumable`() {
        assertTrue(isEligibleForBackup(item(), recordState = BackupState.CLOUDINARY_COMPLETED))
    }

    @Test
    fun `in-flight and queued states are not restarted by a new run`() {
        // These are owned by the running queue drain, which resumes them; a new
        // Start Backup must not re-queue them.
        listOf(
            BackupState.WAITING,
            BackupState.PAUSED,
            BackupState.UPLOADING,
            BackupState.UPLOADING_TO_CLOUDINARY,
            BackupState.REQUESTING_CLOUDINARY_AUTH,
            BackupState.FINALIZING_SUPABASE
        ).forEach { state ->
            assertFalse("$state must not be re-queued", isEligibleForBackup(item(), recordState = state))
        }
    }

    // ── CASE F: removing the local copy does not orphan the cloud backup ──────

    @Test
    fun `cloud-backed media whose local copy is gone is still not eligible`() {
        // CASE F: the item no longer exists in MediaStore, so no sync record may
        // remain; the cloud row still forbids a re-upload. (The item itself is
        // absent from the device list, so nothing is queued.)
        assertFalse(isEligibleForBackup(item(cloudBackedUp = true), recordState = null))
    }

    // ── CASE G: cloud Trash / deletion lifecycle ─────────────────────────────

    @Test
    fun `media whose cloud record left the catalog becomes eligible again`() {
        // A permanently deleted cloud row is no longer catalog-visible, so
        // composeLibrary does not set cloudBackedUp: the media is genuinely
        // un-backed-up and may be uploaded again.
        val trashedAway = item(cloudBackedUp = false, backupState = BackupState.NOT_STARTED)
        assertTrue(isEligibleForBackup(trashedAway, recordState = null))
    }

    @Test
    fun `media still restorable from My Drive Trash keeps its backup`() {
        // Still catalog-visible (status READY, user_hidden_at only) => still
        // backed up. Re-uploading it would duplicate the cloud record.
        assertFalse(isEligibleForBackup(item(cloudBackedUp = true), recordState = null))
    }

    // ── CASE H: repeated discovery is idempotent ─────────────────────────────

    @Test
    fun `discovering the same media repeatedly yields one eligible id per item`() {
        val discovered = listOf(item(id = "img-7"), item(id = "img-7"), item(id = "img-7"))
        val queued = discovered
            .filter { isEligibleForBackup(it, recordState = null) }
            .map { it.id }
            .distinct()

        assertEquals(listOf("img-7"), queued)
    }

    // ── Displayed state must agree with the gate ─────────────────────────────

    @Test
    fun `missing queue record plus cloud row displays as completed`() {
        val restored = item(cloudBackedUp = true)
        assertEquals(BackupState.COMPLETED, backupStateFor(restored, recordState = null))
    }

    @Test
    fun `queue record still wins for genuinely un-backed-up media`() {
        assertEquals(BackupState.NOT_STARTED, backupStateFor(item(), recordState = null))
        assertEquals(
            BackupState.CLOUDINARY_COMPLETED,
            backupStateFor(item(), recordState = BackupState.CLOUDINARY_COMPLETED)
        )
    }
}
