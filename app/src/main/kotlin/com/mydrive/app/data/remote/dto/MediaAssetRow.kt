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

    /** The Cloudinary source is no longer live after verified primary cleanup. */
    val cloudinarySourceUrl: String?
        get() {
            if (isPrimaryCleaned) return null
            return thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: storageUrl?.takeIf { it.isNotBlank() }
        }

    val isPrimaryCleaned: Boolean
        get() = !primaryDeletedAt.isNullOrBlank() ||
            primaryCleanupStatus.equals("cleanup_success", ignoreCase = true)
}
