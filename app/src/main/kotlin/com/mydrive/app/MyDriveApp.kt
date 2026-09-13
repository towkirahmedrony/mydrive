package com.mydrive.app

import android.app.Application
import com.mydrive.app.data.local.FavoritesStore
import com.mydrive.app.data.local.GalleryTabStore
import com.mydrive.app.data.media.MediaPermissions
import com.mydrive.app.data.media.MediaStoreDataSource
import com.mydrive.app.data.repository.MediaRepository

class MyDriveApp : Application() {
    val galleryTabStore: GalleryTabStore by lazy { GalleryTabStore(this) }

    val mediaRepository: MediaRepository by lazy {
        MediaRepository(
            mediaStore = MediaStoreDataSource(this),
            favorites = FavoritesStore(this),
            permissions = MediaPermissions(this)
        )
    }

    companion object {
        const val SUPABASE_URL = "https://gpiuxcdjmrzcouhjapcs.supabase.co"
        const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdwaXV4Y2RqbXJ6Y291aGphcGNzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkyMjY1MTMsImV4cCI6MjEwNDgwMjUxM30.Tm6IPdc5mlSH0J76mbmXut4nd33JnyAG982o30sOu2A"
    }
}
