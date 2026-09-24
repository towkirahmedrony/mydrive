package com.mydrive.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class MediaAssetRow(
    val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("local_media_id") val localMediaId: Long? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("storage_url") val storageUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("storage_asset_id") val storageAssetId: String? = null,
    @SerialName("client_upload_id") val clientUploadId: String? = null,
    val status: String? = null,
    @SerialName("user_hidden_at") val userHiddenAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /**
     * Server-maintained last-write timestamp.
     *
     * This is the incremental synchronization cursor: a refresh asks for the rows
     * whose `updated_at` moved past the last successfully synchronized position.
     * It is not unique — rows written in the same transaction share one value —
     * so it is always paired with [id] (see `MediaSyncCursor`).
     */
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("drive_archived_at") val driveArchivedAt: String? = null,
    @SerialName("primary_cleanup_status") val primaryCleanupStatus: String? = null,
    @SerialName("primary_deleted_at") val primaryDeletedAt: String? = null,
    @Transient val hasCompletedDriveArchive: Boolean = false
) {
    val isHiddenFromLibrary: Boolean
        get() = !userHiddenAt.isNullOrBlank()

    val isCloudAvailable: Boolean
        get() {
            if (status == "DELETED") return false
            if (!cloudinarySourceUrl.isNullOrBlank()) return true
            if (status != "READY") return false
            return hasCompletedDriveArchive || !driveArchivedAt.isNullOrBlank()
        }

    /**
     * The PERSISTENT thumbnail reference.
     *
     * The backend stores it as a Cloudinary asset of its own
     * (`mydrive/{owner}/thumbnails/{media_id}`, recorded on `media_variants` with
     * `variant_type = 'thumbnail'`), so it is addressable independently of the
     * original and outlives the Cloudinary ORIGINAL's deletion. It never depends
     * on the Drive archive either: Drive is for originals, not for previews.
     */
    val persistentThumbnailUrl: String?
        get() = thumbnailUrl?.takeIf { it.isNotBlank() }

    /**
     * The Cloudinary ORIGINAL, or `null` once the verified cleanup deleted it.
     *
     * `storage_url` keeps its historical meaning here: after a successful primary
     * cleanup the original no longer exists, so it is never offered as a live
     * source.
     */
    val originalCloudUrl: String?
        get() {
            if (isPrimaryCleaned) return null
            return storageUrl?.takeIf { it.isNotBlank() }
        }

    /**
     * The Cloudinary candidate a preview is requested from.
     *
     * The persistent thumbnail is preferred because it is the asset meant to be
     * rendered in a gallery tile, and it is the one that still exists after the
     * original is cleaned up; the original is the fallback while it is alive.
     */
    val cloudinarySourceUrl: String?
        get() = persistentThumbnailUrl ?: originalCloudUrl

    /**
     * Thumbnail availability, tracked separately from original availability.
     *
     * A media whose Cloudinary original was deleted — and whose Drive archive may
     * not be reachable at gallery-render time — is still displayable from its
     * persistent thumbnail, so it must never be shown as "Media unavailable".
     */
    val hasPersistentThumbnail: Boolean
        get() = !persistentThumbnailUrl.isNullOrBlank()

    /** A row that can only be previewed: the original is gone, the thumbnail is not. */
    val isThumbnailOnly: Boolean
        get() = hasPersistentThumbnail && originalCloudUrl.isNullOrBlank()

    val isPrimaryCleaned: Boolean
        get() = !primaryDeletedAt.isNullOrBlank() ||
            primaryCleanupStatus.equals("cleanup_success", ignoreCase = true)
}
