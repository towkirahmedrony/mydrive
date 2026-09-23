package com.mydrive.app.data.repository

import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.media.MediaAlbumStats
import com.mydrive.app.data.media.MediaAssetsPage
import com.mydrive.app.data.media.MediaLibraryPaging
import com.mydrive.app.data.media.MediaPageCursor
import com.mydrive.app.data.remote.dto.DriveArchiveJobRow
import com.mydrive.app.data.remote.dto.MediaAssetRow
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
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

    suspend fun loadOwnerAssetsPage(
        cursor: MediaPageCursor? = null,
        pageSize: Int = MediaLibraryPaging.PAGE_SIZE
    ): MediaAssetsPage = withContext(Dispatchers.IO) {
        val supabase = client ?: return@withContext MediaAssetsPage(emptyList(), null, false)
        val userId = currentUserId() ?: return@withContext MediaAssetsPage(emptyList(), null, false)
        try {
            val rows = supabase.from(TABLE)
                .select(columns = Columns.raw(MediaLibraryPaging.LISTING_COLUMNS)) {
                    filter {
                        applyLibraryVisibility(userId)
                        applyKeyset(cursor)
                    }
                    order(column = "created_at", order = Order.DESCENDING)
                    order(column = "id", order = Order.DESCENDING)
                    limit(pageSize.toLong())
                }
                .decodeList<MediaAssetRow>()
            val annotated = annotateDriveArchives(supabase, rows)
            MediaAssetsPage(
                rows = annotated,
                nextCursor = MediaLibraryPaging.nextCursor(annotated, pageSize),
                hasNextPage = MediaLibraryPaging.hasNextPage(annotated.size, pageSize)
            )
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_LOAD_FAILED",
                message = "Failed to load paginated media_assets rows",
                throwable = error
            )
            throw error
        }
    }

    suspend fun loadCloudAlbumStats(): MediaAlbumStats = withContext(Dispatchers.IO) {
        val supabase = client ?: return@withContext MediaAlbumStats()
        val userId = currentUserId() ?: return@withContext MediaAlbumStats()
        try {
            val cloudOnly = supabase.from(TABLE)
                .select(columns = Columns.list("id")) {
                    filter {
                        applyLibraryVisibility(userId)
                        applyAvailability()
                        exact("local_media_id", null)
                    }
                    count(Count.EXACT)
                    limit(1)
                }
                .countOrNull()?.toInt() ?: 0
            val cover = supabase.from(TABLE)
                .select(columns = Columns.raw(MediaLibraryPaging.LISTING_COLUMNS)) {
                    filter {
                        applyLibraryVisibility(userId)
                        applyAvailability()
                        exact("local_media_id", null)
                    }
                    order(column = "created_at", order = Order.DESCENDING)
                    order(column = "id", order = Order.DESCENDING)
                    limit(1)
                }
                .decodeList<MediaAssetRow>()
                .firstOrNull()
            MediaAlbumStats(cloudOnlyCount = cloudOnly, cover = cover)
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_ALBUM_STATS_FAILED",
                message = "Failed to load media_assets album aggregation",
                throwable = error
            )
            MediaAlbumStats()
        }
    }

    suspend fun findMatchingAsset(
        remoteMediaId: String?,
        localMediaId: Long?,
        clientUploadId: String?
    ): MediaAssetRow? = withContext(Dispatchers.IO) {
        val supabase = client ?: return@withContext null
        val userId = currentUserId() ?: return@withContext null
        try {
            if (!remoteMediaId.isNullOrBlank()) {
                return@withContext supabase.from(TABLE)
                    .select(columns = Columns.raw(MediaLibraryPaging.LISTING_COLUMNS)) {
                        filter {
                            eq("owner_id", userId)
                            eq("id", remoteMediaId)
                        }
                        limit(1)
                    }
                    .decodeList<MediaAssetRow>()
                    .firstOrNull()
            }
            if (!clientUploadId.isNullOrBlank()) {
                supabase.from(TABLE)
                    .select(columns = Columns.raw(MediaLibraryPaging.LISTING_COLUMNS)) {
                        filter {
                            eq("owner_id", userId)
                            eq("client_upload_id", clientUploadId)
                        }
                        limit(1)
                    }
                    .decodeList<MediaAssetRow>()
                    .firstOrNull()?.let { return@withContext it }
            }
            if (localMediaId != null && localMediaId > 0L) {
                return@withContext supabase.from(TABLE)
                    .select(columns = Columns.raw(MediaLibraryPaging.LISTING_COLUMNS)) {
                        filter {
                            eq("owner_id", userId)
                            eq("local_media_id", localMediaId)
                        }
                        limit(1)
                    }
                    .decodeList<MediaAssetRow>()
                    .firstOrNull()
            }
            null
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_LOOKUP_FAILED",
                message = "Failed to resolve media_assets identity",
                throwable = error
            )
            null
        }
    }

    private suspend fun annotateDriveArchives(
        supabase: SupabaseClient,
        rows: List<MediaAssetRow>
    ): List<MediaAssetRow> {
        val candidates = rows.mapNotNull { row ->
            row.id.takeIf { MediaLibraryPaging.needsDriveArchiveLookup(row) }
        }
        if (candidates.isEmpty()) return rows
        return try {
            val completed = supabase.from(TABLE_REPLICATION_JOBS)
                .select(columns = Columns.list("media_id")) {
                    filter {
                        eq("destination_type", "google_drive")
                        eq("status", "COMPLETED")
                        isIn("media_id", candidates)
                    }
                }
                .decodeList<DriveArchiveJobRow>()
                .mapNotNull { it.mediaId.takeIf(String::isNotBlank) }
                .toSet()
            if (completed.isEmpty()) rows else rows.map { row ->
                if (row.id in completed) row.copy(hasCompletedDriveArchive = true) else row
            }
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.DATABASE,
                event = "DRIVE_ARCHIVE_LOAD_FAILED",
                message = "Failed to load completed Drive archive jobs",
                throwable = error
            )
            rows
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
        return findMatchingAsset(remoteMediaId, localMediaId, clientUploadId)?.id
    }

    private suspend fun currentUserId(): String? {
        sessionProvider.currentUserIdOrNull()?.let { return it }
        return when (val prepared = sessionProvider.prepare()) {
            is PreparedAuth.Available -> prepared.userId
            else -> null
        }
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

    private fun PostgrestFilterBuilder.applyLibraryVisibility(userId: String) {
        eq("owner_id", userId)
        eq("status", "READY")
        exact("user_hidden_at", null)
    }

    private fun PostgrestFilterBuilder.applyAvailability() {
        or {
            filterNot("storage_url", FilterOperator.IS, null)
            filterNot("thumbnail_url", FilterOperator.IS, null)
            filterNot("drive_archived_at", FilterOperator.IS, null)
        }
    }

    private fun PostgrestFilterBuilder.applyKeyset(cursor: MediaPageCursor?) {
        if (cursor == null) return
        or {
            lt("created_at", cursor.createdAt)
            and {
                eq("created_at", cursor.createdAt)
                lt("id", cursor.id)
            }
        }
    }

    companion object {
        private const val TABLE = "media_assets"
        private const val TABLE_REPLICATION_JOBS = "replication_jobs"
    }
}
