package com.mydrive.app.debug

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object DeveloperLogger {
    private const val TAG = "MyDriveDev"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistQueue = Channel<DeveloperLogEvent>(
        capacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val pauseLive = AtomicBoolean(false)
    private val writeLock = Any()
    private val retentionOps = AtomicInteger(0)

    private val memory = ArrayDeque<DeveloperLogEvent>(DeveloperModeStore.MEMORY_EVENTS)
    private val _events = MutableStateFlow<List<DeveloperLogEvent>>(emptyList())
    val events: StateFlow<List<DeveloperLogEvent>> = _events.asStateFlow()

    private val _live = MutableStateFlow(true)
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private val _counters = MutableStateFlow(DeveloperLogCounters())
    val counters: StateFlow<DeveloperLogCounters> = _counters.asStateFlow()

    @Volatile private var dao: DeveloperLogDao? = null
    @Volatile private var started = false

    fun attach(database: DeveloperLogDatabase) {
        if (started) return
        dao = database.dao()
        started = true
        scope.launch {
            runCatching {
                val loaded = database.dao().recent(DeveloperModeStore.MEMORY_EVENTS).map { it.toEvent() }
                synchronized(writeLock) {
                    val live = memory.toList()
                    val liveIds = live.mapTo(HashSet()) { it.id }
                    memory.clear()
                    loaded.asReversed().forEach { event ->
                        if (event.id !in liveIds) memory.addLast(event)
                    }
                    live.forEach { memory.addLast(it) }
                    while (memory.size > DeveloperModeStore.MEMORY_EVENTS) memory.removeFirst()
                    publishLocked()
                }
            }
            for (event in persistQueue) {
                persist(event)
            }
        }
        log(
            level = LogLevel.INFO,
            category = LogCategory.SYSTEM,
            event = "LOGGER_STARTED",
            message = "Developer console logger attached"
        )
    }

    fun setLive(enabled: Boolean) {
        pauseLive.set(!enabled)
        _live.value = enabled
        if (enabled) {
            synchronized(writeLock) { publishLocked() }
        }
    }

    fun clear() {
        synchronized(writeLock) {
            memory.clear()
            publishLocked()
        }
        scope.launch {
            runCatching { dao?.clear() }
        }
    }

    fun log(
        level: LogLevel,
        category: LogCategory,
        event: String,
        message: String,
        operationId: String? = null,
        localMediaId: String? = null,
        clientUploadId: String? = null,
        workerId: String? = null,
        httpMethod: String? = null,
        urlPath: String? = null,
        httpStatus: Int? = null,
        durationMs: Long? = null,
        throwable: Throwable? = null,
        retryCount: Int? = null,
        metadata: Map<String, String?> = emptyMap()
    ) {
        try {
            val now = System.currentTimeMillis()
            val stack = throwable?.let { stackTraceOf(it) }
            val record = DeveloperLogEvent(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                level = level,
                category = category,
                event = event,
                message = SecretRedactor.text(message),
                operationId = operationId,
                localMediaId = localMediaId,
                clientUploadId = clientUploadId,
                workerId = workerId,
                httpMethod = httpMethod,
                urlPath = SecretRedactor.safePath(urlPath) ?: urlPath,
                httpStatus = httpStatus,
                durationMs = durationMs,
                exceptionType = throwable?.javaClass?.name,
                exceptionMessage = throwable?.message?.let(SecretRedactor::text),
                stackTrace = SecretRedactor.stack(stack),
                retryCount = retryCount,
                metadata = SecretRedactor.metadata(metadata),
                createdAt = now
            )
            echo(record)
            synchronized(writeLock) {
                if (memory.size >= DeveloperModeStore.MEMORY_EVENTS) memory.removeFirst()
                memory.addLast(record)
                if (!pauseLive.get()) publishLocked()
            }
            persistQueue.trySend(record)
        } catch (_: Throwable) {
        }
    }

    fun info(
        category: LogCategory,
        event: String,
        message: String,
        operationId: String? = null,
        localMediaId: String? = null,
        clientUploadId: String? = null,
        workerId: String? = null,
        metadata: Map<String, String?> = emptyMap()
    ) = log(
        level = LogLevel.INFO,
        category = category,
        event = event,
        message = message,
        operationId = operationId,
        localMediaId = localMediaId,
        clientUploadId = clientUploadId,
        workerId = workerId,
        metadata = metadata
    )

    fun warn(
        category: LogCategory,
        event: String,
        message: String,
        operationId: String? = null,
        localMediaId: String? = null,
        clientUploadId: String? = null,
        workerId: String? = null,
        httpStatus: Int? = null,
        retryCount: Int? = null,
        metadata: Map<String, String?> = emptyMap()
    ) = log(
        level = LogLevel.WARNING,
        category = category,
        event = event,
        message = message,
        operationId = operationId,
        localMediaId = localMediaId,
        clientUploadId = clientUploadId,
        workerId = workerId,
        httpStatus = httpStatus,
        retryCount = retryCount,
        metadata = metadata
    )

    fun error(
        category: LogCategory,
        event: String,
        message: String,
        operationId: String? = null,
        localMediaId: String? = null,
        clientUploadId: String? = null,
        workerId: String? = null,
        httpMethod: String? = null,
        urlPath: String? = null,
        httpStatus: Int? = null,
        durationMs: Long? = null,
        throwable: Throwable? = null,
        retryCount: Int? = null,
        metadata: Map<String, String?> = emptyMap()
    ) = log(
        level = LogLevel.ERROR,
        category = category,
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
        throwable = throwable,
        retryCount = retryCount,
        metadata = metadata
    )

    fun network(
        category: LogCategory,
        event: String,
        message: String,
        method: String,
        urlPath: String,
        status: Int?,
        durationMs: Long?,
        level: LogLevel = if ((status ?: 0) in 200..299) LogLevel.INFO else LogLevel.ERROR,
        operationId: String? = null,
        localMediaId: String? = null,
        clientUploadId: String? = null,
        workerId: String? = null,
        responseBody: String? = null,
        errorSource: String? = null,
        retryCount: Int? = null,
        extra: Map<String, String?> = emptyMap()
    ) {
        val metadata = extra.toMutableMap()
        if (!responseBody.isNullOrBlank()) metadata["response_body"] = SecretRedactor.body(responseBody)
        if (!errorSource.isNullOrBlank()) metadata["error_source"] = errorSource
        log(
            level = level,
            category = category,
            event = event,
            message = message,
            operationId = operationId,
            localMediaId = localMediaId,
            clientUploadId = clientUploadId,
            workerId = workerId,
            httpMethod = method,
            urlPath = urlPath,
            httpStatus = status,
            durationMs = durationMs,
            retryCount = retryCount,
            metadata = metadata
        )
    }

    fun snapshot(): List<DeveloperLogEvent> = synchronized(writeLock) { memory.toList() }

    fun filtered(filter: DeveloperLogFilter, source: List<DeveloperLogEvent> = _events.value): List<DeveloperLogEvent> {
        val query = filter.query.trim()
        return source.asSequence()
            .filter { event ->
                if (filter.sinceMillis != null && event.timestamp < filter.sinceMillis) return@filter false
                if (filter.errorsOnly && event.level != LogLevel.ERROR && event.level != LogLevel.FATAL) return@filter false
                if (filter.levels.isNotEmpty() && event.level !in filter.levels) return@filter false
                if (filter.categories.isNotEmpty() && event.category !in filter.categories) return@filter false
                if (filter.operationId.isNotBlank() && !event.operationId.orEmpty().contains(filter.operationId, true)) return@filter false
                if (filter.localMediaId.isNotBlank() && !event.localMediaId.orEmpty().contains(filter.localMediaId, true)) return@filter false
                if (filter.clientUploadId.isNotBlank() && !event.clientUploadId.orEmpty().contains(filter.clientUploadId, true)) return@filter false
                if (filter.workerId.isNotBlank() && !event.workerId.orEmpty().contains(filter.workerId, true)) return@filter false
                if (filter.httpStatus.isNotBlank() && event.httpStatus?.toString() != filter.httpStatus.trim()) return@filter false
                when (filter.quick) {
                    QuickLogFilter.ALL -> true
                    QuickLogFilter.ERRORS -> event.level == LogLevel.ERROR || event.level == LogLevel.FATAL
                    QuickLogFilter.WARNINGS -> event.level == LogLevel.WARNING
                    QuickLogFilter.NETWORK -> event.category == LogCategory.NETWORK || event.httpStatus != null
                    QuickLogFilter.AUTH -> event.category == LogCategory.AUTH || event.category == LogCategory.CLOUDINARY_AUTH
                    QuickLogFilter.UPLOAD -> event.category == LogCategory.CLOUDINARY_UPLOAD ||
                        event.category == LogCategory.CLOUDINARY_AUTH ||
                        event.category == LogCategory.FINALIZE
                    QuickLogFilter.WORKMANAGER -> event.category == LogCategory.WORKMANAGER
                } && matchesQuery(event, query)
            }
            .toList()
    }

    suspend fun loadByOperation(operationId: String): List<DeveloperLogEvent> {
        val fromMemory = synchronized(writeLock) {
            memory.filter { it.operationId == operationId }.sortedBy { it.timestamp }
        }
        val persisted = runCatching { dao?.byOperation(operationId).orEmpty().map { it.toEvent() } }.getOrDefault(emptyList())
        return mergeById(fromMemory, persisted)
    }

    suspend fun loadByLocalMedia(localMediaId: String): List<DeveloperLogEvent> {
        val fromMemory = synchronized(writeLock) {
            memory.filter { it.localMediaId == localMediaId }.sortedBy { it.timestamp }
        }
        val persisted = runCatching { dao?.byLocalMedia(localMediaId).orEmpty().map { it.toEvent() } }.getOrDefault(emptyList())
        return mergeById(fromMemory, persisted)
    }

    suspend fun recentForExport(limit: Int = 500): List<DeveloperLogEvent> {
        val persisted = runCatching { dao?.recent(limit).orEmpty().map { it.toEvent() } }.getOrDefault(emptyList())
        if (persisted.isNotEmpty()) return persisted
        return snapshot().asReversed().take(limit)
    }

    private suspend fun persist(event: DeveloperLogEvent) {
        val store = dao ?: return
        runCatching { store.insert(event.toEntity()) }
        val ops = retentionOps.incrementAndGet()
        if (ops % 50 == 0) {
            runCatching {
                store.deleteOlderThan(System.currentTimeMillis() - DeveloperModeStore.RETENTION_MS)
                val count = store.count()
                val overflow = count - DeveloperModeStore.MAX_EVENTS
                if (overflow > 0) store.deleteOldest(overflow)
            }
        }
    }

    fun recordVerificationSamples() {
        val operationId = "verify-" + java.util.UUID.randomUUID().toString().take(8)
        val mediaId = "img-0"
        info(LogCategory.MEDIASTORE, "MEDIA_DETECTED", "MediaStore detected sample item", operationId, mediaId, metadata = mapOf("file_name" to "IMG_VERIFY.jpg"))
        info(LogCategory.ROOM, "QUEUE_STATE", "Queue DETECTED → QUEUED", operationId, mediaId, metadata = mapOf("previous_state" to "DETECTED", "new_state" to "QUEUED"))
        info(LogCategory.WORKMANAGER, "WORK_SCHEDULED", "Upload work scheduled", workerId = "worker-verify", metadata = mapOf("unique_name" to "mydrive-media-upload-queue"))
        info(LogCategory.WORKMANAGER, "WORKER_STARTED", "Upload worker started", operationId = operationId, localMediaId = mediaId, workerId = "worker-verify")
        info(LogCategory.AUTH, "SESSION_CHECKED", "Auth session available", operationId, mediaId, metadata = mapOf("error_source" to "local_session"))
        network(
            category = LogCategory.CLOUDINARY_AUTH,
            event = "AUTH_REQUEST_FAILED",
            message = "HTTP 401 from cloudinary-upload-auth",
            method = "POST",
            urlPath = "/functions/v1/cloudinary-upload-auth",
            status = 401,
            durationMs = 842,
            operationId = operationId,
            localMediaId = mediaId,
            responseBody = """{"error":"session expired"}""",
            errorSource = "edge_function"
        )
        error(
            category = LogCategory.CLOUDINARY_AUTH,
            event = "AUTH_REQUEST_FAILED",
            message = "HTTP 500 Cloudinary auth Edge Function boot failure",
            operationId = operationId,
            localMediaId = mediaId,
            urlPath = "/functions/v1/cloudinary-upload-auth",
            httpStatus = 500,
            durationMs = 1200,
            metadata = mapOf("error_source" to "edge_function")
        )
        error(
            category = LogCategory.CLOUDINARY_UPLOAD,
            event = "UPLOAD_FAILED",
            message = "Cloudinary upload failed",
            operationId = operationId,
            localMediaId = mediaId,
            urlPath = "/v1_1/cloud/image/upload",
            httpStatus = 400,
            durationMs = 980,
            metadata = mapOf("response_body" to """{"error":{"message":"Invalid signature"}}""")
        )
        error(
            category = LogCategory.FINALIZE,
            event = "FINALIZE_FAILED",
            message = "finalize-media failed: 500",
            operationId = operationId,
            localMediaId = mediaId,
            urlPath = "/functions/v1/finalize-media",
            httpStatus = 500,
            durationMs = 640,
            metadata = mapOf("error_source" to "backend_response")
        )
        warn(
            category = LogCategory.WORKMANAGER,
            event = "WORKER_RETRY",
            message = "Upload worker retrying after network unavailable",
            operationId = operationId,
            localMediaId = mediaId,
            workerId = "worker-verify",
            retryCount = 2
        )
        error(
            category = LogCategory.NETWORK,
            event = "NETWORK_UNAVAILABLE",
            message = "Device is offline",
            operationId = operationId,
            localMediaId = mediaId
        )
        log(
            level = LogLevel.INFO,
            category = LogCategory.ROOM,
            event = "QUEUE_STATE",
            message = "Queue UPLOADING → FAILED",
            operationId = operationId,
            localMediaId = mediaId,
            metadata = mapOf("previous_state" to "UPLOADING", "new_state" to "FAILED")
        )
    }

    private fun publishLocked() {
        val snapshot = memory.toList().asReversed()
        _events.value = snapshot
        _counters.value = countersOf(snapshot)
    }

    private fun countersOf(events: List<DeveloperLogEvent>): DeveloperLogCounters {
        var errors = 0
        var warnings = 0
        var network = 0
        var auth = 0
        var upload = 0
        var work = 0
        var database = 0
        var backend = 0
        for (event in events) {
            val isError = event.level == LogLevel.ERROR || event.level == LogLevel.FATAL
            if (event.level == LogLevel.WARNING) warnings++
            if (!isError) continue
            errors++
            if (event.category == LogCategory.NETWORK || event.httpStatus != null && event.httpStatus >= 400) network++
            if (event.category == LogCategory.AUTH || event.category == LogCategory.CLOUDINARY_AUTH) auth++
            if (event.category == LogCategory.CLOUDINARY_UPLOAD || event.category == LogCategory.CLOUDINARY_AUTH || event.category == LogCategory.FINALIZE) upload++
            if (event.category == LogCategory.WORKMANAGER) work++
            if (event.category == LogCategory.DATABASE || event.category == LogCategory.ROOM) database++
            val path = event.urlPath.orEmpty()
            if (path.contains("/functions/") || event.category == LogCategory.FINALIZE || event.category == LogCategory.CLOUDINARY_AUTH) {
                if (event.httpStatus != null && event.httpStatus >= 400) backend++
            }
        }
        return DeveloperLogCounters(
            total = events.size,
            errors = errors,
            warnings = warnings,
            networkErrors = network,
            authErrors = auth,
            uploadErrors = upload,
            workManagerFailures = work,
            databaseErrors = database,
            backendErrors = backend
        )
    }

    private fun matchesQuery(event: DeveloperLogEvent, query: String): Boolean {
        if (query.isBlank()) return true
        return event.message.contains(query, true) ||
            event.event.contains(query, true) ||
            event.exceptionType.orEmpty().contains(query, true) ||
            event.exceptionMessage.orEmpty().contains(query, true) ||
            event.urlPath.orEmpty().contains(query, true) ||
            event.operationId.orEmpty().contains(query, true) ||
            event.localMediaId.orEmpty().contains(query, true) ||
            event.clientUploadId.orEmpty().contains(query, true) ||
            event.category.name.contains(query, true) ||
            event.httpStatus?.toString()?.contains(query, true) == true
    }

    private fun mergeById(first: List<DeveloperLogEvent>, second: List<DeveloperLogEvent>): List<DeveloperLogEvent> {
        val seen = LinkedHashMap<String, DeveloperLogEvent>()
        (first + second).sortedBy { it.timestamp }.forEach { seen[it.id] = it }
        return seen.values.sortedBy { it.timestamp }
    }

    private fun echo(event: DeveloperLogEvent) {
        val line = buildString {
            append(event.level.name)
            append(' ')
            append(event.category.name)
            append(' ')
            append(event.event)
            append(' ')
            append(event.message)
            event.httpStatus?.let { append(" http=").append(it) }
            event.operationId?.let { append(" op=").append(it) }
        }
        when (event.level) {
            LogLevel.ERROR, LogLevel.FATAL -> Log.e(TAG, line)
            LogLevel.WARNING -> Log.w(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            else -> Log.d(TAG, line)
        }
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
