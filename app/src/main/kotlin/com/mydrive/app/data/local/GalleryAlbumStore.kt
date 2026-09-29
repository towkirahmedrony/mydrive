package com.mydrive.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mydrive.app.data.album.AlbumCatalogState
import com.mydrive.app.data.album.UserAlbumRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Entity(
    tableName = "gallery_albums",
    primaryKeys = ["ownerUserId", "albumId"],
    indices = [Index(value = ["ownerUserId", "updatedAtMillis", "albumId"])]
)
data class GalleryAlbumEntity(
    val ownerUserId: String,
    val albumId: String,
    val name: String,
    val coverMediaId: String? = null,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

@Entity(
    tableName = "gallery_album_members",
    primaryKeys = ["ownerUserId", "albumId", "mediaId"],
    indices = [
        Index(value = ["ownerUserId", "albumId"]),
        Index(value = ["ownerUserId", "mediaId"])
    ]
)
data class GalleryAlbumMemberEntity(
    val ownerUserId: String,
    val albumId: String,
    val mediaId: String
)

@Dao
interface GalleryAlbumDao {

    @Query("SELECT * FROM gallery_albums WHERE ownerUserId = :ownerUserId ORDER BY updatedAtMillis DESC, name ASC")
    fun snapshotAlbumsNow(ownerUserId: String): List<GalleryAlbumEntity>

    @Query("SELECT * FROM gallery_albums WHERE ownerUserId = :ownerUserId ORDER BY updatedAtMillis DESC, name ASC")
    suspend fun snapshotAlbums(ownerUserId: String): List<GalleryAlbumEntity>

    @Query("SELECT * FROM gallery_album_members WHERE ownerUserId = :ownerUserId")
    fun snapshotMembersNow(ownerUserId: String): List<GalleryAlbumMemberEntity>

    @Query("SELECT * FROM gallery_album_members WHERE ownerUserId = :ownerUserId")
    suspend fun snapshotMembers(ownerUserId: String): List<GalleryAlbumMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(rows: List<GalleryAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(rows: List<GalleryAlbumMemberEntity>)

    @Query("DELETE FROM gallery_albums WHERE ownerUserId = :ownerUserId AND albumId IN (:albumIds)")
    suspend fun deleteAlbums(ownerUserId: String, albumIds: List<String>)

    @Query("DELETE FROM gallery_album_members WHERE ownerUserId = :ownerUserId AND albumId IN (:albumIds)")
    suspend fun deleteMembersForAlbums(ownerUserId: String, albumIds: List<String>)

    @Query("DELETE FROM gallery_album_members WHERE ownerUserId = :ownerUserId AND albumId = :albumId AND mediaId IN (:mediaIds)")
    suspend fun deleteMembers(ownerUserId: String, albumId: String, mediaIds: List<String>)

    @Query("DELETE FROM gallery_albums WHERE ownerUserId = :ownerUserId")
    suspend fun deleteAlbumsFor(ownerUserId: String)

    @Query("DELETE FROM gallery_album_members WHERE ownerUserId = :ownerUserId")
    suspend fun deleteMembersFor(ownerUserId: String)
}

class GalleryAlbumStore(private val dao: GalleryAlbumDao) {

    fun snapshotForStartup(ownerUserId: String): AlbumCatalogState? {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        toState(dao.snapshotAlbumsNow(owner), dao.snapshotMembersNow(owner))
                    }
                }
            }
        }.getOrNull()
    }

    suspend fun snapshot(ownerUserId: String): AlbumCatalogState {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return AlbumCatalogState()
        val albums = runCatching { dao.snapshotAlbums(owner) }.getOrDefault(emptyList())
        val members = runCatching { dao.snapshotMembers(owner) }.getOrDefault(emptyList())
        return toState(albums, members)
    }

    suspend fun save(ownerUserId: String, previous: AlbumCatalogState, next: AlbumCatalogState) {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return
        val previousIds = previous.albums.map { it.id }.toSet()
        val nextIds = next.albums.map { it.id }.toSet()
        val removedAlbumIds = previousIds - nextIds
        if (removedAlbumIds.isNotEmpty()) {
            val ids = removedAlbumIds.toList()
            dao.deleteMembersForAlbums(owner, ids)
            dao.deleteAlbums(owner, ids)
        }
        val albumUpserts = next.albums.mapNotNull { record ->
            val entity = record.toEntity(owner)
            val previousRecord = previous.album(record.id)
            if (previousRecord?.toEntity(owner) == entity) null else entity
        }
        if (albumUpserts.isNotEmpty()) dao.upsertAlbums(albumUpserts)

        next.albums.forEach { record ->
            val previousMembers = previous.memberIds(record.id)
            val nextMembers = next.memberIds(record.id)
            val added = nextMembers - previousMembers
            val removed = previousMembers - nextMembers
            if (added.isNotEmpty()) {
                dao.upsertMembers(added.map { GalleryAlbumMemberEntity(owner, record.id, it) })
            }
            if (removed.isNotEmpty()) {
                dao.deleteMembers(owner, record.id, removed.toList())
            }
        }
    }

    suspend fun clearOwner(ownerUserId: String) {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return
        dao.deleteMembersFor(owner)
        dao.deleteAlbumsFor(owner)
    }

    private fun toState(
        albums: List<GalleryAlbumEntity>,
        members: List<GalleryAlbumMemberEntity>
    ): AlbumCatalogState {
        val grouped = LinkedHashMap<String, MutableSet<String>>()
        members.forEach { row ->
            grouped.getOrPut(row.albumId) { LinkedHashSet() }.add(row.mediaId)
        }
        return AlbumCatalogState(
            albums = albums.map { it.toRecord() },
            members = grouped
        )
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 2_000L
    }
}

internal fun UserAlbumRecord.toEntity(ownerUserId: String) = GalleryAlbumEntity(
    ownerUserId = ownerUserId,
    albumId = id,
    name = name,
    coverMediaId = coverMediaId,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis
)

internal fun GalleryAlbumEntity.toRecord() = UserAlbumRecord(
    id = albumId,
    name = name,
    coverMediaId = coverMediaId,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis
)
