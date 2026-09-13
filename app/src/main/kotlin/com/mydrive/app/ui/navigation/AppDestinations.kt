package com.mydrive.app.ui.navigation

import android.net.Uri

sealed class AppDestination(val route: String) {
    data object Photos : AppDestination("photos")
    data object Albums : AppDestination("albums")
    data object Favorites : AppDestination("favorites")
    data object Sync : AppDestination("sync")
    data object Settings : AppDestination("settings")
    data object MediaViewer : AppDestination("viewer/{mediaId}") {
        fun create(mediaId: String) = "viewer/$mediaId"
    }
    data object MediaDetails : AppDestination("media/{mediaId}") {
        fun create(mediaId: String) = "media/$mediaId"
    }
    data object AlbumDetail : AppDestination("album/{albumId}") {
        fun create(albumId: String) = "album/${Uri.encode(albumId)}"
    }
    data object TelegramSettings : AppDestination("settings/telegram")
}

val bottomDestinations = listOf(
    AppDestination.Photos,
    AppDestination.Albums,
    AppDestination.Sync,
    AppDestination.Settings
)

val galleryDestinations = listOf(
    AppDestination.Photos,
    AppDestination.Albums
)
