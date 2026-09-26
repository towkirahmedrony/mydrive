package com.mydrive.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Album identity is the user's own folder, never the storage provider.
 *
 * These pin the rule that stopped cloud-backed media from claiming a "My Drive"
 * album: a folder the device reported is kept, a provider bucket is not a folder,
 * and anything unknown lands in the existing ungrouped album.
 */
class MediaAlbumRefTest {

    @Test
    fun `a device folder is kept as the album`() {
        // MediaStore bucket ids are numeric, which is what a real folder looks like.
        assertEquals(MediaAlbumRef("12345", "Camera"), resolveAlbum("12345", "Camera"))
        assertEquals(MediaAlbumRef("88", "Screenshots"), resolveAlbum("88", "Screenshots"))
        assertEquals(MediaAlbumRef("91", "WhatsApp Images"), resolveAlbum("91", "WhatsApp Images"))
        assertEquals(MediaAlbumRef("70", "Downloads"), resolveAlbum("70", "Downloads"))
    }

    @Test
    fun `a cloud backup never becomes the album`() {
        // The provider sentinel rows written by earlier builds.
        assertEquals(
            MediaAlbumRef(UNGROUPED_ALBUM_ID, UNGROUPED_ALBUM_NAME),
            resolveAlbum("mydrive", "My Drive")
        )
        assertEquals(
            MediaAlbumRef(UNGROUPED_ALBUM_ID, UNGROUPED_ALBUM_NAME),
            resolveAlbum("MyDrive", "My Drive")
        )
    }

    @Test
    fun `the provider sentinel is also caught in other spellings`() {
        assertEquals(
            MediaAlbumRef(UNGROUPED_ALBUM_ID, UNGROUPED_ALBUM_NAME),
            resolveAlbum("MYDRIVE", "My Drive")
        )
        assertEquals(
            MediaAlbumRef(UNGROUPED_ALBUM_ID, UNGROUPED_ALBUM_NAME),
            resolveAlbum(" mydrive ", "My Drive")
        )
    }

    @Test
    fun `a folder whose name merely resembles the provider keeps its identity`() {
        // The sentinel is the id "mydrive", with no space. A bucket whose id is its
        // display name and reads "My Drive" is a real folder and stays one.
        assertEquals(MediaAlbumRef("My Drive", "My Drive"), resolveAlbum("My Drive", "My Drive"))
    }

    @Test
    fun `an unknown folder is ungrouped rather than mislabelled`() {
        assertEquals(
            MediaAlbumRef(UNGROUPED_ALBUM_ID, UNGROUPED_ALBUM_NAME),
            resolveAlbum(null, null)
        )
        assertEquals(
            MediaAlbumRef(UNGROUPED_ALBUM_ID, UNGROUPED_ALBUM_NAME),
            resolveAlbum("", "")
        )
        assertEquals(
            MediaAlbumRef(UNGROUPED_ALBUM_ID, UNGROUPED_ALBUM_NAME),
            resolveAlbum("   ", "My Drive")
        )
    }

    @Test
    fun `a known folder without a display name falls back to the ungrouped label`() {
        assertEquals(MediaAlbumRef("12345", UNGROUPED_ALBUM_NAME), resolveAlbum("12345", ""))
        assertEquals(MediaAlbumRef("12345", UNGROUPED_ALBUM_NAME), resolveAlbum("12345", null))
    }

    @Test
    fun `resolving a library files cloud-only media under its real folder`() {
        val library = listOf(
            item(id = "img-1", albumId = "12345", albumName = "Camera"),
            item(id = "cloud-9", albumId = "mydrive", albumName = "My Drive"),
            item(id = "cloud-10", albumId = "", albumName = "")
        )

        val resolved = library.resolveAlbums()

        assertEquals("12345", resolved[0].albumId)
        assertEquals("Camera", resolved[0].albumName)
        assertEquals(UNGROUPED_ALBUM_ID, resolved[1].albumId)
        assertEquals(UNGROUPED_ALBUM_NAME, resolved[1].albumName)
        assertEquals(UNGROUPED_ALBUM_ID, resolved[2].albumId)
    }

    @Test
    fun `an already-correct item is not copied`() {
        val item = item(id = "img-1", albumId = "12345", albumName = "Camera")
        assertEquals(item, item.withAlbum(resolveAlbum(item.albumId, item.albumName)))
    }

    private fun item(id: String, albumId: String, albumName: String) = MediaItem(
        id = id,
        filename = "$id.jpg",
        type = MediaType.PHOTO,
        fileSizeBytes = 1_024L,
        capturedAtMillis = 1_700_000_000_000L,
        device = "Test device",
        resolution = "1080 x 1920",
        thumbnailSeed = id.hashCode(),
        albumId = albumId,
        albumName = albumName
    )
}
