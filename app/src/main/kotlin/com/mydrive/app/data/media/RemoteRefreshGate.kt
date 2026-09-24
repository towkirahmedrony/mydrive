package com.mydrive.app.data.media

/**
 * Coalesces equivalent remote catalog refreshes.
 *
 * Photos, Albums, Sync, app resume, the MediaStore observer and pull-to-refresh
 * can all ask for a refresh within the same moment. The repository already
 * serializes them behind a mutex, but serialization alone still lets every caller
 * that queued up issue its own remote page fetch afterwards — the expensive part.
 *
 * This gate lets exactly one remote pass run at a time and remembers that someone
 * asked while it ran, so a burst of N callers costs at most two passes: the
 * running one and a single trailing one that picks up the newest intent. Callers
 * that are folded in return immediately instead of waiting on the mutex.
 *
 * Failure mode is deliberately benign: the worst case is one fewer refresh, and
 * any later trigger (screen resume, pull-to-refresh, MediaStore change) restores
 * it. It never blocks a first pass, so an empty catalog still loads immediately.
 */
class RemoteRefreshGate {

    private var inFlight = false
    private var trailing = false

    /** @return true when the caller should run the remote pass itself. */
    @Synchronized
    fun begin(): Boolean {
        if (inFlight) {
            trailing = true
            return false
        }
        inFlight = true
        return true
    }

    /** @return true when a caller asked for a refresh while a pass was running. */
    @Synchronized
    fun end(): Boolean {
        inFlight = false
        val owed = trailing
        trailing = false
        return owed
    }
}
