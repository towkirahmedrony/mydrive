package com.mydrive.app.data.remote

/**
 * Size-aware upload planning for the existing Cloudinary REST pipeline.
 *
 * WHY THIS EXISTS
 * A single POST to the Cloudinary Upload API only works up to 100 MB. The app
 * treated that threshold as a hard cap and threw `FileTooLargeException`, so a
 * large video (e.g. a >100 MB `.mkv`) was reported as
 * "File exceeds Cloudinary size limit" / `file_too_large` even though Cloudinary
 * supports files far larger through its chunked Upload API.
 *
 * LIMITS — taken from Cloudinary's own documentation, not assumed:
 *  - "Chunked asset upload": `upload_large` "is required for any files that are
 *    larger than 100 MB" — without it the API answers `413 Request entity too
 *    large`. That is [SINGLE_REQUEST_MAX_BYTES].
 *  - The same page documents the manual REST mechanism used here: an
 *    `X-Unique-Upload-Id` header (same value for every chunk) plus a
 *    `Content-Range: bytes start-end/total` header per chunk, each chunk POSTed
 *    "with your usual upload parameters"; the intermediate responses carry
 *    `done: false` and only the final chunk returns the full upload response
 *    with `done: true`.
 *  - "Use chunk sizes greater than 5 MB for all chunks except the last one."
 *    The SDK default is 20 MB, which is [CHUNK_BYTES].
 *  - "For files larger than 20 GB, set async to true" — an asynchronous upload
 *    is not wired up in this app, so beyond [MAX_CHUNKED_BYTES] we refuse with a
 *    precise reason instead of failing mid-transfer.
 *
 * A chunked upload is a transport detail only: every chunk targets the same
 * public ID under the same `X-Unique-Upload-Id`, so the media is finalized as
 * exactly ONE Cloudinary asset and never as `part-1`/`part-2` gallery items.
 */
object CloudinaryUploadPlan {

    /** Above this, one POST is rejected by Cloudinary with 413. */
    const val SINGLE_REQUEST_MAX_BYTES: Long = 100L * 1024L * 1024L

    /**
     * The documented SDK default, and comfortably above the documented 5 MB
     * minimum for every non-final chunk.
     */
    const val CHUNK_BYTES: Long = 20L * 1024L * 1024L

    /** Beyond this the upload must be asynchronous, which this app does not use. */
    const val MAX_CHUNKED_BYTES: Long = 20L * 1024L * 1024L * 1024L

    enum class Strategy { SINGLE_REQUEST, CHUNKED }

    sealed class Decision {
        /** The upload can proceed with [strategy]. */
        data class Upload(val strategy: Strategy) : Decision()

        /** No supported mechanism can carry this file; [reason] is user-facing. */
        data class Rejected(
            val reason: String,
            val retryable: Boolean,
            val originalBytes: Long
        ) : Decision()
    }

    /**
     * Chooses the transfer mechanism for a file of [totalBytes].
     *
     * An unknown size keeps the legacy single-request path, which is still
     * guarded while streaming, so no new behaviour is introduced for it.
     */
    fun decide(totalBytes: Long): Decision = when {
        totalBytes <= 0L -> Decision.Upload(Strategy.SINGLE_REQUEST)
        totalBytes <= SINGLE_REQUEST_MAX_BYTES -> Decision.Upload(Strategy.SINGLE_REQUEST)
        totalBytes <= MAX_CHUNKED_BYTES -> Decision.Upload(Strategy.CHUNKED)
        else -> Decision.Rejected(
            reason = buildString {
                append("This file is ")
                append(megabytes(totalBytes))
                append(" MB, which exceeds the ")
                append(megabytes(MAX_CHUNKED_BYTES))
                append(" MB limit for a resumable Cloudinary upload. ")
                append("Compressing it on the device is not supported by this build, ")
                append("so it cannot be backed up.")
            },
            retryable = false,
            originalBytes = totalBytes
        )
    }

    /**
     * Inclusive byte ranges covering [totalBytes], in order.
     *
     * Ranges are contiguous and ascending so the uploader can walk the file with
     * a single forward pass over one input stream.
     */
    fun chunkRanges(totalBytes: Long, chunkBytes: Long = CHUNK_BYTES): List<LongRange> {
        if (totalBytes <= 0L || chunkBytes <= 0L) return emptyList()
        val ranges = ArrayList<LongRange>((totalBytes / chunkBytes).toInt() + 1)
        var start = 0L
        while (start < totalBytes) {
            val end = minOf(start + chunkBytes - 1L, totalBytes - 1L)
            ranges += start..end
            start = end + 1L
        }
        return ranges
    }

    fun megabytes(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytes.toDouble() / (1024.0 * 1024.0))
}
