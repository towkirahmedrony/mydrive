package com.mydrive.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDriveClientTest {

    @Test
    fun `stream url carries only ids`() {
        val url = MediaDriveClient.streamUrl(
            baseUrl = "https://project.supabase.co",
            mediaId = "8a1a2ca0-0b8e-4f1a-9f2e-1c3d4e5f6a7b",
            variant = MediaDriveClient.VARIANT_ORIGINAL
        )

        assertEquals(
            "https://project.supabase.co/functions/v1/media-drive" +
                "?media_id=8a1a2ca0-0b8e-4f1a-9f2e-1c3d4e5f6a7b&variant=original",
            url
        )
    }

    @Test
    fun `stream url tolerates a trailing slash in the project url`() {
        val url = MediaDriveClient.streamUrl(
            baseUrl = "https://project.supabase.co/",
            mediaId = "8a1a2ca0-0b8e-4f1a-9f2e-1c3d4e5f6a7b",
            variant = MediaDriveClient.VARIANT_THUMB
        )

        assertEquals(
            "https://project.supabase.co/functions/v1/media-drive" +
                "?media_id=8a1a2ca0-0b8e-4f1a-9f2e-1c3d4e5f6a7b&variant=thumb",
            url
        )
    }
}
