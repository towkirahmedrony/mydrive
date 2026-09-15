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
    val cloudinaryPublicId: String? = null
)

class SyncStateStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(): Map<String, SyncRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, SyncRecord>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    fun write(records: Map<String, SyncRecord>) {
        preferences.edit()
            .putString(KEY_RECORDS, json.encodeToString(records))
            .apply()
    }

    @Synchronized
    fun readPaused(): Boolean = preferences.getBoolean(KEY_PAUSED, false)

    @Synchronized
    fun writePaused(paused: Boolean) {
        preferences.edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    companion object {
        private const val PREFERENCES = "local_sync_state"
        private const val KEY_RECORDS = "records"
        private const val KEY_PAUSED = "paused"
    }
}
