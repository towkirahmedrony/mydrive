package com.mydrive.app.data.media

/**
 * Coalesces equivalent remote catalog refreshes.
 *
 * Photos, Albums, Sync, app resume, the MediaStore observer and pull-to-refresh
 * can all ask for a refresh within the same moment. The repository already
 * serializes them behind a mutex, but serialization alone still lets every caller
 * that queued up issue its own remote page fetch afterwards — the expensive part.
 *
 * This gate lets exactly one remote pass run at a time. Callers that are folded
 * in return immediately instead of waiting on the mutex, and they are folded
 * *by intent*: a caller that only wanted the catalog re-read is already
 * satisfied by the pass that is running, whereas a caller that demanded a
 * forced pass (a permission grant, a pull-to-refresh, an explicit retry) must
 * not be swallowed by a pass that started without it. Only the latter owes a
 * trailing pass.
 *
 * A launch therefore costs exactly one pass no matter how many startup
 * triggers fire (ViewModel init, app start, foreground, authenticated,
 * screen resume), and a burst of N forced callers still costs at most two.
 *
 * Failure mode is deliberately benign: the worst case is one fewer refresh, and
 * any later trigger (screen resume, pull-to-refresh, MediaStore change) restores
 * it. It never blocks a first pass, so an empty catalog still loads immediately.
 */
class RemoteRefreshGate {

    private var inFlight = false
    private var forcedWhileInFlight = false

    /**
     * @param force whether this caller demands a pass even if the catalog was
     *   already being read.
     * @return true when the caller should run the remote pass itself.
     */
    @Synchronized
    fun begin(force: Boolean = false): Boolean {
        if (inFlight) {
            if (force) forcedWhileInFlight = true
            return false
        }
        inFlight = true
        return true
    }

    /** @return true when a forced refresh was folded in and still owes its own pass. */
    @Synchronized
    fun end(): Boolean {
        inFlight = false
        val owed = forcedWhileInFlight
        forcedWhileInFlight = false
        return owed
    }
}
