package com.mydrive.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

object SupabaseModule {

    /**
     * Transport budget for every call that goes through the Supabase client
     * (PostgREST catalog queries and token refresh).
     *
     * Without this a stalled connection is unbounded: a hung `media_assets`
     * request would hold the catalog's refresh mutex, leaving the gallery
     * spinning with no way out. The budget sits just above the catalog's own
     * `withTimeout` so that timeout is reported as a timeout, and this is the net
     * below it for calls that are not wrapped. Uploads and finalize use their own
     * HttpURLConnection paths and are unaffected.
     */
    private const val REQUEST_TIMEOUT_MS = 25_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SOCKET_TIMEOUT_MS = 25_000L

    fun create(): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = SupabaseConfig.url,
            supabaseKey = SupabaseConfig.anonKey
        ) {
            defaultSerializer = KotlinXSerializer(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                    isLenient = true
                }
            )
            httpEngine = OkHttp.create()
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            install(Auth) {
                autoLoadFromStorage = true
                autoSaveToStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
        }
    }
}
