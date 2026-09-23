package com.mydrive.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaCacheKeysTest {

    @Test
    fun sameUserSameMediaReusesIdentityAcrossUrlRotation() {
        val first = MediaCacheKeys.memoryKey(
            userId = "user-a",
            mediaId = "media-1",
            uri = "https://res.cloudinary.com/a/signed-old",
            variant = MediaCacheKeys.VARIANT_THUMBNAIL,
            sizePx = 256
        )
        val rotated = MediaCacheKeys.memoryKey(
            userId = "user-a",
            mediaId = "media-1",
            uri = "https://res.cloudinary.com/a/signed-new",
            variant = MediaCacheKeys.VARIANT_THUMBNAIL,
            sizePx = 256
        )
        assertEquals("user-a:media-1:thumbnail:256", first)
        assertEquals(first, rotated)
    }

    @Test
    fun sameMediaIdDoesNotCollideAcrossUsers() {
        val userA = MediaCacheKeys.memoryKey("user-a", "media-1", "https://cdn.example/a", MediaCacheKeys.VARIANT_ORIGINAL, 2048)
        val userB = MediaCacheKeys.memoryKey("user-b", "media-1", "https://cdn.example/a", MediaCacheKeys.VARIANT_ORIGINAL, 2048)
        assertNotEquals(userA, userB)
        assertEquals("user-a:media-1:original:2048", userA)
        assertEquals("user-b:media-1:original:2048", userB)
    }

    @Test
    fun remoteUriWithoutAccountIsBlocked() {
        val key = MediaCacheKeys.memoryKey(
            userId = null,
            mediaId = "media-1",
            uri = "https://cdn.example/private.jpg",
            variant = MediaCacheKeys.VARIANT_THUMBNAIL,
            sizePx = 256
        )
        assertEquals("blocked-remote", key)
    }

    @Test
    fun deviceContentUriStaysLocalAndUnscoped() {
        val key = MediaCacheKeys.memoryKey(
            userId = null,
            mediaId = null,
            uri = "content://media/external/images/media/12",
            variant = MediaCacheKeys.VARIANT_THUMBNAIL,
            sizePx = 256
        )
        assertEquals("device:content://media/external/images/media/12@thumbnail@256", key)
    }

    @Test
    fun diskPathsAreIsolatedByUser() {
        val root = File("/tmp")
        val a = MediaCacheKeys.thumbnailFile(root, "user-a", "media-1", 256)
        val b = MediaCacheKeys.thumbnailFile(root, "user-b", "media-1", 256)
        val originalA = MediaCacheKeys.originalFile(root, "user-a", "media-1")
        val originalB = MediaCacheKeys.originalFile(root, "user-b", "media-1")
        assertNotEquals(a.path, b.path)
        assertNotEquals(originalA.path, originalB.path)
        assertTrue(a.path.contains("media_thumbnails/user-a/"))
        assertTrue(b.path.contains("media_thumbnails/user-b/"))
        assertTrue(originalA.path.contains("media_originals/user-a/"))
        assertTrue(originalB.path.contains("media_originals/user-b/"))
        assertEquals(a.name, b.name)
    }
}
