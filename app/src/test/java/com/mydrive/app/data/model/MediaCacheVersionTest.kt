package com.mydrive.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The change signal every media cache key is built from.
 *
 * It has to move when the bytes move and stay still when they do not — that is
 * what lets a thumbnail survive process death without surviving an edit.
 */
class MediaCacheVersionTest {

    private fun item(dateModifiedMillis: Long, fileSizeBytes: Long) = MediaItem(
        id = "img-1",
        filename = "img-1.jpg",
        type = MediaType.PHOTO,
        fileSizeBytes = fileSizeBytes,
        capturedAtMillis = 1_700_000_000_000L,
        device = "Test device",
        resolution = "1080 x 1920",
        thumbnailSeed = 1,
        albumId = "12345",
        albumName = "Camera",
        dateModifiedMillis = dateModifiedMillis
    )

    @Test
    fun `an untouched file keeps its version`() {
        assertEquals(item(1000L, 2048L).cacheVersion, item(1000L, 2048L).cacheVersion)
    }

    @Test
    fun `a modified file changes its version`() {
        assertNotEquals(item(1000L, 2048L).cacheVersion, item(2000L, 2048L).cacheVersion)
    }

    @Test
    fun `a resized file changes its version`() {
        assertNotEquals(item(1000L, 2048L).cacheVersion, item(1000L, 4096L).cacheVersion)
    }

    @Test
    fun `identity alone is not the version`() {
        // Two items with the same id but different bytes must never share a key.
        val first = item(1000L, 2048L)
        val second = item(1000L, 9192L)
        assertEquals(first.id, second.id)
        assertNotEquals(first.cacheVersion, second.cacheVersion)
    }
}
