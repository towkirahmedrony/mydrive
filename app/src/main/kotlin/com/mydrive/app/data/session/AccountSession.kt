package com.mydrive.app.data.session

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide authenticated account identity for local cache and catalog isolation.
 * Generation increments on every bind/unbind so in-flight work can detect a stale session.
 */
object AccountSession {
    @Volatile
    var userId: String? = null
        private set

    private val generation = AtomicLong(0L)

    fun generation(): Long = generation.get()

    data class Snapshot(val userId: String?, val generation: Long)

    fun snapshot(): Snapshot = Snapshot(userId, generation.get())

    fun isCurrent(userId: String?, generation: Long): Boolean {
        return this.userId == userId && this.generation.get() == generation
    }

    @Synchronized
    fun bind(userId: String?) {
        val normalized = userId?.takeIf { it.isNotBlank() }
        this.userId = normalized
        generation.incrementAndGet()
    }
}
