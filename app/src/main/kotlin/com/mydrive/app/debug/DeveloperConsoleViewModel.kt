package com.mydrive.app.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mydrive.app.data.model.BackupState
import com.mydrive.app.data.model.isActive
import com.mydrive.app.data.repository.AuthRepository
import com.mydrive.app.data.repository.MediaRepository
import com.mydrive.app.data.repository.SyncRepository
import com.mydrive.app.data.repository.toBackupState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class DeveloperConsoleUiState(
    val events: List<DeveloperLogEvent> = emptyList(),
    val counters: DeveloperLogCounters = DeveloperLogCounters(),
    val live: Boolean = true,
    val filter: DeveloperLogFilter = DeveloperLogFilter(),
    val session: SessionSnapshot = SessionSnapshot(
        signedIn = false,
        userIdMasked = null,
        hasSession = false,
        expiresAtEpochSeconds = null,
        statusName = "UNKNOWN"
    ),
    val work: WorkManagerSnapshot? = null,
    val queue: QueueSnapshot = QueueSnapshot(0, 0, 0, 0, false),
    val selectedEvent: DeveloperLogEvent? = null,
    val investigation: InvestigationState? = null,
    val showFilters: Boolean = false,
    val exportText: String? = null,
    val copyText: String? = null,
    val candidateUploads: List<InvestigateCandidate> = emptyList()
)

data class InvestigationState(
    val localMediaId: String,
    val fileName: String?,
    val operationId: String?,
    val clientUploadId: String?,
    val events: List<DeveloperLogEvent>
)

data class InvestigateCandidate(
    val localMediaId: String,
    val fileName: String,
    val operationId: String?,
    val clientUploadId: String?,
    val lastEvent: String,
    val failed: Boolean
)

class DeveloperConsoleViewModel(
    application: Application,
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val mediaRepository: MediaRepository,
    private val supabaseClient: SupabaseClient?
) : AndroidViewModel(application) {

    private val _filter = MutableStateFlow(DeveloperLogFilter())
    private val _selected = MutableStateFlow<DeveloperLogEvent?>(null)
    private val _investigation = MutableStateFlow<InvestigationState?>(null)
    private val _showFilters = MutableStateFlow(false)
    private val _exportText = MutableStateFlow<String?>(null)
    private val _copyText = MutableStateFlow<String?>(null)
    private val _work = MutableStateFlow<WorkManagerSnapshot?>(null)

    private val sessionFlow = authRepository.state.map { auth ->
        val session = runCatching { supabaseClient?.auth?.currentSessionOrNull() }.getOrNull()
        val expires = session?.expiresAt?.epochSeconds
        val remaining = expires?.let { it - Clock.System.now().epochSeconds }
        sessionSnapshotOf(
            authState = auth,
            expiresAtEpochSeconds = expires,
            hasSession = session != null,
            tokenExpired = remaining != null && remaining <= 0
        )
    }

    private val queueFlow = combine(syncRepository.records, syncRepository.paused) { records, paused ->
        var pending = 0
        var uploading = 0
        var failed = 0
        var completed = 0
        records.values.forEach { record ->
            when (val state = record.state.toBackupState()) {
                BackupState.COMPLETED -> completed++
                BackupState.FAILED, BackupState.CANCELLED -> failed++
                else -> if (state.isActive) uploading++ else pending++
            }
        }
        QueueSnapshot(pending, uploading, failed, completed, paused)
    }

    val uiState: StateFlow<DeveloperConsoleUiState> = combine(
        combine(
            DeveloperLogger.events,
            DeveloperLogger.counters,
            DeveloperLogger.live,
            _filter
        ) { events, counters, live, filter ->
            Quad(events, counters, live, filter)
        },
        combine(sessionFlow, queueFlow, _work) { session, queue, work ->
            Triple(session, queue, work)
        },
        combine(_selected, _investigation, _showFilters) { selected, investigation, showFilters ->
            Triple(selected, investigation, showFilters)
        },
        combine(_exportText, _copyText, mediaRepository.media) { export, copy, media ->
            Triple(export, copy, media)
        }
    ) { logs, runtime, sheets, extras ->
        val (events, counters, live, filter) = logs
        val (session, queue, work) = runtime
        val (selected, investigation, showFilters) = sheets
        val (export, copy, media) = extras
        val candidates = events
            .mapNotNull { event -> event.localMediaId?.let { id -> id to event } }
            .groupBy({ it.first }, { it.second })
            .map { (id, related) ->
                val latest = related.maxBy { it.timestamp }
                InvestigateCandidate(
                    localMediaId = id,
                    fileName = media.firstOrNull { it.id == id }?.filename ?: related.firstNotNullOfOrNull { it.metadata["file_name"] } ?: id,
                    operationId = related.firstNotNullOfOrNull { it.operationId },
                    clientUploadId = related.firstNotNullOfOrNull { it.clientUploadId },
                    lastEvent = latest.event,
                    failed = related.any { it.level == LogLevel.ERROR || it.level == LogLevel.FATAL }
                )
            }
            .sortedByDescending { candidate ->
                events.filter { it.localMediaId == candidate.localMediaId }.maxOfOrNull { it.timestamp } ?: 0L
            }
            .take(40)
        DeveloperConsoleUiState(
            events = DeveloperLogger.filtered(filter, events),
            counters = counters,
            live = live,
            filter = filter,
            session = session,
            work = work,
            queue = queue,
            selectedEvent = selected,
            investigation = investigation,
            showFilters = showFilters,
            exportText = export,
            copyText = copy,
            candidateUploads = candidates
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DeveloperConsoleUiState()
    )

    init {
        observeWork()
        DeveloperLogger.info(
            category = LogCategory.UI,
            event = "CONSOLE_OPENED",
            message = "Developer Console opened"
        )
    }

    fun setQuickFilter(quick: QuickLogFilter) {
        _filter.update { it.copy(quick = quick, errorsOnly = quick == QuickLogFilter.ERRORS) }
    }

    fun setQuery(query: String) {
        _filter.update { it.copy(query = query) }
    }

    fun setOperationId(value: String) {
        _filter.update { it.copy(operationId = value) }
    }

    fun setLocalMediaId(value: String) {
        _filter.update { it.copy(localMediaId = value) }
    }

    fun setClientUploadId(value: String) {
        _filter.update { it.copy(clientUploadId = value) }
    }

    fun setWorkerId(value: String) {
        _filter.update { it.copy(workerId = value) }
    }

    fun setHttpStatus(value: String) {
        _filter.update { it.copy(httpStatus = value.filter { ch -> ch.isDigit() }.take(3) ) }
    }

    fun toggleLevel(level: LogLevel) {
        _filter.update {
            val next = it.levels.toMutableSet()
            if (!next.add(level)) next.remove(level)
            it.copy(levels = next)
        }
    }

    fun toggleCategory(category: LogCategory) {
        _filter.update {
            val next = it.categories.toMutableSet()
            if (!next.add(category)) next.remove(category)
            it.copy(categories = next)
        }
    }

    fun setShowFilters(show: Boolean) {
        _showFilters.value = show
    }

    fun setLive(enabled: Boolean) {
        DeveloperLogger.setLive(enabled)
    }

    fun clearLogs() {
        DeveloperLogger.clear()
    }

    fun injectVerificationSamples() {
        DeveloperLogger.recordVerificationSamples()
    }

    fun selectEvent(event: DeveloperLogEvent?) {
        _selected.value = event
    }

    fun copySelected() {
        val event = _selected.value ?: return
        _copyText.value = DiagnosticsExporter.formatEventDetails(event)
    }

    fun consumeCopy() {
        _copyText.value = null
    }

    fun consumeExport() {
        _exportText.value = null
    }

    fun exportDiagnostics() {
        viewModelScope.launch {
            val events = DeveloperLogger.recentForExport()
            val state = uiState.value
            _exportText.value = DiagnosticsExporter.buildReport(
                events = events,
                session = state.session,
                queue = state.queue,
                work = state.work,
                counters = state.counters
            )
        }
    }

    fun showEventsForOperation(operationId: String) {
        _filter.update {
            it.copy(
                quick = QuickLogFilter.ALL,
                operationId = operationId,
                query = "",
                errorsOnly = false
            )
        }
        _showFilters.value = false
        _investigation.value = null
    }

    fun investigate(localMediaId: String) {
        viewModelScope.launch {
            val events = DeveloperLogger.loadByLocalMedia(localMediaId)
            val media = mediaRepository.mediaById(localMediaId)
            _investigation.value = InvestigationState(
                localMediaId = localMediaId,
                fileName = media?.filename,
                operationId = events.firstNotNullOfOrNull { it.operationId } ?: OperationTrace.existing(localMediaId),
                clientUploadId = events.firstNotNullOfOrNull { it.clientUploadId } ?: syncRepository.records.value[localMediaId]?.clientUploadId,
                events = events
            )
        }
    }

    fun closeInvestigation() {
        _investigation.value = null
    }

    private fun observeWork() {
        viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfosForUniqueWorkFlow("mydrive-media-upload-queue")
                .collect { infos ->
                    val info = infos.firstOrNull()
                    _work.value = WorkManagerSnapshot(
                        uniqueName = "mydrive-media-upload-queue",
                        state = info?.state?.name ?: "NONE",
                        runAttemptCount = info?.runAttemptCount ?: 0,
                        running = info?.state == WorkInfo.State.RUNNING,
                        lastError = info?.outputData?.getString("error")
                    )
                }
        }
    }

    companion object {
        fun factory(
            application: Application,
            authRepository: AuthRepository,
            syncRepository: SyncRepository,
            mediaRepository: MediaRepository,
            supabaseClient: SupabaseClient?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DeveloperConsoleViewModel(
                    application,
                    authRepository,
                    syncRepository,
                    mediaRepository,
                    supabaseClient
                ) as T
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
