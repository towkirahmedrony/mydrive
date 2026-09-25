package com.mydrive.app.data.accessibility

/**
 * Models for the Accessibility-based monitoring foundation.
 *
 * PHASE 1 SCOPE — metadata only. Nothing in this package captures screens,
 * audio, camera, location or keystrokes. Text is only ever taken from the
 * Accessibility API when it is safe (see [AccessibilityEventProcessor]).
 *
 * Privacy rule that the whole pipeline depends on: a password field's value is
 * never turned into [PendingAccessibilityEvent.eventText]. The database enforces
 * the same rule with
 * `CHECK (NOT is_password_field OR event_text IS NULL)`.
 */

/** The event categories this phase understands, and whether they are collected. */
enum class MonitoredEventType(val wireName: String, val category: EventCategory) {
    WINDOW_STATE_CHANGED("WINDOW_STATE_CHANGED", EventCategory.WINDOW),
    WINDOW_CONTENT_CHANGED("WINDOW_CONTENT_CHANGED", EventCategory.WINDOW),
    VIEW_CLICKED("VIEW_CLICKED", EventCategory.INTERACTION),
    VIEW_FOCUSED("VIEW_FOCUSED", EventCategory.INTERACTION),
    VIEW_SCROLLED("VIEW_SCROLLED", EventCategory.INTERACTION),
    VIEW_SELECTED("VIEW_SELECTED", EventCategory.INTERACTION),
    TEXT_CHANGED("TEXT_CHANGED", EventCategory.TEXT),
    NOTIFICATION_STATE_CHANGED("NOTIFICATION_STATE_CHANGED", EventCategory.NOTIFICATION);

    companion object {
        fun fromWireName(value: String?): MonitoredEventType? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class EventCategory { WINDOW, INTERACTION, TEXT, NOTIFICATION }

/**
 * What the AccessibilityService read off one event, as plain data.
 *
 * Deliberately not an Android type: this is the boundary that keeps
 * [AccessibilityEventProcessor] unit-testable and keeps Android API calls out of
 * the filtering and privacy logic.
 */
data class RawAccessibilityEvent(
    val eventType: MonitoredEventType?,
    val packageName: String?,
    val className: String?,
    val eventTime: Long,
    val windowId: Int,
    val visibleText: List<String>,
    val contentDescription: String?,
    val isPassword: Boolean,
    val isEditable: Boolean,
    val isClickable: Boolean,
    val isScrollable: Boolean
)

/**
 * One processed event, safe to persist and to send to Supabase.
 *
 * [id] is generated on the device so re-sending the same event is an idempotent
 * upsert instead of a duplicate row.
 */
data class PendingAccessibilityEvent(
    val id: String,
    val deviceId: String,
    val userId: String,
    val eventType: MonitoredEventType,
    val packageName: String?,
    val activityName: String?,
    val eventTime: Long,
    val windowId: Int?,
    val windowTitle: String?,
    /** Always null for a password field. */
    val eventText: String?,
    val contentDescription: String?,
    val className: String?,
    val isPasswordField: Boolean,
    val isEditable: Boolean?,
    val isClickable: Boolean?,
    val isScrollable: Boolean?,
    val metadata: Map<String, String>?
)

/** A foreground app/activity transition detected from window events. */
data class ForegroundTransition(
    val packageName: String,
    val activityName: String?,
    val at: Long
)

/**
 * Per-device collection switches, mirroring `device_monitoring_settings`.
 *
 * Defaults minimise collection: everything is off until the device is set up
 * with the documented disclosure in front of the employee.
 */
data class MonitoringSettings(
    val accessibilityMonitoringEnabled: Boolean = false,
    val eventCollectionEnabled: Boolean = false,
    val collectWindowEvents: Boolean = false,
    val collectInteractionEvents: Boolean = false,
    val collectTextEvents: Boolean = false,
    val collectNotificationEvents: Boolean = false,
    val retentionDays: Int = 14
) {
    /** Whether raw events of [category] may be collected at all. */
    fun collects(category: EventCategory): Boolean {
        if (!accessibilityMonitoringEnabled || !eventCollectionEnabled) return false
        return when (category) {
            EventCategory.WINDOW -> collectWindowEvents
            EventCategory.INTERACTION -> collectInteractionEvents
            EventCategory.TEXT -> collectTextEvents
            EventCategory.NOTIFICATION -> collectNotificationEvents
        }
    }

    companion object {
        /** Used when the device has no settings row yet: collect nothing. */
        val COLLECT_NOTHING = MonitoringSettings()
    }
}
