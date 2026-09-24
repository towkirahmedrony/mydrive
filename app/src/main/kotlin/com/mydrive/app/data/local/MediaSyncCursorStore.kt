package com.mydrive.app.data.local

import android.content.Context
import com.mydrive.app.data.media.MediaLibraryPaging
import com.mydrive.app.data.media.MediaSyncCursor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted `updated_at` synchronization cursor, one per account.
 *
 * This is not a new database: it is one small value next to the other
 * per-account preferences (`SyncStateStore`, `LibraryVisibilityStore`,
 * `FavoritesStore`) and it is scoped by [UserStoreKeys.mediaSyncCursor], so
 * account A's position is unreachable while account B is bound.
 *
 * The cursor is only ever moved forward, and only by callers that have finished
 * processing the data they asked for. A legacy or unparseable value reads back as
 * `null`, which falls the caller back to the initial catalog load instead of
 * resuming from a position nobody can interpret.
 */
class MediaSyncCursorStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var ownerUserId: String? = null

    @Synchronized
    fun bindUser(userId: String?) {
        ownerUserId = userId?.takeIf { it.isNotBlank() }
    }

    /** @return this account's cursor, or `null` when there is none to resume from. */
    @Synchronized
    fun read(): MediaSyncCursor? {
        val userId = ownerUserId ?: return null
        val raw = preferences.getString(UserStoreKeys.mediaSyncCursor(userId), null) ?: return null
        return try {
            json.decodeFromString<MediaSyncCursor>(raw)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Moves the cursor to [candidate], but never backwards.
     *
     * A position older than (or equal to) the stored one is a no-op, so an
     * initial load that read already-synchronized rows, or a paging step that
     * saw an older slice of the catalog, cannot undo progress.
     *
     * @return the cursor in effect after the call.
     */
    @Synchronized
    fun advanceTo(candidate: MediaSyncCursor?): MediaSyncCursor? {
        val userId = ownerUserId ?: return null
        if (candidate == null) return read()
        val current = read()
        if (!MediaLibraryPaging.shouldAdvance(current, candidate)) return current
        preferences.edit()
            .putString(UserStoreKeys.mediaSyncCursor(userId), json.encodeToString(candidate))
            .apply()
        return candidate
    }

    /** Drops this account's cursor so the next synchronization is a full load. */
    @Synchronized
    fun clear() {
        val userId = ownerUserId ?: return
        preferences.edit().remove(UserStoreKeys.mediaSyncCursor(userId)).apply()
    }

    /**
     * Unbinds the account. The stored cursor stays on disk for the account that
     * owns it — sign-out must stop using it, not delete the other user's data.
     */
    @Synchronized
    fun clearSession() {
        ownerUserId = null
    }

    companion object {
        private const val PREFERENCES = "media_sync_cursor"
    }
}
