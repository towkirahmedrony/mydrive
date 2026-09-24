package com.mydrive.app.data.media

import java.io.File

/** Freshness is separate from access time so LRU touches do not hide stale data. */
object MediaCacheFreshness {
    const val THUMBNAIL_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun thumbnailStamp(filesDir: File, userId: String, mediaId: String, sizePx: Int): File =
        File(
            filesDir,
            "media_cache_metadata/${MediaCacheKeys.safeUserSegment(userId)}/" +
                "${MediaCacheKeys.sha256("$mediaId@$sizePx")}.stamp"
        )

    fun isThumbnailStale(
        filesDir: File,
        userId: String,
        mediaId: String,
        sizePx: Int,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val stamp = thumbnailStamp(filesDir, userId, mediaId, sizePx)
        return !stamp.isFile || now - stamp.lastModified() >= THUMBNAIL_MAX_AGE_MS
    }

    fun markThumbnailFresh(filesDir: File, userId: String, mediaId: String, sizePx: Int) {
        val stamp = thumbnailStamp(filesDir, userId, mediaId, sizePx)
        stamp.parentFile?.mkdirs()
        runCatching {
            if (!stamp.exists()) stamp.createNewFile()
            stamp.setLastModified(System.currentTimeMillis())
        }
    }
}
