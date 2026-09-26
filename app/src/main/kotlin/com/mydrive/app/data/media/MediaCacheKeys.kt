package com.mydrive.app.data.media

import java.io.File
import java.security.MessageDigest

/**
 * Cache keys, and the rule that keeps them correct.
 *
 * A key is media identity *plus a change signal* ([version]) — never identity
 * alone, and never a filename. Identity is stable across a reinstall and a
 * rename, so it is what makes a cached entry reusable; the change signal is what
 * makes a re-saved or rotated file miss the cache instead of serving the old
 * pixels forever. Callers that only know an identity pass no version, which
 * keeps their keys exactly as they were.
 */
object MediaCacheKeys {
    const val VARIANT_THUMBNAIL = "thumbnail"
    const val VARIANT_ORIGINAL = "original"

    fun memoryKey(
        userId: String?,
        mediaId: String?,
        uri: String,
        variant: String,
        sizePx: Int,
        version: String? = null
    ): String {
        val uid = userId?.takeIf { it.isNotBlank() }
        val stableId = mediaId?.takeIf { it.isNotBlank() }
        val remote = isRemoteUri(uri)
        val stamp = version?.takeIf { it.isNotBlank() }
        return when {
            uid != null && stableId != null && stamp != null -> "$uid:$stableId:$variant:$sizePx:$stamp"
            uid != null && stableId != null -> "$uid:$stableId:$variant:$sizePx"
            uid != null && remote -> "$uid:fetch:${sha256(uri)}:$variant:$sizePx"
            remote -> "blocked-remote"
            else -> "device:$uri@$variant@$sizePx"
        }
    }

    fun thumbnailFile(
        filesDir: File,
        userId: String,
        mediaId: String,
        sizePx: Int,
        version: String? = null
    ): File {
        val stamp = version?.takeIf { it.isNotBlank() }
        val digest = if (stamp == null) sha256("$mediaId@$sizePx") else sha256("$mediaId@$sizePx@$stamp")
        return File(filesDir, "media_thumbnails/${safeUserSegment(userId)}/$digest.webp")
    }

    fun originalFile(
        filesDir: File,
        userId: String,
        mediaId: String,
        version: String? = null
    ): File {
        val stamp = version?.takeIf { it.isNotBlank() }
        val digest = if (stamp == null) sha256(mediaId) else sha256("$mediaId@$stamp")
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
