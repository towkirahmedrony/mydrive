package com.mydrive.app.debug

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL
}

enum class LogCategory {
    AUTH,
    MEDIASTORE,
    ROOM,
    WORKMANAGER,
    CLOUDINARY_AUTH,
    CLOUDINARY_UPLOAD,
    FINALIZE,
    NETWORK,
    REPLICATION,
    DATABASE,
    UI,
    SYSTEM
}

enum class QuickLogFilter {
    ALL,
    ERRORS,
    WARNINGS,
    NETWORK,
    AUTH,
    UPLOAD,
    WORKMANAGER
}

data class DeveloperLogEvent(
    val id: String,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val event: String,
    val message: String,
    val operationId: String? = null,
    val localMediaId: String? = null,
    val clientUploadId: String? = null,
    val workerId: String? = null,
    val httpMethod: String? = null,
    val urlPath: String? = null,
    val httpStatus: Int? = null,
    val durationMs: Long? = null,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val stackTrace: String? = null,
    val retryCount: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = timestamp
)

data class DeveloperLogFilter(
    val quick: QuickLogFilter = QuickLogFilter.ALL,
    val levels: Set<LogLevel> = emptySet(),
    val categories: Set<LogCategory> = emptySet(),
    val query: String = "",
    val operationId: String = "",
    val localMediaId: String = "",
    val clientUploadId: String = "",
    val workerId: String = "",
    val httpStatus: String = "",
    val errorsOnly: Boolean = false,
    val sinceMillis: Long? = null
)

data class DeveloperLogCounters(
    val total: Int = 0,
    val errors: Int = 0,
    val warnings: Int = 0,
    val networkErrors: Int = 0,
    val authErrors: Int = 0,
    val uploadErrors: Int = 0,
    val workManagerFailures: Int = 0,
    val databaseErrors: Int = 0,
    val backendErrors: Int = 0
)

data class SessionSnapshot(
    val signedIn: Boolean,
    val userIdMasked: String?,
    val hasSession: Boolean,
    val expiresAtEpochSeconds: Long?,
    val statusName: String,
    val tokenExpired: Boolean = false
)

data class WorkManagerSnapshot(
    val uniqueName: String,
    val state: String,
    val runAttemptCount: Int,
    val running: Boolean,
    val lastError: String? = null
)

data class QueueSnapshot(
    val pending: Int,
    val uploading: Int,
    val failed: Int,
    val completed: Int,
    val paused: Boolean
)

data class UploadTrace(
    val operationId: String,
    val localMediaId: String,
    val clientUploadId: String? = null,
    val workerId: String? = null,
    val retryCount: Int? = null,
    val fileName: String? = null
)
