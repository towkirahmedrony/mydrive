package com.mydrive.app.data.repository

import com.mydrive.app.data.local.SyncRecord
import com.mydrive.app.data.model.BackupState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecordRetentionTest {

    private val localId = "img-100"
    private val present = setOf("img-1", "img-2")

    @Test
    fun presentLocalIdIsAlwaysKept() {
        val record = SyncRecord(state = BackupState.WAITING.name)
        assertTrue(shouldKeepSyncRecord(localId, record, present + localId))
    }

    @Test
    fun completedBackupSurvivesMissingLocalId() {
        val record = SyncRecord(state = BackupState.COMPLETED.name, remoteMediaId = "remote-1")
        assertTrue(shouldKeepSyncRecord(localId, record, present))
    }

    @Test
    fun remoteMediaIdSurvivesMissingLocalId() {
        val record = SyncRecord(state = BackupState.FAILED.name, remoteMediaId = "remote-1")
        assertTrue(shouldKeepSyncRecord(localId, record, present))
    }

    @Test
    fun cloudinaryResultSurvivesMissingLocalId() {
        val record = SyncRecord(
            state = BackupState.CLOUDINARY_COMPLETED.name,
            cloudinaryAssetId = "asset",
            cloudinaryPublicId = "mydrive/user/file"
        )
        assertTrue(shouldKeepSyncRecord(localId, record, present))
    }

    @Test
    fun activeUploadSurvivesMissingLocalId() {
        val record = SyncRecord(state = BackupState.UPLOADING_TO_CLOUDINARY.name)
        assertTrue(shouldKeepSyncRecord(localId, record, present))
    }

    @Test
    fun unsyncedWaitingRowMayDropWhenLocalGone() {
        val record = SyncRecord(state = BackupState.WAITING.name)
        assertFalse(shouldKeepSyncRecord(localId, record, present))
    }

    @Test
    fun notStartedLocalOnlyRowMayDropWhenLocalGone() {
        val record = SyncRecord(state = BackupState.NOT_STARTED.name)
        assertFalse(shouldKeepSyncRecord(localId, record, present))
    }
}
