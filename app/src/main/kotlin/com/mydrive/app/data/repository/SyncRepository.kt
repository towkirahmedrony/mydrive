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
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.OperationTrace
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
    private val _records = MutableStateFlow<Map<String, SyncRecord>>(emptyMap())
    val records: StateFlow<Map<String, SyncRecord>> = _records.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    @Volatile
    private var ownerUserId: String? = null

    @Synchronized
    fun enqueue(ids: Collection<String>, ownerUserId: String? = null) {
        if (ids.isEmpty()) return
        val boundOwner = this.ownerUserId ?: return
        if (!ownerUserId.isNullOrBlank() && ownerUserId != boundOwner) return
        val now = System.currentTimeMillis()
        val next = HashMap(_records.value)
        for (id in ids) {
            val current = next[id]?.state?.toBackupState()?.resumeLocally()
            if (current != null && (current == BackupState.COMPLETED || current == BackupState.WAITING || current.isActive)) continue
            val previous = next[id]
            val previousOwner = previous?.ownerUserId
            if (!previousOwner.isNullOrBlank() && previousOwner != boundOwner) continue
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
                remoteMediaId = previous?.remoteMediaId,
                ownerUserId = previousOwner ?: boundOwner
            )
            val previousQueue = queueState(previous?.state?.toBackupState() ?: BackupState.NOT_STARTED)
            runBlocking(Dispatchers.IO) {
                dao.find(id)?.let { entity ->
                    upsertOwned(entity.copy(uploadState = "QUEUED", lastError = null, clientUploadId = clientUploadId, ownerUserId = boundOwner, updatedAt = now))
                }
            }
            DeveloperLogger.info(
                category = LogCategory.ROOM,
                event = "QUEUE_STATE",
                message = "Queue $previousQueue → QUEUED",
                operationId = OperationTrace.idFor(id),
                localMediaId = id,
                clientUploadId = clientUploadId,
                metadata = mapOf("previous_state" to previousQueue, "new_state" to "QUEUED", "reason" to "enqueue")
            )
            UploadLog.queueCreated(id, clientUploadId)
        }
        commit(next)
    }

    @Synchronized
    fun updateState(id: String, state: BackupState, errorMessage: String? = null) {
        val record = ownedRecord(id) ?: return
        val normalizedError = errorMessage?.takeIf { it.isNotBlank() }
        if (record.state == state.name && record.errorMessage == normalizedError) return
        val next = HashMap(_records.value)
        val updatedAt = System.currentTimeMillis()
        val previousState = queueState(record.state.toBackupState())
        val newState = queueState(state)
        next[id] = record.copy(state = state.name, errorMessage = normalizedError, updatedAtMillis = updatedAt)
        runBlocking(Dispatchers.IO) {
            val entity = dao.find(id) ?: return@runBlocking
            upsertOwned(entity.copy(uploadState = newState, retryCount = if (state.isRetryable) entity.retryCount + 1 else entity.retryCount, lastError = normalizedError, updatedAt = updatedAt))
        }
        commit(next)
        DeveloperLogger.log(
            level = if (state == BackupState.FAILED) com.mydrive.app.debug.LogLevel.ERROR else com.mydrive.app.debug.LogLevel.INFO,
            category = LogCategory.ROOM,
            event = "QUEUE_STATE",
            message = "Queue $previousState → $newState",
            operationId = OperationTrace.idFor(id),
            localMediaId = id,
            clientUploadId = record.clientUploadId,
            retryCount = if (state.isRetryable) 1 else null,
            metadata = mapOf(
                "previous_state" to previousState,
                "new_state" to newState,
                "backup_state" to state.name,
                "reason" to (normalizedError ?: "state_change")
            )
        )
        UploadLog.localUpdated(id, state.name)
    }

    @Synchronized
    fun updateCloudinaryResult(id: String, assetId: String, publicId: String, secureUrl: String?, version: Long?, format: String?, resourceType: String?) {
        val record = ownedRecord(id) ?: return
        val next = HashMap(_records.value)
        next[id] = record.copy(cloudinaryAssetId = assetId, cloudinaryPublicId = publicId, cloudinarySecureUrl = secureUrl, cloudinaryVersion = version, cloudinaryFormat = format, cloudinaryResourceType = resourceType, clientUploadId = record.clientUploadId ?: UUID.randomUUID().toString(), updatedAtMillis = System.currentTimeMillis())
        runBlocking(Dispatchers.IO) {
            val entity = dao.find(id) ?: return@runBlocking
            upsertOwned(entity.copy(uploadState = "UPLOADED", cloudinaryAssetId = assetId, cloudinaryPublicId = publicId, cloudinarySecureUrl = secureUrl, clientUploadId = next[id]?.clientUploadId.orEmpty(), updatedAt = System.currentTimeMillis()))
        }
        commit(next)
    }

    @Synchronized
    fun updateFinalizedResult(id: String, remoteMediaId: String) {
        val record = ownedRecord(id) ?: return
        val next = HashMap(_records.value)
        next[id] = record.copy(remoteMediaId = remoteMediaId, updatedAtMillis = System.currentTimeMillis())
        runBlocking(Dispatchers.IO) {
            val entity = dao.find(id) ?: return@runBlocking
            upsertOwned(entity.copy(uploadState = "COMPLETED", finalizedMediaId = remoteMediaId, updatedAt = System.currentTimeMillis()))
        }
        commit(next)
    }

    fun retry(id: String) = retryAll(listOf(id))

    @Synchronized
    fun retryAll(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        val next = HashMap(_records.value)
        ids.forEach { id ->
            val record = next[id] ?: return@forEach
            if (!belongsToCurrent(record)) return@forEach
            if (!record.state.toBackupState().isRetryable) return@forEach
            next[id] = record.copy(state = BackupState.WAITING.name, errorMessage = null, updatedAtMillis = now)
            runBlocking(Dispatchers.IO) {
                val entity = dao.find(id) ?: return@runBlocking
                upsertOwned(entity.copy(uploadState = "RETRYING", lastError = null, updatedAt = now))
            }
            DeveloperLogger.warn(
                category = LogCategory.ROOM,
                event = "QUEUE_STATE",
                message = "Queue ${queueState(record.state.toBackupState())} → RETRYING",
                operationId = OperationTrace.idFor(id),
                localMediaId = id,
                clientUploadId = record.clientUploadId,
                metadata = mapOf(
                    "previous_state" to queueState(record.state.toBackupState()),
                    "new_state" to "RETRYING",
                    "reason" to "retry"
                )
            )
        }
        commit(next)
    }

    @Synchronized fun cancel(id: String) { val record = ownedRecord(id) ?: return; if (record.state.toBackupState() == BackupState.COMPLETED) return; updateState(id, BackupState.CANCELLED) }
    fun setPaused(paused: Boolean) { if (_paused.value != paused) { _paused.value = paused; store.writePaused(paused) } }

    @Synchronized
    fun bindOwner(userId: String?) {
        if (userId.isNullOrBlank()) {
            clearSession()
            return
        }
        ownerUserId = userId
        store.bindUser(userId)
        val loaded = store.read().mapValues { (_, record) ->
            if (record.ownerUserId.isNullOrBlank()) record.copy(ownerUserId = userId) else record
        }.filter { belongsTo(it.value, userId) }
        _records.value = loaded
        _paused.value = store.readPaused()
        store.write(loaded)
        runBlocking(Dispatchers.IO) {
            loaded.forEach { (id, record) ->
                upsertOwned(
                    UploadQueueEntity(
                        mediaId = id,
                        uploadState = queueState(record.state.toBackupState()),
                        retryCount = if (record.state.toBackupState().isRetryable) 1 else 0,
                        lastError = record.errorMessage,
                        clientUploadId = record.clientUploadId ?: UUID.randomUUID().toString(),
                        cloudinaryAssetId = record.cloudinaryAssetId,
                        cloudinaryPublicId = record.cloudinaryPublicId,
                        cloudinarySecureUrl = record.cloudinarySecureUrl,
                        finalizedMediaId = record.remoteMediaId,
                        ownerUserId = userId,
                        updatedAt = record.updatedAtMillis
                    )
                )
            }
        }
    }

    fun belongsTo(record: SyncRecord, userId: String): Boolean {
        val owner = record.ownerUserId
        return !owner.isNullOrBlank() && owner == userId
    }

    @Synchronized
    fun retainOwner(userId: String?) {
        if (userId.isNullOrBlank()) {
            clearSession()
            return
        }
        bindOwner(userId)
    }

    @Synchronized
    fun clearSession() {
        ownerUserId = null
        store.clearSession()
        _records.value = emptyMap()
        _paused.value = false
    }

    @Synchronized
    fun reconcile(presentIds: Set<String>) {
        val current = _records.value
        if (current.isEmpty() || current.keys.none { it !in presentIds }) return
        commit(
            current.filter { (id, record) ->
                belongsToCurrent(record) && (
                    id in presentIds ||
                        record.state.toBackupState() == BackupState.COMPLETED ||
                        !record.remoteMediaId.isNullOrBlank()
                    )
            }
        )
    }

    suspend fun reconcileMedia(items: List<MediaItem>, expectedOwner: String? = null) {
        val owner = ownerUserId ?: return
        if (!expectedOwner.isNullOrBlank() && expectedOwner != owner) return
        val now = System.currentTimeMillis()
        items.forEach { item ->
            val record = _records.value[item.id]
            upsertOwned(
                UploadQueueEntity(
                    mediaId = item.id, localMediaId = item.mediaStoreId, contentUri = item.uri,
                    fileName = item.filename, mimeType = item.mimeType, fileSize = item.fileSizeBytes,
                    createdAt = record?.queuedAtMillis?.takeIf { it > 0 } ?: now,
                    uploadState = queueState(record?.state?.toBackupState() ?: BackupState.NOT_STARTED),
                    retryCount = 0, lastError = record?.errorMessage,
                    clientUploadId = record?.clientUploadId ?: UUID.randomUUID().toString(),
                    cloudinaryAssetId = record?.cloudinaryAssetId, cloudinaryPublicId = record?.cloudinaryPublicId,
                    cloudinarySecureUrl = record?.cloudinarySecureUrl, finalizedMediaId = record?.remoteMediaId,
                    ownerUserId = record?.ownerUserId ?: owner,
                    updatedAt = record?.updatedAtMillis ?: now
                )
            )
        }
    }

    fun pendingIds(): List<String> = runBlocking(Dispatchers.IO) {
        val owner = ownerUserId ?: return@runBlocking emptyList()
        dao.pendingForOwner(owner).map { it.mediaId }
    }

    suspend fun queueEntity(id: String): UploadQueueEntity? {
        val owner = ownerUserId ?: return null
        val entity = dao.find(id) ?: return null
        return entity.takeIf { it.ownerUserId == owner }
    }

    suspend fun updateQueueMedia(id: String, item: MediaItem) {
        val owner = ownerUserId ?: return
        dao.find(id)?.let { entity ->
            upsertOwned(
                entity.copy(
                    localMediaId = item.mediaStoreId,
                    contentUri = item.uri,
                    fileName = item.filename,
                    mimeType = item.mimeType,
                    fileSize = item.fileSizeBytes,
                    ownerUserId = entity.ownerUserId ?: owner,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun upsertOwned(entity: UploadQueueEntity) {
        val owner = entity.ownerUserId ?: ownerUserId ?: return
        val existing = dao.find(entity.mediaId)
        if (existing != null && !existing.ownerUserId.isNullOrBlank() && existing.ownerUserId != owner) return
        dao.upsert(entity.copy(ownerUserId = owner))
    }

    private fun ownedRecord(id: String): SyncRecord? {
        val record = _records.value[id] ?: return null
        return record.takeIf { belongsToCurrent(it) }
    }

    private fun belongsToCurrent(record: SyncRecord): Boolean {
        val owner = ownerUserId
        if (owner.isNullOrBlank()) return false
        return belongsTo(record, owner)
    }

    private fun commit(next: Map<String, SyncRecord>) {
        if (ownerUserId.isNullOrBlank()) return
        _records.value = next
        store.write(next)
    }
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
