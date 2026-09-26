package com.mydrive.app.data.local

import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted gallery catalog is what a cold start renders, so two things must
 * hold: the round trip through disk is lossless, and a reconciliation writes only
 * what actually changed. Both are pure functions and are pinned here.
 */
class MediaCatalogReconcilerTest {

    private val owner = "owner-1"

    private fun item(
        id: String,
        capturedAt: Long = 1_700_000_000_000L,
        albumId: String = "camera",
        favorite: Boolean = false,
        backupState: BackupState = BackupState.NOT_STARTED,
        remoteMediaId: String? = null
    ) = MediaItem(
        id = id,
        filename = "$id.jpg",
        type = MediaType.PHOTO,
        fileSizeBytes = 2_048L,
        capturedAtMillis = capturedAt,
        device = "Test device",
        resolution = "1080 x 1920",
        isFavorite = favorite,
        backupState = backupState,
        backupCompleted = backupState == BackupState.COMPLETED,
        thumbnailSeed = id.hashCode(),
        albumId = albumId,
        albumName = albumId,
        remoteMediaId = remoteMediaId
    )

    @Test
    fun `the render model survives a round trip through the catalog`() {
        val original = item("img-7", favorite = true, backupState = BackupState.COMPLETED, remoteMediaId = "remote-7")
        val restored = original.toCatalogEntity(owner).toMediaItem()
        assertEquals(original, restored)
    }

    @Test
    fun `an unchanged library writes nothing`() {
        val library = listOf(item("img-1"), item("img-2"), item("img-3"))
        val diff = MediaCatalogReconciler.diff(owner, library, library)
        assertTrue(diff.isEmpty)
        assertTrue(diff.upserts.isEmpty())
        assertTrue(diff.removedIds.isEmpty())
    }

    @Test
    fun `a new photo writes exactly one row`() {
        val previous = listOf(item("img-2"), item("img-1"))
        val next = listOf(item("img-3"), item("img-2"), item("img-1"))

        val diff = MediaCatalogReconciler.diff(owner, previous, next)

        assertEquals(listOf("img-3"), diff.upserts.map { it.mediaId })
        assertTrue(diff.removedIds.isEmpty())
        assertEquals(owner, diff.upserts.single().ownerUserId)
    }

    @Test
    fun `a removed photo deletes exactly its id`() {
        val previous = listOf(item("img-1"), item("img-2"))
        val next = listOf(item("img-1"))

        val diff = MediaCatalogReconciler.diff(owner, previous, next)

        assertTrue(diff.upserts.isEmpty())
        assertEquals(listOf("img-2"), diff.removedIds)
    }

    @Test
    fun `a metadata change rewrites only the row that changed`() {
        val previous = listOf(item("img-1"), item("img-2"))
        val next = listOf(item("img-1"), item("img-2", backupState = BackupState.COMPLETED))

        val diff = MediaCatalogReconciler.diff(owner, previous, next)

        assertEquals(listOf("img-2"), diff.upserts.map { it.mediaId })
        assertEquals(BackupState.COMPLETED.name, diff.upserts.single().backupState)
    }

    @Test
    fun `a reorder is not a content change`() {
        val previous = listOf(item("img-1", capturedAt = 200L), item("img-2", capturedAt = 100L))
        val next = listOf(item("img-2", capturedAt = 100L), item("img-1", capturedAt = 200L))

        // The rendering order is derived from (capturedAtMillis, mediaId), never
        // stored, so presenting the same rows in another order rewrites nothing.
        assertTrue(MediaCatalogReconciler.diff(owner, previous, next).isEmpty)
    }

    @Test
    fun `an empty previous catalog writes every row and deletes nothing`() {
        val next = listOf(item("img-1"), item("img-2"))
        val diff = MediaCatalogReconciler.diff(owner, emptyList(), next)
        assertEquals(2, diff.upserts.size)
        assertTrue(diff.removedIds.isEmpty())
    }

    @Test
    fun `a blank owner is never persisted`() {
        val diff = MediaCatalogReconciler.diff("", emptyList(), listOf(item("img-1")))
        assertTrue(diff.isEmpty)
    }
}
