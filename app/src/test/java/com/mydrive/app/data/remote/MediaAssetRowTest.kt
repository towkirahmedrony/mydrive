package com.mydrive.app.data.remote

import com.mydrive.app.data.remote.dto.MediaAssetRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAssetRowTest {
    @Test
    fun readyDriveArchivedRowIsCloudAvailableWithoutCloudinaryUrls() {
        val row = MediaAssetRow(
            id = "media-1",
            status = "READY",
            driveArchivedAt = "2026-09-20T23:40:05Z"
        )
        assertTrue(row.isCloudAvailable)
    }

    @Test
    fun readyRowWithCompletedDriveAnnotationIsCloudAvailable() {
        val row = MediaAssetRow(
            id = "media-2",
            status = "READY",
            hasCompletedDriveArchive = true
        )
        assertTrue(row.isCloudAvailable)
    }

    @Test
    fun cleanedCloudinaryUrlIsNotOfferedAsLiveSourceWhenDriveArchiveExists() {
        val row = MediaAssetRow(
            id = "media-archived",
            status = "READY",
            storageUrl = "https://res.cloudinary.com/example/dead.jpg",
            storageAssetId = "asset-1",
            driveArchivedAt = "2026-09-20T23:40:05Z",
            primaryCleanupStatus = "cleanup_success",
            primaryDeletedAt = "2026-09-20T23:40:06Z"
        )

        assertTrue(row.isCloudAvailable)
        assertTrue(row.isPrimaryCleaned)
        assertTrue(row.cloudinarySourceUrl == null)
    }

    @Test
    fun nonReadyRowIsNotCloudAvailableWithoutPrimaryStorage() {
        val row = MediaAssetRow(
            id = "media-3",
            status = "PROCESSING",
            driveArchivedAt = "2026-09-20T23:40:05Z"
        )
        assertFalse(row.isCloudAvailable)
    }
}
