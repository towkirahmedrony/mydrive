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
 * The app's single Room database: the media upload queue, the Private Vault
 * state, and the persisted gallery catalog the Photos/Albums UI is restored from.
 */
@Database(
    entities = [
        UploadQueueEntity::class,
        VaultItemEntity::class,
        MediaCatalogEntity::class,
        MediaCatalogMetaEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun uploadQueueDao(): UploadQueueDao

    /** Local Private Vault state: authoritative for this device's vault files. */
    abstract fun vaultDao(): VaultDao

    /**
     * Last known composed gallery, so a cold start renders Photos/Albums from
     * disk instead of waiting for a MediaStore scan and a cloud reconciliation.
     */
    abstract fun mediaCatalogDao(): MediaCatalogDao

    companion object {
        @Volatile private var instance: UploadQueueDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE upload_queue ADD COLUMN ownerUserId TEXT")
            }
        }

        /**
         * Historical step retained on purpose: it created the accessibility event
         * buffer that MIGRATION_3_4 now removes, so a device still on schema v2 can
         * upgrade along 2 -> 3 -> 4 without a destructive fallback.
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

        /**
         * The accessibility monitoring feature was removed from the app, so its
         * local event buffer is dropped. This deletes only device-local queued
         * monitoring events; nothing server-side is touched, and the media upload
         * queue in the same database is untouched.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `accessibility_outbox`")
            }
        }

        /**
         * Adds the local Private Vault state table. Column names and affinities must
         * match [VaultItemEntity] exactly: Room validates the schema on open.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_vault_items` (" +
                        "`vaultItemId` TEXT NOT NULL, " +
                        "`remoteMediaId` TEXT, " +
                        "`localMediaId` INTEGER NOT NULL, " +
                        "`encryptedFileName` TEXT NOT NULL, " +
                        "`encryptedFileSize` INTEGER NOT NULL, " +
                        "`encryptedSha256` TEXT, " +
                        "`originalMimeType` TEXT NOT NULL, " +
                        "`originalFileName` TEXT NOT NULL, " +
                        "`originalFileSize` INTEGER NOT NULL, " +
                        "`vaultStatus` TEXT NOT NULL, " +
                        "`vaultVersion` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`hiddenAt` INTEGER, " +
                        "`restoredAt` INTEGER, " +
                        "`originalRemoved` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`vaultItemId`))"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_vault_items_localMediaId` " +
                        "ON `media_vault_items` (`localMediaId`)"
                )
            }
        }

        /**
         * Adds the persisted gallery catalog. Purely additive: the upload queue and
         * the vault keep their rows, and an install upgrading from v5 simply has an
         * empty catalog, which is the same state as a first launch.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_catalog` (" +
                        "`ownerUserId` TEXT NOT NULL, " +
                        "`mediaId` TEXT NOT NULL, " +
                        "`filename` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`fileSizeBytes` INTEGER NOT NULL, " +
                        "`capturedAtMillis` INTEGER NOT NULL, " +
                        "`device` TEXT NOT NULL, " +
                        "`resolution` TEXT NOT NULL, " +
                        "`durationSeconds` INTEGER, " +
                        "`isFavorite` INTEGER NOT NULL, " +
                        "`backupState` TEXT NOT NULL, " +
                        "`backupCompleted` INTEGER NOT NULL, " +
                        "`telegramCompleted` INTEGER NOT NULL, " +
                        "`thumbnailSeed` INTEGER NOT NULL, " +
                        "`albumId` TEXT NOT NULL, " +
                        "`albumName` TEXT NOT NULL, " +
                        "`mediaStoreId` INTEGER NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`dateAddedMillis` INTEGER NOT NULL, " +
                        "`dateModifiedMillis` INTEGER NOT NULL, " +
                        "`width` INTEGER NOT NULL, " +
                        "`height` INTEGER NOT NULL, " +
                        "`durationMillis` INTEGER, " +
                        "`relativePath` TEXT, " +
                        "`cloudinaryAssetId` TEXT, " +
                        "`cloudinaryPublicId` TEXT, " +
                        "`isTrashed` INTEGER NOT NULL, " +
                        "`dateExpiresMillis` INTEGER NOT NULL, " +
                        "`remoteMediaId` TEXT, " +
                        "`thumbnailUrl` TEXT, " +
                        "`originLocal` INTEGER NOT NULL, " +
                        "`originalUrl` TEXT, " +
                        "`cloudBackedUp` INTEGER NOT NULL, " +
                        "`errorMessage` TEXT, " +
                        "PRIMARY KEY(`ownerUserId`, `mediaId`))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_catalog_meta` (" +
                        "`ownerUserId` TEXT NOT NULL, " +
                        "`cloudOnlyCount` INTEGER NOT NULL, " +
                        "`coverMediaId` TEXT, " +
                        "`coverMimeType` TEXT, " +
                        "`coverSourceUrl` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerUserId`))"
                )
            }
        }

        fun get(context: Context): UploadQueueDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                UploadQueueDatabase::class.java,
                "upload_queue.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
