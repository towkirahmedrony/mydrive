package com.mydrive.app.data.remote

import com.mydrive.app.BuildConfig

object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL.trim()
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY.trim()

    val isConfigured: Boolean
        get() = url.startsWith("https://") && anonKey.isNotBlank()
}
