package com.mydrive.app.data.album

import com.mydrive.app.data.model.AlbumKind
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAlbumCatalogTest {

    private fun item(
        id: String,
        albumId: String = "camera",
        albumName: String = "Camera",
        capturedAt: Long = 1L,
        trashed: Boolean = false
    ) = MediaItem(
        id = id,
        filename = "$id.jpg",
        type = MediaType.PHOTO,
        fileSizeBytes = 1024L,
        capturedAtMillis = capturedAt,
        device = "Pixel",
        resolution = "100 x 100",
        thumbnailSeed = id.hashCode(),
        albumId = albumId,
        albumName = albumName,
        uri = "content://media/external/images/media/$id",
        isTrashed = trashed
    )

    @Test
    fun `creating an album persists a user album without touching media`() {
        val media = listOf(item("img-1"), item("img-2"))
        val (state, result) = AlbumCatalogState().create("Trip", now = 10L)
        val created = result as AlbumMutationResult.Created
        assertTrue(GalleryAlbumCatalog.isUserAlbumId(created.albumId))
        assertEquals("Trip", created.name)
        val albums = GalleryAlbumCatalog.mergeAlbums(
            GalleryAlbumCatalog.folderAlbums(media),
            GalleryAlbumCatalog.userAlbums(state.albums, state.members, media)
        )
        assertEquals(1, albums.count { it.kind == AlbumKind.USER })
        assertEquals(0, albums.first { it.isUserAlbum }.mediaCount)
        assertEquals(listOf("img-1", "img-2"), media.map { it.id })
    }

    @Test
    fun `blank reserved and duplicate names are rejected`() {
        val created = AlbumCatalogState().create("Summer", now = 1L).first
        assertTrue(created.create("  ", now = 2L).second is AlbumMutationResult.BlankName)
        assertTrue(created.create("My Drive", now = 2L).second is AlbumMutationResult.ReservedName)
        assertTrue(created.create("mydrive", now = 2L).second is AlbumMutationResult.ReservedName)
        assertTrue(created.create("summer", now = 2L).second is AlbumMutationResult.DuplicateName)
        assertTrue(
            created.create("Camera", now = 2L, folderNames = listOf("Camera")).second
                is AlbumMutationResult.DuplicateName
        )
    }

    @Test
    fun `rename keeps the same album id and membership`() {
        val started = AlbumCatalogState().create("One", now = 1L, id = "user:one")
        val withMedia = started.first.addMedia("user:one", listOf("img-1", "img-2"), now = 2L).first
        val (renamed, result) = withMedia.rename("user:one", "Two", now = 3L)
        val renamedResult = result as AlbumMutationResult.Renamed
        assertEquals("user:one", renamedResult.albumId)
        assertEquals("Two", renamed.album("user:one")?.name)
        assertEquals(setOf("img-1", "img-2"), renamed.memberIds("user:one"))
    }

    @Test
    fun `deleting an album removes membership but not the media identities`() {
        val media = listOf(item("img-1"), item("img-2"))
        val created = AlbumCatalogState().create("Keepers", now = 1L, id = "user:keep").first
        val filled = created.addMedia("user:keep", listOf("img-1", "img-2"), now = 2L).first
        val (deleted, result) = filled.delete("user:keep")
        assertTrue(result is AlbumMutationResult.Deleted)
        assertTrue(deleted.albums.isEmpty())
        assertTrue(deleted.memberIds("user:keep").isEmpty())
        assertEquals(listOf("img-1", "img-2"), media.map { it.id })
        val folders = GalleryAlbumCatalog.folderAlbums(media)
        assertEquals(2, folders.single().mediaCount)
    }

    @Test
    fun `adding media is membership only and does not duplicate records`() {
        val media = listOf(item("img-1"), item("img-2"), item("img-3"))
        val created = AlbumCatalogState().create("Trip", now = 1L, id = "user:trip").first
        val (added, result) = created.addMedia("user:trip", listOf("img-1", "img-1", "img-2"), now = 2L)
        val changed = result as AlbumMutationResult.MembersChanged
        assertEquals(2, changed.added)
        assertEquals(setOf("img-1", "img-2"), added.memberIds("user:trip"))
        val visible = GalleryAlbumCatalog.mediaForAlbum("user:trip", media, added.members)
        assertEquals(listOf("img-1", "img-2"), visible.map { it.id })
        assertEquals(3, media.size)
    }

    @Test
    fun `removing media from an album is not a delete`() {
        val media = listOf(item("img-1"), item("img-2"))
        val filled = AlbumCatalogState()
            .create("Trip", now = 1L, id = "user:trip").first
            .addMedia("user:trip", listOf("img-1", "img-2"), now = 2L).first
        val (removed, result) = filled.removeMedia("user:trip", listOf("img-1"), now = 3L)
        val changed = result as AlbumMutationResult.MembersChanged
        assertEquals(1, changed.removed)
        assertEquals(setOf("img-2"), removed.memberIds("user:trip"))
        assertEquals(listOf("img-1", "img-2"), media.map { it.id })
        assertEquals(
            listOf("img-1", "img-2"),
            GalleryAlbumCatalog.folderAlbums(media).single().let { folder ->
                media.filter { it.albumId == folder.id }.map { it.id }
            }
        )
    }

    @Test
    fun `moving between albums updates membership and keeps photos`() {
        val media = listOf(item("img-1"), item("img-2"))
        val start = AlbumCatalogState()
            .create("A", now = 1L, id = "user:a").first
            .create("B", now = 2L, id = "user:b").first
            .addMedia("user:a", listOf("img-1", "img-2"), now = 3L).first
        val (moved, result) = start.moveMedia("user:a", "user:b", listOf("img-1"), now = 4L)
        val changed = result as AlbumMutationResult.MembersChanged
        assertEquals(1, changed.added)
        assertEquals(setOf("img-2"), moved.memberIds("user:a"))
        assertEquals(setOf("img-1"), moved.memberIds("user:b"))
        assertEquals(listOf("img-1", "img-2"), media.map { it.id })
    }

    @Test
    fun `empty albums remain valid and keep their name`() {
        val created = AlbumCatalogState().create("Empty", now = 1L, id = "user:empty").first
        val albums = GalleryAlbumCatalog.userAlbums(created.albums, created.members, emptyList())
        assertEquals(1, albums.size)
        assertEquals("Empty", albums.single().name)
        assertEquals(0, albums.single().mediaCount)
        assertTrue(albums.single().isUserAlbum)
    }

    @Test
    fun `cover falls back when the preferred media leaves the album`() {
        val filled = AlbumCatalogState()
            .create("Trip", now = 1L, id = "user:trip").first
            .addMedia("user:trip", listOf("img-1", "img-2"), now = 2L).first
            .setCover("user:trip", "img-1", now = 3L).first
        assertEquals("img-1", filled.album("user:trip")?.coverMediaId)
        val afterRemove = filled.removeMedia("user:trip", listOf("img-1"), now = 4L).first
        assertEquals("img-2", afterRemove.album("user:trip")?.coverMediaId)
        val emptied = afterRemove.removeMedia("user:trip", listOf("img-2"), now = 5L).first
        assertEquals(null, emptied.album("user:trip")?.coverMediaId)
        assertTrue(emptied.albums.any { it.id == "user:trip" })
    }

    @Test
    fun `trashed media never appears as active album media`() {
        val media = listOf(item("img-1"), item("img-2", trashed = true), item("img-3"))
        val filled = AlbumCatalogState()
            .create("Trip", now = 1L, id = "user:trip").first
            .addMedia("user:trip", listOf("img-1", "img-2", "img-3"), now = 2L).first
        val active = media.filterNot { it.isTrashed }
        val visible = GalleryAlbumCatalog.visibleMembers(active, filled.memberIds("user:trip"))
        assertEquals(listOf("img-1", "img-3"), visible.map { it.id })
        assertFalse(visible.any { it.isTrashed })
    }

    @Test
    fun `sorting does not break album identity or membership`() {
        val media = listOf(
            item("img-1", capturedAt = 3L),
            item("img-2", capturedAt = 1L)
        )
        val state = AlbumCatalogState()
            .create("Beta", now = 2L, id = "user:beta").first
            .create("Alpha", now = 10L, id = "user:alpha").first
            .addMedia("user:beta", listOf("img-1", "img-2"), now = 11L).first
        val albums = GalleryAlbumCatalog.mergeAlbums(
            GalleryAlbumCatalog.folderAlbums(media),
            GalleryAlbumCatalog.userAlbums(state.albums, state.members, media)
        )
        val byName = GalleryAlbumCatalog.sortAlbums(albums, AlbumSort.NAME)
        assertEquals("Alpha", byName.first { it.isUserAlbum }.name)
        val byCount = GalleryAlbumCatalog.sortAlbums(albums.filter { it.isUserAlbum }, AlbumSort.ITEM_COUNT)
        assertEquals("user:beta", byCount.first().id)
        assertEquals(2, byCount.first().mediaCount)
        assertEquals(setOf("img-1", "img-2"), state.memberIds("user:beta"))
    }

    @Test
    fun `a folder album is never treated as a writable user album`() {
        val state = AlbumCatalogState()
        assertTrue(state.rename("camera", "Shots", now = 1L).second is AlbumMutationResult.NotUserAlbum)
        assertTrue(state.delete("camera").second is AlbumMutationResult.NotUserAlbum)
        assertTrue(state.addMedia("camera", listOf("img-1"), now = 1L).second is AlbumMutationResult.NotUserAlbum)
    }
}
