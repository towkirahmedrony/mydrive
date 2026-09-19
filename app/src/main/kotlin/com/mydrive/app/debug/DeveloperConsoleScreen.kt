package com.mydrive.app.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.EmptyState
import com.mydrive.app.ui.components.SearchField
import com.mydrive.app.ui.theme.CardShape
import com.mydrive.app.ui.theme.ChipShape
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.SheetShape
import com.mydrive.app.ui.theme.Sky
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.StatusAttention
import com.mydrive.app.ui.theme.StatusConnected
import com.mydrive.app.ui.theme.StatusIdle
import com.mydrive.app.ui.theme.StatusSyncing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeveloperConsoleScreen(
    viewModel: DeveloperConsoleViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var showInvestigatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.copyText) {
        val text = state.copyText ?: return@LaunchedEffect
        copyToClipboard(context, "Error details", text)
        viewModel.consumeCopy()
    }
    LaunchedEffect(state.exportText) {
        val text = state.exportText ?: return@LaunchedEffect
        shareText(context, "My Drive diagnostics", text)
        viewModel.consumeExport()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ConsoleTopBar(
            live = state.live,
            onBack = onBack,
            onClear = viewModel::clearLogs,
            onToggleLive = { viewModel.setLive(!state.live) },
            onExport = viewModel::exportDiagnostics,
            onFilters = { viewModel.setShowFilters(true) }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SummarySection(state)
            }
            item {
                SearchField(
                    value = state.filter.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search event, error, endpoint, operation ID",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                )
            }
            item {
                QuickFilterRow(
                    selected = state.filter.quick,
                    onSelect = viewModel::setQuickFilter,
                    onInvestigate = { showInvestigatePicker = true }
                )
            }
            if (state.events.isEmpty()) {
                item {
                    EmptyState(
                        title = "No matching events",
                        message = "Upload or retry a photo to stream pipeline diagnostics here.",
                        icon = Icons.Outlined.BugReport
                    )
                }
            } else {
                items(state.events, key = { it.id }) { event ->
                    LogEventRow(
                        event = event,
                        modifier = Modifier.padding(horizontal = Spacing.md),
                        onClick = { viewModel.selectEvent(event) }
                    )
                }
            }
        }
    }

    if (state.showFilters) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setShowFilters(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
            shape = SheetShape
        ) {
            FilterSheet(state = state, viewModel = viewModel)
        }
    }
    state.selectedEvent?.let { event ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.selectEvent(null) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
            shape = SheetShape
        ) {
            ErrorDetailSheet(
                event = event,
                onCopy = viewModel::copySelected,
                onCorrelate = {
                    event.operationId?.let(viewModel::showEventsForOperation)
                    viewModel.selectEvent(null)
                },
                onInvestigate = {
                    event.localMediaId?.let(viewModel::investigate)
                    viewModel.selectEvent(null)
                }
            )
        }
    }
    state.investigation?.let { investigation ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeInvestigation,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
            shape = SheetShape
        ) {
            InvestigateSheet(
                investigation = investigation,
                onEvent = viewModel::selectEvent,
                onClose = viewModel::closeInvestigation
            )
        }
    }
    if (showInvestigatePicker) {
        ModalBottomSheet(
            onDismissRequest = { showInvestigatePicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
            shape = SheetShape
        ) {
            InvestigatePicker(
                candidates = state.candidateUploads,
                onSelect = {
                    showInvestigatePicker = false
                    viewModel.investigate(it)
                }
            )
        }
    }
}

@Composable
private fun ConsoleTopBar(
    live: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onToggleLive: () -> Unit,
    onExport: () -> Unit,
    onFilters: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val liveColor by animateColorAsState(if (live) StatusConnected else StatusIdle, label = "live")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = colors.onBackground)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Developer Console", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(liveColor))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (live) "Recording live" else "Paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = liveColor
                )
            }
        }
        IconButton(onClick = onClear) {
            Icon(Icons.Outlined.ClearAll, contentDescription = "Clear logs", tint = colors.onSurfaceVariant)
        }
        IconButton(onClick = onToggleLive) {
            Icon(
                if (live) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (live) "Pause" else "Resume",
                tint = colors.onSurfaceVariant
            )
        }
        IconButton(onClick = onExport) {
            Icon(Icons.Outlined.IosShare, contentDescription = "Export diagnostics", tint = colors.onSurfaceVariant)
        }
        IconButton(onClick = onFilters) {
            Icon(Icons.Outlined.FilterList, contentDescription = "Filters", tint = colors.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarySection(state: DeveloperConsoleUiState) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = Spacing.md)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            CounterChip("Events", state.counters.total, Copper)
            CounterChip("Errors", state.counters.errors, StatusAttention)
            CounterChip("Warnings", state.counters.warnings, StatusSyncing)
            CounterChip("Network", state.counters.networkErrors, Sky)
            CounterChip("Auth", state.counters.authErrors, StatusAttention)
            CounterChip("Upload", state.counters.uploadErrors, StatusAttention)
            CounterChip("WorkManager", state.counters.workManagerFailures, StatusSyncing)
            CounterChip("Database", state.counters.databaseErrors, Sky)
            CounterChip("Edge Fn", state.counters.backendErrors, StatusAttention)
        }
        Spacer(Modifier.height(Spacing.sm))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(colors.surfaceVariant)
                .border(1.dp, colors.outlineVariant, CardShape)
                .padding(Spacing.md)
        ) {
            Text("Runtime", style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
            Spacer(Modifier.height(Spacing.xs))
            MetaLine("Auth", if (state.session.signedIn) "signed in" else "signed out")
            MetaLine("User", state.session.userIdMasked ?: "—")
            MetaLine("Session", buildString {
                append(if (state.session.hasSession) "present" else "none")
                append(" · ")
                append(state.session.statusName)
                if (state.session.tokenExpired) append(" · token expired")
            })
            MetaLine(
                "WorkManager",
                "${state.work?.state ?: "NONE"} · attempts ${state.work?.runAttemptCount ?: 0}"
            )
            MetaLine("Queue", "${state.queue.pending} pending · ${state.queue.uploading} active · ${state.queue.failed} failed")
        }
    }
}

@Composable
private fun CounterChip(label: String, value: Int, color: Color) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(Radius.sm))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    ) {
        Text("$value", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(108.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun QuickFilterRow(
    selected: QuickLogFilter,
    onSelect: (QuickLogFilter) -> Unit,
    onInvestigate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        QuickLogFilter.entries.forEach { filter ->
            FilterChip(filter.name, selected == filter) { onSelect(filter) }
        }
        FilterChip("INVESTIGATE", false, accent = Copper, onClick = onInvestigate)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, accent: Color = Copper, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg = if (selected) accent.copy(alpha = 0.18f) else colors.surfaceVariant
    val fg = if (selected) accent else colors.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier
            .clip(ChipShape)
            .background(bg)
            .border(1.dp, if (selected) accent.copy(alpha = 0.5f) else colors.outlineVariant, ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    )
}

@Composable
private fun LogEventRow(
    event: DeveloperLogEvent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val levelColor = levelColor(event.level)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(levelColor)
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(event.timestamp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(Spacing.xs))
                Text(event.level.name, style = MaterialTheme.typography.labelSmall, color = levelColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(Spacing.xs))
                Text(event.category.name, style = MaterialTheme.typography.labelSmall, color = Sky)
                event.httpStatus?.let {
                    Spacer(Modifier.width(Spacing.xs))
                    Text("HTTP $it", style = MaterialTheme.typography.labelSmall, color = if (it >= 400) StatusAttention else Sage)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(event.event, style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
            Text(
                event.message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val ids = listOfNotNull(
                event.operationId?.let { "op=$it" },
                event.localMediaId?.let { "media=$it" },
                event.durationMs?.let { "${it}ms" }
            )
            if (ids.isNotEmpty()) {
                Text(ids.joinToString("  "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(state: DeveloperConsoleUiState, viewModel: DeveloperConsoleViewModel) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Filters", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Spacer(Modifier.height(Spacing.md))
        FilterField("Operation ID", state.filter.operationId, viewModel::setOperationId)
        FilterField("Local media ID", state.filter.localMediaId, viewModel::setLocalMediaId)
        FilterField("Client upload ID", state.filter.clientUploadId, viewModel::setClientUploadId)
        FilterField("Worker ID", state.filter.workerId, viewModel::setWorkerId)
        FilterField("HTTP status", state.filter.httpStatus, viewModel::setHttpStatus)
        Spacer(Modifier.height(Spacing.sm))
        Text("Levels", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LogLevel.entries.forEach { level ->
                FilterChip(level.name, level in state.filter.levels, levelColor(level)) { viewModel.toggleLevel(level) }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text("Categories", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LogCategory.entries.forEach { category ->
                FilterChip(category.name, category in state.filter.categories) { viewModel.toggleCategory(category) }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            "Inject sample pipeline events",
            style = MaterialTheme.typography.labelLarge,
            color = Copper,
            modifier = Modifier
                .clickable {
                    viewModel.injectVerificationSamples()
                    viewModel.setShowFilters(false)
                }
                .padding(vertical = Spacing.xs)
        )
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun FilterField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Copper,
            cursorColor = Copper
        )
    )
}

@Composable
private fun ErrorDetailSheet(
    event: DeveloperLogEvent,
    onCopy: () -> Unit,
    onCorrelate: () -> Unit,
    onInvestigate: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
            .verticalScroll(rememberScrollState())
    ) {
        Text(event.event, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(formatFullTime(event.timestamp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.sm))
        Text(DiagnosticsExporter.explanation(event), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.md))
        Detail("Level", event.level.name)
        Detail("Category", event.category.name)
        Detail("Message", event.message)
        event.httpStatus?.let { Detail("HTTP status", it.toString()) }
        event.urlPath?.let { Detail("Endpoint", it) }
        event.httpMethod?.let { Detail("Method", it) }
        event.durationMs?.let { Detail("Duration", "${it}ms") }
        event.exceptionType?.let { Detail("Exception", it) }
        event.exceptionMessage?.let { Detail("Exception message", it) }
        event.retryCount?.let { Detail("Retry count", it.toString()) }
        event.operationId?.let { Detail("Operation ID", it) }
        event.localMediaId?.let { Detail("Local media ID", it) }
        event.clientUploadId?.let { Detail("Client upload ID", it) }
        event.workerId?.let { Detail("Worker ID", it) }
        event.metadata.forEach { (key, value) -> Detail(key, value) }
        if (!event.stackTrace.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text("Stack trace", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(event.stackTrace, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onBackground)
        }
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ActionLink("Copy error details", onCopy)
            if (!event.operationId.isNullOrBlank()) ActionLink("Show this upload", onCorrelate)
            if (!event.localMediaId.isNullOrBlank()) ActionLink("Investigate", onInvestigate)
        }
    }
}

@Composable
private fun InvestigateSheet(
    investigation: InvestigationState,
    onEvent: (DeveloperLogEvent) -> Unit,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Investigate Upload", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Text(investigation.fileName ?: investigation.localMediaId, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close")
            }
        }
        Detail("Local Media ID", investigation.localMediaId)
        investigation.operationId?.let { Detail("Operation ID", it) }
        investigation.clientUploadId?.let { Detail("Client upload ID", it) }
        Spacer(Modifier.height(Spacing.sm))
        Text("Timeline", style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.xs))
        if (investigation.events.isEmpty()) {
            Text("No correlated events yet.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            investigation.events.forEach { event ->
                val ok = event.level != LogLevel.ERROR && event.level != LogLevel.FATAL
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEvent(event) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(if (ok) "OK" else "FAIL", color = if (ok) Sage else StatusAttention, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    Column {
                        Text("${formatClock(event.timestamp)}  ${event.event}", style = MaterialTheme.typography.bodySmall, color = colors.onBackground)
                        Text(
                            buildString {
                                append(event.message)
                                event.httpStatus?.let { append("  HTTP $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}

@Composable
private fun InvestigatePicker(candidates: List<InvestigateCandidate>, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Investigate Upload", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Spacer(Modifier.height(Spacing.sm))
        if (candidates.isEmpty()) {
            Text("No uploads have been logged yet.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            candidates.forEach { candidate ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(candidate.localMediaId) }
                        .padding(vertical = Spacing.sm)
                ) {
                    Text(candidate.fileName, style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                    Text(
                        buildString {
                            append(candidate.lastEvent)
                            candidate.operationId?.let { append(" · op=$it") }
                            if (candidate.failed) append(" · failed")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (candidate.failed) StatusAttention else colors.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = Copper,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = Spacing.xs, horizontal = Spacing.xxs)
    )
}

private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE, LogLevel.DEBUG -> StatusIdle
    LogLevel.INFO -> Sky
    LogLevel.WARNING -> StatusSyncing
    LogLevel.ERROR, LogLevel.FATAL -> StatusAttention
}

private fun formatClock(millis: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis))

private fun formatFullTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title))
}
