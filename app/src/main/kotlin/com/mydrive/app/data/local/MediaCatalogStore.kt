package com.mydrive.app.data.local

import android.os.Looper
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType

/**
 * One rendered row of the local gallery catalog.
 *
 * This is the LAST KNOWN state of the Photos/Albums library as the user last saw
 * it — the rows the Compose UI was handed, not a copy of the media itself.
 * MediaStore stays the authoritative index for local media and `media_assets`
 * stays authoritative for the cloud; this table only remembers what the
 * composition produced, so a cold start can draw the gallery from disk before
 * any scan, network request or reconciliation runs.
 *
 * Nothing about the *provider* is stored as album identity: [albumId] is always
 * an item's real device folder (or the ungrouped album), never "My Drive".
 *
 * It is deliberately part of the app's existing Room database
 * ([UploadQueueDatabase]) rather than a second database, and it is scoped by
 * [ownerUserId] so one account can never render another account's library.
 */
@Entity(
    tableName = "media_catalog",
    primaryKeys = ["ownerUserId", "mediaId"]
)
data class MediaCatalogEntity(
    val ownerUserId: String,
    val mediaId: String,
    val filename: String = "",
    val type: String = MediaType.PHOTO.name,
    val fileSizeBytes: Long = 0L,
    val capturedAtMillis: Long = 0L,
    val device: String = "",
    val resolution: String = "",
    val durationSeconds: Int? = null,
    val isFavorite: Boolean = false,
    val backupState: String = BackupState.NOT_STARTED.name,
    val backupCompleted: Boolean = false,
    val telegramCompleted: Boolean = false,
    val thumbnailSeed: Int = 0,
    val albumId: String = "camera",
    val albumName: String = "",
    val mediaStoreId: Long = 0L,
    val uri: String = "",
    val mimeType: String = "",
    val dateAddedMillis: Long = 0L,
    val dateModifiedMillis: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
    val relativePath: String? = null,
    val cloudinaryAssetId: String? = null,
    val cloudinaryPublicId: String? = null,
    val isTrashed: Boolean = false,
    val dateExpiresMillis: Long = 0L,
    val remoteMediaId: String? = null,
    val thumbnailUrl: String? = null,
    val originLocal: Boolean = true,
    val originalUrl: String? = null,
    val cloudBackedUp: Boolean = false,
    val errorMessage: String? = null
)

@Dao
interface MediaCatalogDao {

    /** Blocking read, for the cold-start hydration that must finish before the first frame. */
    @Query("SELECT * FROM media_catalog WHERE ownerUserId = :ownerUserId ORDER BY capturedAtMillis DESC, mediaId ASC")
    fun snapshotNow(ownerUserId: String): List<MediaCatalogEntity>

    @Query("SELECT * FROM media_catalog WHERE ownerUserId = :ownerUserId ORDER BY capturedAtMillis DESC, mediaId ASC")
    suspend fun snapshot(ownerUserId: String): List<MediaCatalogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MediaCatalogEntity>)

    @Query("DELETE FROM media_catalog WHERE ownerUserId = :ownerUserId AND mediaId IN (:mediaIds)")
    suspend fun deleteByIds(ownerUserId: String, mediaIds: List<String>)

    @Query("DELETE FROM media_catalog WHERE ownerUserId = :ownerUserId")
    suspend fun deleteFor(ownerUserId: String)
}

/**
 * The change set between the catalog on disk and the freshly composed library.
 *
 * Pure and side-effect free so the "apply only what changed" rule is testable
 * without Room or a device: an unchanged library yields an empty [upserts] and
 * an empty [removedIds], and therefore no write at all.
 */
object MediaCatalogReconciler {

    data class Diff(
        val upserts: List<MediaCatalogEntity>,
        val removedIds: List<String>
    ) {
        val isEmpty: Boolean get() = upserts.isEmpty() && removedIds.isEmpty()
    }

    fun diff(ownerUserId: String, previous: List<MediaItem>, next: List<MediaItem>): Diff {
        if (ownerUserId.isBlank()) return Diff(emptyList(), emptyList())
        val previousById = HashMap<String, MediaCatalogEntity>(previous.size * 2)
        for (item in previous) {
            previousById[item.id] = item.toCatalogEntity(ownerUserId)
        }
        val upserts = ArrayList<MediaCatalogEntity>()
        val seen = HashSet<String>(next.size * 2)
        for (item in next) {
            seen += item.id
            val entity = item.toCatalogEntity(ownerUserId)
            // `position` is not stored: the rendering order is derived from
            // (capturedAtMillis DESC, mediaId ASC) by both the query and the
            // composition, so inserting one new photo rewrites exactly one row.
            if (previousById[item.id] != entity) upserts += entity
        }
        if (previousById.isEmpty()) return Diff(upserts, emptyList())
        val removed = previousById.keys.filterNot { it in seen }
        return Diff(upserts, removed)
    }
}

/**
 * Account-scoped persistence for the composed gallery.
 *
 * Reads are served from the existing Room database; writes are incremental, so
 * a refresh that changes nothing writes nothing and a refresh that adds one
 * photo writes one row.
 */
class MediaCatalogStore(private val dao: MediaCatalogDao) {

    /**
     * The persisted catalog for [ownerUserId], read synchronously.
     *
     * Room refuses blocking queries on the main thread, and that refusal is
     * respected here: on the main thread this returns `null`, which the caller
     * reads as "not known yet" and answers with an asynchronous load instead of
     * a wrong "there is nothing".
     */
    fun snapshotBlocking(ownerUserId: String): List<MediaCatalogEntity>? {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return runCatching { dao.snapshotNow(owner) }.getOrElse { null }
    }

    suspend fun snapshot(ownerUserId: String): List<MediaCatalogEntity> {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { dao.snapshot(owner) }.getOrDefault(emptyList())
    }

    /**
     * Applies only the difference between [previous] and [next].
     *
     * Never a "clear everything, write everything": an unchanged library is a
     * no-op, and the row identity a caller already holds stays untouched.
     */
    suspend fun save(
        ownerUserId: String,
        previous: List<MediaItem>,
        next: List<MediaItem>
    ): Boolean {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return false
        val diff = MediaCatalogReconciler.diff(owner, previous, next)
        if (diff.upserts.isNotEmpty()) dao.upsertAll(diff.upserts)
        if (diff.removedIds.isNotEmpty()) dao.deleteByIds(owner, diff.removedIds)
        return !diff.isEmpty
    }

    /** Drops one account's cached gallery. Session-scoped, never called on a normal launch. */
    suspend fun clearOwner(ownerUserId: String) {
        val owner = ownerUserId.takeIf { it.isNotBlank() } ?: return
        dao.deleteFor(owner)
    }
}

internal fun MediaItem.toCatalogEntity(ownerUserId: String): MediaCatalogEntity = MediaCatalogEntity(
    ownerUserId = ownerUserId,
    mediaId = id,
    filename = filename,
    type = type.name,
    fileSizeBytes = fileSizeBytes,
    capturedAtMillis = capturedAtMillis,
    device = device,
    resolution = resolution,
    durationSeconds = durationSeconds,
    isFavorite = isFavorite,
    backupState = backupState.name,
    backupCompleted = backupCompleted,
    telegramCompleted = telegramCompleted,
    thumbnailSeed = thumbnailSeed,
    albumId = albumId,
    albumName = albumName,
    mediaStoreId = mediaStoreId,
    uri = uri,
    mimeType = mimeType,
    dateAddedMillis = dateAddedMillis,
    dateModifiedMillis = dateModifiedMillis,
    width = width,
    height = height,
    durationMillis = durationMillis,
    relativePath = relativePath,
    cloudinaryAssetId = cloudinaryAssetId,
    cloudinaryPublicId = cloudinaryPublicId,
    isTrashed = isTrashed,
    dateExpiresMillis = dateExpiresMillis,
    remoteMediaId = remoteMediaId,
    thumbnailUrl = thumbnailUrl,
    originLocal = originLocal,
    originalUrl = originalUrl,
    cloudBackedUp = cloudBackedUp,
    errorMessage = errorMessage
)

/**
 * Rebuilds the render model from the persisted row.
 *
 * Every field the entity carries is restored, so a hydrated library is equal to
 * the library it was written from — which is what makes "write only when
 * something changed" and "render the same thing twice" both true.
 */
fun MediaCatalogEntity.toMediaItem(favoriteIds: Set<String> = emptySet()): MediaItem = MediaItem(
    id = mediaId,
    filename = filename,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.PHOTO),
    fileSizeBytes = fileSizeBytes,
    capturedAtMillis = capturedAtMillis,
    device = device,
    resolution = resolution,
    durationSeconds = durationSeconds,
    isFavorite = isFavorite || mediaId in favoriteIds,
    backupState = runCatching { BackupState.valueOf(backupState) }.getOrDefault(BackupState.NOT_STARTED),
    backupCompleted = backupCompleted,
    telegramCompleted = telegramCompleted,
    thumbnailSeed = thumbnailSeed,
    progress = 0f,
    errorMessage = errorMessage,
    albumId = albumId,
    albumName = albumName,
    mediaStoreId = mediaStoreId,
    uri = uri,
    mimeType = mimeType,
    dateAddedMillis = dateAddedMillis,
    dateModifiedMillis = dateModifiedMillis,
    width = width,
    height = height,
    durationMillis = durationMillis,
    relativePath = relativePath,
    cloudinaryAssetId = cloudinaryAssetId,
    cloudinaryPublicId = cloudinaryPublicId,
    isTrashed = isTrashed,
    dateExpiresMillis = dateExpiresMillis,
    remoteMediaId = remoteMediaId,
    thumbnailUrl = thumbnailUrl,
    originLocal = originLocal,
    hiddenFromLibrary = false,
    originalUrl = originalUrl,
    cloudBackedUp = cloudBackedUp
)

