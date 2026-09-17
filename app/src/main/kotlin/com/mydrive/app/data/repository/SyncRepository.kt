package com.mydrive.app.data.repository

import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.local.SyncStateStore
import com.mydrive.app.data.local.UploadQueueDatabase
import com.mydrive.app.data.local.UploadQueueEntity
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.data.model.isActive
import com.mydrive.app.data.model.isRetryable
import com.mydrive.app.data.remote.UploadLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SyncRepository(
    private val store: SyncStateStore,
    private val queueDatabase: UploadQueueDatabase
) {
    private val dao = queueDatabase.uploadQueueDao()
    private val _records = MutableStateFlow(store.read())
    val records: StateFlow<Map<String, SyncRecord>> = _records.asStateFlow()
    private val _paused = MutableStateFlow(store.readPaused())
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    init {
        // Import the old SharedPreferences queue without deleting it. The upsert is
        // idempotent and later MediaStore reconciliation fills in URI metadata.
        runBlocking(Dispatchers.IO) {
            dao.upsertAll(_records.value.map { (id, record) ->
                UploadQueueEntity(
                    mediaId = id,
                    uploadState = queueState(record.state.toBackupState()),
                    retryCount = if (record.state.toBackupState().isRetryable) 1 else 0,
                    lastError = record.errorMessage,
                    clientUploadId = record.clientUploadId ?: UUID.randomUUID().toString(),
                    cloudinaryAssetId = record.cloudinaryAssetId,
                    cloudinaryPublicId = record.cloudinaryPublicId,
                    cloudinarySecureUrl = record.cloudinarySecureUrl,
                    updatedAt = record.updatedAtMillis
                )
            })
        }
    }

    @Synchronized
    fun enqueue(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val next = HashMap(_records.value)
        for (id in ids) {
            val current = next[id]?.state?.toBackupState()?.resumeLocally()
            if (current != null && (current == BackupState.COMPLETED || current == BackupState.WAITING || current.isActive)) continue
            val previous = next[id]
            val clientUploadId = previous?.clientUploadId ?: UUID.randomUUID().toString()
            next[id] = SyncRecord(
                state = BackupState.WAITING.name,
                errorMessage = null,
                queuedAtMillis = previous?.queuedAtMillis?.takeIf { it > 0L } ?: now,
                updatedAtMillis = now,
                cloudinaryAssetId = previous?.cloudinaryAssetId,
                cloudinaryPublicId = previous?.cloudinaryPublicId,
                cloudinarySecureUrl = previous?.cloudinarySecureUrl,
                cloudinaryVersion = previous?.cloudinaryVersion,
                cloudinaryFormat = previous?.cloudinaryFormat,
                cloudinaryResourceType = previous?.cloudinaryResourceType,
                clientUploadId = clientUploadId,
                remoteMediaId = previous?.remoteMediaId
            )
            runBlocking(Dispatchers.IO) {
                dao.find(id)?.let { entity ->
                    dao.upsert(entity.copy(uploadState = "QUEUED", lastError = null, clientUploadId = clientUploadId, updatedAt = now))
                }
            }
            UploadLog.queueCreated(id, clientUploadId)
        }
        commit(next)
    }

    @Synchronized
    fun updateState(id: String, state: BackupState, errorMessage: String? = null) {
        val record = _records.value[id] ?: return
        val normalizedError = errorMessage?.takeIf { it.isNotBlank() }
        if (record.state == state.name && record.errorMessage == normalizedError) return
        val next = HashMap(_records.value)
        val updatedAt = System.currentTimeMillis()
        next[id] = record.copy(state = state.name, errorMessage = normalizedError, updatedAtMillis = updatedAt)
        runBlocking(Dispatchers.IO) {
            val entity = dao.find(id)
            if (entity != null) dao.upsert(entity.copy(uploadState = queueState(state), retryCount = if (state.isRetryable) entity.retryCount + 1 else entity.retryCount, lastError = normalizedError, updatedAt = updatedAt))
        }
        commit(next)
        UploadLog.localUpdated(id, state.name)
    }

    @Synchronized
    fun updateCloudinaryResult(id: String, assetId: String, publicId: String, secureUrl: String?, version: Long?, format: String?, resourceType: String?) {
        val record = _records.value[id] ?: return
        val next = HashMap(_records.value)
        next[id] = record.copy(cloudinaryAssetId = assetId, cloudinaryPublicId = publicId, cloudinarySecureUrl = secureUrl, cloudinaryVersion = version, cloudinaryFormat = format, cloudinaryResourceType = resourceType, clientUploadId = record.clientUploadId ?: UUID.randomUUID().toString(), updatedAtMillis = System.currentTimeMillis())
        runBlocking(Dispatchers.IO) { dao.find(id)?.let { dao.upsert(it.copy(uploadState = "UPLOADED", cloudinaryAssetId = assetId, cloudinaryPublicId = publicId, cloudinarySecureUrl = secureUrl, clientUploadId = next[id]?.clientUploadId.orEmpty(), updatedAt = System.currentTimeMillis())) } }
        commit(next)
    }

    @Synchronized
    fun updateFinalizedResult(id: String, remoteMediaId: String) {
        val record = _records.value[id] ?: return
        val next = HashMap(_records.value)
        next[id] = record.copy(remoteMediaId = remoteMediaId, updatedAtMillis = System.currentTimeMillis())
        runBlocking(Dispatchers.IO) { dao.find(id)?.let { dao.upsert(it.copy(uploadState = "COMPLETED", finalizedMediaId = remoteMediaId, updatedAt = System.currentTimeMillis())) } }
        commit(next)
    }

    fun retry(id: String) = retryAll(listOf(id))

    @Synchronized
    fun retryAll(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        val next = HashMap(_records.value)
        ids.forEach { id ->
            val record = next[id] ?: return@forEach
            if (!record.state.toBackupState().isRetryable) return@forEach
            next[id] = record.copy(state = BackupState.WAITING.name, errorMessage = null, updatedAtMillis = now)
            runBlocking(Dispatchers.IO) { dao.find(id)?.let { dao.upsert(it.copy(uploadState = "RETRYING", lastError = null, updatedAt = now)) } }
        }
        commit(next)
    }

    @Synchronized fun cancel(id: String) { val record = _records.value[id] ?: return; if (record.state.toBackupState() == BackupState.COMPLETED) return; updateState(id, BackupState.CANCELLED) }
    fun setPaused(paused: Boolean) { if (_paused.value != paused) { _paused.value = paused; store.writePaused(paused) } }

    @Synchronized
    fun reconcile(presentIds: Set<String>) { val current = _records.value; if (current.isNotEmpty() && current.keys.any { it !in presentIds }) commit(current.filterKeys { it in presentIds }) }

    suspend fun reconcileMedia(items: List<MediaItem>) {
        val now = System.currentTimeMillis()
        dao.upsertAll(items.map { item ->
            val record = _records.value[item.id]
            UploadQueueEntity(
                mediaId = item.id, localMediaId = item.mediaStoreId, contentUri = item.uri,
                fileName = item.filename, mimeType = item.mimeType, fileSize = item.fileSizeBytes,
                createdAt = record?.queuedAtMillis?.takeIf { it > 0 } ?: now,
                uploadState = queueState(record?.state?.toBackupState() ?: BackupState.NOT_STARTED),
                retryCount = 0, lastError = record?.errorMessage,
                clientUploadId = record?.clientUploadId ?: UUID.randomUUID().toString(),
                cloudinaryAssetId = record?.cloudinaryAssetId, cloudinaryPublicId = record?.cloudinaryPublicId,
                cloudinarySecureUrl = record?.cloudinarySecureUrl, finalizedMediaId = record?.remoteMediaId,
                updatedAt = record?.updatedAtMillis ?: now
            )
        })
    }

    fun pendingIds(): List<String> = runBlocking(Dispatchers.IO) { dao.pending().map { it.mediaId } }

    private fun commit(next: Map<String, SyncRecord>) { _records.value = next; store.write(next) }
}

private fun queueState(state: BackupState): String = when (state) {
    BackupState.NOT_STARTED -> "DETECTED"
    BackupState.WAITING -> "QUEUED"
    BackupState.CLOUDINARY_COMPLETED -> "UPLOADED"
    BackupState.FINALIZING_SUPABASE -> "FINALIZING"
    BackupState.COMPLETED -> "COMPLETED"
    BackupState.FAILED -> "FAILED"
    BackupState.CANCELLED -> "FAILED"
    else -> "UPLOADING"
}

fun String.toBackupState(): BackupState = try { BackupState.valueOf(this) } catch (_: IllegalArgumentException) { BackupState.NOT_STARTED }
fun BackupState.resumeLocally(): BackupState = when (this) {
    BackupState.PREPARING, BackupState.UPLOADING, BackupState.PROCESSING, BackupState.SENDING_TELEGRAM,
    BackupState.PAUSED, BackupState.REQUESTING_CLOUDINARY_AUTH, BackupState.UPLOADING_TO_CLOUDINARY,
    BackupState.FINALIZING_SUPABASE -> BackupState.WAITING
    else -> this
}
