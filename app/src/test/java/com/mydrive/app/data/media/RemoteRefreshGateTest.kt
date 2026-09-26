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
    fun `a launch-time burst collapses into exactly one pass`() {
        val gate = RemoteRefreshGate()

        assertTrue("first caller runs", gate.begin())
        assertFalse("photos resume is folded in", gate.begin(force = false))
        assertFalse("albums entry is folded in", gate.begin(force = false))
        assertFalse("app start is folded in", gate.begin(force = false))
        assertFalse("foreground is folded in", gate.begin(force = false))
        assertFalse("authenticated is folded in", gate.begin(force = false))

        assertFalse("nothing further is owed: the running pass covers them all", gate.end())
        assertFalse("and the gate stays quiet", gate.end())
    }

    @Test
    fun `a forced caller folded in still gets its own trailing pass`() {
        val gate = RemoteRefreshGate()

        assertTrue("first caller runs", gate.begin())
        assertFalse("photos resume is folded in", gate.begin(force = false))
        assertFalse("a permission grant is folded in", gate.begin(force = true))
        assertFalse("albums entry is folded in", gate.begin(force = false))

        assertTrue("the forced intent owes one trailing pass", gate.end())
        assertTrue("the trailing pass runs", gate.begin())
        assertFalse("the trailing pass owes nothing itself", gate.end())
    }

    @Test
    fun `a forced caller arriving while the trailing pass runs asks for its own trailing pass`() {
        val gate = RemoteRefreshGate()

        assertTrue(gate.begin())
        assertFalse(gate.begin(force = true))
        assertTrue(gate.end())

        // Trailing pass starts; a pull-to-refresh arrives mid-flight.
        assertTrue(gate.begin())
        assertFalse(gate.begin(force = true))
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
