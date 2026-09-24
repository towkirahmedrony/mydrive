package com.mydrive.app.data.media

import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType

/**
 * Everything an album grid needs to render one cover, already reduced to the
 * candidates the media resolver understands.
 *
 * [uri] is tried first (a device URI when the album has a local copy, otherwise
 * the item's cloud URL), [previewUri] is the lightweight cloud candidate kept
 * for when a device URI turns out to be stale, and [remoteMediaId] is the stable
 * identity that drives the Drive thumbnail fallback and the cache key.
 */
data class AlbumCover(
    val uri: String,
    val previewUri: String?,
    val remoteMediaId: String?,
    val seed: Int,
    val type: MediaType
)

/**
 * Picks the cover for one album from the items the catalog has already loaded.
 *
 * A source priority of `local -> Cloudinary -> Drive` is preserved, and no
 * database query, extra scan or duplicate media row is involved: the choice is
 * made from the in-memory album slice the caller already grouped.
 *
 * Why the extra fallback: the preferred cover used to be the album's newest item
 * alone, and its `displayUri` alone. When that item is a device URI whose
 * MediaStore row was removed externally, the cover load failed and the album
 * rendered as an empty placeholder even though the very same media was still
 * readable from Cloudinary or from the Drive archive. Carrying the item's cloud
 * URL alongside the device URI lets the existing resolver walk
 * `disk -> local -> Cloudinary -> Drive` instead of `disk -> local -> Drive`, so
 * an album only loses its cover when the media genuinely has no copy left.
 *
 * The cover identity itself is deliberately unchanged for a normal album: the
 * newest item that still has a device copy represents the album, including a
 * photo that was taken a moment ago and has not been uploaded yet. Cloud-backed
 * items are only chosen when the album has no device copy at all; preferring a
 * backed-up sibling over a newer local one would silently swap the cover of every
 * album that is mid-upload.
 */
object AlbumCoverResolver {

    /**
     * Cover for [items], or null when the album has nothing to show.
     *
     * The newest locally backed item wins (unchanged behaviour for a normal
     * album); cloud-backed items are only preferred when the album has no local
     * copy at all, which is the cloud-only / Drive-only case.
     */
    fun select(items: List<MediaItem>): AlbumCover? {
        if (items.isEmpty()) return null
        val locallyBacked = items.filter { MediaFetchOrder.isLocalUri(it.uri) }
        val cloudBacked = items.filter { it.hasCloudSource }
        val chosen = locallyBacked.maxByOrNull { it.capturedAtMillis }
            ?: cloudBacked.maxByOrNull { it.capturedAtMillis }
            ?: items.maxByOrNull { it.capturedAtMillis }
        return chosen?.let(::coverOf)
    }

    /** Cover for a single known media item (used for the cloud-only albums). */
    fun coverOf(item: MediaItem): AlbumCover {
        val deviceUri = item.uri.trim().takeIf { MediaFetchOrder.isLocalUri(it) }
        val cloudUri = item.cloudSourceUri
        return if (deviceUri != null) {
            AlbumCover(
                uri = deviceUri,
                previewUri = cloudUri,
                remoteMediaId = item.remoteMediaId?.takeIf { it.isNotBlank() },
                seed = item.thumbnailSeed,
                type = item.type
            )
        } else {
            AlbumCover(
                uri = cloudUri ?: item.displayUri,
                previewUri = null,
                remoteMediaId = item.remoteMediaId?.takeIf { it.isNotBlank() },
                seed = item.thumbnailSeed,
                type = item.type
            )
        }
    }

    private val MediaItem.hasCloudSource: Boolean
        get() = !remoteMediaId.isNullOrBlank() || cloudSourceUri != null

    /** The item's own remote delivery URL, when it has one. */
    private val MediaItem.cloudSourceUri: String?
        get() = thumbnailUrl?.trim()?.takeIf { MediaCacheKeys.isRemoteUri(it) }
}
