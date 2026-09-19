package com.mydrive.app.debug

import android.content.Context
import com.mydrive.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeveloperModeStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(computeEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun isEnabled(): Boolean = _enabled.value

    fun enable() {
        preferences.edit().putBoolean(KEY_ENABLED, true).apply()
        _enabled.value = true
    }

    fun disable() {
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        _enabled.value = BuildConfig.DEBUG
    }

    private fun computeEnabled(): Boolean =
        BuildConfig.DEBUG || preferences.getBoolean(KEY_ENABLED, false)

    companion object {
        private const val PREFERENCES = "developer_console"
        private const val KEY_ENABLED = "developer_mode"
        const val MAX_EVENTS = 2_000
        const val MEMORY_EVENTS = 400
        const val RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
