package com.mydrive.app.data.backup

import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which device items discovery must ask the cloud about, and which local states are
 * final without asking.
 *
 * The distinction is what makes discovery idempotent *and* complete:
 * `First discovery: 243 media → 243 uploaded`, `Second discovery: 243 media → 0
 * uploads`, `Add 5 new media → next discovery uploads only 5`.
 */
class CloudVerificationCandidateTest {

    private fun item(id: String, cloudBackedUp: Boolean = false) = MediaItem(
        id = id,
        mediaStoreId = id.substringAfter('-').toLongOrNull() ?: 1L,
        uri = "content://media/external/images/media/${id.substringAfter('-')}",
        filename = "$id.jpg",
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        fileSizeBytes = 1024L,
        capturedAtMillis = 1L,
        device = "Pixel",
        resolution = "100 x 100",
        thumbnailSeed = id.hashCode(),
        cloudBackedUp = cloudBackedUp
    )

    private fun record(state: BackupState) = SyncRecord(state = state.name)

    // ── local states that are final without asking the cloud ──────────────────

    @Test
    fun `a completed record needs no cloud check`() {
        assertFalse(AutomaticBackupCoordinator.needsCloudVerification(BackupState.COMPLETED))
    }

    @Test
    fun `queued and in-flight states are owned by the running drain`() {
        listOf(
            BackupState.WAITING,
            BackupState.PAUSED,
            BackupState.UPLOADING,
            BackupState.UPLOADING_TO_CLOUDINARY,
            BackupState.REQUESTING_CLOUDINARY_AUTH,
            BackupState.FINALIZING_SUPABASE
        ).forEach { state ->
            assertFalse("$state is owned by the drain", AutomaticBackupCoordinator.needsCloudVerification(state))
        }
    }

    @Test
    fun `never-uploaded, partial and retryable states all need the cloud's answer`() {
        listOf(
            null,
            BackupState.NOT_STARTED,
            // Uploaded to Cloudinary but never recorded in Supabase: the cloud row
            // may or may not exist, so it is asked about rather than assumed.
            BackupState.CLOUDINARY_COMPLETED,
            BackupState.FAILED,
            BackupState.CANCELLED
        ).forEach { state ->
            assertTrue("$state still needs verification", AutomaticBackupCoordinator.needsCloudVerification(state))
        }
    }

    // ── what discovery offers to the cloud ────────────────────────────────────

    @Test
    fun `first run offers every device item`() {
        val device = (1..243).map { item("img-$it") }
        val candidates = AutomaticBackupCoordinator.selectCloudVerificationCandidates(
            deviceItems = device,
            libraryItems = device,
            records = emptyMap()
        )
        assertEquals(243, candidates.size)
    }

    @Test
    fun `a restart and a fresh login offer nothing once the queue remembers completion`() {
        val device = (1..243).map { item("img-$it") }
        val records = device.associate { it.id to record(BackupState.COMPLETED) }
        val candidates = AutomaticBackupCoordinator.selectCloudVerificationCandidates(
            deviceItems = device,
            libraryItems = device,
            records = records
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `added media is the only thing offered on the next run`() {
        val existing = (1..100).map { item("img-$it") }
        val added = (1000..1004).map { item("img-$it") }
        val device = existing + added
        val records = existing.associate { it.id to record(BackupState.COMPLETED) }
        val candidates = AutomaticBackupCoordinator.selectCloudVerificationCandidates(
            deviceItems = device,
            libraryItems = device,
            records = records
        )
        assertEquals((1000..1004).map { "img-$it" }, candidates.map { it.id })
    }

    @Test
    fun `media the local cloud index believes is backed up is still asked about`() {
        // The queue was lost (reinstall), so local records prove nothing, and the
        // local index may itself be stale. Only the server can settle it — so the
        // item is offered rather than assumed done, and no upload happens either way.
        val device = listOf(item("img-1", cloudBackedUp = true))
        val candidates = AutomaticBackupCoordinator.selectCloudVerificationCandidates(
            deviceItems = device,
            libraryItems = device,
            records = emptyMap()
        )
        assertEquals(listOf("img-1"), candidates.map { it.id })
        // The flag is carried through so a stale index entry can be recognised.
        assertTrue(candidates.single().cloudBackedUp)
    }

    @Test
    fun `an already queued media is not offered twice`() {
        val device = listOf(item("img-1"), item("img-2"))
        val records = mapOf("img-1" to record(BackupState.WAITING))
        val candidates = AutomaticBackupCoordinator.selectCloudVerificationCandidates(
            deviceItems = device,
            libraryItems = device,
            records = records
        )
        assertEquals(listOf("img-2"), candidates.map { it.id })
    }

    @Test
    fun `the same media is offered once, however often the device list repeats it`() {
        val device = listOf(item("img-7"), item("img-7"), item("img-7"))
        val candidates = AutomaticBackupCoordinator.selectCloudVerificationCandidates(
            deviceItems = device,
            libraryItems = device,
            records = emptyMap()
        )
        assertEquals(1, candidates.size)
    }

    // ── the legacy rule is unchanged for the no-coordinator path ──────────────

    @Test
    fun `the eligibility rule still excludes cloud-backed media`() {
        val device = listOf(item("img-1", cloudBackedUp = true))
        val ids = AutomaticBackupCoordinator.selectEligibleBackupIds(
            deviceItems = device,
            libraryItems = device,
            records = emptyMap()
        )
        assertTrue(ids.isEmpty())
    }
}
