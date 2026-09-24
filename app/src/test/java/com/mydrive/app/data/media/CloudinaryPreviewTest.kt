package com.mydrive.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudinaryPreviewTest {

    private val imageUrl = "https://res.cloudinary.com/demo/image/upload/v1/mydrive/user/file.jpg"
    private val videoUrl = "https://res.cloudinary.com/demo/video/upload/v1/mydrive/user/clip.mp4"

    @Test
    fun `image delivery url gains a size bounded transformation`() {
        assertEquals(
            "https://res.cloudinary.com/demo/image/upload/w_256,h_256,c_fill,q_auto/" +
                "v1/mydrive/user/file.jpg",
            CloudinaryPreview.previewUrl(imageUrl, 256)
        )
    }

    @Test
    fun `requested size is honoured and clamped`() {
        assertTrue(CloudinaryPreview.previewUrl(imageUrl, 144)!!.contains("w_144,h_144,c_fill,q_auto/"))
        assertTrue(CloudinaryPreview.previewUrl(imageUrl, 40)!!.contains("w_64,h_64,c_fill,q_auto/"))
        assertTrue(CloudinaryPreview.previewUrl(imageUrl, 9_999)!!.contains("w_1024,h_1024,c_fill,q_auto/"))
    }

    @Test
    fun `a video is previewed as a still frame instead of the full file`() {
        assertTrue(CloudinaryPreview.isVideoDeliveryUrl(videoUrl))
        assertEquals(
            "https://res.cloudinary.com/demo/video/upload/so_0,w_256,h_256,c_fill,q_auto/" +
                "v1/mydrive/user/clip.jpg",
            CloudinaryPreview.previewUrl(videoUrl, 256)
        )
    }

    @Test
    fun `a video with an unknown container is skipped`() {
        assertNull(
            CloudinaryPreview.previewUrl(
                "https://res.cloudinary.com/demo/video/upload/v1/mydrive/user/clip.bin",
                256
            )
        )
    }

    @Test
    fun `raw delivery url is never requested as a bitmap`() {
        assertNull(
            CloudinaryPreview.previewUrl(
                "https://res.cloudinary.com/demo/raw/upload/v1/mydrive/user/notes.txt",
                256
            )
        )
    }

    @Test
    fun `an already transformed url is left untouched`() {
        val transformed = "https://res.cloudinary.com/demo/image/upload/w_1000,q_auto/v1/mydrive/file.jpg"
        assertEquals(transformed, CloudinaryPreview.previewUrl(transformed, 256))
    }

    @Test
    fun `non cloudinary and blank urls are unchanged or null`() {
        assertNull(CloudinaryPreview.previewUrl("", 256))
        assertNull(CloudinaryPreview.previewUrl("content://media/external/images/media/12", 256))
        assertNull(CloudinaryPreview.previewUrl("file:///storage/emulated/0/DCIM/photo.jpg", 256))

        val otherHost = "https://project.supabase.co/storage/v1/object/public/media/file.jpg"
        assertEquals(otherHost, CloudinaryPreview.previewUrl(otherHost, 256))
    }

    @Test
    fun `preview stays deterministic so the same media never splits its cache entry`() {
        assertEquals(
            CloudinaryPreview.previewUrl(imageUrl, 256),
            CloudinaryPreview.previewUrl(imageUrl, 256)
        )
    }
}
