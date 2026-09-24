package com.mydrive.app.data.media

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCacheFreshnessTest {
    @Test
    fun `missing stamp is stale`() {
        val root = createTempDir()
        try {
            assertTrue(MediaCacheFreshness.isThumbnailStale(root, "user-a", "media-1", 256))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `fresh stamp is not stale and remains account scoped`() {
        val root = createTempDir()
        try {
            MediaCacheFreshness.markThumbnailFresh(root, "user-a", "media-1", 256)
            assertFalse(MediaCacheFreshness.isThumbnailStale(root, "user-a", "media-1", 256))
            assertTrue(MediaCacheFreshness.isThumbnailStale(root, "user-b", "media-1", 256))
            assertTrue(MediaCacheFreshness.thumbnailStamp(root, "user-a", "media-1", 256).path.contains("user-a"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `old stamp is stale`() {
        val root = createTempDir()
        try {
            val stamp = MediaCacheFreshness.thumbnailStamp(root, "user-a", "media-1", 256)
            stamp.parentFile?.mkdirs()
            stamp.writeText("")
            stamp.setLastModified(System.currentTimeMillis() - MediaCacheFreshness.THUMBNAIL_MAX_AGE_MS - 1L)
            assertTrue(MediaCacheFreshness.isThumbnailStale(root, "user-a", "media-1", 256))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempDir(): File = createTempDir(prefix = "media-cache-freshness-")
}
