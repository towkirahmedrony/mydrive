package com.mydrive.app.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mydrive.app.MyDriveApp
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory

/**
 * Accessibility-based monitoring foundation for company-owned devices (phase 1).
 *
 * This service is deliberately thin. It does NOT talk to Supabase, does NOT open
 * a database and does NOT do any network I/O — every event is handed to
 * [AccessibilityMonitoringCoordinator], which buffers it in the existing local
 * queue and lets the existing WorkManager infrastructure upload it in batches.
 * That keeps Accessibility event bursts off the main thread and off the network.
 *
 * SCOPE — metadata only. There is no screen capture, no microphone/camera, no
 * location, no keylogging and no accessibility-tree crawl: only the flags of the
 * event's own source node are read.
 *
 * Android only binds this service after an authorised person enables it in
 * Accessibility settings; nothing here tries to bypass that.
 */
class MyDriveAccessibilityService : AccessibilityService() {

    private val processor = AccessibilityEventProcessor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = applicationContext as? MyDriveApp ?: return
        processor.reset()

        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "ACCESSIBILITY_SERVICE_CONNECTED",
            message = "Accessibility monitoring service connected",
            metadata = mapOf(
                "accessibility_api_level" to Build.VERSION.SDK_INT.toString(),
                "app_version" to appVersion(app)
            )
        )

        app.accessibilityMonitoringCoordinator.onServiceConnected(
            accessibilityApiLevel = Build.VERSION.SDK_INT,
            serviceVersion = appVersion(app)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val incoming = event ?: return
        val app = applicationContext as? MyDriveApp ?: return
        val coordinator = app.accessibilityMonitoringCoordinator

        // Cheapest gates first, before touching any node: unmapped type, then the
        // per-category switch. This is what keeps a chatty app from costing CPU.
        val type = eventTypeOf(incoming.eventType) ?: return
        val settings = coordinator.settings
        if (!settings.collects(type.category)) return

        val deviceId = coordinator.deviceId ?: return
        val userId = coordinator.userId ?: return

        val raw = readRaw(incoming, type)
        val outcome = try {
            processor.process(raw, settings, deviceId, userId)
        } catch (error: Exception) {
            DeveloperLogger.error(
                category = LogCategory.MEDIA,
                event = "ACCESSIBILITY_EVENT_PROCESS_FAILED",
                message = "Dropped an accessibility event during processing",
                metadata = mapOf("reason" to error.javaClass.simpleName)
            )
            return
        }

        when (outcome) {
            is AccessibilityEventProcessor.Outcome.Ignored -> Unit // expected, high volume
            is AccessibilityEventProcessor.Outcome.Emit -> coordinator.enqueue(outcome)
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt: the service holds no long-running work.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Android calls this when the service is disabled or is being unbound,
        // which is the reliable "disconnected" signal for accessibility services.
        val app = applicationContext as? MyDriveApp
        DeveloperLogger.info(
            category = LogCategory.MEDIA,
            event = "ACCESSIBILITY_SERVICE_DISCONNECTED",
            message = "Accessibility monitoring service disconnected"
        )
        app?.accessibilityMonitoringCoordinator?.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    /**
     * Reads only the event's own source node, with a bounded amount of work: no
     * descendant traversal and no text from a password field.
     */
    private fun readRaw(event: AccessibilityEvent, type: MonitoredEventType): RawAccessibilityEvent {
        var node: AccessibilityNodeInfo? = null
        var isPassword = false
        var isEditable = false
        var isClickable = false
        var isScrollable = false
        var className = event.className?.toString()
        var contentDescription: String? = null

        try {
            node = event.source
            if (node != null) {
                isPassword = node.isPassword
                isEditable = node.isEditable
                isClickable = node.isClickable
                isScrollable = node.isScrollable
                className = node.className?.toString() ?: className
                contentDescription = node.contentDescription?.toString()
            }
        } catch (_: Exception) {
            // A stale/recycled node is normal; keep whatever the event itself gave us.
        } finally {
            // Only recycle what we obtained here, and only on API levels where the
            // platform still requires it (deprecated and a no-op from API 33).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                node?.recycle()
            }
        }

        // A password field contributes NO text at all.
        val text = if (isPassword) {
            emptyList()
        } else {
            event.text?.mapNotNull { it?.toString() } ?: emptyList()
        }

        return RawAccessibilityEvent(
            eventType = type,
            packageName = event.packageName?.toString(),
            className = className,
            eventTime = if (event.eventTime > 0L) event.eventTime else System.currentTimeMillis(),
            windowId = event.windowId,
            visibleText = text,
            contentDescription = contentDescription,
            isPassword = isPassword,
            isEditable = isEditable,
            isClickable = isClickable,
            isScrollable = isScrollable
        )
    }

    private fun appVersion(app: MyDriveApp): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    companion object {
        /**
         * Maps platform event types onto the monitored set. An unmapped type is
         * never processed, so enabling a new type is an explicit decision.
         */
        fun eventTypeOf(platformEventType: Int): MonitoredEventType? = when (platformEventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> MonitoredEventType.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> MonitoredEventType.WINDOW_CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_CLICKED -> MonitoredEventType.VIEW_CLICKED
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> MonitoredEventType.VIEW_FOCUSED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> MonitoredEventType.VIEW_SCROLLED
            AccessibilityEvent.TYPE_VIEW_SELECTED -> MonitoredEventType.VIEW_SELECTED
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                MonitoredEventType.NOTIFICATION_STATE_CHANGED
            else -> null
        }
    }
}
