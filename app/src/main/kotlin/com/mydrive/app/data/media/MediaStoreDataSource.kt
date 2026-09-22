package com.mydrive.app.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.mydrive.app.data.local.UploadQueueEntity
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreDataSource(context: Context) {

    private val appContext = context.applicationContext

    suspend fun loadMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        var failures = 0
        val photos = try {
            queryCollection(imageCollection(), MediaType.PHOTO, "img")
        } catch (_: MediaQueryException) {
            failures += 1
            emptyList()
        }
        val videos = try {
            queryCollection(videoCollection(), MediaType.VIDEO, "vid")
        } catch (_: MediaQueryException) {
            failures += 1
            emptyList()
        }
        if (failures == 2) {
            DeveloperLogger.error(
                category = LogCategory.MEDIASTORE,
                event = "MEDIASTORE_QUERY_FAILED",
                message = "MediaStore photo and video queries both failed"
            )
            throw MediaQueryException()
        }
        val items = (photos + videos).sortedByDescending { it.capturedAtMillis }
        DeveloperLogger.info(
            category = LogCategory.MEDIASTORE,
            event = "MEDIASTORE_SCAN",
            message = "MediaStore scan completed",
            metadata = mapOf(
                "photos" to photos.size.toString(),
                "videos" to videos.size.toString(),
                "total" to items.size.toString()
            )
        )
        items
    }

    suspend fun loadTrashedMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext emptyList()
        }
        val photos = try {
            queryTrashedCollection(imageCollection(), MediaType.PHOTO, "img")
        } catch (_: MediaQueryException) {
            emptyList()
        }
        val videos = try {
            queryTrashedCollection(videoCollection(), MediaType.VIDEO, "vid")
        } catch (_: MediaQueryException) {
            emptyList()
        }
        (photos + videos).sortedByDescending { item ->
            item.dateExpiresMillis.takeIf { it > 0L } ?: item.dateModifiedMillis
        }
    }

    /**
     * Re-resolve a queued item without trusting the current in-memory gallery list.
     * The original URI is preferred; the MediaStore scan is only a fallback for a
     * stale URI or a refresh that temporarily omitted the item.
     */
    suspend fun resolveQueuedMedia(entity: UploadQueueEntity): MediaItem? = withContext(Dispatchers.IO) {
        val current = loadMedia()
        current.firstOrNull { it.uri == entity.contentUri }
            ?: current.firstOrNull {
                it.id == entity.mediaId ||
                    (entity.fileName.isNotBlank() && it.filename == entity.fileName &&
                        (entity.fileSize <= 0L || it.fileSizeBytes == entity.fileSize) &&
                        (entity.mimeType.isBlank() || it.mimeType == entity.mimeType))
            }
    }

    fun probeUri(rawUri: String): MediaUriProbe {
        if (rawUri.isBlank()) return MediaUriProbe(rawUri, errorType = "BlankUri", errorMessage = "URI is blank")
        val uri = try {
            Uri.parse(rawUri)
        } catch (error: Exception) {
            return MediaUriProbe(rawUri, errorType = error.javaClass.name, errorMessage = error.message)
        }
        var queryFound = false
        var queryError: Throwable? = null
        var inputOpened = false
        var inputError: Throwable? = null
        var descriptorOpened = false
        var descriptorSize: Long? = null
        var descriptorError: Throwable? = null
        try {
            appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
                queryFound = it.moveToFirst()
            }
        } catch (error: Throwable) {
            queryError = error
        }
        try {
            appContext.contentResolver.openInputStream(uri)?.use { inputOpened = true }
        } catch (error: Throwable) {
            inputError = error
        }
        try {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor: ParcelFileDescriptor ->
                descriptorOpened = true
                descriptorSize = descriptor.statSize.takeIf { it >= 0L }
            }
        } catch (error: Throwable) {
            descriptorError = error
        }
        val error = queryError ?: inputError ?: descriptorError
        return MediaUriProbe(
            rawUri = rawUri,
            authority = uri.authority,
            scheme = uri.scheme,
            mediaStoreId = uri.lastPathSegment?.toLongOrNull(),
            queryFound = queryFound,
            inputStreamOpened = inputOpened,
            fileDescriptorOpened = descriptorOpened,
            readableSize = descriptorSize,
            errorType = error?.javaClass?.name,
            errorMessage = error?.message
        )
    }

    private fun imageCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun videoCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun queryCollection(
        collection: Uri,
        type: MediaType,
        idPrefix: String
    ): List<MediaItem> {
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "${MediaStore.MediaColumns.IS_TRASHED}=0"
        } else {
            null
        }
        return try {
            queryWithProjection(collection, type, idPrefix, buildProjection(type), sortOrder, selection)
                ?: queryWithProjection(collection, type, idPrefix, minimalProjection(type), sortOrder, selection)
                ?: queryWithProjection(collection, type, idPrefix, buildProjection(type), sortOrder, null)
                ?: queryWithProjection(collection, type, idPrefix, minimalProjection(type), sortOrder, null)
                ?: throw MediaQueryException()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun queryTrashedCollection(
        collection: Uri,
        type: MediaType,
        idPrefix: String
    ): List<MediaItem> {
        val expiresSort = "${MediaStore.MediaColumns.DATE_EXPIRES} DESC"
        val modifiedSort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val selection = "${MediaStore.MediaColumns.IS_TRASHED}=1"
        return try {
            queryWithProjection(
                collection = collection,
                type = type,
                idPrefix = idPrefix,
                projection = buildProjection(type, includeTrashColumns = true),
                sortOrder = expiresSort,
                selection = selection,
                matchTrashedOnly = true
            ) ?: queryWithProjection(
                collection = collection,
                type = type,
                idPrefix = idPrefix,
                projection = buildProjection(type, includeTrashColumns = true),
                sortOrder = modifiedSort,
                selection = selection,
                matchTrashedOnly = true
            ) ?: queryWithProjection(
                collection = collection,
                type = type,
                idPrefix = idPrefix,
                projection = minimalProjection(type, includeTrashColumns = true),
                sortOrder = modifiedSort,
                selection = selection,
                matchTrashedOnly = true
            ) ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun queryWithProjection(
        collection: Uri,
        type: MediaType,
        idPrefix: String,
        projection: Array<String>,
        sortOrder: String,
        selection: String?,
        matchTrashedOnly: Boolean = false
    ): List<MediaItem>? {
        val items = mutableListOf<MediaItem>()
        val cursor = try {
            if (matchTrashedOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    }
                }
                appContext.contentResolver.query(collection, projection, args, null)
            } else {
                appContext.contentResolver.query(collection, projection, selection, null, sortOrder)
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: Exception) {
            return null
        } ?: return emptyList()

        cursor.use {
            val idCol = it.getColumnIndex(MediaStore.MediaColumns._ID)
            if (idCol < 0) return emptyList()
            val nameCol = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val addedCol = it.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedCol = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val widthCol = it.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = it.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val takenCol = it.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val bucketIdCol = it.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol = it.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val relativeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                -1
            }
            val durationCol = if (type == MediaType.VIDEO) {
                it.getColumnIndex(MediaStore.MediaColumns.DURATION)
            } else {
                -1
            }
            val expiresCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
            } else {
                -1
            }
            val trashedCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                it.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            } else {
                -1
            }

            while (it.moveToNext()) {
                try {
                    val mediaStoreId = it.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, mediaStoreId)
                    val filename = (if (nameCol >= 0) it.getString(nameCol) else null)
                        .orEmpty()
                        .ifBlank { "media_$mediaStoreId" }
                    val mime = if (mimeCol >= 0) it.getString(mimeCol).orEmpty() else ""
                    val size = if (sizeCol >= 0) it.getLong(sizeCol).coerceAtLeast(0L) else 0L
                    val dateAddedSec = if (addedCol >= 0) it.getLong(addedCol) else 0L
                    val dateModifiedSec = if (modifiedCol >= 0) it.getLong(modifiedCol) else 0L
                    val dateAddedMs = dateAddedSec * 1000L
                    val dateModifiedMs = dateModifiedSec * 1000L
                    val dateTaken = if (takenCol >= 0) it.getLong(takenCol) else 0L
                    val captured = when {
                        dateTaken > 0L -> dateTaken
                        dateAddedMs > 0L -> dateAddedMs
                        else -> dateModifiedMs
                    }
                    val width = if (widthCol >= 0) it.getInt(widthCol).coerceAtLeast(0) else 0
                    val height = if (heightCol >= 0) it.getInt(heightCol).coerceAtLeast(0) else 0
                    val durationMs = if (durationCol >= 0) it.getLong(durationCol).coerceAtLeast(0L) else 0L
                    val bucketId = if (bucketIdCol >= 0) it.getLong(bucketIdCol) else 0L
                    val bucketName = if (bucketNameCol >= 0) it.getString(bucketNameCol) else null
                    val relativePath = if (relativeCol >= 0) it.getString(relativeCol) else null
                    val albumName = bucketName?.trim().orEmpty().ifBlank {
                        albumNameFromPath(relativePath) ?: "Other"
                    }
                    val albumId = if (bucketId != 0L) bucketId.toString() else albumName
                    val durationSeconds = if (type == MediaType.VIDEO && durationMs > 0L) {
                        (durationMs / 1000L).toInt().coerceAtLeast(0)
                    } else {
                        null
                    }
                    val dateExpiresSec = if (expiresCol >= 0) it.getLong(expiresCol) else 0L
                    val isTrashed = matchTrashedOnly || (trashedCol >= 0 && it.getInt(trashedCol) == 1)
                    items += MediaItem(
                        id = "$idPrefix-$mediaStoreId",
                        mediaStoreId = mediaStoreId,
                        uri = uri.toString(),
                        filename = filename,
                        type = type,
                        mimeType = mime,
                        fileSizeBytes = size,
                        capturedAtMillis = captured,
                        dateAddedMillis = dateAddedMs,
                        dateModifiedMillis = dateModifiedMs,
                        device = Build.MODEL.orEmpty().ifBlank { "This device" },
                        resolution = if (width > 0 && height > 0) "$width x $height" else "Unknown",
                        width = width,
                        height = height,
                        durationSeconds = durationSeconds,
                        durationMillis = if (type == MediaType.VIDEO) durationMs else null,
                        relativePath = relativePath,
                        albumId = albumId,
                        albumName = albumName,
                        thumbnailSeed = (mediaStoreId % Int.MAX_VALUE).toInt(),
                        isTrashed = isTrashed,
                        dateExpiresMillis = if (dateExpiresSec > 0L) dateExpiresSec * 1000L else 0L
                    )
                } catch (_: Exception) {
                }
            }
        }
        return items
    }

    private fun buildProjection(type: MediaType, includeTrashColumns: Boolean = false): Array<String> {
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns += MediaStore.MediaColumns.RELATIVE_PATH
        }
        if (type == MediaType.VIDEO) {
            columns += MediaStore.MediaColumns.DURATION
        }
        appendTrashColumns(columns, includeTrashColumns)
        return columns.toTypedArray()
    }

    private fun minimalProjection(type: MediaType, includeTrashColumns: Boolean = false): Array<String> {
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        if (type == MediaType.VIDEO) {
            columns += MediaStore.MediaColumns.DURATION
        }
        appendTrashColumns(columns, includeTrashColumns)
        return columns.toTypedArray()
    }

    private fun appendTrashColumns(columns: MutableList<String>, includeTrashColumns: Boolean) {
        if (!includeTrashColumns) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            columns += MediaStore.MediaColumns.IS_TRASHED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns += MediaStore.MediaColumns.DATE_EXPIRES
        }
    }

    private fun albumNameFromPath(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) return null
        return relativePath
            .trim('/')
            .split('/')
            .lastOrNull { it.isNotBlank() }
    }
}

data class MediaUriProbe(
    val rawUri: String,
    val authority: String? = null,
    val scheme: String? = null,
    val mediaStoreId: Long? = null,
    val queryFound: Boolean = false,
    val inputStreamOpened: Boolean = false,
    val fileDescriptorOpened: Boolean = false,
    val readableSize: Long? = null,
    val errorType: String? = null,
    val errorMessage: String? = null
)

class MediaQueryException : Exception()
