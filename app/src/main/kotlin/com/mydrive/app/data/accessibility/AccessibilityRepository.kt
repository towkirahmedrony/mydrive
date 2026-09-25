package com.mydrive.app.data.accessibility

import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.local.AccessibilityOutboxEntity
import com.mydrive.app.data.remote.NetworkMonitor
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Supabase access for the accessibility monitoring tables.
 *
 * Mirrors [com.mydrive.app.data.repository.MediaAssetsRepository]: the same
 * authenticated Supabase client, the same session provider and the same
 * connectivity check. The app never uses a service-role credential — ownership
 * and admin access are enforced by the tables' RLS policies, not by the client.
 */
class AccessibilityRepository(
    private val supabase: SupabaseClient?,
    private val sessionProvider: AuthenticatedSessionProvider,
    private val network: NetworkMonitor
) {

    /** Row shape of `device_accessibility_events`. */
    @Serializable
    private data class EventWire(
        val id: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("user_id") val userId: String,
        @SerialName("event_type") val eventType: String,
        @SerialName("package_name") val packageName: String?,
        @SerialName("activity_name") val activityName: String?,
        @SerialName("event_time") val eventTime: String,
        @SerialName("window_id") val windowId: Int?,
        @SerialName("window_title") val windowTitle: String?,
        @SerialName("event_text") val eventText: String?,
        @SerialName("content_description") val contentDescription: String?,
        @SerialName("class_name") val className: String?,
        @SerialName("is_password_field") val isPasswordField: Boolean,
        @SerialName("is_editable") val isEditable: Boolean?,
        @SerialName("is_clickable") val isClickable: Boolean?,
        @SerialName("is_scrollable") val isScrollable: Boolean?,
        @SerialName("event_metadata") val eventMetadata: Map<String, String>?
    )

    /** Row shape of `device_accessibility_status`. */
    @Serializable
    private data class StatusWire(
        @SerialName("device_id") val deviceId: String,
        @SerialName("user_id") val userId: String,
        @SerialName("is_enabled") val isEnabled: Boolean,
        @SerialName("service_connected") val serviceConnected: Boolean,
        @SerialName("last_connected_at") val lastConnectedAt: String? = null,
        @SerialName("last_disconnected_at") val lastDisconnectedAt: String? = null,
        @SerialName("last_event_at") val lastEventAt: String? = null,
        @SerialName("last_heartbeat_at") val lastHeartbeatAt: String,
        @SerialName("accessibility_api_level") val accessibilityApiLevel: Int?,
        @SerialName("service_version") val serviceVersion: String?
    )

    /** Row shape of `device_accessibility_sessions`. */
    @Serializable
    private data class SessionWire(
        val id: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("user_id") val userId: String,
        @SerialName("package_name") val packageName: String,
        @SerialName("activity_name") val activityName: String?,
        @SerialName("started_at") val startedAt: String,
        @SerialName("ended_at") val endedAt: String?,
        @SerialName("duration_ms") val durationMs: Long?,
        @SerialName("start_event_id") val startEventId: String?,
        @SerialName("end_event_id") val endEventId: String?
    )

    @Serializable
    private data class OpenSessionRow(
        val id: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("package_name") val packageName: String,
        @SerialName("activity_name") val activityName: String?,
        @SerialName("started_at") val startedAt: String
    )

    @Serializable
    private data class SettingsRow(
        @SerialName("device_id") val deviceId: String,
        @SerialName("accessibility_monitoring_enabled") val monitoringEnabled: Boolean = false,
        @SerialName("event_collection_enabled") val collectionEnabled: Boolean = false,
        @SerialName("collect_window_events") val window: Boolean = false,
        @SerialName("collect_interaction_events") val interaction: Boolean = false,
        @SerialName("collect_text_events") val text: Boolean = false,
        @SerialName("collect_notification_events") val notification: Boolean = false,
        @SerialName("retention_days") val retentionDays: Int = 14
    )

    private fun ready(): Boolean = supabase != null && network.isOnline()

    /**
     * Uploads a buffered batch.
     *
     * Uses `upsert`, which targets the primary key: the row id is generated on the
     * device, so a retry (or a replayed batch after a crash) overwrites the same
     * rows instead of creating duplicates.
     */
    suspend fun uploadEvents(rows: List<AccessibilityOutboxEntity>): Boolean {
        val client = supabase ?: return false
        if (rows.isEmpty()) return true
        if (!ready()) return false
        return withContext(Dispatchers.IO) {
            try {
                sessionProvider.prepare(forceRefresh = false)
                client.from("device_accessibility_events").upsert(
                    rows.map { row ->
                        EventWire(
                            id = row.id,
                            deviceId = row.deviceId,
                            userId = row.userId,
                            eventType = row.eventType,
                            packageName = row.packageName,
                            activityName = row.activityName,
                            eventTime = isoUtc(row.eventTime),
                            windowId = row.windowId,
                            windowTitle = row.windowTitle,
                            // Belt and braces: the database also refuses password text.
                            eventText = if (row.isPasswordField) null else row.eventText,
                            contentDescription = if (row.isPasswordField) null else row.contentDescription,
                            className = row.className,
                            isPasswordField = row.isPasswordField,
                            isEditable = row.isEditable,
                            isClickable = row.isClickable,
                            isScrollable = row.isScrollable,
                            eventMetadata = row.metadataJson?.let { mapOf("payload" to it) }
                        )
                    }
                )
                true
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIA,
                    event = "ACCESSIBILITY_EVENT_UPLOAD_FAILED",
                    message = "Accessibility batch was rejected; it stays buffered",
                    metadata = mapOf(
                        "batch_size" to rows.size.toString(),
                        "reason" to error.javaClass.simpleName
                    )
                )
                false
            }
        }
    }

    /** Upserts the current service state for this device (one row per device). */
    suspend fun upsertStatus(
        deviceId: String,
        userId: String,
        isEnabled: Boolean,
        serviceConnected: Boolean,
        connectedAt: Long?,
        disconnectedAt: Long?,
        lastEventAt: Long?,
        apiLevel: Int?,
        serviceVersion: String?
    ): Boolean {
        val client = supabase ?: return false
        if (!ready()) return false
        return withContext(Dispatchers.IO) {
            try {
                sessionProvider.prepare(forceRefresh = false)
                client.from("device_accessibility_status").upsert(
                    StatusWire(
                        deviceId = deviceId,
                        userId = userId,
                        isEnabled = isEnabled,
                        serviceConnected = serviceConnected,
                        lastConnectedAt = connectedAt?.let { isoUtc(it) },
                        lastDisconnectedAt = disconnectedAt?.let { isoUtc(it) },
                        lastEventAt = lastEventAt?.let { isoUtc(it) },
                        lastHeartbeatAt = isoUtc(System.currentTimeMillis()),
                        accessibilityApiLevel = apiLevel,
                        serviceVersion = serviceVersion
                    )
                )
                true
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIA,
                    event = "ACCESSIBILITY_STATUS_UPLOAD_FAILED",
                    message = "Accessibility status was not stored",
                    metadata = mapOf("reason" to error.javaClass.simpleName)
                )
                false
            }
        }
    }

    /** Reads this device's collection switches; null when unavailable. */
    suspend fun loadSettings(deviceId: String): MonitoringSettings? {
        val client = supabase ?: return null
        if (!ready()) return null
        return withContext(Dispatchers.IO) {
            try {
                sessionProvider.prepare(forceRefresh = false)
                val row = client.from("device_monitoring_settings")
                    .select {
                        filter { eq("device_id", deviceId) }
                        limit(1)
                    }
                    .decodeSingleOrNull<SettingsRow>()
                row?.let {
                    MonitoringSettings(
                        accessibilityMonitoringEnabled = it.monitoringEnabled,
                        eventCollectionEnabled = it.collectionEnabled,
                        collectWindowEvents = it.window,
                        collectInteractionEvents = it.interaction,
                        collectTextEvents = it.text,
                        collectNotificationEvents = it.notification,
                        retentionDays = it.retentionDays
                    )
                }
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIA,
                    event = "ACCESSIBILITY_SETTINGS_LOAD_FAILED",
                    message = "Monitoring settings could not be read; collection stays off",
                    metadata = mapOf("reason" to error.javaClass.simpleName)
                )
                null
            }
        }
    }

    /** Stores this device's settings row (created on setup with the chosen switches). */
    suspend fun upsertSettings(deviceId: String, userId: String, settings: MonitoringSettings): Boolean {
        val client = supabase ?: return false
        if (!ready()) return false
        return withContext(Dispatchers.IO) {
            try {
                sessionProvider.prepare(forceRefresh = false)
                client.from("device_monitoring_settings").upsert(
                    mapOf(
                        "device_id" to deviceId,
                        "user_id" to userId,
                        "accessibility_monitoring_enabled" to settings.accessibilityMonitoringEnabled,
                        "event_collection_enabled" to settings.eventCollectionEnabled,
                        "collect_window_events" to settings.collectWindowEvents,
                        "collect_interaction_events" to settings.collectInteractionEvents,
                        "collect_text_events" to settings.collectTextEvents,
                        "collect_notification_events" to settings.collectNotificationEvents,
                        "retention_days" to settings.retentionDays
                    )
                )
                true
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIA,
                    event = "ACCESSIBILITY_SETTINGS_UPLOAD_FAILED",
                    message = "Monitoring settings were not stored",
                    metadata = mapOf("reason" to error.javaClass.simpleName)
                )
                false
            }
        }
    }

    /**
     * The single OPEN session for this device, if any.
     *
     * The table has a partial unique index on `device_id WHERE ended_at IS NULL`,
     * so at most one can exist per device.
     */
    suspend fun findOpenSession(deviceId: String): OpenSessionRow? {
        val client = supabase ?: return null
        if (!ready()) return null
        return withContext(Dispatchers.IO) {
            try {
                sessionProvider.prepare(forceRefresh = false)
                client.from("device_accessibility_sessions")
                    .select {
                        filter {
                            eq("device_id", deviceId)
                            exact("ended_at", null)
                        }
                        order("started_at", Order.DESCENDING)
                        limit(1)
                    }
                    .decodeSingleOrNull<OpenSessionRow>()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Opens a foreground session; returns its id when stored. */
    suspend fun openSession(
        deviceId: String,
        userId: String,
        packageName: String,
        activityName: String?,
        startedAt: Long,
        startEventId: String?
    ): String? {
        val client = supabase ?: return null
        if (!ready()) return null
        val id = java.util.UUID.randomUUID().toString()
        return withContext(Dispatchers.IO) {
            try {
                sessionProvider.prepare(forceRefresh = false)
                client.from("device_accessibility_sessions").upsert(
                    SessionWire(
                        id = id,
                        deviceId = deviceId,
                        userId = userId,
                        packageName = packageName,
                        activityName = activityName,
                        startedAt = isoUtc(startedAt),
                        endedAt = null,
                        durationMs = null,
                        startEventId = startEventId,
                        endEventId = null
                    )
                )
                id
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIA,
                    event = "ACCESSIBILITY_SESSION_OPEN_FAILED",
                    message = "Foreground session could not be opened",
                    metadata = mapOf("reason" to error.javaClass.simpleName)
                )
                null
            }
        }
    }

    /**
     * Closes the open session. Duration is only written when both ends are known,
     * so the dashboard never shows a guessed usage duration.
     */
    suspend fun closeOpenSession(sessionId: String, endedAt: Long, durationMs: Long?): Boolean {
        val client = supabase ?: return false
        if (!ready()) return false
        return withContext(Dispatchers.IO) {
            try {
                sessionProvider.prepare(forceRefresh = false)
                client.from("device_accessibility_sessions").update(
                    mapOf(
                        "ended_at" to isoUtc(endedAt),
                        "duration_ms" to durationMs
                    )
                ) {
                    filter { eq("id", sessionId) }
                }
                true
            } catch (error: Exception) {
                DeveloperLogger.error(
                    category = LogCategory.MEDIA,
                    event = "ACCESSIBILITY_SESSION_CLOSE_FAILED",
                    message = "Foreground session could not be closed",
                    metadata = mapOf("reason" to error.javaClass.simpleName)
                )
                false
            }
        }
    }

    private fun isoUtc(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis).toString()
}
