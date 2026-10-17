package com.mcgeelee.photos.data

import kotlin.math.ceil
import kotlin.math.min

object VideoSampling {
    /** Uniform bounded OCR samples; samples cover the entire duration, including long videos. */
    fun timestamps(durationMs: Long, maxFrames: Int = 8): List<Long> {
        require(maxFrames in 1..120)
        if (durationMs <= 0) return listOf(0L)
        val count = min(maxFrames, ceil(durationMs / 1000.0).toInt().coerceAtLeast(1))
        return List(count) { index ->
            ((index + 0.5) * durationMs / count).toLong().coerceIn(0L, durationMs - 1)
        }.distinct()
    }

    /** Fixed intervals for a thorough index; consume sequentially to keep memory bounded. */
    fun intervalTimestamps(durationMs: Long, intervalMs: Long = 5_000): Sequence<Long> {
        require(intervalMs > 0)
        if (durationMs <= 0) return sequenceOf(0L)
        return generateSequence(0L) { previous ->
            if (previous <= Long.MAX_VALUE - intervalMs && previous + intervalMs < durationMs) previous + intervalMs else null
        }
    }
}
