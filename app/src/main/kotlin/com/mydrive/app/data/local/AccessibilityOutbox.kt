package com.mydrive.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Local buffer for processed accessibility events.
 *
 * This lives in the EXISTING UploadQueueDatabase rather than a second database:
 * the project already has one Room database plus WorkManager, so monitoring
 * reuses that infrastructure instead of adding a parallel queue.
 *
 * The primary key is the event id generated on the device
 * ([com.mydrive.app.data.accessibility.PendingAccessibilityEvent.id]), which makes
 * both buffering and uploading idempotent: re-inserting the same event is
 * ignored, and re-uploading it is a server-side no-op.
 *
 * [eventText] is null for password fields — see the pipeline rule in
 * AccessibilityEventProcessor and the database CHECK constraint.
 */
@Entity(tableName = "accessibility_outbox")
data class AccessibilityOutboxEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val deviceId: String,
    val eventType: String,
    val packageName: String?,
    val activityName: String?,
    val eventTime: Long,
    val windowId: Int?,
    val windowTitle: String?,
    val eventText: String?,
    val contentDescription: String?,
    val className: String?,
    val isPasswordField: Boolean,
    val isEditable: Boolean?,
    val isClickable: Boolean?,
    val isScrollable: Boolean?,
    val metadataJson: String?,
    val createdAt: Long
)

@Dao
interface AccessibilityOutboxDao {

    /**
     * Buffers events. Duplicate discovery of the same event id is ignored, so one
     * accessibility event can never produce two queued rows.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<AccessibilityOutboxEntity>): List<Long>

    /** Oldest first, so uploads preserve event order (needed for sessions). */
    @Query(
        "SELECT * FROM accessibility_outbox WHERE userId = :userId " +
            "ORDER BY eventTime ASC LIMIT :limit"
    )
    suspend fun batch(userId: String, limit: Int): List<AccessibilityOutboxEntity>

    /** Only called after the batch is confirmed stored server-side. */
    @Query("DELETE FROM accessibility_outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM accessibility_outbox WHERE userId = :userId")
    suspend fun pendingCount(userId: String): Int

    /** Stale local buffering is dropped so a device cannot hoard data forever. */
    @Query("DELETE FROM accessibility_outbox WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
