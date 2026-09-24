package com.mydrive.app.data.media

enum class MediaFetchSource {
    DISK,
    LOCAL,
    CLOUDINARY,
    DRIVE
}

object MediaFetchOrder {

    /**
     * @param previewUri an extra cloud candidate (a `thumbnail_url`/`storage_url`
     *   the item carries) that is only reachable through the resolver. It keeps
     *   Cloudinary ahead of Drive when [uriString] is a device URI that no longer
     *   resolves, because a MediaStore row can be removed externally while the
     *   backup it mirrors is still perfectly readable.
     */
    fun steps(
        uriString: String,
        hasStableMediaId: Boolean,
        includeDisk: Boolean = true,
        previewUri: String? = null
    ): List<MediaFetchSource> {
        val ordered = ArrayList<MediaFetchSource>(4)
        if (includeDisk) ordered.add(MediaFetchSource.DISK)
        when {
            isLocalUri(uriString) -> {
                ordered.add(MediaFetchSource.LOCAL)
                if (MediaCacheKeys.isRemoteUri(previewUri.orEmpty())) {
                    ordered.add(MediaFetchSource.CLOUDINARY)
                }
            }
            MediaCacheKeys.isRemoteUri(uriString) -> ordered.add(MediaFetchSource.CLOUDINARY)
        }
        if (hasStableMediaId) ordered.add(MediaFetchSource.DRIVE)
        return ordered
    }

    fun firstRemote(
        uriString: String,
        hasStableMediaId: Boolean,
        previewUri: String? = null
    ): MediaFetchSource? {
        return steps(uriString, hasStableMediaId, includeDisk = false, previewUri = previewUri)
            .firstOrNull { it == MediaFetchSource.CLOUDINARY || it == MediaFetchSource.DRIVE }
    }

    fun isLocalUri(uri: String): Boolean {
        if (uri.isBlank()) return false
        return uri.startsWith("content://", ignoreCase = true) ||
            uri.startsWith("file://", ignoreCase = true)
    }
}
