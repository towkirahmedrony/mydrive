package com.mydrive.app.data.model

import com.mydrive.app.data.media.MediaFetchOrder
import com.mydrive.app.data.media.MediaFetchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemDisplayUriTest {

    private val cloudUrl = "https://res.cloudinary.com/demo/image/upload/v1/mydrive/user/file.jpg"

    private fun item(
        uri: String = "",
        thumbnailUrl: String? = null,
        remoteMediaId: String? = null,
        originLocal: Boolean = true
    ) = MediaItem(
        id = "id",
        filename = "file.jpg",
        type = MediaType.PHOTO,
        fileSizeBytes = 1L,
        capturedAtMillis = 1L,
        device = "Pixel",
        resolution = "100 x 100",
        thumbnailSeed = 1,
        uri = uri,
        remoteMediaId = remoteMediaId,
        thumbnailUrl = thumbnailUrl,
        originLocal = originLocal
    )

    @Test
    fun driveOnlyBlankUriIsStillFetchableThroughMediaId() {
        val item = item(uri = "", thumbnailUrl = null, remoteMediaId = "media-archived", originLocal = false)
        assertEquals("", item.displayUri)
        assertEquals(
            listOf(MediaFetchSource.DISK, MediaFetchSource.DRIVE),
            MediaFetchOrder.steps(
                item.displayUri,
                hasStableMediaId = !item.remoteMediaId.isNullOrBlank(),
                previewUri = item.thumbnailUrl
            )
        )
    }

    @Test
    fun cloudOnlyItemSurfacesThePreviewAsDisplayUri() {
        val item = item(uri = "", thumbnailUrl = cloudUrl, remoteMediaId = "media-9", originLocal = false)
        assertEquals(cloudUrl, item.displayUri)
        assertEquals(
            listOf(MediaFetchSource.DISK, MediaFetchSource.CLOUDINARY, MediaFetchSource.DRIVE),
            MediaFetchOrder.steps(
                item.displayUri,
                hasStableMediaId = true,
                previewUri = item.thumbnailUrl
            )
        )
    }

    @Test
    fun localUriKeepsDevicePrimaryAndCloudPreviewBeforeDrive() {
        val item = item(
            uri = "content://media/external/images/media/12",
            thumbnailUrl = cloudUrl,
            remoteMediaId = "media-12",
            originLocal = true
        )
        assertEquals("content://media/external/images/media/12", item.displayUri)
        val steps = MediaFetchOrder.steps(
            item.displayUri,
            hasStableMediaId = true,
            previewUri = item.thumbnailUrl
        )
        assertEquals(
            listOf(
                MediaFetchSource.DISK,
                MediaFetchSource.LOCAL,
                MediaFetchSource.CLOUDINARY,
                MediaFetchSource.DRIVE
            ),
            steps
        )
        assertTrue(item.uri.isNotBlank())
    }
}
