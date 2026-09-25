package com.mydrive.app.data.backup

import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutomaticBackupCoordinatorTest {

    private fun item(
        id: String,
        cloudBackedUp: Boolean = false,
        backupState: BackupState = BackupState.NOT_STARTED
    ) = MediaItem(
        id = id,
        mediaStoreId = id.substringAfter('-').toLongOrNull() ?: 1L,
        uri = "content://media/external/images/media/$id",
        filename = "$id.jpg",
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        fileSizeBytes = 1024L,
        capturedAtMillis = 1L,
        device = "Pixel",
        resolution = "100 x 100",
        thumbnailSeed = id.hashCode(),
        backupState = backupState,
        cloudBackedUp = cloudBackedUp
    )

    private fun record(state: BackupState) = SyncRecord(state = state.name)

    private fun coordinator(
        canRead: Boolean = true,
        automatic: Boolean = true,
        userId: String? = "user-1",
        device: List<MediaItem> = emptyList(),
        library: List<MediaItem> = device,
        records: Map<String, SyncRecord> = emptyMap(),
        paused: Boolean = false,
        started: MutableList<Pair<List<String>, Boolean>> = mutableListOf(),
        refreshCount: AtomicInteger = AtomicInteger(0),
        gate: BackupDiscoveryGate = BackupDiscoveryGate(),
        refreshDelayMs: Long = 0L
    ): AutomaticBackupCoordinator {
        return AutomaticBackupCoordinator(
            canReadMedia = { canRead },
            automaticBackupEnabled = { automatic },
            currentUserId = { userId },
            refreshLibrary = { _, _ ->
                refreshCount.incrementAndGet()
                if (refreshDelayMs > 0L) delay(refreshDelayMs)
            },
            deviceMedia = { device },
            libraryMedia = { library },
            records = { records },
            startBackup = { ids, resume -> started += ids.toList() to resume },
            isPaused = { paused },
            gate = gate
        )
    }

    @Test
    fun `permission granted starts automatic initial scan`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        val refreshes = AtomicInteger(0)
        val result = coordinator(
            device = listOf(item("img-1"), item("img-2")),
            started = started,
            refreshCount = refreshes
        ).request(BackupDiscoveryReason.PERMISSION_GRANTED)

        assertTrue(result is BackupDiscoveryResult.Enqueued)
        assertEquals(listOf("img-1", "img-2"), (result as BackupDiscoveryResult.Enqueued).ids)
        assertEquals(1, started.size)
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `permission denied does not start backup`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        val result = coordinator(
            canRead = false,
            device = listOf(item("img-1")),
            started = started
        ).request(BackupDiscoveryReason.PERMISSION_GRANTED)

        assertEquals(BackupDiscoveryResult.PermissionDenied, result)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `repeated permission callbacks coalesce into one backup session`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        val gate = BackupDiscoveryGate()
        val coordinator = coordinator(
            device = listOf(item("img-1")),
            started = started,
            gate = gate,
            refreshDelayMs = 40L
        )

        val first = async { coordinator.request(BackupDiscoveryReason.PERMISSION_GRANTED) }
        delay(10)
        val second = coordinator.request(BackupDiscoveryReason.PERMISSION_GRANTED)
        val third = coordinator.request(BackupDiscoveryReason.PERMISSION_GRANTED)
        val initial = first.await()

        assertTrue(initial is BackupDiscoveryResult.Enqueued)
        assertEquals(BackupDiscoveryResult.Coalesced, second)
        assertEquals(BackupDiscoveryResult.Coalesced, third)
        assertTrue("in-flight plus at most one trailing scan", started.size in 1..2)
        assertEquals(listOf("img-1"), started.flatMap { it.first }.distinct())
    }

    @Test
    fun `existing backed-up media yields zero duplicate uploads`() {
        val existing = (1..50).map { item("img-$it", cloudBackedUp = true) }
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(
            deviceItems = existing,
            libraryItems = existing,
            records = emptyMap()
        )
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `new media is automatically queued`() = runBlocking {
        val result = coordinator(
            device = listOf(item("img-new"))
        ).request(BackupDiscoveryReason.MEDIASTORE)

        assertEquals(listOf("img-new"), (result as BackupDiscoveryResult.Enqueued).ids)
    }

    @Test
    fun `multiple new media are queued once each`() {
        val items = listOf(item("img-1"), item("img-2"), item("img-3"))
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(items, items, emptyMap())
        assertEquals(listOf("img-1", "img-2", "img-3"), queued)
    }

    @Test
    fun `same MediaStore event repeated does not duplicate queue entries`() {
        val discovered = listOf(item("img-7"), item("img-7"), item("img-7"))
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(
            deviceItems = discovered,
            libraryItems = discovered,
            records = emptyMap()
        )
        assertEquals(listOf("img-7"), queued)
    }

    @Test
    fun `waiting and in-flight records are not restarted`() {
        val items = listOf(
            item("img-wait"),
            item("img-uploading"),
            item("img-done", cloudBackedUp = true),
            item("img-new")
        )
        val records = mapOf(
            "img-wait" to record(BackupState.WAITING),
            "img-uploading" to record(BackupState.UPLOADING_TO_CLOUDINARY),
            "img-done" to record(BackupState.COMPLETED)
        )
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(items, items, records)
        assertEquals(listOf("img-new"), queued)
    }

    @Test
    fun `failed and unfinalized media resume instead of duplicating`() {
        val items = listOf(
            item("img-failed"),
            item("img-partial")
        )
        val records = mapOf(
            "img-failed" to record(BackupState.FAILED),
            "img-partial" to record(BackupState.CLOUDINARY_COMPLETED)
        )
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(items, items, records)
        assertEquals(listOf("img-failed", "img-partial"), queued)
    }

    @Test
    fun `app restart performs incremental discovery only`() {
        val existing = listOf(item("img-old", cloudBackedUp = true), item("img-new"))
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(existing, existing, emptyMap())
        assertEquals(listOf("img-new"), queued)
    }

    @Test
    fun `permission revoked stops scanning without enqueue`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        val result = coordinator(
            canRead = false,
            device = listOf(item("img-1")),
            started = started
        ).request(BackupDiscoveryReason.FOREGROUND)
        assertEquals(BackupDiscoveryResult.PermissionDenied, result)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `permission restored resumes incremental backup`() = runBlocking {
        val existing = listOf(item("img-old", cloudBackedUp = true), item("img-new"))
        val result = coordinator(device = existing, library = existing)
            .request(BackupDiscoveryReason.PERMISSION_GRANTED)
        assertEquals(listOf("img-new"), (result as BackupDiscoveryResult.Enqueued).ids)
    }

    @Test
    fun `manual start backup is incremental only`() = runBlocking {
        val items = listOf(item("img-old", cloudBackedUp = true), item("img-new"))
        val result = coordinator(device = items, library = items)
            .request(BackupDiscoveryReason.MANUAL)
        assertEquals(listOf("img-new"), (result as BackupDiscoveryResult.Enqueued).ids)
    }

    @Test
    fun `workmanager retry does not create duplicate upload jobs`() {
        val items = listOf(item("img-1"))
        val records = mapOf("img-1" to record(BackupState.WAITING))
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(items, items, records)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `cloud-first behavior remains intact when local queue is missing`() {
        val restored = item("img-100", cloudBackedUp = true)
        val queued = AutomaticBackupCoordinator.selectEligibleBackupIds(
            deviceItems = listOf(restored),
            libraryItems = listOf(restored),
            records = emptyMap()
        )
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `zero media on first permission grant enqueues nothing`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        val result = coordinator(device = emptyList(), started = started)
            .request(BackupDiscoveryReason.PERMISSION_GRANTED)
        assertEquals(BackupDiscoveryResult.NothingToBackup, result)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `automatic backup off does not enqueue except manual`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        val auto = coordinator(
            automatic = false,
            device = listOf(item("img-1")),
            started = started
        ).request(BackupDiscoveryReason.APP_START)
        assertEquals(BackupDiscoveryResult.AutomaticDisabled, auto)
        assertTrue(started.isEmpty())

        val manual = coordinator(
            automatic = false,
            device = listOf(item("img-1")),
            started = started
        ).request(BackupDiscoveryReason.MANUAL)
        assertTrue(manual is BackupDiscoveryResult.Enqueued)
    }

    @Test
    fun `no session does not start backup`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        val result = coordinator(userId = null, device = listOf(item("img-1")), started = started)
            .request(BackupDiscoveryReason.APP_START)
        assertEquals(BackupDiscoveryResult.NoSession, result)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `automatic enqueue does not unpause a user-paused backup`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        coordinator(
            device = listOf(item("img-1")),
            paused = true,
            started = started
        ).request(BackupDiscoveryReason.MEDIASTORE)
        assertFalse(started.single().second)
    }

    @Test
    fun `manual enqueue resumes a paused backup`() = runBlocking {
        val started = mutableListOf<Pair<List<String>, Boolean>>()
        coordinator(
            device = listOf(item("img-1")),
            paused = true,
            started = started
        ).request(BackupDiscoveryReason.MANUAL)
        assertTrue(started.single().second)
    }

    @Test
    fun `discovery gate coalesces like the catalog refresh gate`() {
        val gate = BackupDiscoveryGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertFalse(gate.begin())
        assertTrue(gate.end())
        assertTrue(gate.begin())
        assertFalse(gate.end())
    }
}

class BackupDiscoveryGateTest {

    @Test
    fun `a burst of discovery requests costs at most two scans`() {
        val gate = BackupDiscoveryGate()
        assertTrue("first caller runs", gate.begin())
        assertFalse("permission callback is folded in", gate.begin())
        assertFalse("app resume is folded in", gate.begin())
        assertFalse("mediastore change is folded in", gate.begin())
        assertTrue("one trailing pass is owed", gate.end())
        assertTrue("the trailing pass runs", gate.begin())
        assertFalse("nothing else was queued", gate.end())
    }
}
