package com.mydrive.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("client_upload_id") val clientUploadId: String? = null,
    val status: String? = null,
    @SerialName("user_hidden_at") val userHiddenAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    val isHiddenFromLibrary: Boolean
        get() = !userHiddenAt.isNullOrBlank()
}
