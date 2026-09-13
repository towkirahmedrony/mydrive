package com.mydrive.app.data.local

import android.content.Context
import java.util.UUID

class DeviceIdStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceUid(): String {
        val existing = prefs.getString(KEY_UID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UID, created).apply()
        return created
    }

    companion object {
        private const val PREFS = "albums_device"
        private const val KEY_UID = "device_uid"
    }
}
