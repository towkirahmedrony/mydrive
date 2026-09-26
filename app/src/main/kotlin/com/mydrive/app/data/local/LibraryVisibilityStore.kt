package com.mydrive.app.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CloudLibraryEntry(
    val localId: String,
    val remoteMediaId: String,
    val uri: String,
    val thumbnailUrl: String? = null,
    val filename: String,
    val mimeType: String = "",
    val fileSizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
    val capturedAtMillis: Long = 0L,
    /**
     * The media's own device folder. Defaults to ungrouped, never to the storage
     * provider: this entry is the album metadata a cloud-only item keeps after its
     * local copy is gone, and "My Drive" is where the media is stored, not a
     * folder the user put it in. Entries written by an earlier build carry
     * `"mydrive"` and are mapped to the ungrouped album on read.
     */
    val albumId: String = "",
    val albumName: String = "",
    val type: String = "PHOTO",
    /**
     * The ORIGINAL's cloud URL, kept apart from [thumbnailUrl] (the persistent
     * thumbnail). Nullable with a default, so entries persisted by an earlier
     * build still decode; they simply carry no original and full resolution falls
     * back to the Drive copy.
     */
    val originalUrl: String? = null
)

class LibraryVisibilityStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var ownerUserId: String? = null

    @Synchronized
    fun bindUser(userId: String?) {
        ownerUserId = userId?.takeIf { it.isNotBlank() }
        dropUnscopedLegacy()
    }

    @Synchronized
    fun hiddenLocalIds(): Set<String> {
        val userId = ownerUserId ?: return emptySet()
        return preferences.getStringSet(UserStoreKeys.hidden(userId), emptySet()).orEmpty().toSet()
    }

    @Synchronized
    fun isHidden(localId: String): Boolean = hiddenLocalIds().contains(localId)

    @Synchronized
    fun hideLocal(localId: String) {
        val userId = ownerUserId ?: return
        val next = HashSet(hiddenLocalIds())
        if (next.add(localId)) {
            preferences.edit().putStringSet(UserStoreKeys.hidden(userId), next).apply()
        }
        removeCloud(localId)
    }

    @Synchronized
    fun unhideLocal(localId: String) {
        val userId = ownerUserId ?: return
        val next = HashSet(hiddenLocalIds())
        if (next.remove(localId)) {
            preferences.edit().putStringSet(UserStoreKeys.hidden(userId), next).apply()
        }
    }

    @Synchronized
    fun replaceHidden(ids: Set<String>) {
        val userId = ownerUserId ?: return
        preferences.edit().putStringSet(UserStoreKeys.hidden(userId), HashSet(ids)).apply()
    }

    @Synchronized
    fun cloudEntries(): Map<String, CloudLibraryEntry> {
        val userId = ownerUserId ?: return emptyMap()
        val raw = preferences.getString(UserStoreKeys.cloud(userId), null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, CloudLibraryEntry>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun cloudEntry(localId: String): CloudLibraryEntry? = cloudEntries()[localId]

    @Synchronized
    fun putCloud(entry: CloudLibraryEntry) {
        val next = HashMap(cloudEntries())
        next[entry.localId] = entry
        writeCloud(next)
    }

    @Synchronized
    fun removeCloud(localId: String) {
        val current = cloudEntries()
        if (localId !in current) return
        writeCloud(current - localId)
    }

    @Synchronized
    fun replaceCloud(entries: Map<String, CloudLibraryEntry>) {
        writeCloud(entries)
    }

    /**
     * Local ids whose Move-to-Trash succeeded on the device but whose My Drive
     * (cloud) half was not confirmed.
     *
     * The two halves are allowed to fail independently. The local trash is
     * authoritative for this device's gallery and is never rolled back, so the
     * media disappears from Photos/Albums immediately while the cloud operation
     * waits; this set is what makes it wait *durably*, including across a process
     * death between the two halves.
     */
    @Synchronized
    fun pendingCloudTrash(): Set<String> {
        val userId = ownerUserId ?: return emptySet()
        return preferences.getStringSet(UserStoreKeys.pendingCloudTrash(userId), emptySet())
            .orEmpty()
            .toSet()
    }

    @Synchronized
    fun addPendingCloudTrash(localId: String) {
        if (localId.isBlank()) return
        val userId = ownerUserId ?: return
        val next = HashSet(pendingCloudTrash())
        if (next.add(localId)) {
            preferences.edit().putStringSet(UserStoreKeys.pendingCloudTrash(userId), next).apply()
        }
    }

    @Synchronized
    fun removePendingCloudTrash(localIds: Collection<String>) {
        if (localIds.isEmpty()) return
        val userId = ownerUserId ?: return
        val current = pendingCloudTrash()
        val next = current - localIds.toSet()
        if (next.size != current.size) {
            preferences.edit().putStringSet(UserStoreKeys.pendingCloudTrash(userId), next).apply()
        }
    }

    /**
     * The mirror of [pendingCloudTrash] for restores: local ids whose device-side
     * restore succeeded but whose My Drive (cloud) half was not confirmed.
     *
     * Kept separately from the trash set because the two are opposites. An action
     * clears the other's pending entry, so a photo restored and then trashed again
     * can never be dragged back by a stale retry.
     */
    @Synchronized
    fun pendingCloudRestore(): Set<String> {
        val userId = ownerUserId ?: return emptySet()
        return preferences.getStringSet(UserStoreKeys.pendingCloudRestore(userId), emptySet())
            .orEmpty()
            .toSet()
    }

    @Synchronized
    fun addPendingCloudRestore(localId: String) {
        if (localId.isBlank()) return
        val userId = ownerUserId ?: return
        val next = HashSet(pendingCloudRestore())
        if (next.add(localId)) {
            preferences.edit().putStringSet(UserStoreKeys.pendingCloudRestore(userId), next).apply()
        }
    }

    @Synchronized
    fun removePendingCloudRestore(localIds: Collection<String>) {
        if (localIds.isEmpty()) return
        val userId = ownerUserId ?: return
        val current = pendingCloudRestore()
        val next = current - localIds.toSet()
        if (next.size != current.size) {
            preferences.edit().putStringSet(UserStoreKeys.pendingCloudRestore(userId), next).apply()
        }
    }

    @Synchronized
    fun clearSession() {
        ownerUserId = null
    }

    private fun writeCloud(entries: Map<String, CloudLibraryEntry>) {
        val userId = ownerUserId ?: return
        preferences.edit()
            .putString(UserStoreKeys.cloud(userId), json.encodeToString(entries))
            .apply()
    }

    private fun dropUnscopedLegacy() {
        if (!preferences.contains(LEGACY_HIDDEN) && !preferences.contains(LEGACY_CLOUD)) return
        preferences.edit().remove(LEGACY_HIDDEN).remove(LEGACY_CLOUD).apply()
    }

    companion object {
        private const val PREFERENCES = "library_visibility"
        private const val LEGACY_HIDDEN = "hidden_local_ids"
        private const val LEGACY_CLOUD = "cloud_library_entries"
    }
}
