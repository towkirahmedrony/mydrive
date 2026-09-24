package com.mydrive.app.data.media

/**
 * Lightweight Cloudinary preview URLs.
 *
 * `media_assets.storage_url` is the Cloudinary `secure_url` of the uploaded
 * original. The app keeps that value in `MediaItem.thumbnailUrl` and, for a
 * cloud-only item, in `MediaItem.uri` as well, so a grid tile, an album cover or
 * a viewer neighbour that is painted through the normal resolver used to request
 * the *full-size* asset and only then down-sample it in memory.
 *
 * Cloudinary derives a resized copy on the fly from a URL transformation, so the
 * same stored URL can be requested as a small preview: no re-upload, no new
 * `media_assets` row, no second delivery pipeline, and no bypass of the resolver
 * — this only supplies the CLOUDINARY candidate a cheaper URL. The Drive
 * thumbnail (`variant=thumb`) and the MediaStore thumbnail paths are untouched.
 *
 * `f_auto` is deliberately NOT requested: it lets Cloudinary answer with AVIF,
 * which `BitmapFactory` cannot decode on every supported API level, and a failed
 * decode would drop a healthy cover. `q_auto` keeps the quality trade-off
 * automatic while the delivered format stays the stored one.
 *
 * Modeled on the server-side URL shape used by `supabase/functions/shared/cloudinary.ts`
 * (`<cloud>/<resource_type>/<delivery_type>/<transformations>/…public_id`), which
 * inserts the transformation immediately after the delivery type.
 */
object CloudinaryPreview {

    private const val IMAGE_UPLOAD_MARKER = "/image/upload/"
    private const val VIDEO_UPLOAD_MARKER = "/video/upload/"
    private const val RAW_UPLOAD_MARKER = "/raw/upload/"
    private const val MIN_SIZE_PX = 64
    private const val MAX_SIZE_PX = 1024

    /**
     * Folder segment the backend stores persistent thumbnails under
     * (`mydrive/{owner}/thumbnails/{media_id}`). Must match
     * `supabase/functions/shared/thumbnail-lifecycle.ts`.
     */
    private const val THUMBNAIL_FOLDER_SEGMENT = "thumbnails"

    /** Video containers Cloudinary can derive a still frame from. */
    private val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mov", ".m4v", ".webm", ".avi", ".mkv", ".3gp", ".3g2", ".mts"
    )

    /**
     * True when [url] addresses a Cloudinary video asset. The stored file can
     * never be decoded as a bitmap, so it must not be downloaded for a preview —
     * [previewUrl] answers with a still frame instead.
     */
    fun isVideoDeliveryUrl(url: String): Boolean =
        url.contains(VIDEO_UPLOAD_MARKER, ignoreCase = true)

    /**
     * True when [url] addresses the media's PERSISTENT thumbnail rather than the
     * original.
     *
     * This is the signal that a preview does not depend on the original still
     * existing: the thumbnail is its own Cloudinary asset under
     * `…/thumbnails/…`, so it survives the original's cleanup. Used for
     * diagnostics and to keep the resolver's behaviour explicit; it never changes
     * which URL is requested.
     *
     * Deliberately strict on the path shape: `…/{cloud}/{type}/upload/…/thumbnails/…`
     * must have at least one segment after the folder, and the folder must be a
     * whole path segment (an original named `thumbnails.jpg` must not match).
     */
    fun isPersistentThumbnailUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val marker = when {
            trimmed.contains(IMAGE_UPLOAD_MARKER, ignoreCase = true) -> IMAGE_UPLOAD_MARKER
            trimmed.contains(VIDEO_UPLOAD_MARKER, ignoreCase = true) -> VIDEO_UPLOAD_MARKER
            else -> return false
        }
        val markerAt = trimmed.indexOf(marker, ignoreCase = true)
        val segments = trimmed.substring(markerAt + marker.length)
            .split('/')
            .filter { it.isNotEmpty() }
        if (segments.size < 3) return false
        return segments.dropLast(1).any { it == THUMBNAIL_FOLDER_SEGMENT }
    }

    /**
     * A size-bounded delivery URL for [url], or `null` when no bitmap can be
     * produced from it.
     *
     * - Cloudinary image URL -> the same asset with a `w_<size>,h_<size>,c_fill,q_auto`
     *   transformation, so the request is bounded to a thumbnail-sized derivative;
     * - Cloudinary video URL -> a still frame (`so_0`) delivered as `.jpg`, which is
     *   the only lightweight preview a video has; a video whose extension is not a
     *   known container returns `null`;
     * - Cloudinary raw URL -> `null` (never an image);
     * - any other URL -> returned unchanged, so delivery URLs the app cannot
     *   rewrite keep their current behaviour instead of breaking;
     * - blank or non-remote URL -> `null`.
     */
    fun previewUrl(url: String, sizePx: Int = ThumbnailLoader.PREVIEW_SIZE_PX): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || !MediaCacheKeys.isRemoteUri(trimmed)) return null
        if (trimmed.contains(RAW_UPLOAD_MARKER, ignoreCase = true)) return null
        val size = sizePx.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        if (isVideoDeliveryUrl(trimmed)) return videoFrameUrl(trimmed, size)
        val markerAt = trimmed.indexOf(IMAGE_UPLOAD_MARKER, ignoreCase = true)
        if (markerAt < 0) return trimmed
        val head = trimmed.substring(0, markerAt + IMAGE_UPLOAD_MARKER.length)
        val tail = trimmed.substring(head.length)
        if (tail.isEmpty()) return null
        if (startsWithTransformation(tail)) return trimmed
        return head + transformation(size) + tail
    }

    private fun transformation(size: Int): String =
        "w_$size,h_$size,c_fill,q_auto/"

    /**
     * Cloudinary's still frame for a video: `so_0` (first frame) delivered as a
     * `.jpg` derivative, at the requested thumbnail size.
     */
    private fun videoFrameUrl(url: String, size: Int): String? {
        val markerAt = url.indexOf(VIDEO_UPLOAD_MARKER, ignoreCase = true)
        if (markerAt < 0) return null
        val head = url.substring(0, markerAt + VIDEO_UPLOAD_MARKER.length)
        val tail = url.substring(head.length)
        val extension = VIDEO_EXTENSIONS.firstOrNull { tail.endsWith(it, ignoreCase = true) }
            ?: return null
        if (startsWithTransformation(tail)) return null
        return head + "so_0," + transformation(size) + tail.dropLast(extension.length) + ".jpg"
    }

    /**
     * A `_` in the segment right after the delivery type means the URL already
     * carries a transformation (or a folder we cannot classify). Leaving it
     * untouched keeps today's behaviour instead of risking a second,
     * conflicting transformation.
     */
    private fun startsWithTransformation(tail: String): Boolean =
        tail.substringBefore('/').contains('_')
}
