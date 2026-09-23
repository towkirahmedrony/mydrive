package com.mydrive.app.data.media

import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.remote.dto.MediaAssetRow

data class MediaPageCursor(
    val createdAt: String,
    val id: String
)

data class MediaAssetsPage(
    val rows: List<MediaAssetRow>,
    val nextCursor: MediaPageCursor?,
    val hasNextPage: Boolean
)

data class MediaAlbumStats(
    val cloudOnlyCount: Int = 0,
    val cover: MediaAssetRow? = null
)

object MediaLibraryPaging {
    const val PAGE_SIZE = 80

    const val LISTING_COLUMNS = "id,owner_id,local_media_id,file_name,mime_type,file_size,width,height,duration_ms,storage_url,thumbnail_url,client_upload_id,status,user_hidden_at,deleted_at,uploaded_at,created_at,drive_archived_at"

    fun cursorOf(row: MediaAssetRow): MediaPageCursor? {
        val createdAt = row.createdAt?.takeIf { it.isNotBlank() }
            ?: row.uploadedAt?.takeIf { it.isNotBlank() }
            ?: return null
        if (row.id.isBlank()) return null
        return MediaPageCursor(createdAt = createdAt, id = row.id)
    }

    fun hasNextPage(fetchedCount: Int, pageSize: Int = PAGE_SIZE): Boolean =
        fetchedCount >= pageSize

    fun nextCursor(rows: List<MediaAssetRow>, pageSize: Int = PAGE_SIZE): MediaPageCursor? {
        if (!hasNextPage(rows.size, pageSize)) return null
        return rows.lastOrNull()?.let(::cursorOf)
    }

    fun mergeRows(
        existing: List<MediaAssetRow>,
        incoming: List<MediaAssetRow>
    ): List<MediaAssetRow> {
        if (existing.isEmpty()) return incoming.distinctBy { it.id }
        if (incoming.isEmpty()) return existing
        val seen = HashSet<String>(existing.size + incoming.size)
        val merged = ArrayList<MediaAssetRow>(existing.size + incoming.size)
        for (row in existing) {
            if (seen.add(row.id)) merged += row
        }
        for (row in incoming) {
            if (seen.add(row.id)) merged += row
        }
        return merged
    }

    fun libraryIdentity(item: MediaItem): String =
        item.remoteMediaId?.takeIf { it.isNotBlank() } ?: "local:${item.id}"

    fun mergeLibraryItems(
        existing: List<MediaItem>,
        incoming: List<MediaItem>
    ): List<MediaItem> {
        if (existing.isEmpty()) {
            return incoming.distinctBy(::libraryIdentity).sortedByDescending { it.capturedAtMillis }
        }
        if (incoming.isEmpty()) return existing
        val seen = HashSet<String>(existing.size + incoming.size)
        val merged = ArrayList<MediaItem>(existing.size + incoming.size)
        for (item in existing) {
            if (seen.add(libraryIdentity(item))) merged += item
        }
        for (item in incoming) {
            if (seen.add(libraryIdentity(item))) merged += item
        }
        return merged.sortedByDescending { it.capturedAtMillis }
    }

    fun needsDriveArchiveLookup(row: MediaAssetRow): Boolean =
        row.status == "READY" &&
            row.storageUrl.isNullOrBlank() &&
            row.thumbnailUrl.isNullOrBlank() &&
            row.driveArchivedAt.isNullOrBlank()

    fun groupChronologically(
        items: List<MediaItem>,
        labelFor: (Long) -> String
    ): List<Pair<String, List<MediaItem>>> {
        if (items.isEmpty()) return emptyList()
        val ordered = items.sortedByDescending { it.capturedAtMillis }
        val groups = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in ordered) {
            val label = labelFor(item.capturedAtMillis)
            groups.getOrPut(label) { ArrayList() }.add(item)
        }
        return groups.map { it.key to it.value.toList() }
    }
}
