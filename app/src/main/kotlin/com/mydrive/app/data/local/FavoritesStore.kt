package com.mydrive.app.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FavoritesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _ids = MutableStateFlow(readIds())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun contains(id: String): Boolean = _ids.value.contains(id)

    fun toggle(id: String) {
        _ids.update { current ->
            val next = if (id in current) current - id else current + id
            prefs.edit().putStringSet(KEY_IDS, HashSet(next)).apply()
            next
        }
    }

    private fun readIds(): Set<String> {
        return prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
    }

    companion object {
        private const val PREFS = "albums_favorites"
        private const val KEY_IDS = "media_ids"
    }
}
