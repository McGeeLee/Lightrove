package com.mcgeelee.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSamplingTest {
    @Test fun boundedOcrSamplesCoverAnEntireLongVideo() {
        val samples = VideoSampling.timestamps(3_600_000, maxFrames = 8)
        assertEquals(8, samples.size)
        assertTrue(samples.first() < 300_000)
        assertTrue(samples.last() > 3_300_000)
        assertTrue(samples.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun unknownAndVeryShortVideosStillHaveAValidFrame() {
        assertEquals(listOf(0L), VideoSampling.timestamps(0))
        assertEquals(listOf(0L), VideoSampling.timestamps(1))
        assertEquals(listOf(250L), VideoSampling.timestamps(500))
    }

    @Test fun thoroughSamplingHasNoLongVideoCoverageCap() {
        val samples = VideoSampling.intervalTimestamps(60_001, 5_000).toList()
        assertEquals(13, samples.size)
        assertEquals(60_000L, samples.last())
        assertEquals(listOf(0L), VideoSampling.intervalTimestamps(1, 5_000).toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveIntervalIsRejectedInsteadOfLoopingForever() {
        VideoSampling.intervalTimestamps(10_000, 0)
    }
}
