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
        // The stale original URL is never offered as a live source...
        assertTrue(row.originalCloudUrl == null)
        // ...and with no persistent thumbnail there is no Cloudinary candidate.
        assertTrue(row.cloudinarySourceUrl == null)
    }

    @Test
    fun cleanedOriginalWithPersistentThumbnailStaysDisplayable() {
        // The state the persistent-thumbnail lifecycle exists to support:
        // the Cloudinary ORIGINAL is gone (verified cleanup ran) but the media is
        // still READY and carries its own persistent thumbnail.
        val thumbnail =
            "https://res.cloudinary.com/example/image/upload/mydrive/u/thumbnails/media-4.jpg"
        val row = MediaAssetRow(
            id = "media-4",
            status = "READY",
            storageUrl = null,
            thumbnailUrl = thumbnail,
            driveArchivedAt = "2026-09-20T23:40:05Z",
            primaryCleanupStatus = "cleanup_success",
            primaryDeletedAt = "2026-09-20T23:40:06Z"
        )

        assertTrue(row.isPrimaryCleaned)
        assertTrue(row.isCloudAvailable)
        assertTrue(row.hasPersistentThumbnail)
        assertTrue(row.isThumbnailOnly)
        // The gallery renders from the thumbnail, NOT from the dead original.
        assertTrue(row.cloudinarySourceUrl == thumbnail)
        assertTrue(row.persistentThumbnailUrl == thumbnail)
        assertTrue(row.originalCloudUrl == null)
    }

    @Test
    fun aLiveOriginalIsStillOfferedAfterTheThumbnail() {
        val original = "https://res.cloudinary.com/example/image/upload/v1/mydrive/u/full.jpg"
        val thumbnail =
            "https://res.cloudinary.com/example/image/upload/mydrive/u/thumbnails/media-5.jpg"
        val row = MediaAssetRow(
            id = "media-5",
            status = "READY",
            storageUrl = original,
            thumbnailUrl = thumbnail
        )

        // The tile uses the thumbnail; the original remains available separately.
        assertTrue(row.cloudinarySourceUrl == thumbnail)
        assertTrue(row.persistentThumbnailUrl == thumbnail)
        assertTrue(row.originalCloudUrl == original)
        assertTrue(!row.isThumbnailOnly)
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
