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

/**
 * The durable identity of a media the user moved to Trash.
 *
 * Trash must survive the local copy becoming unindexable, so the identity is
 * recorded in full rather than left to be re-derived: `localId` is the gallery
 * handle, `localMediaId` is the MediaStore `_ID` it was deleted under, and the
 * cloud side is identified by `remoteMediaIds` and `clientUploadId`.
 *
 * This matters because the previous design deleted the cloud-index entry for the
 * hidden media (`hideLocal` → `removeCloud`), which destroyed the only link
 * between the deletion and that media's `media_assets` rows. Any cloud row whose
 * local copy was not in the MediaStore scan — an expired Android Trash entry, a
 * partial scan, a second install's row — then had nothing left to match against
 * and was composed straight back into Photos/Albums while the device copy was
 * still in Trash. That is the resurrection this record removes.
 */
@Serializable
data class TrashedIdentity(
    /** Gallery handle, e.g. `img-1000174387` (or a `cloud-…` tile id). */
    val localId: String,
    /** MediaStore `_ID` the media was deleted under, or 0 when unknown. */
    val localMediaId: Long = 0L,
    /** `media_assets.id` values known to belong to this media. */
    val remoteMediaIds: List<String> = emptyList(),
    /** The queue's stable upload identity, when the media has one. */
    val clientUploadId: String? = null,
    val trashedAtMillis: Long = 0L
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

    /**
     * The durable Trash identities for this account, keyed by [TrashedIdentity.localId].
     *
     * Present for every media the user has deleted and not restored, including ones
     * whose local copy is no longer in the MediaStore scan.
     */
    @Synchronized
    fun trashedIdentities(): Map<String, TrashedIdentity> {
        val userId = ownerUserId ?: return emptyMap()
        val raw = preferences.getString(UserStoreKeys.trashedIdentities(userId), null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, TrashedIdentity>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Records that the user moved a media to Trash, and hides it locally in the same
     * step.
     *
     * The local id goes into the hidden set (what the gallery filters on) and the
     * full identity into the Trash record (what makes the media's cloud rows
     * recognisable as deleted). Both are persisted before the cloud is asked
     * anything, so a process death between the two halves cannot lose the deletion.
     */
    @Synchronized
    fun markTrashed(identity: TrashedIdentity) {
        if (identity.localId.isBlank()) return
        val userId = ownerUserId ?: return
        val nextHidden = HashSet(hiddenLocalIds())
        nextHidden += identity.localId
        val nextTrashed = HashMap(trashedIdentities())
        val previous = nextTrashed[identity.localId]
        nextTrashed[identity.localId] = if (previous == null) {
            identity
        } else {
            // Merge: a repeated delete must not drop what an earlier one learned.
            previous.copy(
                localMediaId = identity.localMediaId.takeIf { it > 0L } ?: previous.localMediaId,
                remoteMediaIds = (previous.remoteMediaIds + identity.remoteMediaIds).distinct(),
                clientUploadId = identity.clientUploadId ?: previous.clientUploadId,
                trashedAtMillis = identity.trashedAtMillis.takeIf { it > 0L } ?: previous.trashedAtMillis
            )
        }
        preferences.edit()
            .putStringSet(UserStoreKeys.hidden(userId), nextHidden)
            .putString(UserStoreKeys.trashedIdentities(userId), json.encodeToString(nextTrashed))
            .apply()
        // The cloud index entry for a trashed media is not evidence of anything any
        // more; the Trash record above is what carries the identity from here on.
        removeCloud(identity.localId)
    }

    /**
     * Clears the Trash record for [localIds].
     *
     * Only an explicit Restore (or a permanent delete, which ends the lifecycle)
     * may do this — it is the one and only way a trashed media can become active
     * again.
     */
    @Synchronized
    fun forgetTrashedIdentities(localIds: Collection<String>) {
        if (localIds.isEmpty()) return
        val userId = ownerUserId ?: return
        val current = trashedIdentities()
        val remaining = current - localIds.toSet()
        if (remaining.size == current.size) return
        val nextHidden = HashSet(hiddenLocalIds()) - localIds.toSet()
        preferences.edit()
            .putStringSet(UserStoreKeys.hidden(userId), nextHidden)
            .putString(UserStoreKeys.trashedIdentities(userId), json.encodeToString(remaining))
            .apply()
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
