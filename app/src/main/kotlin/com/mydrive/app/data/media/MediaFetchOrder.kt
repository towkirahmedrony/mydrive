package com.mydrive.app.data.media

enum class MediaFetchSource {
    DISK,
    LOCAL,
    CLOUDINARY,
    DRIVE
}

object MediaFetchOrder {

    fun steps(
        uriString: String,
        hasStableMediaId: Boolean,
        includeDisk: Boolean = true
    ): List<MediaFetchSource> {
        val ordered = ArrayList<MediaFetchSource>(4)
        if (includeDisk) ordered.add(MediaFetchSource.DISK)
        when {
            isLocalUri(uriString) -> ordered.add(MediaFetchSource.LOCAL)
            MediaCacheKeys.isRemoteUri(uriString) -> ordered.add(MediaFetchSource.CLOUDINARY)
        }
        if (hasStableMediaId) ordered.add(MediaFetchSource.DRIVE)
        return ordered
    }

    fun firstRemote(uriString: String, hasStableMediaId: Boolean): MediaFetchSource? {
        return steps(uriString, hasStableMediaId, includeDisk = false)
            .firstOrNull { it == MediaFetchSource.CLOUDINARY || it == MediaFetchSource.DRIVE }
    }

    fun isLocalUri(uri: String): Boolean {
        if (uri.isBlank()) return false
        return uri.startsWith("content://", ignoreCase = true) ||
            uri.startsWith("file://", ignoreCase = true)
    }
}
