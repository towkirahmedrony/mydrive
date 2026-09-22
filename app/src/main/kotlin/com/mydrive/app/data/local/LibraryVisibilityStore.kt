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
    val albumId: String = "mydrive",
    val albumName: String = "My Drive",
    val type: String = "PHOTO"
)

class LibraryVisibilityStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun hiddenLocalIds(): Set<String> {
        return preferences.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
    }

    @Synchronized
    fun isHidden(localId: String): Boolean = hiddenLocalIds().contains(localId)

    @Synchronized
    fun hideLocal(localId: String) {
        val next = HashSet(hiddenLocalIds())
        if (next.add(localId)) {
            preferences.edit().putStringSet(KEY_HIDDEN, next).apply()
        }
        removeCloud(localId)
    }

    @Synchronized
    fun unhideLocal(localId: String) {
        val next = HashSet(hiddenLocalIds())
        if (next.remove(localId)) {
            preferences.edit().putStringSet(KEY_HIDDEN, next).apply()
        }
    }

    @Synchronized
    fun replaceHidden(ids: Set<String>) {
        preferences.edit().putStringSet(KEY_HIDDEN, HashSet(ids)).apply()
    }

    @Synchronized
    fun cloudEntries(): Map<String, CloudLibraryEntry> {
        val raw = preferences.getString(KEY_CLOUD, null) ?: return emptyMap()
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

    private fun writeCloud(entries: Map<String, CloudLibraryEntry>) {
        preferences.edit()
            .putString(KEY_CLOUD, json.encodeToString(entries))
            .apply()
    }

    companion object {
        private const val PREFERENCES = "library_visibility"
        private const val KEY_HIDDEN = "hidden_local_ids"
        private const val KEY_CLOUD = "cloud_library_entries"
    }
}
