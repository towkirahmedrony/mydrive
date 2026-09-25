package com.mydrive.app.data.accessibility

import android.content.Context
import com.mydrive.app.data.local.AccessibilityOutboxDao
import com.mydrive.app.data.local.AccessibilityOutboxEntity
import com.mydrive.app.data.worker.AccessibilitySyncScheduler
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.data.session.AccountSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Bridges the AccessibilityService to local storage and Supabase.
 *
 * The service stays thin on purpose: it hands over an already-processed event and
 * this coordinator buffers it in the existing Room outbox and asks the existing
 * WorkManager to flush. No network call ever runs on the accessibility callback.
 *
 * Offline behaviour: events accumulate in the outbox (bounded by the device's
 * retention window) and are uploaded in batches once connectivity returns, so a
 * device that is offline keeps working and nothing is lost.
 */
class AccessibilityMonitoringCoordinator(
    private val context: Context,
    private val outboxDao: AccessibilityOutboxDao,
    private val repository: AccessibilityRepository,
    private val deviceIdProvider: suspend () -> String?,
    /** Buffer flush ceiling per worker run; keeps one upload small and bounded. */
    private val batchSize: Int = BATCH_SIZE
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = false }

    /**
     * Current collection switches. Defaults to collecting NOTHING until the
     * device's settings have been read, so a service that connects before the
     * settings are known cannot collect by accident.
     */
    @Volatile
    var settings: MonitoringSettings = MonitoringSettings.COLLECT_NOTHING
        private set

    /** Registered `devices.id` for this install, or null until registration. */
    @Volatile
    var deviceId: String? = null
        private set

    @Volatile
    private var connectedAt: Long? = null

    @Volatile
    private var lastEventAt: Long? = null

    val userId: String?
        get() = AccountSession.userId

    sealed class FlushResult {
        /** Batch accepted (or nothing to send). */
        data object Complete : FlushResult()

        /** Transient failure: keep the buffer and let WorkManager retry. */
        data object Retry : FlushResult()

        /** No signed-in user/device: nothing to attribute events to. */
        data object NotSignedIn : FlushResult()
    }

    // ── Service lifecycle ────────────────────────────────────────────────────

    fun onServiceConnected(accessibilityApiLevel: Int, serviceVersion: String) {
        connectedAt = System.currentTimeMillis()
        scope.launch {
            val userId = userId
            val device = resolveDeviceId()
            if (userId != null && device != null) {
                repository.upsertStatus(
                    deviceId = device,
                    userId = userId,
                    isEnabled = true,
                    serviceConnected = true,
                    connectedAt = connectedAt,
                    disconnectedAt = null,
                    lastEventAt = lastEventAt,
                    apiLevel = accessibilityApiLevel,
                    serviceVersion = serviceVersion
                )
            }
            // Refresh the switches; until this succeeds, collection stays off.
            val device2 = device ?: return@launch
            repository.loadSettings(device2)?.let { settings = it }
        }
    }

    fun onServiceDisconnected() {
        val disconnectedAt = System.currentTimeMillis()
        connectedAt = null
        scope.launch {
            val userId = userId
            val device = deviceId
            if (userId != null && device != null) {
                repository.upsertStatus(
                    deviceId = device,
                    userId = userId,
                    isEnabled = false,
                    serviceConnected = false,
                    connectedAt = null,
                    disconnectedAt = disconnectedAt,
                    lastEventAt = lastEventAt,
                    apiLevel = null,
                    serviceVersion = null
                )
            }
        }
    }

    /**
     * Enables monitoring after the documented setup disclosure. Writes the
     * settings row that the service reads on its next connect.
     */
    fun enableAfterDisclosure(settings: MonitoringSettings) {
        this.settings = settings
        scope.launch {
            val userId = userId ?: return@launch
            val device = resolveDeviceId() ?: return@launch
            repository.upsertSettings(device, userId, settings)
            DeveloperLogger.info(
                category = LogCategory.MEDIA,
                event = "ACCESSIBILITY_MONITORING_ENABLED",
                message = "Monitoring enabled after disclosure",
                metadata = mapOf(
                    "window_events" to settings.collectWindowEvents.toString(),
                    "interaction_events" to settings.collectInteractionEvents.toString(),
                    "text_events" to settings.collectTextEvents.toString(),
                    "notification_events" to settings.collectNotificationEvents.toString(),
                    "retention_days" to settings.retentionDays.toString()
                )
            )
        }
    }

    // ── Event buffering ──────────────────────────────────────────────────────

    fun enqueue(outcome: AccessibilityEventProcessor.Outcome.Emit) {
        val event = outcome.event
        lastEventAt = event.eventTime
        scope.launch {
            try {
                outboxDao.insertAll(listOf(event.toEntity()))
                AccessibilitySyncScheduler.schedule(context)
                outcome.transition?.let { recordTransition(it, event.id) }
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIA,
                    event = "ACCESSIBILITY_BUFFER_FAILED",
                    message = "Accessibility event could not be buffered locally",
                    metadata = mapOf("reason" to error.javaClass.simpleName)
                )
            }
        }
    }

    /**
     * Closes the previous foreground session (when the app/activity changed) and
     * opens the new one. Duration is only written when the previous session's start
     * is known, so the dashboard shows a measured duration or nothing at all.
     */
    private suspend fun recordTransition(transition: ForegroundTransition, startEventId: String) {
        val userId = userId ?: return
        val device = resolveDeviceId() ?: return
        val open = repository.findOpenSession(device)
        if (open != null) {
            val sameTarget = open.packageName == transition.packageName &&
                open.activityName == transition.activityName
            if (sameTarget) return
            val startedMillis = runCatching {
                java.time.Instant.parse(open.startedAt).toEpochMilli()
            }.getOrNull()
            val duration = startedMillis?.let { (transition.at - it).coerceAtLeast(0L) }
            repository.closeOpenSession(open.id, transition.at, duration)
        }
        repository.openSession(
            deviceId = device,
            userId = userId,
            packageName = transition.packageName,
            activityName = transition.activityName,
            startedAt = transition.at,
            startEventId = startEventId
        )
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    /**
     * Uploads one bounded batch. Buffered rows are deleted ONLY after the server
     * confirms the batch, so a failure leaves the events queued for retry.
     */
    suspend fun flush(): FlushResult {
        val userId = userId ?: return FlushResult.NotSignedIn
        val rows = try {
            outboxDao.batch(userId, batchSize)
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.MEDIA,
                event = "ACCESSIBILITY_FLUSH_READ_FAILED",
                message = "Could not read the accessibility buffer",
                metadata = mapOf("reason" to error.javaClass.simpleName)
            )
            return FlushResult.Retry
        }
        if (rows.isEmpty()) {
            // Maintenance still runs: prune the local buffer by retention policy.
            pruneLocalBuffer()
            return FlushResult.Complete
        }
        if (!repository.uploadEvents(rows)) return FlushResult.Retry

        outboxDao.delete(rows.map { it.id })
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "ACCESSIBILITY_BATCH_UPLOADED",
            message = "Accessibility batch uploaded",
            metadata = mapOf("batch_size" to rows.size.toString())
        )

        val device = deviceId
        if (device != null) {
            repository.upsertStatus(
                deviceId = device,
                userId = userId,
                isEnabled = settings.accessibilityMonitoringEnabled,
                serviceConnected = connectedAt != null,
                connectedAt = connectedAt,
                disconnectedAt = null,
                lastEventAt = lastEventAt,
                apiLevel = null,
                serviceVersion = null
            )
        }
        pruneLocalBuffer()
        return FlushResult.Complete
    }

    /** Stale buffered events are dropped so a device cannot hoard data past retention. */
    private suspend fun pruneLocalBuffer() {
        val cutoff = System.currentTimeMillis() -
            settings.retentionDays.toLong() * 24L * 60L * 60L * 1000L
        runCatching { outboxDao.deleteOlderThan(cutoff) }
    }

    private suspend fun resolveDeviceId(): String? {
        deviceId?.let { return it }
        return runCatching { deviceIdProvider() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { deviceId = it }
    }

    private fun PendingAccessibilityEvent.toEntity(): AccessibilityOutboxEntity =
        AccessibilityOutboxEntity(
            id = id,
            userId = userId,
            deviceId = deviceId,
            eventType = eventType.wireName,
            packageName = packageName,
            activityName = activityName,
            eventTime = eventTime,
            windowId = windowId,
            windowTitle = windowTitle,
            // The password guarantee, restated at the last point before storage.
            eventText = if (isPasswordField) null else eventText,
            contentDescription = if (isPasswordField) null else contentDescription,
            className = className,
            isPasswordField = isPasswordField,
            isEditable = isEditable,
            isClickable = isClickable,
            isScrollable = isScrollable,
            metadataJson = metadata?.let { json.encodeToString(it) },
            createdAt = System.currentTimeMillis()
        )

    companion object {
        const val BATCH_SIZE = 50
    }
}
