package com.mydrive.app.ui.selection

import com.mydrive.app.data.local.TrashedIdentity
import com.mydrive.app.data.media.LibraryVisibilityRules
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.repository.DeleteMediaResult
import com.mydrive.app.data.repository.RemoveFromLibraryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSelectionTest {

    private fun item(
        id: String,
        albumId: String = "camera",
        type: MediaType = MediaType.PHOTO,
        trashed: Boolean = false,
        hidden: Boolean = false,
        mediaStoreId: Long = id.hashCode().toLong(),
        uri: String = "content://media/external/images/media/$id",
        originLocal: Boolean = true
    ) = MediaItem(
        id = id,
        filename = "$id.jpg",
        type = type,
        fileSizeBytes = 1024L,
        capturedAtMillis = 1L,
        device = "Pixel",
        resolution = "100 x 100",
        thumbnailSeed = id.hashCode(),
        albumId = albumId,
        albumName = albumId,
        mediaStoreId = mediaStoreId,
        uri = uri,
        isTrashed = trashed,
        hiddenFromLibrary = hidden,
        originLocal = originLocal
    )

    @Test
    fun `long press enters selection mode with the first item selected`() {
        val eligible = setOf("a", "b", "c")
        val next = MediaSelection.enter("b", eligible)
        assertTrue(next.mode)
        assertEquals(setOf("b"), next.selectedIds)
        assertEquals(1, next.count)
        assertEquals("1 selected", MediaSelection.countLabel(next.count))
    }

    @Test
    fun `tap in selection mode toggles without using grid indexes`() {
        val eligible = setOf("photo-1", "photo-2", "video-3")
        var state = MediaSelection.enter("photo-1", eligible)
        state = MediaSelection.toggle(state, "video-3", eligible)
        assertEquals(setOf("photo-1", "video-3"), state.selectedIds)
        state = MediaSelection.toggle(state, "photo-1", eligible)
        assertEquals(setOf("video-3"), state.selectedIds)
        assertTrue(state.mode)
    }

    @Test
    fun `deselecting the last item exits selection mode`() {
        val eligible = setOf("a")
        var state = MediaSelection.enter("a", eligible)
        state = MediaSelection.toggle(state, "a", eligible)
        assertFalse(state.mode)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `clearing selection does not change media ids`() {
        val items = listOf(item("a"), item("b"))
        val state = MediaSelection.selectAll(MediaSelection.eligibleIds(items))
        val exited = MediaSelection.exit()
        assertFalse(exited.mode)
        assertEquals(listOf("a", "b"), items.map { it.id })
        assertEquals(2, state.count)
    }

    @Test
    fun `select all is scoped to the current photos screen and skips ineligible records`() {
        val photos = listOf(
            item("cam-1", albumId = "camera"),
            item("cam-2", albumId = "camera"),
            item("trashed", trashed = true),
            item("hidden", hidden = true),
            item(""),
            item("dup-1"),
            item("dup-1")
        )
        val ids = MediaSelection.eligibleIds(photos)
        assertEquals(listOf("cam-1", "cam-2", "dup-1"), ids)
        val all = MediaSelection.selectAll(ids)
        assertEquals(setOf("cam-1", "cam-2", "dup-1"), all.selectedIds)
        assertFalse("trashed" in all.selectedIds)
        assertFalse("hidden" in all.selectedIds)
    }

    @Test
    fun `select all on an album does not select unrelated photos`() {
        val camera = listOf(item("c1", albumId = "camera"), item("c2", albumId = "camera"))
        val screenshots = listOf(item("s1", albumId = "screenshots"))
        val albumIds = MediaSelection.eligibleIds(screenshots)
        val selected = MediaSelection.selectAll(albumIds)
        assertEquals(setOf("s1"), selected.selectedIds)
        assertFalse(selected.contains("c1"))
        assertFalse(selected.contains("c2"))
        assertEquals(2, MediaSelection.eligibleIds(camera).size)
    }

    @Test
    fun `private vault media is never eligible for gallery selection`() {
        // Vault lives on a separate route and is never composed into Photos/Albums.
        val gallery = listOf(item("public-1"), item("public-2"))
        val vaultOnly = item("vault-secret")
        assertFalse(vaultOnly.id in MediaSelection.eligibleIdSet(gallery))
        val entered = MediaSelection.enter("vault-secret", MediaSelection.eligibleIdSet(gallery))
        assertFalse(entered.mode)
    }

    @Test
    fun `selection survives pagination by keeping ids that are not in the current page`() {
        val pageOne = setOf("a", "b")
        var state = MediaSelection.selectAll(pageOne)
        state = MediaSelection.toggle(state, "c", setOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), state.selectedIds)
        val stillOnScreen = MediaSelection.intersectVisible(state, setOf("b", "c", "d"))
        assertEquals(setOf("b", "c"), stillOnScreen.selectedIds)
        assertTrue(stillOnScreen.mode)
    }

    @Test
    fun `bulk trash confirmation copy includes the selected count`() {
        assertEquals("Move 1 item to Trash?", MediaSelection.trashConfirmTitle(1))
        assertEquals("Move 3 items to Trash?", MediaSelection.trashConfirmTitle(3))
        assertTrue(MediaSelection.trashConfirmBody(3).contains("3 items will move to Trash"))
    }

    @Test
    fun `partial trash failures keep successful items and report both counts`() {
        val counts = BulkItemCounts(succeeded = 18, failed = 2)
        assertEquals("18 deleted, 2 failed", MediaSelection.trashResultMessage(counts))
        val allOk = BulkItemCounts(succeeded = 3, failed = 0)
        assertEquals("3 items moved to Trash", MediaSelection.trashResultMessage(allOk))
    }

    @Test
    fun `partial move failures keep successful items and preserve remaining selection ids`() {
        val before = MediaSelection.selectAll(listOf("a", "b", "c"))
        val after = MediaSelection.minus(before, listOf("a", "c"))
        assertEquals(setOf("b"), after.selectedIds)
        assertEquals("2 moved, 1 failed", MediaSelection.moveResultMessage(BulkItemCounts(2, 1)))
    }

    @Test
    fun `cloud only tiles never pass https urls as local trash targets`() {
        val cloud = item(
            id = "cloud-9",
            uri = "https://res.cloudinary.com/demo/image/upload/v1/cloud-9.jpg",
            originLocal = false
        )
        val local = item(id = "img-1")
        assertEquals(TrashAttempt.CLOUD_ONLY, trashAttemptFor(cloud))
        assertEquals(TrashAttempt.LOCAL_THEN_CLOUD, trashAttemptFor(local))
        assertEquals(TrashAttempt.CLOUD_ONLY, trashAttemptFor(null))
    }

    @Test
    fun `bulk deleted ids stay out of photos until restore via the existing trash identity`() {
        val library = listOf(item("img-1", mediaStoreId = 100), item("img-2", mediaStoreId = 101))
        val selected = MediaSelection.selectAll(MediaSelection.eligibleIds(library))
        assertEquals(setOf("img-1", "img-2"), selected.selectedIds)

        val trashed = TrashedIdentity(
            localId = "img-1",
            localMediaId = 100L,
            remoteMediaIds = listOf("remote-1"),
            clientUploadId = "cu-1",
            trashedAtMillis = 10L
        )
        val visible = LibraryVisibilityRules.withoutTrashed(library, setOf("img-1"), listOf(trashed))
        assertEquals(listOf("img-2"), visible.map { it.id })
        assertTrue(LibraryVisibilityRules.isTrashedItem(library[0], setOf("img-1"), listOf(trashed)))

        val pruned = MediaSelection.intersectVisible(
            selected,
            MediaSelection.eligibleIdSet(visible)
        )
        assertEquals(setOf("img-2"), pruned.selectedIds)

        val restored = LibraryVisibilityRules.withoutTrashed(library, emptySet(), emptyList())
        assertEquals(listOf("img-1", "img-2"), restored.map { it.id })
        assertEquals("img-1", library[0].id)
        assertEquals(100L, library[0].mediaStoreId)
    }

    @Test
    fun `a successful bulk delete never resurrects from a later cloud sibling`() {
        val remaining = listOf(item("img-2", mediaStoreId = 101))
        val sibling = item("cloud-img-1", mediaStoreId = 100, originLocal = false, uri = "https://cdn.example/img-1.jpg")
        val identity = TrashedIdentity(
            localId = "img-1",
            localMediaId = 100L,
            remoteMediaIds = listOf("remote-1"),
            clientUploadId = null,
            trashedAtMillis = 1L
        )
        val composed = remaining + sibling
        val visible = LibraryVisibilityRules.withoutTrashed(composed, emptySet(), listOf(identity))
        assertEquals(listOf("img-2"), visible.map { it.id })
        assertTrue(LibraryVisibilityRules.isTrashedItem(sibling, emptySet(), listOf(identity)))
    }

    @Test
    fun `move updates keep the same stable ids for items that remain`() {
        val before = listOf(item("keep", albumId = "camera"), item("moved", albumId = "camera"))
        val after = listOf(item("keep", albumId = "camera"))
        val selection = MediaSelection.selectAll(listOf("keep", "moved"))
        val remaining = MediaSelection.minus(selection, listOf("moved"))
        assertEquals(setOf("keep"), remaining.selectedIds)
        assertEquals("keep", after.single().id)
        assertEquals(before[0].id, after.single().id)
    }

    @Test
    fun `eligible ids skip blank hidden trashed and duplicate catalog rows`() {
        val items = listOf(
            item("ok"),
            item("ok"),
            item("gone", trashed = true),
            item("hid", hidden = true),
            item("   ").copy(id = " ")
        )
        assertEquals(listOf("ok"), MediaSelection.eligibleIds(items))
    }

    @Test
    fun `local trash results map onto finalize cloud-only pause or fail`() {
        assertEquals(LocalTrashDecision.Finalize, interpretLocalTrash(DeleteMediaResult.Success))
        assertEquals(LocalTrashDecision.CloudOnly, interpretLocalTrash(DeleteMediaResult.NotFound))
        assertEquals(LocalTrashDecision.PauseManageMedia, interpretLocalTrash(DeleteMediaResult.RequiresManageMedia))
        assertEquals(LocalTrashDecision.Fail, interpretLocalTrash(DeleteMediaResult.PermissionDenied))
        assertEquals(LocalTrashDecision.Fail, interpretLocalTrash(DeleteMediaResult.Failed))
    }

    @Test
    fun `cloud trash pending still counts as a successful user-facing delete`() {
        var counts = BulkItemCounts()
        counts = recordTrashSuccess(counts, RemoveFromLibraryResult.Success)
        counts = recordTrashSuccess(counts, RemoveFromLibraryResult.Unauthorized)
        counts = recordTrashSuccess(counts, RemoveFromLibraryResult.Failed)
        counts = recordTrashFailure(counts)
        assertEquals(3, counts.succeeded)
        assertEquals(1, counts.failed)
        assertEquals("3 deleted, 1 failed", MediaSelection.trashResultMessage(counts))
    }

    @Test
    fun `progress labels stay on the selected count`() {
        assertEquals(
            "Deleting 24 items…",
            BulkActionProgress(inProgress = true, kind = BulkActionKind.DELETE, processed = 0, total = 24).label
        )
        assertEquals(
            "Moving 1 item…",
            BulkActionProgress(inProgress = true, kind = BulkActionKind.MOVE, processed = 0, total = 1).label
        )
    }
}
