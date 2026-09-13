package com.mydrive.app.data.local

import android.content.Context
import com.mydrive.app.ui.navigation.AppDestination

class GalleryTabStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun startRoute(): String {
        return when (prefs.getString(KEY_TAB, TAB_PHOTOS)) {
            TAB_ALBUMS -> AppDestination.Albums.route
            else -> AppDestination.Photos.route
        }
    }

    fun saveRoute(route: String) {
        val tab = when (route) {
            AppDestination.Albums.route -> TAB_ALBUMS
            AppDestination.Photos.route -> TAB_PHOTOS
            else -> return
        }
        prefs.edit().putString(KEY_TAB, tab).apply()
    }

    companion object {
        private const val PREFS = "albums_gallery_tab"
        private const val KEY_TAB = "last_gallery_tab"
        const val TAB_PHOTOS = "PHOTOS"
        const val TAB_ALBUMS = "ALBUMS"
    }
}
