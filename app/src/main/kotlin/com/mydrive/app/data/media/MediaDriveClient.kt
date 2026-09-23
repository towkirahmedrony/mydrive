package com.mydrive.app.data.media

import com.mydrive.app.BuildConfig
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal object MediaDriveClient {
    const val VARIANT_THUMB = "thumb"
    const val VARIANT_ORIGINAL = "original"

    /** An authenticated media-drive URL a media player can open on its own. */
    data class AuthorizedStream(val url: String, val headers: Map<String, String>)

    fun streamUrl(baseUrl: String, mediaId: String, variant: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/functions/v1/media-drive?media_id=$mediaId&variant=$variant"
    }

    /**
     * Builds a GET request the platform player can issue itself, so playback
     * is driven by byte ranges instead of a download into app memory. Only ids
     * travel in the URL; the session token stays in the request headers.
     */
    suspend fun authorizedStream(
        mediaId: String,
        variant: String,
        sessionProvider: AuthenticatedSessionProvider
    ): AuthorizedStream? {
        val accessToken = when (val prepared = sessionProvider.prepare(forceRefresh = false)) {
            is PreparedAuth.Available -> prepared.accessToken
            else -> return null
        }
        return AuthorizedStream(
            url = streamUrl(BuildConfig.SUPABASE_URL, mediaId, variant),
            headers = mapOf(
                "apikey" to BuildConfig.SUPABASE_ANON_KEY,
                "Authorization" to "Bearer $accessToken"
            )
        )
    }

    suspend fun fetchBytes(
        mediaId: String,
        variant: String,
        sessionProvider: AuthenticatedSessionProvider,
        maxBytes: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): ByteArray? {
        val accessToken = when (val prepared = sessionProvider.prepare(forceRefresh = false)) {
            is PreparedAuth.Available -> prepared.accessToken
            else -> return null
        }
        val connection = try {
            (URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/media-drive")
                .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    outputStream.use { output ->
                        output.write(
                            buildJsonObject {
                                put("media_id", mediaId)
                                put("variant", variant)
                            }.toString().toByteArray(Charsets.UTF_8)
                        )
                    }
                }
        } catch (_: Exception) {
            return null
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBounded(maxBytes) }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return ByteArray(0)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
