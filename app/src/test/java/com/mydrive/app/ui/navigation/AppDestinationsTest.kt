package com.mydrive.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppDestinationsTest {

    @Test
    fun bottomNavigationContainsPhotosAlbumsSettingsOnly() {
        assertEquals(
            listOf(
                AppDestination.Photos,
                AppDestination.Albums,
                AppDestination.Settings
            ),
            bottomDestinations
        )
        assertFalse(bottomDestinations.contains(AppDestination.Sync))
    }

    @Test
    fun galleryDestinationsRemainPhotosAndAlbums() {
        assertEquals(
            listOf(AppDestination.Photos, AppDestination.Albums),
            galleryDestinations
        )
    }

    @Test
    fun syncRemainsANavigableRoute() {
        assertEquals("sync", AppDestination.Sync.route)
        assertEquals("settings", AppDestination.Settings.route)
    }
}
