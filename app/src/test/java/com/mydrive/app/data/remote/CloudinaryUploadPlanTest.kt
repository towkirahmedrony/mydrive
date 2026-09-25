package com.mydrive.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Size-aware upload planning.
 *
 * Regression cover for: a >100 MB video was rejected with
 * "File exceeds Cloudinary size limit" / `file_too_large` because the 100 MB
 * single-request threshold was used as a hard cap. Cloudinary documents that
 * files larger than 100 MB require the chunked Upload API, which produces ONE
 * asset — so the threshold must select a strategy, not fail the media.
 */
class CloudinaryUploadPlanTest {

    private val mb = 1024L * 1024L

    private fun strategy(bytes: Long): CloudinaryUploadPlan.Strategy? =
        (CloudinaryUploadPlan.decide(bytes) as? CloudinaryUploadPlan.Decision.Upload)?.strategy

    // ── 1 & 2: normal media keeps the normal path ─────────────────────────────

    @Test
    fun `small photo uses a single request`() {
        assertEquals(CloudinaryUploadPlan.Strategy.SINGLE_REQUEST, strategy(3 * mb))
    }

    @Test
    fun `normal sized video uses a single request`() {
        assertEquals(CloudinaryUploadPlan.Strategy.SINGLE_REQUEST, strategy(80 * mb))
    }

    @Test
    fun `exactly the single-request limit still uses a single request`() {
        assertEquals(
            CloudinaryUploadPlan.Strategy.SINGLE_REQUEST,
            strategy(CloudinaryUploadPlan.SINGLE_REQUEST_MAX_BYTES)
        )
    }

    @Test
    fun `unknown size keeps the legacy single-request path`() {
        assertEquals(CloudinaryUploadPlan.Strategy.SINGLE_REQUEST, strategy(-1L))
        assertEquals(CloudinaryUploadPlan.Strategy.SINGLE_REQUEST, strategy(0L))
    }

    // ── 3: a large file is no longer a failure ───────────────────────────────

    @Test
    fun `one byte over the limit switches to chunked instead of failing`() {
        assertEquals(
            CloudinaryUploadPlan.Strategy.CHUNKED,
            strategy(CloudinaryUploadPlan.SINGLE_REQUEST_MAX_BYTES + 1L)
        )
    }

    @Test
    fun `large video does not produce a rejection`() {
        val decision = CloudinaryUploadPlan.decide(1_500 * mb) // 1.5 GB
        assertTrue("large media must remain uploadable", decision is CloudinaryUploadPlan.Decision.Upload)
    }

    // ── 8: .mkv is handled by size, not by container ─────────────────────────

    @Test
    fun `mkv sized video follows the size rule like any other video`() {
        // ".mkv" is a container, not a Cloudinary upload constraint: the plan is
        // decided by byte size, so an oversized mkv is chunked, not rejected, and
        // no transcoding is attempted merely because of the extension.
        assertEquals(
            CloudinaryUploadPlan.Strategy.CHUNKED,
            strategy(700 * mb)
        )
    }

    // ── 7: genuinely unsupported size fails precisely ────────────────────────

    @Test
    fun `beyond the resumable ceiling is rejected with a precise reason`() {
        val decision = CloudinaryUploadPlan.decide(CloudinaryUploadPlan.MAX_CHUNKED_BYTES + 1L)
        assertTrue(decision is CloudinaryUploadPlan.Decision.Rejected)
        decision as CloudinaryUploadPlan.Decision.Rejected
        assertFalse("a size decision is not retryable", decision.retryable)
        assertEquals(
            CloudinaryUploadPlan.MAX_CHUNKED_BYTES + 1L,
            decision.originalBytes
        )
        assertTrue(
            "reason must state the size and the limit",
            decision.reason.contains("20480.0") && decision.reason.contains("MB")
        )
    }

    // ── 4: chunk ranges describe ONE asset ───────────────────────────────────

    @Test
    fun `ranges cover the file exactly once with no gaps or overlaps`() {
        val total = 250 * mb
        val ranges = CloudinaryUploadPlan.chunkRanges(total)

        assertEquals(0L, ranges.first().first)
        assertEquals(total - 1L, ranges.last().last)
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals("ranges must be contiguous", a.last + 1L, b.first)
        }
    }

    @Test
    fun `every non-final chunk respects the documented minimum chunk size`() {
        val ranges = CloudinaryUploadPlan.chunkRanges(1_500 * mb)
        val fiveMb = 5L * mb

        ranges.dropLast(1).forEach { range ->
            assertTrue(
                "non-final chunks must be > 5 MB (Cloudinary requirement)",
                (range.last - range.first + 1L) > fiveMb
            )
        }
    }

    @Test
    fun `final chunk carries the remainder and never exceeds the chunk size`() {
        val total = CloudinaryUploadPlan.CHUNK_BYTES * 3L + 1234L
        val ranges = CloudinaryUploadPlan.chunkRanges(total)

        assertEquals(4, ranges.size)
        assertEquals(1234L, ranges.last().last - ranges.last().first + 1L)
    }

    @Test
    fun `an exact multiple of the chunk size does not add an empty trailing chunk`() {
        val total = CloudinaryUploadPlan.CHUNK_BYTES * 4L
        val ranges = CloudinaryUploadPlan.chunkRanges(total)

        assertEquals(4, ranges.size)
        assertEquals(total - 1L, ranges.last().last)
        assertTrue(ranges.all { (it.last - it.first + 1L) == CloudinaryUploadPlan.CHUNK_BYTES })
    }

    @Test
    fun `no ranges are produced for an unusable size`() {
        assertTrue(CloudinaryUploadPlan.chunkRanges(0L).isEmpty())
        assertTrue(CloudinaryUploadPlan.chunkRanges(-5L).isEmpty())
    }

    @Test
    fun `a large file is split into many chunks rather than a few huge ones`() {
        val ranges = CloudinaryUploadPlan.chunkRanges(100 * mb)
        assertEquals(5, ranges.size) // 100 MB / 20 MB
    }
}
