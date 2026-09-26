package com.mydrive.app.data.media

import com.mydrive.app.data.remote.dto.MediaAssetRow

/**
 * One local media item offered to the cloud-backed check.
 *
 * Carries the identity pair the project already uses —
 * `device_id + local_media_id + client_upload_id` — so the check can be answered
 * from `media_assets` without opening the media itself and without depending on
 * which catalog page happens to be loaded.
 */
data class CloudBackupCandidate(
    /** MediaStore `_ID`, or `0` when the local row is unknown. */
    val localMediaId: Long,
    /** The queue's stable upload identity, when it has one. */
    val clientUploadId: String? = null,
    /** Local file size in bytes, or `0` when unknown. */
    val fileSize: Long = 0L
)

/**
 * The outcome of an authoritative "does the cloud already own this media?" check
 * against `media_assets`.
 *
 * The distinction between [Checked] and [Unavailable] is the whole point: an empty
 * [Checked] means the server answered and holds nothing, while [Unavailable] means
 * the question was never answered. Only the first may be read as "not backed up",
 * because treating a failed lookup as an absent cloud copy is exactly what made
 * already-uploaded media upload again.
 */
sealed interface CloudBackupLookup {
    /**
     * The server answered.
     *
     * [rowsByClientUploadId] is consulted first: the client upload identity is a
     * UUID minted for one specific upload, so a `READY` row carrying it is that
     * upload and nothing else. `local_media_id` is the second key — a MediaStore
     * `_ID` is only unique per device and can be reused — so that path also checks
     * that the stored size still agrees with the local file.
     */
    data class Checked(
        val rowsByLocalMediaId: Map<Long, MediaAssetRow> = emptyMap(),
        val rowsByClientUploadId: Map<String, MediaAssetRow> = emptyMap(),
        /**
         * The status of every row the server returned for a candidate identity,
         * whatever that status is. Used only for logging, so a half-finished upload
         * is reported as `PENDING` rather than as an absent record.
         */
        val seenStatusByLocalMediaId: Map<Long, String> = emptyMap(),
        val seenStatusByClientUploadId: Map<String, String> = emptyMap()
    ) : CloudBackupLookup {

        /** The authoritative cloud row for [candidate], if the cloud holds one. */
        fun rowFor(candidate: CloudBackupCandidate): MediaAssetRow? {
            candidate.clientUploadId?.takeIf { it.isNotBlank() }
                ?.let { rowsByClientUploadId[it] }
                ?.let { return it }
            val localId = candidate.localMediaId
            if (localId <= 0L) return null
            return rowsByLocalMediaId[localId]
        }

        /** The status of any row seen for [candidate]: `UPLOADING` means PENDING. */
        fun statusOf(candidate: CloudBackupCandidate): String? {
            candidate.clientUploadId?.takeIf { it.isNotBlank() }
                ?.let { seenStatusByClientUploadId[it] }
                ?.let { return it }
            val localId = candidate.localMediaId
            if (localId <= 0L) return null
            return seenStatusByLocalMediaId[localId]
        }

        /** Whether the server holds any non-tombstone row for [candidate]. */
        fun hasAnyRecordFor(candidate: CloudBackupCandidate): Boolean = statusOf(candidate) != null
    }

    /** Offline, timed out, unauthenticated or a backend error: nothing was proven. */
    data object Unavailable : CloudBackupLookup
}

/**
 * Pure matching rules for the cloud-backed check, kept free of Android and network
 * dependencies so they can be tested directly.
 */
object CloudBackupMatch {

    /**
     * Whether [row] is an authoritative record that the cloud already holds this
     * media.
     *
     * `READY` is required: `DELETED` is a tombstone and proves nothing. A media in
     * the user's My Drive Trash (`user_hidden_at` set) is still `READY` and is
     * still backed up — re-uploading it would duplicate the cloud record.
     */
    fun isAuthoritativeBackup(row: MediaAssetRow): Boolean = row.status == "READY"

    /**
     * Whether [row]'s stored size agrees with the local file's size.
     *
     * `local_media_id` is a MediaStore `_ID`: it is stable for a given file on a
     * given device, but it can be reused by a *different* file after the media
     * store is rebuilt. Size is the cheap change signal this schema actually
     * carries (`sha256_hash` is unpopulated in production), so a size mismatch is
     * treated as "content changed" — the one case where a new upload/version is
     * legitimate. Unknown sizes on either side cannot prove a change and agree.
     */
    fun sizeAgrees(row: MediaAssetRow, localFileSize: Long): Boolean {
        val stored = row.fileSize ?: 0L
        if (localFileSize <= 0L || stored <= 0L) return true
        return stored == localFileSize
    }

    /**
     * Indexes the authoritative rows of [rows] by the identities of [candidates].
     *
     * A local id may carry several rows from earlier installs of the same media;
     * any one authoritative row proves the backup, and the last one wins.
     */
    fun index(
        rows: List<MediaAssetRow>,
        candidates: List<CloudBackupCandidate>
    ): CloudBackupLookup.Checked {
        if (rows.isEmpty() || candidates.isEmpty()) return CloudBackupLookup.Checked()
        val sizes = HashMap<Long, Long>(candidates.size)
        val clientIds = HashSet<String>(candidates.size)
        for (candidate in candidates) {
            if (candidate.localMediaId > 0L) sizes[candidate.localMediaId] = candidate.fileSize
            candidate.clientUploadId?.takeIf { it.isNotBlank() }?.let { clientIds += it }
        }
        val byLocalId = HashMap<Long, MediaAssetRow>()
        val byClientId = HashMap<String, MediaAssetRow>()
        val seenLocalStatus = HashMap<Long, String>()
        val seenClientStatus = HashMap<String, String>()
        for (row in rows) {
            val status = row.status.orEmpty()
            // A tombstone proves nothing: it is not a backup and it is not a
            // pending upload either.
            if (status != "DELETED") {
                row.localMediaId?.takeIf { it > 0L && sizes.containsKey(it) }
                    ?.let { seenLocalStatus[it] = status }
                row.clientUploadId?.takeIf { it in clientIds }
                    ?.let { seenClientStatus[it] = status }
            }
            if (!isAuthoritativeBackup(row)) continue
            row.localMediaId?.takeIf { it > 0L }?.let { localId ->
                val expected = sizes[localId]
                if (expected != null && sizeAgrees(row, expected)) byLocalId[localId] = row
            }
            row.clientUploadId?.takeIf { it in clientIds }?.let { byClientId[it] = row }
        }
        return CloudBackupLookup.Checked(
            rowsByLocalMediaId = byLocalId,
            rowsByClientUploadId = byClientId,
            seenStatusByLocalMediaId = seenLocalStatus,
            seenStatusByClientUploadId = seenClientStatus
        )
    }
}
