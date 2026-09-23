package com.mydrive.app.data.local

object UserStoreKeys {
    fun hidden(userId: String) = "hidden_local_ids/$userId"
    fun cloud(userId: String) = "cloud_library_entries/$userId"
    fun favorites(userId: String) = "media_ids/$userId"
    fun syncRecords(userId: String) = "records/$userId"
    fun syncPaused(userId: String) = "paused/$userId"
}
