package com.mydrive.app.data.repository

import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.isActive

/**
 * Decides whether a local sync/queue record must survive a missing MediaStore id.
 *
 * A disappeared local file is not a deleted cloud backup. Completed and
 * in-flight cloud work stays; only unsynced local-only queue rows may drop.
 */
fun shouldKeepSyncRecord(
    id: String,
    record: SyncRecord,
    presentIds: Set<String>
): Boolean {
    if (id in presentIds) return true
    val state = record.state.toBackupState()
    if (state == BackupState.COMPLETED) return true
    if (state == BackupState.CLOUDINARY_COMPLETED) return true
    if (state == BackupState.FINALIZING_SUPABASE) return true
    if (state.isActive) return true
    if (!record.remoteMediaId.isNullOrBlank()) return true
    if (!record.cloudinaryAssetId.isNullOrBlank()) return true
    if (!record.cloudinaryPublicId.isNullOrBlank()) return true
    return false
}
