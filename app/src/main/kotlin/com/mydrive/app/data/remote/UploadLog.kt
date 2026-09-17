package com.mydrive.app.data.remote

import android.util.Log

/** Structured, secret-free diagnostics for one client upload attempt. */
object UploadLog {
    private const val TAG = "MyDriveUpload"

    fun queueCreated(mediaId: String, clientUploadId: String) =
        log("queue_created", mediaId, "client_upload_id" to clientUploadId)

    fun uploadStarted(mediaId: String, resourceType: String) =
        log("cloudinary_upload_started", mediaId, "resource_type" to resourceType)

    fun authObtained(mediaId: String, resourceType: String) =
        log("cloudinary_authorization_obtained", mediaId, "resource_type" to resourceType)

    fun uploadCompleted(mediaId: String, assetId: String, publicId: String) =
        log("cloudinary_upload_completed", mediaId, "asset_id" to assetId, "public_id" to publicId)

    fun uploadFailed(mediaId: String, reason: String) =
        log("cloudinary_upload_failed", mediaId, "reason" to reason)

    fun finalizeStarted(mediaId: String, clientUploadId: String) =
        log("finalize_media_started", mediaId, "client_upload_id" to clientUploadId)

    fun finalizeSucceeded(mediaId: String, remoteMediaId: String) =
        log("finalize_media_succeeded", mediaId, "remote_media_id" to remoteMediaId)

    fun finalizeFailed(mediaId: String, reason: String) =
        log("finalize_media_failed", mediaId, "reason" to reason)

    fun localUpdated(mediaId: String, state: String) =
        log("local_record_updated", mediaId, "state" to state)

    fun workScheduled() = Log.i(TAG, "{\"event\":\"work_scheduled\"}")
    fun workStarted() = Log.i(TAG, "{\"event\":\"work_started\"}")
    fun itemClaimed(mediaId: String) = log("item_claimed", mediaId)
    fun retryScheduled(attempt: Int) = Log.i(TAG, "{\"event\":\"retry_scheduled\",\"attempt\":$attempt}")
    fun permanentFailure(reason: String) = log("permanent_failure", "worker", "reason" to reason)

    private fun log(event: String, mediaId: String, vararg fields: Pair<String, String>) {
        val details = fields.joinToString(",") { (key, value) ->
            "\"$key\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        Log.i(TAG, "{\"event\":\"$event\",\"media_id\":\"$mediaId\"${if (details.isBlank()) "" else ",$details"}}")
    }
}
