package com.mydrive.app.data.repository

import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.remote.dto.MediaAssetRow
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

sealed class HideMediaResult {
    data object Success : HideMediaResult()
    data object NotFound : HideMediaResult()
    data object Unauthorized : HideMediaResult()
    data object Failed : HideMediaResult()
}

class MediaAssetsRepository(
    private val client: SupabaseClient?,
    private val sessionProvider: AuthenticatedSessionProvider
) {

    suspend fun loadOwnerAssets(): List<MediaAssetRow> = withContext(Dispatchers.IO) {
        val supabase = client ?: return@withContext emptyList()
        val userId = sessionProvider.currentUserIdOrNull() ?: when (val prepared = sessionProvider.prepare()) {
            is PreparedAuth.Available -> prepared.userId
            else -> return@withContext emptyList()
        }
        try {
            supabase.from(TABLE)
                .select {
                    filter { eq("owner_id", userId) }
                }
                .decodeList<MediaAssetRow>()
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_LOAD_FAILED",
                message = "Failed to load media_assets visibility rows",
                throwable = error
            )
            emptyList()
        }
    }

    suspend fun hideFromLibrary(remoteMediaId: String): HideMediaResult =
        updateHiddenAt(remoteMediaId, Instant.now().toString())

    suspend fun unhideFromLibrary(remoteMediaId: String): HideMediaResult =
        updateHiddenAt(remoteMediaId, hiddenAt = null)

    suspend fun hideMatchingAsset(
        remoteMediaId: String?,
        localMediaId: Long?,
        clientUploadId: String?
    ): HideMediaResult {
        val resolvedId = resolveRemoteId(remoteMediaId, localMediaId, clientUploadId)
            ?: return HideMediaResult.NotFound
        return hideFromLibrary(resolvedId)
    }

    suspend fun unhideMatchingAsset(
        remoteMediaId: String?,
        localMediaId: Long?,
        clientUploadId: String?
    ): HideMediaResult {
        val resolvedId = resolveRemoteId(remoteMediaId, localMediaId, clientUploadId)
            ?: return HideMediaResult.NotFound
        return unhideFromLibrary(resolvedId)
    }

    private suspend fun resolveRemoteId(
        remoteMediaId: String?,
        localMediaId: Long?,
        clientUploadId: String?
    ): String? {
        if (!remoteMediaId.isNullOrBlank()) return remoteMediaId
        val assets = loadOwnerAssets()
        if (!clientUploadId.isNullOrBlank()) {
            assets.firstOrNull { it.clientUploadId == clientUploadId }?.id?.let { return it }
        }
        if (localMediaId != null && localMediaId > 0L) {
            assets.firstOrNull { it.localMediaId == localMediaId }?.id?.let { return it }
        }
        return null
    }

    private suspend fun updateHiddenAt(remoteMediaId: String, hiddenAt: String?): HideMediaResult =
        withContext(Dispatchers.IO) {
            val supabase = client ?: return@withContext HideMediaResult.Failed
            val prepared = sessionProvider.prepare()
            val userId = when (prepared) {
                is PreparedAuth.Available -> prepared.userId
                PreparedAuth.SignedOut -> return@withContext HideMediaResult.Unauthorized
                PreparedAuth.NetworkError -> return@withContext HideMediaResult.Failed
            }
            if (remoteMediaId.isBlank()) return@withContext HideMediaResult.NotFound
            try {
                val payload = buildJsonObject {
                    if (hiddenAt == null) {
                        put("user_hidden_at", JsonNull)
                    } else {
                        put("user_hidden_at", hiddenAt)
                    }
                }
                supabase.from(TABLE).update(payload) {
                    filter {
                        eq("id", remoteMediaId)
                        eq("owner_id", userId)
                    }
                }
                DeveloperLogger.info(
                    category = LogCategory.DATABASE,
                    event = if (hiddenAt == null) "MEDIA_LIBRARY_UNHIDDEN" else "MEDIA_LIBRARY_HIDDEN",
                    message = if (hiddenAt == null) {
                        "Cleared user_hidden_at without touching archive or status"
                    } else {
                        "Set user_hidden_at without touching archive, status, or deleted_at"
                    },
                    metadata = mapOf(
                        "remote_media_id" to remoteMediaId,
                        "user_hidden_at" to hiddenAt
                    )
                )
                HideMediaResult.Success
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.DATABASE,
                    event = "MEDIA_LIBRARY_HIDE_FAILED",
                    message = "Failed to update user_hidden_at",
                    throwable = error,
                    metadata = mapOf("remote_media_id" to remoteMediaId)
                )
                HideMediaResult.Failed
            }
        }

    companion object {
        private const val TABLE = "media_assets"
    }
}
