package com.mydrive.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFetchOrderTest {

    @Test
    fun cloudinaryUriPutsCloudinaryBeforeDrive() {
        val steps = MediaFetchOrder.steps(
            uriString = "https://res.cloudinary.com/demo/image/upload/v1/mydrive/user/file.jpg",
            hasStableMediaId = true
        )
        assertEquals(
            listOf(
                MediaFetchSource.DISK,
                MediaFetchSource.CLOUDINARY,
                MediaFetchSource.DRIVE
            ),
            steps
        )
        assertEquals(
            MediaFetchSource.CLOUDINARY,
            MediaFetchOrder.firstRemote(
                "https://res.cloudinary.com/demo/image/upload/v1/mydrive/user/file.jpg",
                hasStableMediaId = true
            )
        )
    }

    @Test
    fun driveIsLastResortWhenNoRemoteUri() {
        val steps = MediaFetchOrder.steps(
            uriString = "",
            hasStableMediaId = true
        )
        assertEquals(
            listOf(MediaFetchSource.DISK, MediaFetchSource.DRIVE),
            steps
        )
        assertEquals(MediaFetchSource.DRIVE, MediaFetchOrder.firstRemote("", hasStableMediaId = true))
    }

    @Test
    fun localContentUriNeverStartsWithDrive() {
        val steps = MediaFetchOrder.steps(
            uriString = "content://media/external/images/media/12",
            hasStableMediaId = true
        )
        assertEquals(
            listOf(
                MediaFetchSource.DISK,
                MediaFetchSource.LOCAL,
                MediaFetchSource.DRIVE
            ),
            steps
        )
        assertEquals(
            MediaFetchSource.DRIVE,
            MediaFetchOrder.firstRemote(
                "content://media/external/images/media/12",
                hasStableMediaId = true
            )
        )
    }

    @Test
    fun remoteWithoutMediaIdStopsAtCloudinary() {
        val steps = MediaFetchOrder.steps(
            uriString = "https://res.cloudinary.com/demo/image/upload/v1/file.jpg",
            hasStableMediaId = false
        )
        assertEquals(
            listOf(MediaFetchSource.DISK, MediaFetchSource.CLOUDINARY),
            steps
        )
        assertNull(MediaFetchOrder.firstRemote("", hasStableMediaId = false))
    }

    @Test
    fun localFileUriIsTriedBeforeDrive() {
        val steps = MediaFetchOrder.steps(
            uriString = "file:///storage/emulated/0/DCIM/photo.jpg",
            hasStableMediaId = true
        )
        assertEquals(MediaFetchSource.LOCAL, steps[1])
        assertEquals(MediaFetchSource.DRIVE, steps.last())
    }

    @Test
    fun cloudPreviewIsTriedBeforeDriveForADeviceUri() {
        val steps = MediaFetchOrder.steps(
            uriString = "content://media/external/images/media/12",
            hasStableMediaId = true,
            previewUri = "https://res.cloudinary.com/demo/image/upload/v1/mydrive/user/file.jpg"
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
        assertEquals(
            MediaFetchSource.CLOUDINARY,
            MediaFetchOrder.firstRemote(
                "content://media/external/images/media/12",
                hasStableMediaId = true,
                previewUri = "https://res.cloudinary.com/demo/image/upload/v1/mydrive/user/file.jpg"
            )
        )
    }

    @Test
    fun aNonRemotePreviewNeverAddsACloudStep() {
        val steps = MediaFetchOrder.steps(
            uriString = "content://media/external/images/media/12",
            hasStableMediaId = true,
            previewUri = ""
        )
        assertEquals(
            listOf(
                MediaFetchSource.DISK,
                MediaFetchSource.LOCAL,
                MediaFetchSource.DRIVE
            ),
            steps
        )
    }
}
