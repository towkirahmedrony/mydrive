package com.mydrive.app.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaAccess {
    GRANTED,
    PARTIAL,
    NEEDS_REQUEST,
    DENIED
}

class MediaPermissions(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasBeenAsked(): Boolean = prefs.getBoolean(KEY_ASKED, false)

    fun markAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun access(): MediaAccess {
        return when {
            hasFullAccess() -> MediaAccess.GRANTED
            canReadMedia() -> MediaAccess.PARTIAL
            !hasBeenAsked() -> MediaAccess.NEEDS_REQUEST
            else -> MediaAccess.DENIED
        }
    }

    fun canReadMedia(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                (Build.VERSION.SDK_INT >= 34 &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasFullAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val PREFS = "albums_media_permissions"
        private const val KEY_ASKED = "asked"
    }
}
