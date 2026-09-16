package com.mydrive.app.data.repository

import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.local.SyncStateStore
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.isActive
import com.mydrive.app.data.model.isRetryable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SyncRepository(private val store: SyncStateStore) {

    private val _records = MutableStateFlow(store.read())
    val records: StateFlow<Map<String, SyncRecord>> = _records.asStateFlow()

    private val _paused = MutableStateFlow(store.readPaused())
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    @Synchronized
    fun enqueue(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val next = HashMap(_records.value)
        var changed = false
        for (id in ids) {
            val current = next[id]?.state?.toBackupState()?.resumeLocally()
            if (current != null &&
                (current == BackupState.COMPLETED || current == BackupState.WAITING || current.isActive)
            ) {
                continue
            }
            val previous = next[id]
            next[id] = SyncRecord(
                state = BackupState.WAITING.name,
                errorMessage = null,
                queuedAtMillis = previous?.queuedAtMillis?.takeIf { it > 0L } ?: now,
                updatedAtMillis = now
            )
            changed = true
        }
        if (changed) commit(next)
    }

    fun retry(id: String) {
        retryAll(listOf(id))
    }

    @Synchronized
    fun updateState(id: String, state: BackupState, errorMessage: String? = null) {
        val record = _records.value[id] ?: return
        val normalizedError = errorMessage?.takeIf { it.isNotBlank() }
        if (record.state == state.name && record.errorMessage == normalizedError) return
        val next = HashMap(_records.value)
        next[id] = record.copy(
            state = state.name,
            errorMessage = normalizedError,
            updatedAtMillis = System.currentTimeMillis()
        )
        commit(next)
    }

    @Synchronized
    fun updateCloudinaryResult(
        id: String,
        assetId: String,
        publicId: String,
        secureUrl: String?,
        version: Long?,
        format: String?,
        resourceType: String?
    ) {
        val record = _records.value[id] ?: return
        val unchanged = record.cloudinaryAssetId == assetId &&
            record.cloudinaryPublicId == publicId &&
            record.cloudinarySecureUrl == secureUrl &&
            record.cloudinaryVersion == version &&
            record.cloudinaryFormat == format &&
            record.cloudinaryResourceType == resourceType
        if (unchanged) return
        val next = HashMap(_records.value)
        next[id] = record.copy(
            cloudinaryAssetId = assetId,
            cloudinaryPublicId = publicId,
            cloudinarySecureUrl = secureUrl,
            cloudinaryVersion = version,
            cloudinaryFormat = format,
            cloudinaryResourceType = resourceType,
            clientUploadId = record.clientUploadId ?: java.util.UUID.randomUUID().toString(),
            updatedAtMillis = System.currentTimeMillis()
        )
        commit(next)
    }

    @Synchronized
    fun retryAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val next = HashMap(_records.value)
        var changed = false
        for (id in ids) {
            val record = next[id] ?: continue
            if (!record.state.toBackupState().isRetryable) continue
            next[id] = record.copy(
                state = BackupState.WAITING.name,
                errorMessage = null,
                updatedAtMillis = now
            )
            changed = true
        }
        if (changed) commit(next)
    }

    @Synchronized
    fun cancel(id: String) {
        val record = _records.value[id] ?: return
        val state = record.state.toBackupState()
        if (state == BackupState.COMPLETED || state == BackupState.CANCELLED) return
        val next = HashMap(_records.value)
        next[id] = record.copy(
            state = BackupState.CANCELLED.name,
            errorMessage = null,
            updatedAtMillis = System.currentTimeMillis()
        )
        commit(next)
    }

    fun setPaused(paused: Boolean) {
        if (_paused.value == paused) return
        _paused.value = paused
        store.writePaused(paused)
    }

    @Synchronized
    fun reconcile(presentIds: Set<String>) {
        val current = _records.value
        if (current.isEmpty() || current.keys.all { it in presentIds }) return
        commit(current.filterKeys { it in presentIds })
    }

    private fun commit(next: Map<String, SyncRecord>) {
        _records.value = next
        store.write(next)
    }
}

fun String.toBackupState(): BackupState = try {
    BackupState.valueOf(this)
} catch (_: IllegalArgumentException) {
    BackupState.NOT_STARTED
}

fun BackupState.resumeLocally(): BackupState = when (this) {
    BackupState.PREPARING,
    BackupState.UPLOADING,
    BackupState.PROCESSING,
    BackupState.SENDING_TELEGRAM,
    BackupState.PAUSED,
    BackupState.REQUESTING_CLOUDINARY_AUTH,
    BackupState.UPLOADING_TO_CLOUDINARY,
    BackupState.FINALIZING_SUPABASE -> BackupState.WAITING
    else -> this
}
