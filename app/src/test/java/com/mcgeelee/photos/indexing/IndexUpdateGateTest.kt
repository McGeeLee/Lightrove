package com.mcgeelee.photos.indexing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexUpdateGateTest {
    @Test fun fastFramesProduceAtMostOneWritePerInterval() {
        val gate = IndexUpdateGate(1_000)
        assertTrue(gate.shouldUpdate(0))
        (1L until 1_000L).forEach { assertFalse(gate.shouldUpdate(it)) }
        assertTrue(gate.shouldUpdate(1_000))
        assertFalse(gate.shouldUpdate(1_001))
    }

    @Test fun completionIsPublishedEvenImmediatelyAfterAFrame() {
        val gate = IndexUpdateGate(3_000)
        assertTrue(gate.shouldUpdate(10_000))
        assertTrue(gate.shouldUpdate(10_001, force = true))
        assertFalse(gate.shouldUpdate(10_002))
    }

    @Test fun resetClockDoesNotSuppressUpdatesIndefinitely() {
        val gate = IndexUpdateGate(1_000)
        assertTrue(gate.shouldUpdate(10_000))
        assertTrue(gate.shouldUpdate(0))
        assertFalse(gate.shouldUpdate(1))
    }
}
