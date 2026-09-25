package com.mydrive.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "upload_queue")
data class UploadQueueEntity(
    @androidx.room.PrimaryKey val mediaId: String,
    val localMediaId: Long = 0L,
    val contentUri: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val fileSize: Long = 0L,
    val createdAt: Long = 0L,
    val uploadState: String = "DETECTED",
    val retryCount: Int = 0,
    val lastError: String? = null,
    val clientUploadId: String = "",
    val cloudinaryAssetId: String? = null,
    val cloudinaryPublicId: String? = null,
    val cloudinarySecureUrl: String? = null,
    val finalizedMediaId: String? = null,
    val ownerUserId: String? = null,
    val updatedAt: Long = 0L
)

@Dao
interface UploadQueueDao {
    @Query("SELECT * FROM upload_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<UploadQueueEntity>>

    @Query("SELECT * FROM upload_queue WHERE uploadState IN ('DETECTED', 'QUEUED', 'RETRYING', 'UPLOADED', 'FINALIZING') ORDER BY createdAt ASC")
    suspend fun pending(): List<UploadQueueEntity>

    @Query("SELECT * FROM upload_queue WHERE ownerUserId = :ownerUserId AND uploadState IN ('DETECTED', 'QUEUED', 'RETRYING', 'UPLOADED', 'FINALIZING') ORDER BY createdAt ASC")
    suspend fun pendingForOwner(ownerUserId: String): List<UploadQueueEntity>

    @Query("SELECT * FROM upload_queue WHERE mediaId = :mediaId LIMIT 1")
    suspend fun find(mediaId: String): UploadQueueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UploadQueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<UploadQueueEntity>)

    @Query("UPDATE upload_queue SET uploadState = :state, retryCount = :retryCount, lastError = :lastError, updatedAt = :updatedAt WHERE mediaId = :mediaId")
    suspend fun updateState(mediaId: String, state: String, retryCount: Int, lastError: String?, updatedAt: Long)
}

/**
 * The app's single Room database. Accessibility monitoring buffers its events in
 * `accessibility_outbox` here rather than in a second database, so there is one
 * local queue and one WorkManager setup shared with the media upload pipeline.
 */
@Database(
    entities = [UploadQueueEntity::class, AccessibilityOutboxEntity::class],
    version = 3,
    exportSchema = false
)
abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun uploadQueueDao(): UploadQueueDao

    abstract fun accessibilityOutboxDao(): AccessibilityOutboxDao

    companion object {
        @Volatile private var instance: UploadQueueDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE upload_queue ADD COLUMN ownerUserId TEXT")
            }
        }

        /**
         * Adds the accessibility event buffer. Column names and affinities must
         * match [AccessibilityOutboxEntity] exactly: Room validates the schema when
         * the database is opened.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `accessibility_outbox` (" +
                        "`id` TEXT NOT NULL, " +
                        "`userId` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, " +
                        "`eventType` TEXT NOT NULL, " +
                        "`packageName` TEXT, " +
                        "`activityName` TEXT, " +
                        "`eventTime` INTEGER NOT NULL, " +
                        "`windowId` INTEGER, " +
                        "`windowTitle` TEXT, " +
                        "`eventText` TEXT, " +
                        "`contentDescription` TEXT, " +
                        "`className` TEXT, " +
                        "`isPasswordField` INTEGER NOT NULL, " +
                        "`isEditable` INTEGER, " +
                        "`isClickable` INTEGER, " +
                        "`isScrollable` INTEGER, " +
                        "`metadataJson` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        fun get(context: Context): UploadQueueDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                UploadQueueDatabase::class.java,
                "upload_queue.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
