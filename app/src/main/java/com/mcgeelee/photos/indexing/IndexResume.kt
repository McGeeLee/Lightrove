package com.mcgeelee.photos.indexing

import com.mcgeelee.photos.data.IndexModality
import com.mcgeelee.photos.data.IndexedSegment
import com.mcgeelee.photos.data.OcrScript

/** Decisions are based on persisted successes, never on whether recognized text is nonempty. */
internal object IndexResume {
    fun scriptsMask(scripts: Set<OcrScript>): Int = scripts.fold(0) { mask, script -> mask or (1 shl script.ordinal) }

    fun missingScripts(segment: IndexedSegment?): Set<OcrScript> =
        OcrScript.entries.filterTo(linkedSetOf()) { (segment?.ocrScripts ?: 0) and (1 shl it.ordinal) == 0 }

    fun needsFrame(
        segments: Map<Pair<IndexModality, Long>, IndexedSegment>,
        time: Long,
        visualModality: IndexModality,
        semantic: Boolean,
        ocrEnabled: Boolean,
    ): Boolean = (semantic && segments[visualModality to time]?.vector == null) ||
        (ocrEnabled && missingScripts(segments[IndexModality.TEXT to time]).isNotEmpty())
}

/** Stop a repeating shared OCR/inference failure before applying it to the whole library. */
internal class RepeatedIndexFailure(private val limit: Int = 3) {
    private var streaks = emptyMap<String, Int>()
    fun shouldStop(failures: Set<String>): Boolean {
        streaks = failures.associateWith { (streaks[it] ?: 0) + 1 }
        return streaks.values.any { it >= limit }
    }
}
