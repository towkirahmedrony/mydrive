package com.mydrive.app.debug

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "developer_log_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["operationId"]),
        Index(value = ["localMediaId"])
    ]
)
data class DeveloperLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val level: String,
    val category: String,
    val event: String,
    val message: String,
    val operationId: String?,
    val localMediaId: String?,
    val clientUploadId: String?,
    val workerId: String?,
    val httpMethod: String?,
    val urlPath: String?,
    val httpStatus: Int?,
    val durationMs: Long?,
    val exceptionType: String?,
    val exceptionMessage: String?,
    val stackTrace: String?,
    val retryCount: Int?,
    val metadataJson: String,
    val createdAt: Long
)

class DeveloperLogConverters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @TypeConverter
    fun fromMap(value: Map<String, String>?): String = json.encodeToString(value ?: emptyMap())

    @TypeConverter
    fun toMap(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(value) }.getOrDefault(emptyMap())
    }
}

@Dao
interface DeveloperLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DeveloperLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DeveloperLogEntity>)

    @Query("SELECT * FROM developer_log_events ORDER BY timestamp DESC, createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DeveloperLogEntity>

    @Query("SELECT * FROM developer_log_events WHERE operationId = :operationId ORDER BY timestamp ASC, createdAt ASC")
    suspend fun byOperation(operationId: String): List<DeveloperLogEntity>

    @Query("SELECT * FROM developer_log_events WHERE localMediaId = :localMediaId ORDER BY timestamp ASC, createdAt ASC")
    suspend fun byLocalMedia(localMediaId: String): List<DeveloperLogEntity>

    @Query("SELECT COUNT(*) FROM developer_log_events")
    suspend fun count(): Int

    @Query("DELETE FROM developer_log_events")
    suspend fun clear()

    @Query("DELETE FROM developer_log_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query(
        """
        DELETE FROM developer_log_events WHERE id IN (
            SELECT id FROM developer_log_events ORDER BY timestamp ASC, createdAt ASC LIMIT :count
        )
        """
    )
    suspend fun deleteOldest(count: Int)
}

@Database(entities = [DeveloperLogEntity::class], version = 1, exportSchema = false)
@TypeConverters(DeveloperLogConverters::class)
abstract class DeveloperLogDatabase : RoomDatabase() {
    abstract fun dao(): DeveloperLogDao

    companion object {
        @Volatile private var instance: DeveloperLogDatabase? = null

        fun get(context: Context): DeveloperLogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                DeveloperLogDatabase::class.java,
                "developer_console.db"
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
        }
    }
}

fun DeveloperLogEvent.toEntity(): DeveloperLogEntity = DeveloperLogEntity(
    id = id,
    timestamp = timestamp,
    level = level.name,
    category = category.name,
    event = event,
    message = message,
    operationId = operationId,
    localMediaId = localMediaId,
    clientUploadId = clientUploadId,
    workerId = workerId,
    httpMethod = httpMethod,
    urlPath = urlPath,
    httpStatus = httpStatus,
    durationMs = durationMs,
    exceptionType = exceptionType,
    exceptionMessage = exceptionMessage,
    stackTrace = stackTrace,
    retryCount = retryCount,
    metadataJson = Json.encodeToString(metadata),
    createdAt = createdAt
)

fun DeveloperLogEntity.toEvent(): DeveloperLogEvent = DeveloperLogEvent(
    id = id,
    timestamp = timestamp,
    level = runCatching { LogLevel.valueOf(level) }.getOrDefault(LogLevel.INFO),
    category = runCatching { LogCategory.valueOf(category) }.getOrDefault(LogCategory.SYSTEM),
    event = event,
    message = message,
    operationId = operationId,
    localMediaId = localMediaId,
    clientUploadId = clientUploadId,
    workerId = workerId,
    httpMethod = httpMethod,
    urlPath = urlPath,
    httpStatus = httpStatus,
    durationMs = durationMs,
    exceptionType = exceptionType,
    exceptionMessage = exceptionMessage,
    stackTrace = stackTrace,
    retryCount = retryCount,
    metadata = runCatching { Json.decodeFromString<Map<String, String>>(metadataJson) }.getOrDefault(emptyMap()),
    createdAt = createdAt
)
