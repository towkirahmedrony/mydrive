package com.mydrive.app.data.media

import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.remote.dto.MediaAssetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryPagingTest {

    @Test
    fun testA_initialPageSizeIsBounded() {
        assertEquals(80, MediaLibraryPaging.PAGE_SIZE)
        val rows = rows(MediaLibraryPaging.PAGE_SIZE)
        assertTrue(MediaLibraryPaging.hasNextPage(rows.size))
        assertEquals("a-0079", MediaLibraryPaging.nextCursor(rows)?.id)
    }

    @Test
    fun testB_nextPageCursorUsesLastItem() {
        val rows = rows(80)
        val cursor = MediaLibraryPaging.nextCursor(rows)
        assertEquals(rows.last().createdAt, cursor?.createdAt)
        assertEquals(rows.last().id, cursor?.id)
    }

    @Test
    fun testC_itemDeduplicationKeepsFirstOccurrence() {
        val first = rows(3)
        val overlap = listOf(first[2], row("a-0003", "2026-01-01T00:00:03Z"), row("a-0004", "2026-01-01T00:00:04Z"))
        val merged = MediaLibraryPaging.mergeRows(first, overlap)
        assertEquals(listOf("a-0000", "a-0001", "a-0002", "a-0003", "a-0004"), merged.map { it.id })
    }

    @Test
    fun testD_chronologicalOrderIsNewestFirst() {
        val items = listOf(
            media("older", captured = 1_000L),
            media("newer", captured = 3_000L),
            media("mid", captured = 2_000L)
        )
        val grouped = MediaLibraryPaging.groupChronologically(items) { "day" }
        assertEquals(listOf("newer", "mid", "older"), grouped.single().second.map { it.id })
    }

    @Test
    fun testE_crossPageDateGroupingDoesNotSplitSameDay() {
        val pageOne = listOf(
            media("today-a", captured = 2_000L),
            media("today-b", captured = 1_500L)
        )
        val pageTwo = listOf(
            media("today-c", captured = 1_200L),
            media("yesterday", captured = 100L)
        )
        val merged = MediaLibraryPaging.mergeLibraryItems(pageOne, pageTwo)
        val groups = MediaLibraryPaging.groupChronologically(merged) { millis ->
            if (millis >= 1_000L) "Today" else "Yesterday"
        }
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.first })
        assertEquals(listOf("today-a", "today-b", "today-c"), groups[0].second.map { it.id })
        assertEquals(listOf("yesterday"), groups[1].second.map { it.id })
    }

    @Test
    fun testF_driveOnlyReadyRowsRemainAvailable() {
        val driveOnly = MediaAssetRow(
            id = "drive-1",
            status = "READY",
            storageUrl = null,
            thumbnailUrl = null,
            driveArchivedAt = "2026-01-02T00:00:00Z"
        )
        assertTrue(driveOnly.isCloudAvailable)
        assertFalse(MediaLibraryPaging.needsDriveArchiveLookup(driveOnly))
    }

    @Test
    fun testG_locallyDeletedCloudCopyStaysIdentifiable() {
        val localGone = media("img-1", remoteId = "cloud-1", originLocal = false)
        val cloudOnly = media("cloud-cloud-1", remoteId = "cloud-1", originLocal = false)
        val merged = MediaLibraryPaging.mergeLibraryItems(listOf(localGone), listOf(cloudOnly))
        assertEquals(1, merged.size)
        assertEquals("img-1", merged.single().id)
    }

    @Test
    fun testH_hiddenAndDeletedRowsAreNotCloudAvailable() {
        val hidden = MediaAssetRow(id = "h1", status = "READY", userHiddenAt = "2026-01-01T00:00:00Z", storageUrl = "https://cdn/x")
        val deleted = MediaAssetRow(id = "d1", status = "DELETED", storageUrl = "https://cdn/x")
        assertTrue(hidden.isHiddenFromLibrary)
        assertFalse(deleted.isCloudAvailable)
    }

    @Test
    fun testI_shortPageEndsPagination() {
        val rows = rows(12)
        assertFalse(MediaLibraryPaging.hasNextPage(rows.size))
        assertEquals(null, MediaLibraryPaging.nextCursor(rows))
    }

    @Test
    fun testK_duplicateParallelMergeDoesNotGrowUnbounded() {
        val page = rows(5)
        val once = MediaLibraryPaging.mergeRows(page, page)
        val twice = MediaLibraryPaging.mergeRows(once, page)
        assertEquals(5, twice.size)
    }

    @Test
    fun testM_emptyCatalogProducesEmptyGroups() {
        val groups = MediaLibraryPaging.groupChronologically(emptyList()) { "Today" }
        assertTrue(groups.isEmpty())
    }

    @Test
    fun testN_pageMergeDoesNotRetainDroppedFullTable() {
        val first = rows(MediaLibraryPaging.PAGE_SIZE)
        val second = rows(MediaLibraryPaging.PAGE_SIZE).map { row ->
            row.copy(id = "b-${row.id.removePrefix("a-")}", createdAt = "2025-12-31T00:00:00Z")
        }
        val merged = MediaLibraryPaging.mergeRows(first, second)
        assertEquals(MediaLibraryPaging.PAGE_SIZE * 2, merged.size)
        assertEquals(MediaLibraryPaging.PAGE_SIZE, first.size)
    }

    private fun rows(count: Int): List<MediaAssetRow> =
        (0 until count).map { index ->
            row("a-%04d".format(index), "2026-01-01T00:00:%02dZ".format(index % 60))
        }

    private fun row(id: String, createdAt: String) = MediaAssetRow(
        id = id,
        status = "READY",
        createdAt = createdAt,
        storageUrl = "https://cdn/$id"
    )

    private fun media(
        id: String,
        captured: Long = 0L,
        remoteId: String? = null,
        originLocal: Boolean = true
    ) = MediaItem(
        id = id,
        filename = id,
        type = MediaType.PHOTO,
        fileSizeBytes = 1L,
        capturedAtMillis = captured,
        device = "test",
        resolution = "1 x 1",
        thumbnailSeed = 0,
        remoteMediaId = remoteId,
        originLocal = originLocal
    )
}
