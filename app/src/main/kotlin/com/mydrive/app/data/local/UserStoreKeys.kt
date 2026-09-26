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

    /**
     * Local ids whose device-side Move-to-Trash succeeded but whose My Drive
     * (cloud) half has not been confirmed yet.
     *
     * Account-scoped like the other per-user keys, and persisted rather than
     * in-memory so an app killed between the two halves resumes the cloud half
     * instead of leaving the device and the account permanently divergent.
     */
    fun pendingCloudTrash(userId: String) = "pending_cloud_trash/$userId"

    /**
     * Local ids whose device-side Restore succeeded but whose My Drive (cloud) half
     * has not been confirmed yet. The mirror of [pendingCloudTrash].
     */
    fun pendingCloudRestore(userId: String) = "pending_cloud_restore/$userId"

    /**
     * The durable identity of every media the user has moved to Trash.
     *
     * Keyed per account and kept until an explicit Restore (or a permanent delete),
     * because this is the record that lets a cloud row be recognised as belonging to
     * a deleted media when the local MediaStore copy is no longer indexable.
     */
    fun trashedIdentities(userId: String) = "trashed_identities/$userId"
}
