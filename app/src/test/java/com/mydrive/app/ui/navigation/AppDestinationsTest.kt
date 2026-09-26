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

    @Test
    fun vaultSettingsIsASeparateRouteFromTheMediaGallery() {
        assertEquals("settings/hidden-photos", AppDestination.HiddenPhotos.route)
        assertEquals("settings/hidden-photos/settings", AppDestination.VaultSettings.route)
        assertFalse(bottomDestinations.contains(AppDestination.HiddenPhotos))
        assertFalse(bottomDestinations.contains(AppDestination.VaultSettings))
        assertFalse(galleryDestinations.contains(AppDestination.VaultSettings))
    }

    @Test
    fun photoEditorIsANavigableRouteOutsideBottomAndGalleryTabs() {
        assertEquals("editor/{mediaId}", AppDestination.PhotoEditor.route)
        assertEquals("editor/photo%201", AppDestination.PhotoEditor.create("photo 1"))
        assertFalse(bottomDestinations.contains(AppDestination.PhotoEditor))
        assertFalse(galleryDestinations.contains(AppDestination.PhotoEditor))
        assertFalse(AppDestination.PhotoEditor.route.startsWith("settings/hidden-photos"))
    }

    @Test
    fun photoEditorIsReachedFromTheViewerAndPopsBackToTheSameMedia() {
        val mediaId = "abc 123"
        val viewer = AppDestination.MediaViewer.create(mediaId)
        val editor = AppDestination.PhotoEditor.create(mediaId)
        assertEquals("viewer/abc%20123", viewer)
        assertEquals("editor/abc%20123", editor)
        assertFalse(editor.startsWith("viewer"))
        assertFalse(viewer.startsWith("editor"))
        assertFalse(AppDestination.HiddenPhotos.route.contains("editor"))
        assertFalse(AppDestination.VaultSettings.route.contains("editor"))
    }
}
