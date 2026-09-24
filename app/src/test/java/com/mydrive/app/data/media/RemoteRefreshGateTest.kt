package com.mydrive.app.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRefreshGateTest {

    @Test
    fun `the first caller always runs so an empty catalog still loads`() {
        val gate = RemoteRefreshGate()
        assertTrue(gate.begin())
        assertFalse(gate.end())
    }

    @Test
    fun `a burst of callers collapses into one running pass plus one trailing pass`() {
        val gate = RemoteRefreshGate()

        assertTrue("first caller runs", gate.begin())
        assertFalse("photos resume is folded in", gate.begin())
        assertFalse("albums entry is folded in", gate.begin())
        assertFalse("media store refresh is folded in", gate.begin())

        assertTrue("one trailing pass is owed", gate.end())
        assertTrue("the trailing pass runs", gate.begin())
        assertFalse("nothing else was queued", gate.end())
    }

    @Test
    fun `a caller arriving while the trailing pass runs asks for its own trailing pass`() {
        val gate = RemoteRefreshGate()

        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertTrue(gate.end())

        // Trailing pass starts; a pull-to-refresh arrives mid-flight.
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertTrue(gate.end())
        assertFalse(gate.end())
    }

    @Test
    fun `the gate never reports a trailing pass that nobody asked for`() {
        val gate = RemoteRefreshGate()
        assertTrue(gate.begin())
        assertFalse(gate.end())
        assertFalse(gate.end())
    }
}
