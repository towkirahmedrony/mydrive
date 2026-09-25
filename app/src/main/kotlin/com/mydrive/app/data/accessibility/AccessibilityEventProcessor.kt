package com.mydrive.app.data.accessibility

import java.util.UUID

/**
 * Turns raw Accessibility events into the small, safe subset worth persisting.
 *
 * Responsibilities, in order:
 *   1. drop event types this phase does not monitor,
 *   2. honour the per-category switches in [MonitoringSettings],
 *   3. strip sensitive values (a password field's text is never kept),
 *   4. debounce repeats so a chatty app cannot flood Supabase,
 *   5. detect foreground transitions so sessions can be summarised.
 *
 * Deliberately free of Android API calls: all Android-specific extraction
 * happens in the service, which hands over a [RawAccessibilityEvent]. That keeps
 * the privacy and filtering rules under unit test.
 */
class AccessibilityEventProcessor(
    private val debounceWindowMs: Long = DEFAULT_DEBOUNCE_MS,
    private val windowContentDebounceMs: Long = WINDOW_CONTENT_DEBOUNCE_MS,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    sealed class Outcome {
        /** Nothing worth keeping: irrelevant type, disabled category, or a repeat. */
        data class Ignored(val reason: String) : Outcome()

        data class Emit(
            val event: PendingAccessibilityEvent,
            val transition: ForegroundTransition? = null
        ) : Outcome()
    }

    private val lastEmittedAt = HashMap<String, Long>()
    private var currentForeground: ForegroundTransition? = null

    /** Diagnostic counters, exposed for the Developer Console timeline. */
    var ignoredCount: Int = 0
        private set
    var debouncedCount: Int = 0
        private set
    var emittedCount: Int = 0
        private set
    var passwordStrippedCount: Int = 0
        private set

    fun process(
        raw: RawAccessibilityEvent,
        settings: MonitoringSettings,
        deviceId: String,
        userId: String
    ): Outcome {
        val type = raw.eventType ?: return ignore("unsupported_event_type")

        if (!settings.collects(type.category)) return ignore("category_disabled:${type.category}")

        // A password field is still worth recording as METADATA (which app, which
        // window), but its value never is.
        val strippedText = if (raw.isPassword) {
            passwordStrippedCount += 1
            null
        } else if (type.category == EventCategory.TEXT || type.category == EventCategory.INTERACTION) {
            raw.visibleText.firstOrNull { it.isNotBlank() }
        } else {
            null
        }

        val description = if (raw.isPassword) null else raw.contentDescription?.takeIf { it.isNotBlank() }

        val transition = foregroundTransition(raw, type)
        val debounceKey = debounceKey(raw, type, strippedText, description)
        val now = raw.eventTime
        val window = if (type == MonitoredEventType.WINDOW_CONTENT_CHANGED) {
            windowContentDebounceMs
        } else {
            debounceWindowMs
        }
        val previous = lastEmittedAt[debounceKey]
        if (previous != null && now - previous < window) {
            debouncedCount += 1
            // A repeat is dropped, but a foreground change accompanying it is not:
            // losing a session boundary would corrupt usage summaries.
            return if (transition != null) {
                emittedCount += 1
                lastEmittedAt[debounceKey] = now
                Outcome.Emit(buildEvent(raw, type, null, null, deviceId, userId), transition)
            } else {
                ignore("debounced", countDebounce = false)
            }
        }

        lastEmittedAt[debounceKey] = now
        emittedCount += 1
        return Outcome.Emit(
            event = buildEvent(raw, type, strippedText, description, deviceId, userId),
            transition = transition
        )
    }

    /** Clears debounce/session state, e.g. when the service reconnects. */
    fun reset() {
        lastEmittedAt.clear()
        currentForeground = null
    }

    private fun buildEvent(
        raw: RawAccessibilityEvent,
        type: MonitoredEventType,
        text: String?,
        description: String?,
        deviceId: String,
        userId: String
    ): PendingAccessibilityEvent {
        val foreground = currentForeground
        return PendingAccessibilityEvent(
            id = idFactory(),
            deviceId = deviceId,
            userId = userId,
            eventType = type,
            packageName = raw.packageName,
            activityName = foreground?.takeIf { it.packageName == raw.packageName }?.activityName,
            eventTime = raw.eventTime,
            windowId = raw.windowId,
            // Only kept for window events, where it describes the screen rather
            // than user-entered content.
            windowTitle = if (type == MonitoredEventType.WINDOW_STATE_CHANGED) {
                raw.visibleText.firstOrNull { it.isNotBlank() }?.take(MAX_TITLE_CHARS)
            } else {
                null
            },
            eventText = text?.take(MAX_TEXT_CHARS),
            contentDescription = description?.take(MAX_TEXT_CHARS),
            className = raw.className,
            isPasswordField = raw.isPassword,
            isEditable = raw.isEditable,
            isClickable = raw.isClickable,
            isScrollable = raw.isScrollable,
            metadata = buildMap {
                put("event_type_raw", type.wireName)
                if (raw.isPassword) put("sensitive_field", "password")
            }
        )
    }

    private fun foregroundTransition(
        raw: RawAccessibilityEvent,
        type: MonitoredEventType
    ): ForegroundTransition? {
        if (type != MonitoredEventType.WINDOW_STATE_CHANGED) return null
        val packageName = raw.packageName?.takeIf { it.isNotBlank() } ?: return null
        val activity = raw.className?.takeIf { it.isNotBlank() }
        val previous = currentForeground
        if (previous != null && previous.packageName == packageName && previous.activityName == activity) {
            return null
        }
        val transition = ForegroundTransition(packageName, activity, raw.eventTime)
        currentForeground = transition
        return transition
    }

    private fun debounceKey(
        raw: RawAccessibilityEvent,
        type: MonitoredEventType,
        text: String?,
        description: String?
    ): String = buildString {
        append(type.wireName)
        append('|')
        append(raw.packageName.orEmpty())
        append('|')
        append(raw.className.orEmpty())
        append('|')
        append(raw.windowId)
        append('|')
        append(text.orEmpty())
        append('|')
        append(description.orEmpty())
    }

    private fun ignore(reason: String, countDebounce: Boolean = true): Outcome.Ignored {
        ignoredCount += 1
        if (countDebounce && reason == "debounced") debouncedCount += 1
        return Outcome.Ignored(reason)
    }

    companion object {
        /** Repeat window for ordinary events. */
        const val DEFAULT_DEBOUNCE_MS = 750L

        /** WINDOW_CONTENT_CHANGED fires constantly; widen its window. */
        const val WINDOW_CONTENT_DEBOUNCE_MS = 2_000L

        /** Bounds a single stored string so a long UI text cannot bloat a row. */
        const val MAX_TEXT_CHARS = 200
        const val MAX_TITLE_CHARS = 200
    }
}
