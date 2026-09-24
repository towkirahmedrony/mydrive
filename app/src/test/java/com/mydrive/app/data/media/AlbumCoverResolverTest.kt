package com.mydrive.app.data.media

import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumCoverResolverTest {

    private val cloudUrl = "https://res.cloudinary.com/demo/image/upload/v1/mydrive/user/file.jpg"

    private fun item(
        id: String,
        capturedAtMillis: Long,
        uri: String = "",
        thumbnailUrl: String? = null,
        remoteMediaId: String? = null,
        originLocal: Boolean = true,
        type: MediaType = MediaType.PHOTO
    ) = MediaItem(
        id = id,
        filename = "$id.jpg",
        type = type,
        fileSizeBytes = 1_000L,
        capturedAtMillis = capturedAtMillis,
        device = "Pixel",
        resolution = "1000 x 1000",
        thumbnailSeed = id.hashCode(),
        uri = uri,
        remoteMediaId = remoteMediaId,
        thumbnailUrl = thumbnailUrl,
        originLocal = originLocal,
        albumId = "camera"
    )

    @Test
    fun `no items means no cover`() {
        assertNull(AlbumCoverResolver.select(emptyList()))
    }

    @Test
    fun `locally backed album keeps the newest local item as primary`() {
        val older = item("older", 1L, uri = "content://media/external/images/media/1")
        val newer = item(
            id = "newer",
            capturedAtMillis = 2L,
            uri = "content://media/external/images/media/2",
            thumbnailUrl = cloudUrl,
            remoteMediaId = "media-2"
        )

        val cover = AlbumCoverResolver.select(listOf(older, newer))!!

        assertEquals("content://media/external/images/media/2", cover.uri)
        assertEquals(cloudUrl, cover.previewUri)
        assertEquals("media-2", cover.remoteMediaId)
        assertEquals("newer".hashCode(), cover.seed)
    }

    @Test
    fun `stale local cover still carries the cloud fallback for the resolver`() {
        // The MediaStore row of the preferred cover was removed externally; the
        // backup is still readable. The device URI stays primary, but Cloudinary
        // and Drive must remain reachable behind it.
        val cover = AlbumCoverResolver.coverOf(
            item(
                id = "gone",
                capturedAtMillis = 5L,
                uri = "content://media/external/images/media/77",
                thumbnailUrl = cloudUrl,
                remoteMediaId = "media-77"
            )
        )

        assertEquals("content://media/external/images/media/77", cover.uri)
        assertEquals(cloudUrl, cover.previewUri)
        assertEquals("media-77", cover.remoteMediaId)

        val ordered = MediaFetchOrder.steps(
            uriString = cover.uri,
            hasStableMediaId = true,
            previewUri = cover.previewUri
        )
        assertEquals(
            listOf(
                MediaFetchSource.DISK,
                MediaFetchSource.LOCAL,
                MediaFetchSource.CLOUDINARY,
                MediaFetchSource.DRIVE
            ),
            ordered
        )
    }

    @Test
    fun `cloud only album uses the remote preview as primary`() {
        val cover = AlbumCoverResolver.select(
            listOf(
                item(
                    id = "cloud-1",
                    capturedAtMillis = 9L,
                    uri = cloudUrl,
                    thumbnailUrl = cloudUrl,
                    remoteMediaId = "media-9",
                    originLocal = false
                )
            )
        )!!

        assertEquals(cloudUrl, cover.uri)
        assertNull(cover.previewUri)
        assertEquals("media-9", cover.remoteMediaId)
    }

    @Test
    fun `drive only album keeps a blank primary and resolves through the media id`() {
        val cover = AlbumCoverResolver.select(
            listOf(
                item(
                    id = "archived",
                    capturedAtMillis = 3L,
                    uri = "",
                    thumbnailUrl = null,
                    remoteMediaId = "media-archived",
                    originLocal = false
                )
            )
        )!!

        assertEquals("", cover.uri)
        assertNull(cover.previewUri)
        assertEquals("media-archived", cover.remoteMediaId)
        assertEquals(
            listOf(MediaFetchSource.DISK, MediaFetchSource.DRIVE),
            MediaFetchOrder.steps(cover.uri, hasStableMediaId = true, previewUri = cover.previewUri)
        )
    }

    @Test
    fun `a local cover wins over a newer cloud copy`() {
        val cover = AlbumCoverResolver.select(
            listOf(
                item("local-only", 1L, uri = "content://media/external/images/media/1"),
                item(
                    id = "cloud-newer",
                    capturedAtMillis = 8L,
                    uri = cloudUrl,
                    thumbnailUrl = cloudUrl,
                    remoteMediaId = "media-8",
                    originLocal = false
                )
            )
        )

        // The local copy is still the preferred cover while it exists; the cloud
        // item is only reached through the resolver when it does not.
        assertEquals("content://media/external/images/media/1", cover!!.uri)
        assertEquals("local-only".hashCode(), cover.seed)
    }

    @Test
    fun `cloud item becomes the cover when the album has no local copy left`() {
        val cover = AlbumCoverResolver.select(
            listOf(
                item(
                    id = "cloud-only",
                    capturedAtMillis = 4L,
                    uri = cloudUrl,
                    thumbnailUrl = cloudUrl,
                    remoteMediaId = "media-4",
                    originLocal = false
                )
            )
        )!!

        assertEquals(cloudUrl, cover.uri)
        assertEquals("media-4", cover.remoteMediaId)
        assertEquals(
            listOf(MediaFetchSource.DISK, MediaFetchSource.CLOUDINARY, MediaFetchSource.DRIVE),
            MediaFetchOrder.steps(cover.uri, hasStableMediaId = true, previewUri = cover.previewUri)
        )
    }

    @Test
    fun `media without any source keeps the album renderable`() {
        val cover = AlbumCoverResolver.select(
            listOf(item("nothing", 1L, uri = "", thumbnailUrl = null, remoteMediaId = null))
        )!!

        assertEquals("", cover.uri)
        assertNull(cover.previewUri)
        assertNull(cover.remoteMediaId)
        assertEquals(
            listOf(MediaFetchSource.DISK),
            MediaFetchOrder.steps(cover.uri, hasStableMediaId = false)
        )
    }
}
