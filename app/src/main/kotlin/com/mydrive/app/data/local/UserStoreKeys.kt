package com.mydrive.app.data.local

object UserStoreKeys {
    fun hidden(userId: String) = "hidden_local_ids/$userId"
    fun cloud(userId: String) = "cloud_library_entries/$userId"
    fun favorites(userId: String) = "media_ids/$userId"
    fun syncRecords(userId: String) = "records/$userId"
    fun syncPaused(userId: String) = "paused/$userId"

    /**
     * Incremental `updated_at` synchronization cursor for one account.
     *
     * Account-scoped like every other persistent key here: a cursor describes how
     * far *that* account's cloud catalog has been synchronized, and reusing it
     * for another account would either skip or repeat that account's changes.
     */
    fun mediaSyncCursor(userId: String) = "media_sync_cursor/$userId"
}
