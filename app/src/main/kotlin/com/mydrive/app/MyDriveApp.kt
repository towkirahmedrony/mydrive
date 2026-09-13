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
}
