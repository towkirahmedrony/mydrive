package com.mydrive.app.data.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtering, debouncing and privacy rules for the accessibility pipeline.
 *
 * These are pure JVM tests: the Android-specific extraction lives in the
 * AccessibilityService, which hands the processor a plain [RawAccessibilityEvent].
 */
class AccessibilityEventProcessorTest {

    private var nextId = 0
    private fun processor() = AccessibilityEventProcessor(idFactory = { "evt-${nextId++}" })

    private val allOn = MonitoringSettings(
        accessibilityMonitoringEnabled = true,
        eventCollectionEnabled = true,
        collectWindowEvents = true,
        collectInteractionEvents = true,
        collectTextEvents = true,
        collectNotificationEvents = true
    )

    private fun windowEvent(
        at: Long,
        pkg: String = "com.example.app",
        cls: String = "com.example.app.MainActivity",
        windowId: Int = 1,
        text: List<String> = listOf("Home"),
        password: Boolean = false,
        type: MonitoredEventType = MonitoredEventType.WINDOW_STATE_CHANGED
    ) = RawAccessibilityEvent(
        eventType = type,
        packageName = pkg,
        className = cls,
        eventTime = at,
        windowId = windowId,
        visibleText = text,
        contentDescription = null,
        isPassword = password,
        isEditable = false,
        isClickable = true,
        isScrollable = false
    )

    private fun emit(processor: AccessibilityEventProcessor, raw: RawAccessibilityEvent) =
        processor.process(raw, allOn, deviceId = "device-1", userId = "user-1")

    // ── 4: foreground changes drive sessions ──────────────────────────────────

    @Test
    fun `first window event opens a foreground transition`() {
        val outcome = emit(processor(), windowEvent(at = 1_000L))
        outcome as AccessibilityEventProcessor.Outcome.Emit
        assertEquals("com.example.app", outcome.transition?.packageName)
        assertEquals("com.example.app.MainActivity", outcome.transition?.activityName)
    }

    @Test
    fun `switching app produces a new transition`() {
        val processor = processor()
        emit(processor, windowEvent(at = 1_000L, pkg = "com.a"))
        val second = emit(processor, windowEvent(at = 5_000L, pkg = "com.b", cls = "com.b.B"))
        second as AccessibilityEventProcessor.Outcome.Emit
        assertEquals("com.b", second.transition?.packageName)
    }

    @Test
    fun `staying in the same activity produces no further transition`() {
        val processor = processor()
        emit(processor, windowEvent(at = 1_000L))
        val repeat = emit(processor, windowEvent(at = 9_000L, windowId = 2))
        // Debounce may or may not drop it, but it must never re-open a session.
        if (repeat is AccessibilityEventProcessor.Outcome.Emit) {
            assertNull(repeat.transition)
        }
    }

    // ── 5: repeated identical events are debounced ────────────────────────────

    @Test
    fun `identical events inside the debounce window are dropped`() {
        val processor = processor()
        val first = emit(processor, windowEvent(at = 10_000L, type = MonitoredEventType.VIEW_CLICKED))
        val second = emit(processor, windowEvent(at = 10_100L, type = MonitoredEventType.VIEW_CLICKED))

        assertTrue(first is AccessibilityEventProcessor.Outcome.Emit)
        assertTrue(second is AccessibilityEventProcessor.Outcome.Ignored)
        assertEquals(1, processor.emittedCount)
    }

    @Test
    fun `a different target is not debounced away`() {
        val processor = processor()
        emit(processor, windowEvent(at = 10_000L, type = MonitoredEventType.VIEW_CLICKED, pkg = "com.a"))
        val other = emit(processor, windowEvent(at = 10_100L, type = MonitoredEventType.VIEW_CLICKED, pkg = "com.b"))

        assertTrue(other is AccessibilityEventProcessor.Outcome.Emit)
    }

    @Test
    fun `content changes use the longer debounce window`() {
        val processor = processor()
        emit(processor, windowEvent(at = 1_000L, type = MonitoredEventType.WINDOW_CONTENT_CHANGED))
        // Beyond the ordinary window but inside the content window.
        val soonAfter = emit(
            processor,
            windowEvent(at = 1_000L + AccessibilityEventProcessor.DEFAULT_DEBOUNCE_MS + 50L,
                type = MonitoredEventType.WINDOW_CONTENT_CHANGED)
        )
        assertTrue(soonAfter is AccessibilityEventProcessor.Outcome.Ignored)
    }

    // ── 7: password text is never persisted ──────────────────────────────────

    @Test
    fun `password field text is stripped but the event is still recorded`() {
        val processor = processor()
        val outcome = emit(
            processor,
            windowEvent(
                at = 1_000L,
                type = MonitoredEventType.VIEW_FOCUSED,
                text = listOf("hunter2"),
                password = true
            )
        )

        outcome as AccessibilityEventProcessor.Outcome.Emit
        assertNull("password text must never be stored", outcome.event.eventText)
        assertNull("password content description must never be stored", outcome.event.contentDescription)
        assertTrue(outcome.event.isPasswordField)
        assertEquals(1, processor.passwordStrippedCount)
    }

    @Test
    fun `non-password text is stored`() {
        val outcome = emit(
            processor(),
            windowEvent(at = 1_000L, type = MonitoredEventType.VIEW_CLICKED, text = listOf("Delete"))
        )
        outcome as AccessibilityEventProcessor.Outcome.Emit
        assertEquals("Delete", outcome.event.eventText)
        assertFalse(outcome.event.isPasswordField)
    }

    @Test
    fun `window content text is not duplicated into event text`() {
        // Plain window changes keep the screen title, not arbitrary field content.
        val outcome = emit(processor(), windowEvent(at = 1_000L))
        outcome as AccessibilityEventProcessor.Outcome.Emit
        assertNull(outcome.event.eventText)
    }

    // ── 11: settings disable collection ──────────────────────────────────────

    @Test
    fun `collection off means every category is ignored`() {
        val processor = processor()
        val disabled = MonitoringSettings(
            accessibilityMonitoringEnabled = true,
            eventCollectionEnabled = false
        )
        val outcome = processor.process(
            windowEvent(at = 1_000L),
            disabled,
            deviceId = "device-1",
            userId = "user-1"
        )
        assertTrue(outcome is AccessibilityEventProcessor.Outcome.Ignored)
        assertEquals(0, processor.emittedCount)
    }

    @Test
    fun `a disabled category is ignored while another stays enabled`() {
        val processor = processor()
        val windowOnly = MonitoringSettings(
            accessibilityMonitoringEnabled = true,
            eventCollectionEnabled = true,
            collectWindowEvents = true,
            collectInteractionEvents = false
        )
        val click = processor.process(
            windowEvent(at = 1_000L, type = MonitoredEventType.VIEW_CLICKED),
            windowOnly,
            deviceId = "device-1",
            userId = "user-1"
        )
        assertTrue(click is AccessibilityEventProcessor.Outcome.Ignored)

        val window = processor.process(
            windowEvent(at = 2_000L),
            windowOnly,
            deviceId = "device-1",
            userId = "user-1"
        )
        assertTrue(window is AccessibilityEventProcessor.Outcome.Emit)
    }

    @Test
    fun `unknown event types are ignored`() {
        val processor = processor()
        val outcome = processor.process(
            windowEvent(at = 1_000L).copy(eventType = null),
            allOn,
            deviceId = "device-1",
            userId = "user-1"
        )
        assertTrue(outcome is AccessibilityEventProcessor.Outcome.Ignored)
    }

    // ── payload hygiene ─────────────────────────────────────────────────────

    @Test
    fun `stored strings are bounded`() {
        val long = "x".repeat(1_000)
        val outcome = emit(
            processor(),
            windowEvent(at = 1_000L, type = MonitoredEventType.VIEW_CLICKED, text = listOf(long))
        )
        outcome as AccessibilityEventProcessor.Outcome.Emit
        assertEquals(AccessibilityEventProcessor.MAX_TEXT_CHARS, outcome.event.eventText?.length)
    }

    // ── text-change events are no longer subscribed at all ───────────────────

    @Test
    fun `no text-change event type is monitored`() {
        // The monitoring requirement excludes typed text, so there must be no
        // subscription (and no enum entry) that can observe user input.
        assertTrue(
            MonitoredEventType.entries.none { it.wireName == "TEXT_CHANGED" }
        )
    }

    @Test
    fun `unsubscribed text-change type maps to nothing`() {
        // Guards the service mapping too: even if Android delivered the event, the
        // service would have no mapping for it and would drop it.
        assertEquals(null, MonitoredEventType.fromWireName("TEXT_CHANGED"))
    }

    // ── window titles are no longer persisted ────────────────────────────────

    @Test
    fun `window title is never persisted`() {
        val outcome = emit(
            processor(),
            windowEvent(at = 1_000L, text = listOf("Inbox - confidential subject"))
        )
        outcome as AccessibilityEventProcessor.Outcome.Emit
        assertNull(
            "screen-derived window text must not be stored",
            outcome.event.windowTitle
        )
    }

    @Test
    fun `the event identity is carried through for idempotent storage`() {
        val outcome = emit(processor(), windowEvent(at = 1_000L))
        outcome as AccessibilityEventProcessor.Outcome.Emit
        assertEquals("device-1", outcome.event.deviceId)
        assertEquals("user-1", outcome.event.userId)
        assertTrue(outcome.event.id.startsWith("evt-"))
    }
}
