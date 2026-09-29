package com.mydrive.app.data.album

import com.mydrive.app.data.media.AlbumCoverResolver
import com.mydrive.app.data.model.AlbumFolder
import com.mydrive.app.data.model.AlbumKind
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.data.model.UNGROUPED_ALBUM_NAME
import com.mydrive.app.data.model.resolveAlbum
import java.util.UUID

const val USER_ALBUM_ID_PREFIX = "user:"
const val MAX_ALBUM_NAME_LENGTH = 80

enum class AlbumSort {
    RECENTLY_UPDATED,
    NAME,
    ITEM_COUNT
}

data class UserAlbumRecord(
    val id: String,
    val name: String,
    val coverMediaId: String? = null,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

sealed class AlbumMutationResult {
    data class Created(val albumId: String, val name: String) : AlbumMutationResult()
    data class Renamed(val albumId: String, val name: String) : AlbumMutationResult()
    data class Deleted(val albumId: String) : AlbumMutationResult()
    data class MembersChanged(
        val albumId: String,
        val added: Int = 0,
        val removed: Int = 0
    ) : AlbumMutationResult()
    data class CoverChanged(val albumId: String, val coverMediaId: String?) : AlbumMutationResult()
    data object BlankName : AlbumMutationResult()
    data object InvalidName : AlbumMutationResult()
    data object ReservedName : AlbumMutationResult()
    data object DuplicateName : AlbumMutationResult()
    data object NotFound : AlbumMutationResult()
    data object NotUserAlbum : AlbumMutationResult()
    data object Unchanged : AlbumMutationResult()
}

data class AlbumCatalogState(
    val albums: List<UserAlbumRecord> = emptyList(),
    val members: Map<String, Set<String>> = emptyMap()
) {
    fun album(id: String): UserAlbumRecord? = albums.firstOrNull { it.id == id }

    fun memberIds(albumId: String): Set<String> = members[albumId].orEmpty()

    fun create(
        rawName: String,
        now: Long,
        folderNames: Collection<String> = emptyList(),
        id: String = GalleryAlbumCatalog.newAlbumId()
    ): Pair<AlbumCatalogState, AlbumMutationResult> {
        if (!GalleryAlbumCatalog.isUserAlbumId(id)) {
            return this to AlbumMutationResult.NotUserAlbum
        }
        if (albums.any { it.id == id }) {
            return this to AlbumMutationResult.DuplicateName
        }
        val name = when (val check = GalleryAlbumCatalog.validateName(rawName, existingNames(), folderNames)) {
            is GalleryAlbumCatalog.NameValidation.Valid -> check.name
            GalleryAlbumCatalog.NameValidation.Blank -> return this to AlbumMutationResult.BlankName
            GalleryAlbumCatalog.NameValidation.TooLong -> return this to AlbumMutationResult.InvalidName
            GalleryAlbumCatalog.NameValidation.Reserved -> return this to AlbumMutationResult.ReservedName
            GalleryAlbumCatalog.NameValidation.Duplicate -> return this to AlbumMutationResult.DuplicateName
        }
        val record = UserAlbumRecord(
            id = id,
            name = name,
            coverMediaId = null,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        return copy(albums = albums + record) to AlbumMutationResult.Created(id, name)
    }

    fun rename(
        albumId: String,
        rawName: String,
        now: Long,
        folderNames: Collection<String> = emptyList()
    ): Pair<AlbumCatalogState, AlbumMutationResult> {
        if (!GalleryAlbumCatalog.isUserAlbumId(albumId)) {
            return this to AlbumMutationResult.NotUserAlbum
        }
        val current = album(albumId) ?: return this to AlbumMutationResult.NotFound
        val name = when (
            val check = GalleryAlbumCatalog.validateName(
                rawName,
                existingNames(ignoreId = albumId),
                folderNames
            )
        ) {
            is GalleryAlbumCatalog.NameValidation.Valid -> check.name
            GalleryAlbumCatalog.NameValidation.Blank -> return this to AlbumMutationResult.BlankName
            GalleryAlbumCatalog.NameValidation.TooLong -> return this to AlbumMutationResult.InvalidName
            GalleryAlbumCatalog.NameValidation.Reserved -> return this to AlbumMutationResult.ReservedName
            GalleryAlbumCatalog.NameValidation.Duplicate -> return this to AlbumMutationResult.DuplicateName
        }
        if (name == current.name) return this to AlbumMutationResult.Unchanged
        val next = current.copy(name = name, updatedAtMillis = now)
        return replaceAlbum(next) to AlbumMutationResult.Renamed(albumId, name)
    }

    fun delete(albumId: String): Pair<AlbumCatalogState, AlbumMutationResult> {
        if (!GalleryAlbumCatalog.isUserAlbumId(albumId)) {
            return this to AlbumMutationResult.NotUserAlbum
        }
        if (album(albumId) == null) return this to AlbumMutationResult.NotFound
        return copy(
            albums = albums.filterNot { it.id == albumId },
            members = members - albumId
        ) to AlbumMutationResult.Deleted(albumId)
    }

    fun addMedia(
        albumId: String,
        mediaIds: Collection<String>,
        now: Long
    ): Pair<AlbumCatalogState, AlbumMutationResult> {
        if (!GalleryAlbumCatalog.isUserAlbumId(albumId)) {
            return this to AlbumMutationResult.NotUserAlbum
        }
        val current = album(albumId) ?: return this to AlbumMutationResult.NotFound
        val existing = memberIds(albumId)
        val added = LinkedHashSet<String>()
        for (id in mediaIds) {
            val trimmed = id.trim()
            if (trimmed.isEmpty() || trimmed in existing || trimmed in added) continue
            added += trimmed
        }
        if (added.isEmpty()) return this to AlbumMutationResult.Unchanged
        val nextMembers = LinkedHashSet(existing).apply { addAll(added) }
        val cover = current.coverMediaId?.takeIf { it in nextMembers } ?: added.first()
        val next = current.copy(coverMediaId = cover, updatedAtMillis = now)
        return copy(
            albums = replaceRecord(next),
            members = members + (albumId to nextMembers)
        ) to AlbumMutationResult.MembersChanged(albumId, added = added.size)
    }

    fun removeMedia(
        albumId: String,
        mediaIds: Collection<String>,
        now: Long
    ): Pair<AlbumCatalogState, AlbumMutationResult> {
        if (!GalleryAlbumCatalog.isUserAlbumId(albumId)) {
            return this to AlbumMutationResult.NotUserAlbum
        }
        val current = album(albumId) ?: return this to AlbumMutationResult.NotFound
        val existing = memberIds(albumId)
        val removing = mediaIds.map { it.trim() }.filter { it.isNotEmpty() && it in existing }.toSet()
        if (removing.isEmpty()) return this to AlbumMutationResult.Unchanged
        val nextMembers = existing - removing
        val cover = GalleryAlbumCatalog.fallbackCoverId(current.coverMediaId, nextMembers)
        val next = current.copy(coverMediaId = cover, updatedAtMillis = now)
        return copy(
            albums = replaceRecord(next),
            members = members + (albumId to nextMembers)
        ) to AlbumMutationResult.MembersChanged(albumId, removed = removing.size)
    }

    fun moveMedia(
        fromAlbumId: String,
        toAlbumId: String,
        mediaIds: Collection<String>,
        now: Long
    ): Pair<AlbumCatalogState, AlbumMutationResult> {
        if (fromAlbumId == toAlbumId) return this to AlbumMutationResult.Unchanged
        if (!GalleryAlbumCatalog.isUserAlbumId(toAlbumId)) {
            return this to AlbumMutationResult.NotUserAlbum
        }
        val added = addMedia(toAlbumId, mediaIds, now)
        if (added.second is AlbumMutationResult.NotFound ||
            added.second is AlbumMutationResult.NotUserAlbum
        ) {
            return added
        }
        var nextState = added.first
        val addedCount = (added.second as? AlbumMutationResult.MembersChanged)?.added ?: 0
        var removedCount = 0
        if (GalleryAlbumCatalog.isUserAlbumId(fromAlbumId) && nextState.album(fromAlbumId) != null) {
            val removed = nextState.removeMedia(fromAlbumId, mediaIds, now)
            nextState = removed.first
            removedCount = (removed.second as? AlbumMutationResult.MembersChanged)?.removed ?: 0
        }
        if (addedCount == 0 && removedCount == 0) {
            return this to AlbumMutationResult.Unchanged
        }
        return nextState to AlbumMutationResult.MembersChanged(
            albumId = toAlbumId,
            added = addedCount,
            removed = removedCount
        )
    }

    fun setCover(
        albumId: String,
        mediaId: String?,
        now: Long
    ): Pair<AlbumCatalogState, AlbumMutationResult> {
        if (!GalleryAlbumCatalog.isUserAlbumId(albumId)) {
            return this to AlbumMutationResult.NotUserAlbum
        }
        val current = album(albumId) ?: return this to AlbumMutationResult.NotFound
        val membersForAlbum = memberIds(albumId)
        val nextCover = mediaId?.trim()?.takeIf { it.isNotEmpty() && it in membersForAlbum }
        if (nextCover == current.coverMediaId) return this to AlbumMutationResult.Unchanged
        val next = current.copy(coverMediaId = nextCover, updatedAtMillis = now)
        return replaceAlbum(next) to AlbumMutationResult.CoverChanged(albumId, nextCover)
    }

    private fun existingNames(ignoreId: String? = null): Collection<String> =
        albums.filter { it.id != ignoreId }.map { it.name }

    private fun replaceAlbum(next: UserAlbumRecord): AlbumCatalogState =
        copy(albums = replaceRecord(next))

    private fun replaceRecord(next: UserAlbumRecord): List<UserAlbumRecord> =
        albums.map { if (it.id == next.id) next else it }
}

object GalleryAlbumCatalog {

    sealed class NameValidation {
        data class Valid(val name: String) : NameValidation()
        data object Blank : NameValidation()
        data object TooLong : NameValidation()
        data object Reserved : NameValidation()
        data object Duplicate : NameValidation()
    }

    fun isUserAlbumId(id: String): Boolean =
        id.startsWith(USER_ALBUM_ID_PREFIX) && id.length > USER_ALBUM_ID_PREFIX.length

    fun newAlbumId(): String = USER_ALBUM_ID_PREFIX + UUID.randomUUID().toString()

    fun normalizeName(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    fun isReservedName(name: String): Boolean {
        val normalized = normalizeName(name)
        if (normalized.isEmpty()) return false
        if (normalized.equals("My Drive", ignoreCase = true)) return true
        if (normalized.equals("mydrive", ignoreCase = true)) return true
        return false
    }

    fun validateName(
        raw: String,
        existingAlbumNames: Collection<String>,
        folderNames: Collection<String> = emptyList()
    ): NameValidation {
        val name = normalizeName(raw)
        if (name.isEmpty()) return NameValidation.Blank
        if (name.length > MAX_ALBUM_NAME_LENGTH) return NameValidation.TooLong
        if (isReservedName(name)) return NameValidation.Reserved
        val taken = (existingAlbumNames.asSequence() + folderNames.asSequence())
            .map { normalizeName(it) }
            .filter { it.isNotEmpty() }
        if (taken.any { it.equals(name, ignoreCase = true) }) return NameValidation.Duplicate
        return NameValidation.Valid(name)
    }

    fun fallbackCoverId(preferred: String?, memberIds: Set<String>): String? {
        if (preferred != null && preferred in memberIds) return preferred
        return memberIds.firstOrNull()
    }

    fun visibleMembers(media: List<MediaItem>, memberIds: Set<String>): List<MediaItem> {
        if (memberIds.isEmpty() || media.isEmpty()) return emptyList()
        return media.filter { it.id in memberIds }
    }

    fun eligibleToAdd(media: List<MediaItem>, memberIds: Set<String>): List<MediaItem> {
        if (media.isEmpty()) return emptyList()
        return media.filter { item ->
            item.id.isNotBlank() &&
                !item.isTrashed &&
                !item.hiddenFromLibrary &&
                item.id !in memberIds
        }
    }

    fun folderAlbums(items: List<MediaItem>): List<AlbumFolder> {
        if (items.isEmpty()) return emptyList()
        return items
            .groupBy { resolveAlbum(it.albumId, it.albumName).id }
            .map { (albumId, albumItems) -> folderAlbum(albumId, albumItems) }
    }

    fun userAlbums(
        records: List<UserAlbumRecord>,
        members: Map<String, Set<String>>,
        media: List<MediaItem>
    ): List<AlbumFolder> {
        if (records.isEmpty()) return emptyList()
        val byId = media.associateBy { it.id }
        return records.map { record ->
            val visible = members[record.id].orEmpty().mapNotNull { byId[it] }
            userAlbum(record, visible)
        }
    }

    fun mergeAlbums(folderAlbums: List<AlbumFolder>, userAlbums: List<AlbumFolder>): List<AlbumFolder> {
        if (userAlbums.isEmpty()) return folderAlbums
        if (folderAlbums.isEmpty()) return userAlbums
        return folderAlbums + userAlbums
    }

    fun sortAlbums(albums: List<AlbumFolder>, sort: AlbumSort): List<AlbumFolder> = when (sort) {
        AlbumSort.RECENTLY_UPDATED -> albums.sortedWith(
            compareByDescending<AlbumFolder> { it.updatedAtMillis }
                .thenBy { it.name.lowercase() }
        )
        AlbumSort.NAME -> albums.sortedBy { it.name.lowercase() }
        AlbumSort.ITEM_COUNT -> albums.sortedWith(
            compareByDescending<AlbumFolder> { it.mediaCount }
                .thenBy { it.name.lowercase() }
        )
    }

    fun mediaForAlbum(
        albumId: String,
        media: List<MediaItem>,
        members: Map<String, Set<String>>
    ): List<MediaItem> {
        if (isUserAlbumId(albumId)) {
            return visibleMembers(media, members[albumId].orEmpty())
        }
        return media.filter { it.albumId == albumId }
    }

    fun folderNames(albums: List<AlbumFolder>): Collection<String> =
        albums.filter { it.kind == AlbumKind.FOLDER }.map { it.name }

    fun nameErrorMessage(result: AlbumMutationResult): String? = when (result) {
        AlbumMutationResult.BlankName -> "Enter an album name."
        AlbumMutationResult.InvalidName -> "That name is too long."
        AlbumMutationResult.ReservedName -> "My Drive cannot be used as an album name."
        AlbumMutationResult.DuplicateName -> "An album with that name already exists."
        else -> null
    }

    fun deleteAlbumTitle(name: String): String = "Delete \"$name\"?"

    fun deleteAlbumBody(): String =
        "This album will be removed. Photos and videos inside it will stay in Photos and will not be moved to Trash or deleted."

    fun removeFromAlbumTitle(count: Int): String =
        if (count == 1) "Remove 1 item from album?" else "Remove $count items from album?"

    fun removeFromAlbumBody(count: Int): String =
        if (count == 1) {
            "This item will leave the album but stay in Photos. It will not be moved to Trash or deleted."
        } else {
            "These $count items will leave the album but stay in Photos. They will not be moved to Trash or deleted."
        }

    fun removeFromAlbumMessage(counts: Pair<Int, Int>): String {
        val (succeeded, failed) = counts
        if (failed == 0) {
            return if (succeeded == 1) "Removed from album" else "$succeeded items removed from album"
        }
        if (succeeded == 0) {
            return if (failed == 1) "Could not remove this item." else "$failed items could not be removed."
        }
        return "$succeeded removed, $failed failed"
    }

    fun addToAlbumMessage(added: Int): String =
        if (added == 1) "Added to album" else "$added items added to album"

    fun moveToAlbumMessage(moved: Int): String =
        if (moved == 1) "Moved to album" else "$moved items moved"

    private fun folderAlbum(albumId: String, albumItems: List<MediaItem>): AlbumFolder {
        val newest = albumItems.maxByOrNull { it.capturedAtMillis }
        val cover = AlbumCoverResolver.select(albumItems)
        return AlbumFolder(
            id = albumId,
            name = newest?.let { resolveAlbum(it.albumId, it.albumName).name } ?: UNGROUPED_ALBUM_NAME,
            coverSeed = cover?.seed ?: 0,
            coverType = cover?.type ?: MediaType.PHOTO,
            mediaCount = albumItems.size,
            coverUri = cover?.uri.orEmpty(),
            coverPreviewUri = cover?.previewUri,
            coverRemoteMediaId = cover?.remoteMediaId,
            coverVersion = cover?.version,
            kind = AlbumKind.FOLDER,
            coverMediaId = null,
            updatedAtMillis = newest?.capturedAtMillis ?: 0L,
            createdAtMillis = albumItems.minOfOrNull { it.capturedAtMillis } ?: 0L
        )
    }

    private fun userAlbum(record: UserAlbumRecord, items: List<MediaItem>): AlbumFolder {
        val cover = AlbumCoverResolver.select(items, record.coverMediaId)
        return AlbumFolder(
            id = record.id,
            name = record.name,
            coverSeed = cover?.seed ?: 0,
            coverType = cover?.type ?: MediaType.PHOTO,
            mediaCount = items.size,
            coverUri = cover?.uri.orEmpty(),
            coverPreviewUri = cover?.previewUri,
            coverRemoteMediaId = cover?.remoteMediaId,
            coverVersion = cover?.version,
            kind = AlbumKind.USER,
            coverMediaId = record.coverMediaId,
            updatedAtMillis = record.updatedAtMillis,
            createdAtMillis = record.createdAtMillis
        )
    }

    private val WHITESPACE = Regex("\\s+")
}
