package com.mydrive.app.data.media

import android.content.Context
import android.net.Uri
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.session.AccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A playable video location plus the headers the player must send with it. */
data class VideoSource(
    val uri: Uri,
    val headers: Map<String, String> = emptyMap(),
    val source: MediaFetchSource,
    /** The cache entry backing this source, when it is played from disk. */
    val file: File? = null
)

/**
 * Resolves what a video player should open, without downloading the video.
 *
 * Remote videos are handed to the player as URLs so playback starts from the
 * first ranges the codec needs; only a previously completed cache file is ever
 * played from disk, so a partial body can never be mistaken for an original.
 */
object VideoSourceResolver {

    suspend fun resolve(
        context: Context,
        uriString: String,
        mediaId: String?,
        sessionProvider: AuthenticatedSessionProvider?,
        userId: String? = AccountSession.userId,
        exclude: Set<MediaFetchSource> = emptySet()
    ): VideoSource? = withContext(Dispatchers.IO) {
        val ownerId = userId ?: AccountSession.userId
        val cachedOriginal = if (!ownerId.isNullOrBlank() && !mediaId.isNullOrBlank()) {
            MediaCacheKeys.originalFile(context.applicationContext.filesDir, ownerId, mediaId)
                .also { MediaDiskCache.discardInvalid(it) }
                .takeIf { MediaDiskCache.isComplete(it) }
        } else {
            null
        }

        val steps = MediaFetchOrder.steps(uriString, hasStableMediaId = !mediaId.isNullOrBlank())
        for (step in steps) {
            if (step in exclude) continue
            val resolved = when (step) {
                MediaFetchSource.DISK -> cachedOriginal?.let {
                    MediaDiskCache.touch(it)
                    VideoSource(Uri.fromFile(it), source = MediaFetchSource.DISK, file = it)
                }
                MediaFetchSource.LOCAL -> localSource(context, uriString)
                MediaFetchSource.CLOUDINARY -> runCatching { Uri.parse(uriString) }.getOrNull()
                    ?.let { VideoSource(it, source = MediaFetchSource.CLOUDINARY) }
                MediaFetchSource.DRIVE -> driveSource(mediaId, sessionProvider)
            }
            if (resolved != null) return@withContext resolved
        }
        null
    }

    private fun localSource(context: Context, uriString: String): VideoSource? {
        if (!MediaFetchOrder.isLocalUri(uriString)) return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val readable = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        // A file deleted from another gallery must fall through to the cloud
        // copy rather than fail playback, and must not touch the catalog.
        return if (readable) VideoSource(uri, source = MediaFetchSource.LOCAL) else null
    }

    private suspend fun driveSource(
        mediaId: String?,
        sessionProvider: AuthenticatedSessionProvider?
    ): VideoSource? {
        if (mediaId.isNullOrBlank() || sessionProvider == null) return null
        val stream = MediaDriveClient.authorizedStream(
            mediaId = mediaId,
            variant = MediaDriveClient.VARIANT_ORIGINAL,
            sessionProvider = sessionProvider
        ) ?: return null
        return VideoSource(
            uri = Uri.parse(stream.url),
            headers = stream.headers,
            source = MediaFetchSource.DRIVE
        )
    }
}
