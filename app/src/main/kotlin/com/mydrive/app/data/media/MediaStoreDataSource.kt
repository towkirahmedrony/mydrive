package com.mydrive.app.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.MediaType
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
        if (failures == 2) throw MediaQueryException()
        (photos + videos).sortedByDescending { it.capturedAtMillis }
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
        return try {
            queryWithProjection(collection, type, idPrefix, buildProjection(type), sortOrder)
                ?: queryWithProjection(collection, type, idPrefix, minimalProjection(type), sortOrder)
                ?: throw MediaQueryException()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun queryWithProjection(
        collection: Uri,
        type: MediaType,
        idPrefix: String,
        projection: Array<String>,
        sortOrder: String
    ): List<MediaItem>? {
        val items = mutableListOf<MediaItem>()
        val cursor = try {
            appContext.contentResolver.query(collection, projection, null, null, sortOrder)
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
                        thumbnailSeed = (mediaStoreId % Int.MAX_VALUE).toInt()
                    )
                } catch (_: Exception) {
                }
            }
        }
        return items
    }

    private fun buildProjection(type: MediaType): Array<String> {
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
        return columns.toTypedArray()
    }

    private fun minimalProjection(type: MediaType): Array<String> {
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
        return columns.toTypedArray()
    }

    private fun albumNameFromPath(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) return null
        return relativePath
            .trim('/')
            .split('/')
            .lastOrNull { it.isNotBlank() }
    }
}

class MediaQueryException : Exception()
