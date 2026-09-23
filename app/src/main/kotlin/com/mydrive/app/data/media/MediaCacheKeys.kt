package com.mydrive.app.data.media

import java.io.File
import java.security.MessageDigest

object MediaCacheKeys {
    const val VARIANT_THUMBNAIL = "thumbnail"
    const val VARIANT_ORIGINAL = "original"

    fun memoryKey(
        userId: String?,
        mediaId: String?,
        uri: String,
        variant: String,
        sizePx: Int
    ): String {
        val uid = userId?.takeIf { it.isNotBlank() }
        val stableId = mediaId?.takeIf { it.isNotBlank() }
        val remote = isRemoteUri(uri)
        return when {
            uid != null && stableId != null -> "$uid:$stableId:$variant:$sizePx"
            uid != null && remote -> "$uid:fetch:${sha256(uri)}:$variant:$sizePx"
            remote -> "blocked-remote"
            else -> "device:$uri@$variant@$sizePx"
        }
    }

    fun thumbnailFile(filesDir: File, userId: String, mediaId: String, sizePx: Int): File {
        val digest = sha256("$mediaId@$sizePx")
        return File(filesDir, "media_thumbnails/${safeUserSegment(userId)}/$digest.webp")
    }

    fun originalFile(filesDir: File, userId: String, mediaId: String): File {
        val digest = sha256(mediaId)
        return File(filesDir, "media_originals/${safeUserSegment(userId)}/$digest.bin")
    }

    fun isRemoteUri(uri: String): Boolean {
        return uri.startsWith("http://") || uri.startsWith("https://")
    }

    fun safeUserSegment(userId: String): String {
        val cleaned = userId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "unknown" }
    }

    fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
