package com.mydrive.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the `No content provider: https://res.cloudinary.com/...`
 * failure: a Cloudinary delivery URL was resolved as an item's `uri` and handed to
 * `ContentResolver` as the upload body.
 *
 * The classification is what stops that happening, so these cases are the guard:
 * no remote source may ever be reported as an upload source.
 */
class MediaSourceKindTest {

    @Test
    fun `cloudinary delivery url is remote and never an upload source`() {
        val kind = classifyMediaSource("https://res.cloudinary.com/demo/image/upload/v1/photo.jpg")
        assertEquals(MediaSourceKind.REMOTE, kind)
        assertFalse(kind.isLocalUploadSource)
    }

    @Test
    fun `plain http is remote too`() {
        assertFalse(classifyMediaSource("http://example.com/photo.jpg").isLocalUploadSource)
    }

    @Test
    fun `a remote url stays remote even when the item claims to be local`() {
        // A stale originLocal flag must never promote a cloud URL to an upload source.
        val kind = classifyMediaSource("https://res.cloudinary.com/demo/image/upload/photo.jpg", originLocal = true)
        assertEquals(MediaSourceKind.REMOTE, kind)
        assertFalse(kind.isLocalUploadSource)
    }

    @Test
    fun `mediastore content uri is a local upload source`() {
        val kind = classifyMediaSource("content://media/external/images/media/1000093882")
        assertEquals(MediaSourceKind.MEDIASTORE, kind)
        assertTrue(kind.isLocalUploadSource)
    }

    @Test
    fun `file scheme and bare absolute path are local upload sources`() {
        assertTrue(classifyMediaSource("file:///storage/emulated/0/DCIM/Camera/a.jpg").isLocalUploadSource)
        assertTrue(classifyMediaSource("/data/user/0/com.mydrive.app/files/a.jpg").isLocalUploadSource)
    }

    @Test
    fun `blank and unrecognised sources are never opened`() {
        listOf(null, "", "   ", "not a uri", "ftp://example.com/a.jpg").forEach { raw ->
            val kind = classifyMediaSource(raw)
            assertEquals("input=$raw", MediaSourceKind.UNKNOWN, kind)
            assertFalse(kind.isLocalUploadSource)
        }
    }

    @Test
    fun `a bare path is only local for an item that claims to be local`() {
        // The path belongs to whatever device produced it; a cloud-side item naming
        // one is not evidence about this device.
        assertEquals(MediaSourceKind.UNKNOWN, classifyMediaSource("/data/local/a.jpg", originLocal = false))
    }
}
