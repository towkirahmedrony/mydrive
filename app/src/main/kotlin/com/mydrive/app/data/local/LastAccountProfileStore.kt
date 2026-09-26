package com.mydrive.app.data.local

import android.content.Context
import com.mydrive.app.data.auth.AuthUserProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The identity this device last established, kept on disk.
 *
 * The gallery is account-scoped, so rendering it needs to know *which* account to
 * render before anything can be read from the catalog — and asking Supabase that
 * question at launch is exactly the "spinner, then gallery" startup a Gallery app
 * must not have. This answers it locally, from a value the app itself wrote after
 * a confirmed sign-in.
 *
 * It grants nothing. It is a claim that [AuthRepository] hands to Supabase to
 * confirm, and it is dropped the moment the stored session turns out not to
 * exist, so a signed-out device cannot keep rendering a previous account.
 * Suspended accounts are never stored, because they must go through the real
 * check on every launch.
 */
class LastAccountProfileStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(): AuthUserProfile? {
        val raw = preferences.getString(KEY_PROFILE, null) ?: return null
        val cached = runCatching { json.decodeFromString<CachedAccountProfile>(raw) }.getOrNull()
            ?: return null
        if (cached.id.isBlank()) return null
        return AuthUserProfile(
            id = cached.id,
            email = cached.email,
            fullName = cached.fullName,
            role = cached.role,
            status = cached.status
        )
    }

    @Synchronized
    fun save(profile: AuthUserProfile) {
        if (profile.id.isBlank() || profile.isSuspended) return
        val encoded = runCatching {
            json.encodeToString(
                CachedAccountProfile(
                    id = profile.id,
                    email = profile.email,
                    fullName = profile.fullName,
                    role = profile.role,
                    status = profile.status
                )
            )
        }.getOrNull() ?: return
        preferences.edit().putString(KEY_PROFILE, encoded).apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_PROFILE).apply()
    }

    @Serializable
    private data class CachedAccountProfile(
        val id: String,
        val email: String = "",
        val fullName: String = "",
        val role: String = "",
        val status: String = "active"
    )

    private companion object {
        const val PREFERENCES = "last_account_profile"
        const val KEY_PROFILE = "profile"
    }
}
