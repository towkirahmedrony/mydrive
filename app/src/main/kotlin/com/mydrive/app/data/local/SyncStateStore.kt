package com.mydrive.app.data.local

import android.content.Context
import com.mydrive.app.data.model.BackupState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SyncRecord(
    val state: String = BackupState.NOT_STARTED.name,
    val errorMessage: String? = null,
    val queuedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val cloudinaryAssetId: String? = null,
    val cloudinaryPublicId: String? = null,
    val cloudinarySecureUrl: String? = null,
    val cloudinaryVersion: Long? = null,
    val cloudinaryFormat: String? = null,
    val cloudinaryResourceType: String? = null,
    val clientUploadId: String? = null,
    val remoteMediaId: String? = null,
    val ownerUserId: String? = null
)

/**
 * The cloud identity of media that `media_assets` already holds.
 *
 * Adopted from the server rather than generated locally: a queue record created for
 * media that is already in the cloud must not be given a brand-new upload identity,
 * or finalizing it would look like a different upload and could duplicate the
 * record.
 */
data class CloudBackedUpIdentity(
    val remoteMediaId: String,
    val clientUploadId: String? = null
)

class SyncStateStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var ownerUserId: String? = null

    @Synchronized
    fun bindUser(userId: String?) {
        ownerUserId = userId?.takeIf { it.isNotBlank() }
        migrateOwnedLegacyIfNeeded()
    }

    @Synchronized
    fun read(): Map<String, SyncRecord> {
        val userId = ownerUserId ?: return emptyMap()
        val raw = preferences.getString(UserStoreKeys.syncRecords(userId), null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, SyncRecord>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun write(records: Map<String, SyncRecord>) {
        val userId = ownerUserId ?: return
        preferences.edit()
            .putString(UserStoreKeys.syncRecords(userId), json.encodeToString(records))
            .apply()
    }

    @Synchronized
    fun readPaused(): Boolean {
        val userId = ownerUserId ?: return false
        return preferences.getBoolean(UserStoreKeys.syncPaused(userId), false)
    }

    @Synchronized
    fun writePaused(paused: Boolean) {
        val userId = ownerUserId ?: return
        preferences.edit().putBoolean(UserStoreKeys.syncPaused(userId), paused).apply()
    }

    @Synchronized
    fun clearSession() {
        ownerUserId = null
    }

    private fun migrateOwnedLegacyIfNeeded() {
        val userId = ownerUserId ?: return
        if (preferences.contains(UserStoreKeys.syncRecords(userId))) return
        val legacyRaw = preferences.getString(KEY_RECORDS, null) ?: return
        val legacy = try {
            json.decodeFromString<Map<String, SyncRecord>>(legacyRaw)
        } catch (_: Exception) {
            return
        }
        val owned = legacy.filter { (_, record) ->
            record.ownerUserId.isNullOrBlank() || record.ownerUserId == userId
        }
        if (owned.isEmpty()) {
            preferences.edit().remove(KEY_RECORDS).remove(KEY_PAUSED).apply()
            return
        }
        val mixedOwners = owned.values.mapNotNull { it.ownerUserId?.takeIf(String::isNotBlank) }.toSet()
        if (mixedOwners.size > 1) {
            preferences.edit().remove(KEY_RECORDS).remove(KEY_PAUSED).apply()
            return
        }
        preferences.edit()
            .putString(UserStoreKeys.syncRecords(userId), json.encodeToString(owned))
            .remove(KEY_RECORDS)
            .remove(KEY_PAUSED)
            .apply()
    }

    companion object {
        private const val PREFERENCES = "local_sync_state"
        private const val KEY_RECORDS = "records"
        private const val KEY_PAUSED = "paused"
    }
}
