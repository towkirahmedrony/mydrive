package com.mydrive.app.data.remote

import android.util.Log
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.LogLevel
import com.mydrive.app.debug.OperationTrace

/** Structured, secret-free diagnostics for one client upload attempt. */
object UploadLog {
    private const val TAG = "MyDriveUpload"

    fun queueCreated(mediaId: String, clientUploadId: String) {
        val operationId = OperationTrace.idFor(mediaId)
        DeveloperLogger.info(
            category = LogCategory.ROOM,
            event = "QUEUE_CREATED",
            message = "Room queue inserted",
            operationId = operationId,
            localMediaId = mediaId,
            clientUploadId = clientUploadId
        )
        log("queue_created", mediaId, "client_upload_id" to clientUploadId, "operation_id" to operationId)
    }

    fun uploadStarted(mediaId: String, resourceType: String) {
        DeveloperLogger.info(
            category = LogCategory.CLOUDINARY_UPLOAD,
            event = "UPLOAD_STARTED",
            message = "Cloudinary upload started",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            metadata = mapOf("resource_type" to resourceType)
        )
        log("cloudinary_upload_started", mediaId, "resource_type" to resourceType)
    }

    fun authObtained(mediaId: String, resourceType: String) {
        DeveloperLogger.info(
            category = LogCategory.CLOUDINARY_AUTH,
            event = "AUTH_OBTAINED",
            message = "Cloudinary authorization obtained",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            metadata = mapOf("resource_type" to resourceType)
        )
        log("cloudinary_authorization_obtained", mediaId, "resource_type" to resourceType)
    }

    fun uploadCompleted(mediaId: String, assetId: String, publicId: String) {
        DeveloperLogger.info(
            category = LogCategory.CLOUDINARY_UPLOAD,
            event = "UPLOAD_COMPLETED",
            message = "Cloudinary upload completed",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            metadata = mapOf(
                "asset_id_present" to (assetId.isNotBlank()).toString(),
                "public_id_present" to (publicId.isNotBlank()).toString()
            )
        )
        log("cloudinary_upload_completed", mediaId, "asset_id" to assetId, "public_id" to publicId)
    }

    fun uploadFailed(mediaId: String, reason: String) {
        DeveloperLogger.error(
            category = LogCategory.CLOUDINARY_UPLOAD,
            event = "UPLOAD_FAILED",
            message = "Cloudinary upload failed: $reason",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            metadata = mapOf("reason" to reason)
        )
        log("cloudinary_upload_failed", mediaId, "reason" to reason)
    }

    fun finalizeStarted(mediaId: String, clientUploadId: String) {
        DeveloperLogger.info(
            category = LogCategory.FINALIZE,
            event = "FINALIZE_STARTED",
            message = "finalize-media request started",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            clientUploadId = clientUploadId
        )
        log("finalize_media_started", mediaId, "client_upload_id" to clientUploadId)
    }

    fun finalizeSucceeded(mediaId: String, remoteMediaId: String) {
        DeveloperLogger.info(
            category = LogCategory.FINALIZE,
            event = "FINALIZE_SUCCEEDED",
            message = "finalize-media succeeded",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            metadata = mapOf("remote_media_id" to remoteMediaId)
        )
        log("finalize_media_succeeded", mediaId, "remote_media_id" to remoteMediaId)
    }

    fun finalizeFailed(mediaId: String, reason: String) {
        DeveloperLogger.error(
            category = LogCategory.FINALIZE,
            event = "FINALIZE_FAILED",
            message = "finalize-media failed: $reason",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            metadata = mapOf("reason" to reason)
        )
        log("finalize_media_failed", mediaId, "reason" to reason)
    }

    fun localUpdated(mediaId: String, state: String) {
        DeveloperLogger.info(
            category = LogCategory.ROOM,
            event = "LOCAL_RECORD_UPDATED",
            message = "Local record updated to $state",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId,
            metadata = mapOf("state" to state)
        )
        log("local_record_updated", mediaId, "state" to state)
    }

    fun workScheduled() {
        DeveloperLogger.info(
            category = LogCategory.WORKMANAGER,
            event = "WORK_SCHEDULED",
            message = "Upload work scheduled",
            metadata = mapOf("unique_name" to "mydrive-media-upload-queue")
        )
        Log.i(TAG, "{\"event\":\"work_scheduled\"}")
    }

    fun workStarted() {
        DeveloperLogger.info(
            category = LogCategory.WORKMANAGER,
            event = "WORK_STARTED",
            message = "Upload worker started"
        )
        Log.i(TAG, "{\"event\":\"work_started\"}")
    }

    fun itemClaimed(mediaId: String) {
        DeveloperLogger.info(
            category = LogCategory.WORKMANAGER,
            event = "ITEM_CLAIMED",
            message = "Worker claimed queue item",
            operationId = OperationTrace.idFor(mediaId),
            localMediaId = mediaId
        )
        log("item_claimed", mediaId)
    }

    fun retryScheduled(attempt: Int) {
        DeveloperLogger.warn(
            category = LogCategory.WORKMANAGER,
            event = "RETRY_SCHEDULED",
            message = "Worker retry scheduled",
            retryCount = attempt,
            metadata = mapOf("attempt" to attempt.toString())
        )
        Log.i(TAG, "{\"event\":\"retry_scheduled\",\"attempt\":$attempt}")
    }

    fun permanentFailure(reason: String) {
        DeveloperLogger.log(
            level = LogLevel.FATAL,
            category = LogCategory.WORKMANAGER,
            event = "PERMANENT_FAILURE",
            message = "Worker stopped after attempt limit",
            metadata = mapOf("reason" to reason)
        )
        log("permanent_failure", "worker", "reason" to reason)
    }

    private fun log(event: String, mediaId: String, vararg fields: Pair<String, String>) {
        val details = fields.joinToString(",") { (key, value) ->
            "\"$key\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        Log.i(TAG, "{\"event\":\"$event\",\"media_id\":\"$mediaId\"${if (details.isBlank()) "" else ",$details"}}")
    }
}
