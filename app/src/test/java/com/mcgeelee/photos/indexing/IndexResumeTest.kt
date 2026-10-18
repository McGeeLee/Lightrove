package com.mcgeelee.photos.indexing

import com.mcgeelee.photos.data.IndexModality
import com.mcgeelee.photos.data.IndexedSegment
import com.mcgeelee.photos.data.OcrScript
import org.junit.Assert.*
import org.junit.Test

class IndexResumeTest {
    private val all = IndexResume.scriptsMask(OcrScript.entries.toSet())
    private fun text(time: Long, mask: Int = all) = IndexedSegment("video", IndexModality.TEXT, time, ocrScripts = mask)
    private fun visual(time: Long) = IndexedSegment("video", IndexModality.VIDEO, time, vector = floatArrayOf(1f))

    @Test fun interruptedVideoRequestsOnlyUnfinishedFrameOffsets() {
        // Two fully processed frames (one with empty OCR), a visual-only frame, then an unseen frame.
        val segments = listOf(text(0), visual(0), text(5000).copy(text = "hello"), visual(5000), visual(10000))
            .associateBy { it.modality to it.timestampMs }
        val decoded = listOf(0L, 5000L, 10000L, 15000L).filter {
            IndexResume.needsFrame(segments, it, IndexModality.VIDEO, semantic = true, ocrEnabled = true)
        }
        assertEquals(listOf(10000L, 15000L), decoded)
        assertEquals(listOf(15000L), listOf(0L, 5000L, 10000L, 15000L).filter {
            IndexResume.needsFrame(segments, it, IndexModality.VIDEO, semantic = true, ocrEnabled = false)
        })
    }

    @Test fun onlyFailedScriptIsRetriedEvenWhenSuccessfulScriptsFoundNoText() {
        val succeeded = OcrScript.entries.toSet() - OcrScript.KOREAN
        val partial = text(-1, IndexResume.scriptsMask(succeeded))
        assertEquals(setOf(OcrScript.KOREAN), IndexResume.missingScripts(partial))
        assertTrue(IndexResume.missingScripts(partial.copy(ocrScripts = all)).isEmpty())
        assertEquals(OcrScript.entries.toSet(), IndexResume.missingScripts(null))
    }

    @Test fun missingTextEmbeddingDoesNotRequestImageDecodeAgain() {
        val segments = listOf(text(-1).copy(text = "receipt", vector = null),
            IndexedSegment("image", IndexModality.IMAGE, vector = floatArrayOf(1f)))
            .associateBy { it.modality to it.timestampMs }
        assertFalse(IndexResume.needsFrame(segments, -1, IndexModality.IMAGE, semantic = true, ocrEnabled = true))
    }

    @Test fun repeatedSharedFailureStopsButSuccessOrDifferentFailureResetsStreak() {
        val guard = RepeatedIndexFailure()
        assertFalse(guard.shouldStop(setOf("OCR init failed")))
        assertFalse(guard.shouldStop(setOf("OCR init failed")))
        assertFalse(guard.shouldStop(emptySet()))
        assertFalse(guard.shouldStop(setOf("OCR init failed")))
        assertFalse(guard.shouldStop(setOf("Model init failed")))
        assertFalse(guard.shouldStop(setOf("Model init failed")))
        assertTrue(guard.shouldStop(setOf("Model init failed")))
    }
}
