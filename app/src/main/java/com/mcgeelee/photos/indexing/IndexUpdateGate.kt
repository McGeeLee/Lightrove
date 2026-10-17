package com.mcgeelee.photos.indexing

/** The caller uses a monotonic clock so changing wall time cannot stall progress updates. */
internal class IndexUpdateGate(private val intervalMs: Long) {
    private var lastUpdateAt: Long? = null

    init { require(intervalMs > 0) }

    fun shouldUpdate(now: Long, force: Boolean = false): Boolean {
        val previous = lastUpdateAt
        if (!force && previous != null && now >= previous && now - previous < intervalMs) return false
        lastUpdateAt = now
        return true
    }
}
