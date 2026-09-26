package com.mydrive.app.data.repository

import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.media.CloudBackupCandidate
import com.mydrive.app.data.media.CloudBackupLookup
import com.mydrive.app.data.media.CloudBackupMatch
import com.mydrive.app.data.media.MediaAssetsPage
import com.mydrive.app.data.media.MediaLibraryPaging
import com.mydrive.app.data.media.MediaPageCursor
import com.mydrive.app.data.media.MediaSyncCursor
import com.mydrive.app.data.media.MediaSyncPage
import com.mydrive.app.data.media.RemoteFailureClassifier
import com.mydrive.app.data.media.RemoteMediaException
import com.mydrive.app.data.media.RemoteMediaFailure
import com.mydrive.app.data.remote.DurableMediaLifecycleClient
import com.mydrive.app.data.remote.MediaPurgeResult
import com.mydrive.app.data.remote.NetworkMonitor
import com.mydrive.app.data.remote.dto.DriveArchiveJobRow
import com.mydrive.app.data.remote.dto.MediaAssetRow
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

sealed class HideMediaResult {
    data object Success : HideMediaResult()
    data object NotFound : HideMediaResult()
    data object Unauthorized : HideMediaResult()
    data object Failed : HideMediaResult()
}

/**
 * Outcome of the server-side permanent deletion of media.
 *
 * [Partial] is not a success: some media were not purged, so the caller must not
 * report the deletion as complete.
 */
sealed class PurgeMediaResult {
    data class Success(val purgedIds: List<String>) : PurgeMediaResult()
    data class Partial(val purgedIds: List<String>, val requested: Int) : PurgeMediaResult()
    data object NotFound : PurgeMediaResult()
    data object Unauthorized : PurgeMediaResult()
    data object Failed : PurgeMediaResult()
}

class MediaAssetsRepository(
    private val client: SupabaseClient?,
    private val sessionProvider: AuthenticatedSessionProvider,
    private val network: NetworkMonitor,
    /**
     * Server-side media lifecycle (permanent deletion). Defaulted so the
     * repository still constructs in a build without a configured backend.
     */
    private val mediaLifecycleClient: DurableMediaLifecycleClient? = null
) {

    /**
     * One page of the authoritative cloud catalog.
     *
     * Throws [RemoteMediaException] when the catalog could not be read — offline,
     * timed out, unauthenticated or a backend error. An empty page is returned
     * *only* for a genuinely successful empty answer, because a caller may treat
     * an empty page as "these media_assets rows are gone".
     */
    suspend fun loadOwnerAssetsPage(
        cursor: MediaPageCursor? = null,
        pageSize: Int = MediaLibraryPaging.PAGE_SIZE
    ): MediaAssetsPage = withContext(Dispatchers.IO) {
        val supabase = client
        if (supabase == null) {
            // No backend configured (local build): there is no cloud catalog to
            // reconcile against, so an empty page is the truthful answer.
            return@withContext MediaAssetsPage(emptyList(), null, false)
        }
        val userId = currentUserId()
        if (userId.isNullOrBlank()) {
            // The session is absent or no longer usable. That is not proof that
            // the account owns no media, and must never blank the cloud catalog.
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_LOAD_SKIPPED",
                message = "Skipped media_assets load: no authenticated user id",
                metadata = mapOf("failure" to RemoteMediaFailure.UNAUTHORIZED.name)
            )
            throw RemoteMediaException(RemoteMediaFailure.UNAUTHORIZED)
        }
        try {
            val rows = remote {
                supabase.from(TABLE)
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
            }
            val annotated = annotateDriveArchives(supabase, rows)
            MediaAssetsPage(
                rows = annotated,
                nextCursor = MediaLibraryPaging.nextCursor(annotated, pageSize),
                hasNextPage = MediaLibraryPaging.hasNextPage(annotated.size, pageSize)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = RemoteFailureClassifier.classify(error)
            val message = "Failed to load paginated media_assets rows"
            val metadata = mapOf("failure" to failure.name)
            if (failure == RemoteMediaFailure.OFFLINE) {
                // Expected while the device has no network: not an error, but it
                // must still be visible that the catalog was left untouched.
                DeveloperLogger.warn(
                    category = LogCategory.DATABASE,
                    event = "MEDIA_ASSETS_LOAD_SKIPPED",
                    message = message,
                    throwable = error,
                    metadata = metadata
                )
            } else {
                DeveloperLogger.error(
                    category = LogCategory.DATABASE,
                    event = "MEDIA_ASSETS_LOAD_FAILED",
                    message = message,
                    throwable = error,
                    metadata = metadata
                )
            }
            throw error
        }
    }

    /**
     * One page of the cloud catalog that changed after [cursor].
     *
     * The `updated_at` + `id` pair is the cursor because `updated_at` is not
     * unique: several rows written in one transaction share the exact same
     * instant, and a bare timestamp would either skip the rest of that batch or
     * replay it forever. Ordering by the same pair ascending (with `id` breaking
     * the tie) makes the keyset stable, so a large batch of changes is walked
     * page by page without offsets, repeats or gaps.
     *
     * The library's visibility rule is deliberately **not** applied here — unlike
     * [loadOwnerAssetsPage], which serves the visible page. A row that just left
     * the library (trashed, restored, or moved on by the server lifecycle) has to
     * come back so the caller can apply the unchanged rule locally; see
     * [MediaLibraryPaging.isVisibleInCatalog].
     *
     * Throws [RemoteMediaException] exactly like [loadOwnerAssetsPage]: an empty
     * page is only ever a genuinely successful "nothing changed" answer, never a
     * failed request.
     */
    suspend fun loadChangedAssetsPage(
        cursor: MediaSyncCursor,
        pageSize: Int = MediaLibraryPaging.PAGE_SIZE
    ): MediaSyncPage = withContext(Dispatchers.IO) {
        val supabase = client
        if (supabase == null) {
            // No backend configured (local build): nothing can have changed.
            return@withContext MediaSyncPage(emptyList(), null, false)
        }
        val userId = currentUserId()
        if (userId.isNullOrBlank()) {
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_SYNC_SKIPPED",
                message = "Skipped incremental media_assets load: no authenticated user id",
                metadata = mapOf("failure" to RemoteMediaFailure.UNAUTHORIZED.name)
            )
            throw RemoteMediaException(RemoteMediaFailure.UNAUTHORIZED)
        }
        try {
            val rows = remote {
                supabase.from(TABLE)
                    .select(columns = Columns.raw(MediaLibraryPaging.LISTING_COLUMNS)) {
                        filter {
                            eq("owner_id", userId)
                            applySyncCursor(cursor)
                        }
                        order(column = "updated_at", order = Order.ASCENDING)
                        order(column = "id", order = Order.ASCENDING)
                        limit(pageSize.toLong())
                    }
                    .decodeList<MediaAssetRow>()
            }
            val annotated = annotateDriveArchives(supabase, rows)
            MediaSyncPage(
                rows = annotated,
                nextCursor = MediaLibraryPaging.nextSyncCursor(annotated, pageSize),
                hasNextPage = MediaLibraryPaging.hasNextPage(annotated.size, pageSize)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = RemoteFailureClassifier.classify(error)
            val message = "Failed to load incrementally changed media_assets rows"
            val metadata = mapOf(
                "failure" to failure.name,
                "cursor_updated_at" to cursor.updatedAt,
                "cursor_id" to cursor.id
            )
            if (failure == RemoteMediaFailure.OFFLINE) {
                DeveloperLogger.warn(
                    category = LogCategory.DATABASE,
                    event = "MEDIA_ASSETS_SYNC_SKIPPED",
                    message = message,
                    throwable = error,
                    metadata = metadata
                )
            } else {
                DeveloperLogger.error(
                    category = LogCategory.DATABASE,
                    event = "MEDIA_ASSETS_SYNC_FAILED",
                    message = message,
                    throwable = error,
                    metadata = metadata
                )
            }
            throw error
        }
    }

    /**
     * Runs one remote reconciliation call with an explicit budget.
     *
     * - offline: no request is attempted at all;
     * - timeout: surfaced as [RemoteMediaFailure.TIMEOUT], never as an empty
     *   result and never as caller cancellation;
     * - cancellation by the caller: rethrown untouched;
     * - anything else: wrapped with its classification so the caller can keep the
     *   last known catalog and report the right reason.
     */
    private suspend fun <T> remote(block: suspend () -> T): T {
        if (!network.isOnline()) {
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_OFFLINE_SKIPPED",
                message = "Skipped a remote catalog request while offline",
                metadata = mapOf("failure" to RemoteMediaFailure.OFFLINE.name)
            )
            throw RemoteMediaException(RemoteMediaFailure.OFFLINE)
        }
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { block() }
        } catch (timedOut: TimeoutCancellationException) {
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "MEDIA_ASSETS_REQUEST_TIMEOUT",
                message = "Remote catalog request exceeded its budget",
                throwable = timedOut,
                metadata = mapOf(
                    "failure" to RemoteMediaFailure.TIMEOUT.name,
                    "timeout_ms" to REQUEST_TIMEOUT_MS.toString()
                )
            )
            throw RemoteMediaException(RemoteMediaFailure.TIMEOUT, timedOut)
        } catch (cancelled: CancellationException) {
            // Caller cancellation (screen left, refresh superseded) is not a
            // request failure and must propagate as cancellation.
            throw cancelled
        } catch (error: Exception) {
            throw RemoteMediaException(RemoteFailureClassifier.classify(error), error)
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

    /**
     * The authoritative "is this already in the cloud?" answer for [candidates].
     *
     * This is deliberately NOT the paginated library read. `media_assets` may hold
     * far more rows than one page, and the backup gate used to be decided from the
     * rows a single page happened to carry — so with ~1.1k cloud rows and an 80-row
     * page, every media outside that page looked un-backed-up and was uploaded
     * again. This asks the question per identity instead, in batches, against the
     * server that is the source of truth.
     *
     * Returns [CloudBackupLookup.Unavailable] — never an empty [CloudBackupLookup.Checked]
     * — when the question could not be answered: no session, offline, a timeout, a
     * backend error, or one failed chunk out of several. A failed lookup must never
     * be read as "the cloud has nothing", because that is precisely the reading that
     * causes a duplicate upload.
     *
     * A build without a configured backend has no cloud catalog to reconcile
     * against, so an empty [CloudBackupLookup.Checked] is the truthful answer there.
     */
    suspend fun verifyCloudBackedUp(
        candidates: List<CloudBackupCandidate>
    ): CloudBackupLookup = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext CloudBackupLookup.Checked(emptyMap())
        val supabase = client ?: return@withContext CloudBackupLookup.Checked(emptyMap())
        val userId = currentUserId()
        if (userId.isNullOrBlank()) {
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "CLOUD_BACKUP_VERIFY_UNAVAILABLE",
                message = "Cloud-backed check skipped: no authenticated user id",
                metadata = mapOf("candidates" to candidates.size.toString(), "cause" to "no_session")
            )
            return@withContext CloudBackupLookup.Unavailable
        }
        if (!network.isOnline()) {
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "CLOUD_BACKUP_VERIFY_UNAVAILABLE",
                message = "Cloud-backed check skipped: offline",
                metadata = mapOf("candidates" to candidates.size.toString(), "cause" to "offline")
            )
            return@withContext CloudBackupLookup.Unavailable
        }
        try {
            val rows = ArrayList<MediaAssetRow>(candidates.size)
            for (chunk in candidates.distinctBy { it.localMediaId to it.clientUploadId }.chunked(LOOKUP_CHUNK_SIZE)) {
                val localIds = chunk.mapNotNull { it.localMediaId.takeIf { id -> id > 0L } }.distinct()
                if (localIds.isNotEmpty()) {
                    rows += cloudRowsWhere(supabase, userId) { isIn("local_media_id", localIds) }
                }
                // Also matched by the queue's stable upload identity, so a record
                // whose MediaStore id moved or disappeared is still recognised.
                val clientIds = chunk.mapNotNull { it.clientUploadId?.takeIf(String::isNotBlank) }.distinct()
                if (clientIds.isNotEmpty()) {
                    rows += cloudRowsWhere(supabase, userId) { isIn("client_upload_id", clientIds) }
                }
            }
            CloudBackupMatch.index(rows.distinctBy { it.id }, candidates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DeveloperLogger.warn(
                category = LogCategory.DATABASE,
                event = "CLOUD_BACKUP_VERIFY_UNAVAILABLE",
                message = "Cloud-backed check failed; uploads will wait instead of risking a duplicate",
                throwable = error,
                metadata = mapOf(
                    "candidates" to candidates.size.toString(),
                    "cause" to RemoteFailureClassifier.classify(error).name
                )
            )
            CloudBackupLookup.Unavailable
        }
    }

    /**
     * The rows owned by [userId] that satisfy [criteria], at any status.
     *
     * The status is deliberately NOT filtered here: the caller has to be able to
     * tell "the cloud holds nothing" (`MISSING`, the only case in which a re-upload
     * is legitimate) apart from "the cloud holds a row that is not finished"
     * (`PENDING`) and from a `DELETED` tombstone. Only `READY` counts as a backup —
     * see [CloudBackupMatch.isAuthoritativeBackup].
     */
    private suspend fun cloudRowsWhere(
        supabase: SupabaseClient,
        userId: String,
        criteria: PostgrestFilterBuilder.() -> Unit
    ): List<MediaAssetRow> = supabase.from(TABLE)
        .select(columns = Columns.raw(LOCAL_IDENTITY_COLUMNS)) {
            filter {
                eq("owner_id", userId)
                criteria()
            }
        }
        .decodeList<MediaAssetRow>()

    private suspend fun annotateDriveArchives(
        supabase: SupabaseClient,
        rows: List<MediaAssetRow>
    ): List<MediaAssetRow> {
        val candidates = rows.mapNotNull { row ->
            row.id.takeIf { MediaLibraryPaging.needsDriveArchiveLookup(row) }
        }
        if (candidates.isEmpty()) return rows
        return try {
            val completed = remote {
                supabase.from(TABLE_REPLICATION_JOBS)
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
            }
            if (completed.isEmpty()) rows else rows.map { row ->
                if (row.id in completed) row.copy(hasCompletedDriveArchive = true) else row
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Drive-archive annotations are an enhancement on top of a page that
            // already loaded, so a failure only drops the annotation.
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

    /**
     * Permanently deletes the given media through the server-side lifecycle.
     *
     * This is the ONLY Android-reachable path that makes a persistent thumbnail
     * eligible for deletion; moving media to Trash or restoring it never does.
     * The server authorizes the request against `media_assets.owner_id`, removes
     * the thumbnail, deletes the Cloudinary original only behind a verified Drive
     * archive, and tombstones the record.
     */
    suspend fun purgeMediaAssets(remoteMediaIds: Collection<String>): PurgeMediaResult {
        val ids = remoteMediaIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (ids.isEmpty()) return PurgeMediaResult.NotFound
        val lifecycle = mediaLifecycleClient
        if (lifecycle == null) {
            DeveloperLogger.error(
                category = LogCategory.THUMBNAIL,
                event = "PERMANENT_DELETE",
                message = "Permanent delete requested without a configured media lifecycle client",
                metadata = mapOf(
                    "media_count" to ids.size.toString(),
                    "server_purge_requested" to "false",
                    "server_purge_result" to "MISCONFIGURED"
                )
            )
            return PurgeMediaResult.Failed
        }
        return when (val result = lifecycle.purge(ids)) {
            is MediaPurgeResult.Success -> PurgeMediaResult.Success(result.purgedIds)
            is MediaPurgeResult.Partial ->
                PurgeMediaResult.Partial(result.purgedIds, result.requested)
            MediaPurgeResult.Unauthorized -> PurgeMediaResult.Unauthorized
            MediaPurgeResult.NetworkUnavailable,
            MediaPurgeResult.Timeout,
            MediaPurgeResult.Misconfigured,
            is MediaPurgeResult.Rejected,
            is MediaPurgeResult.Error -> PurgeMediaResult.Failed
        }
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
            // media_assets no longer grants UPDATE on user_hidden_at, so the
            // only supported way to move an item in or out of Trash is this
            // controlled RPC. Ownership is re-checked server-side against the
            // caller's JWT (owner, or admin).
            when (sessionProvider.prepare()) {
                is PreparedAuth.Available -> Unit
                PreparedAuth.SignedOut -> return@withContext HideMediaResult.Unauthorized
                PreparedAuth.NetworkError -> return@withContext HideMediaResult.Failed
            }
            if (remoteMediaId.isBlank()) return@withContext HideMediaResult.NotFound
            try {
                // The SECURITY DEFINER RPC returns the number of rows actually
                // updated (0 or 1) and raises "media_not_found" when the id is
                // missing or belongs to another user. A 2xx with no changed row
                // is therefore treated as a real failure, never as success.
                val rpcResult = supabase.postgrest.rpc(
                    function = RPC_SET_LIBRARY_VISIBILITY,
                    parameters = buildJsonObject {
                        put("p_media_id", remoteMediaId)
                        put("p_hidden", hiddenAt != null)
                    }
                )
                val affectedRows = rpcResult.data
                    .takeIf { it is kotlinx.serialization.json.JsonPrimitive }
                    ?.let { (it as kotlinx.serialization.json.JsonPrimitive).jsonPrimitive.intOrNull }
                if (affectedRows != 1) {
                    DeveloperLogger.error(
                        category = LogCategory.DATABASE,
                        event = "MEDIA_LIBRARY_HIDE_FAILED",
                        message = "Library visibility RPC affected $affectedRows row(s); expected exactly 1",
                        metadata = mapOf(
                            "remote_media_id" to remoteMediaId,
                            "affected_rows" to affectedRows.toString()
                        )
                    )
                    return@withContext HideMediaResult.Failed
                }
                DeveloperLogger.info(
                    category = LogCategory.DATABASE,
                    event = if (hiddenAt == null) "MEDIA_LIBRARY_UNHIDDEN" else "MEDIA_LIBRARY_HIDDEN",
                    message = if (hiddenAt == null) {
                        "Cleared user_hidden_at via set_media_library_visibility; archive and status untouched"
                    } else {
                        "Set user_hidden_at via set_media_library_visibility; archive, status and deleted_at untouched"
                    },
                    metadata = mapOf(
                        "remote_media_id" to remoteMediaId,
                        "user_hidden_at" to hiddenAt,
                        "affected_rows" to "1"
                    )
                )
                HideMediaResult.Success
            } catch (error: RestException) {
                // PostgREST surfaces the RPC's P0001 "media_not_found" as a
                // REST error body. Missing rows and foreign rows are the same
                // signal, so both map to NotFound instead of a silent success.
                val isMediaNotFound = error.message?.contains("media_not_found", ignoreCase = true) == true
                if (isMediaNotFound) {
                    DeveloperLogger.warn(
                        category = LogCategory.DATABASE,
                        event = "MEDIA_LIBRARY_NOT_FOUND",
                        message = "Library visibility RPC matched no owned row (missing or foreign media)",
                        metadata = mapOf(
                            "remote_media_id" to remoteMediaId,
                            "user_hidden_at" to hiddenAt
                        )
                    )
                    HideMediaResult.NotFound
                } else {
                    DeveloperLogger.error(
                        category = LogCategory.DATABASE,
                        event = "MEDIA_LIBRARY_HIDE_FAILED",
                        message = "Library visibility RPC failed",
                        throwable = error,
                        metadata = mapOf("remote_media_id" to remoteMediaId)
                    )
                    HideMediaResult.Failed
                }
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.DATABASE,
                    event = "MEDIA_LIBRARY_HIDE_FAILED",
                    message = "Failed to set library visibility for media",
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

    /**
     * Everything strictly after the cursor position, in the same
     * (`updated_at`, `id`) order the page is read in.
     *
     * The comparison stays on the server's `timestamptz` type — the exact
     * `updated_at` text is sent back verbatim — so no precision is lost between
     * the cursor and the row that produced it, and the `id` tie-break is what
     * carries the rest of a batch that shares one timestamp.
     */
    private fun PostgrestFilterBuilder.applySyncCursor(cursor: MediaSyncCursor) {
        or {
            gt("updated_at", cursor.updatedAt)
            and {
                eq("updated_at", cursor.updatedAt)
                gt("id", cursor.id)
            }
        }
    }

    companion object {
        /**
         * Budget for one remote catalog call. A hung backend must never hold the
         * refresh mutex — and therefore the gallery — for an unbounded time.
         */
        private const val REQUEST_TIMEOUT_MS = 20_000L

        private const val TABLE = "media_assets"
        private const val TABLE_REPLICATION_JOBS = "replication_jobs"
        private const val RPC_SET_LIBRARY_VISIBILITY = "set_media_library_visibility"

        /**
         * Enough of a `media_assets` row to decide "already backed up" and to adopt
         * its identity locally, and nothing more.
         */
        private const val LOCAL_IDENTITY_COLUMNS =
            "id,owner_id,local_media_id,file_name,mime_type,file_size,storage_url,thumbnail_url," +
                "storage_asset_id,client_upload_id,status,user_hidden_at,uploaded_at,created_at," +
                "updated_at,drive_archived_at,primary_cleanup_status,primary_deleted_at"

        /**
         * Identities per request. A device library is walked in a handful of
         * indexed `IN (…)` lookups instead of one request per media, which keeps a
         * reconciliation cheap enough to run on every launch.
         */
        private const val LOOKUP_CHUNK_SIZE = 100
    }
}
