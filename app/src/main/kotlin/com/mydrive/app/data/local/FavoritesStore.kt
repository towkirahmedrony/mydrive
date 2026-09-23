package com.mydrive.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FavoritesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var ownerUserId: String? = null

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    @Synchronized
    fun bindUser(userId: String?) {
        ownerUserId = userId?.takeIf { it.isNotBlank() }
        dropUnscopedLegacy()
        _ids.value = readIds()
    }

    fun contains(id: String): Boolean = _ids.value.contains(id)

    fun toggle(id: String) {
        val userId = ownerUserId ?: return
        _ids.update { current ->
            val next = if (id in current) current - id else current + id
            prefs.edit().putStringSet(UserStoreKeys.favorites(userId), HashSet(next)).apply()
            next
        }
    }

    /**
     * Drops any stored favorite whose media id is not present in [presentIds].
     * Used to clean up references to media that has been deleted from the device.
     * Only call this once a complete MediaStore load has succeeded, so that
     * temporarily unavailable media is never discarded.
     */
    fun retainAll(presentIds: Set<String>) {
        val userId = ownerUserId ?: return
        _ids.update { current ->
            val stale = current - presentIds
            if (stale.isEmpty()) {
                current
            } else {
                val next = current - stale
                prefs.edit().putStringSet(UserStoreKeys.favorites(userId), HashSet(next)).apply()
                next
            }
        }
    }

    @Synchronized
    fun clearSession() {
        ownerUserId = null
        _ids.value = emptySet()
    }

    private fun readIds(): Set<String> {
        val userId = ownerUserId ?: return emptySet()
        return prefs.getStringSet(UserStoreKeys.favorites(userId), emptySet()).orEmpty().toSet()
    }

    private fun dropUnscopedLegacy() {
        if (!prefs.contains(LEGACY_IDS)) return
        prefs.edit().remove(LEGACY_IDS).apply()
    }

    companion object {
        private const val PREFS = "albums_favorites"
        private const val LEGACY_IDS = "media_ids"
    }
}
