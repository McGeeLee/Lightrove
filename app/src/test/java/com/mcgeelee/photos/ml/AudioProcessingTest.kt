package com.mcgeelee.photos.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProcessingTest {
    @Test fun wavHasCorrectHeaderAndSignedPcm() {
        val wave = PcmWave.encode(floatArrayOf(-1f, 0f, 1f))
        val bytes = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wave, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wave, 8, 4, Charsets.US_ASCII))
        assertEquals(42, bytes.getInt(4))
        assertEquals(16_000, bytes.getInt(24))
        assertEquals(1, bytes.getShort(22).toInt())
        assertEquals(16, bytes.getShort(34).toInt())
        assertEquals(6, bytes.getInt(40))
        assertEquals(-32767, bytes.getShort(44).toInt())
        assertEquals(0, bytes.getShort(46).toInt())
        assertEquals(32767, bytes.getShort(48).toInt())
    }

    @Test fun resamplingKeepsTimeAndWaveformWhenSeeking() = runBlocking {
        val startUs = 1_000_000L
        val output = mutableListOf<Float>()
        val timestamps = mutableListOf<Double>()
        val resampler = MonoResampler(startUs, 1_100_000)
        // Simulate seeking to an earlier compressed sync frame, with 48 kHz PCM output.
        for (frame in 0..7200) {
            val timeUs = 950_000 + frame * 1_000_000.0 / 48_000
            val sample = sin(2 * PI * 440 * timeUs / 1_000_000).toFloat()
            resampler.accept(sample, timeUs) { value, timestamp ->
                output += value
                timestamps += timestamp
            }
        }
        assertEquals(1600, output.size)
        assertEquals(startUs.toDouble(), timestamps.first(), 0.001)
        output.forEachIndexed { index, value ->
            val expected = sin(2 * PI * 440 * (1.0 + index / 16_000.0)).toFloat()
            assertEquals(expected, value, 0.001f)
        }
    }

    @Test fun negativeAacPrimingTimestampsTrimAtZeroWithoutDroppingAudio() = runBlocking {
        val timestamps = mutableListOf<Double>()
        val samples = mutableListOf<Float>()
        val resampler = MonoResampler(0, 100_000)
        // 2112 AAC encoder-delay samples at 16 kHz precede the first audible PCM frame.
        for (frame in -2112..1600) {
            resampler.accept(if (frame < 0) -1f else 0.25f, frame * 62.5) { sample, timestamp ->
                samples += sample
                timestamps += timestamp
            }
        }
        assertEquals(1600, samples.size)
        assertEquals(0.0, timestamps.first(), 0.001)
        assertEquals(99_937.5, timestamps.last(), 0.001)
        assertTrue(samples.all { it == 0.25f })
    }

    @Test fun windowsOverlapAndDoNotEmitDuplicateTail() = runBlocking {
        val chunks = mutableListOf<AudioChunk>()
        val windows = AudioWindowAccumulator(1000, 250) { chunks += it }
        repeat(28_000) { index -> windows.accept(index.toFloat(), 5_000_000 + index * 62.5) }
        windows.flush()
        assertEquals(2, chunks.size)
        assertEquals(5000, chunks[0].startMs)
        assertEquals(6000, chunks[0].endMs)
        assertEquals(5750, chunks[1].startMs)
        assertEquals(6750, chunks[1].endMs)
        assertArrayEquals(chunks[0].samples.takeLast(4000).toFloatArray(), chunks[1].samples.take(4000).toFloatArray(), 0f)
    }

    @Test fun finalShortWindowIsPreserved() = runBlocking {
        val chunks = mutableListOf<AudioChunk>()
        val windows = AudioWindowAccumulator(1000, 250) { chunks += it }
        repeat(18_000) { index -> windows.accept(0.5f, index * 62.5) }
        windows.flush()
        assertEquals(2, chunks.size)
        assertEquals(750, chunks[1].startMs)
        assertEquals(1125, chunks[1].endMs)
        assertEquals(6000, chunks[1].samples.size)
    }

    @Test fun discontinuityDoesNotInventSilenceOrMergeTimestamps() = runBlocking {
        val chunks = mutableListOf<AudioChunk>()
        val windows = AudioWindowAccumulator(1000, 100) { chunks += it }
        repeat(1600) { windows.accept(1f, it * 62.5) }
        repeat(1600) { windows.accept(-1f, 5_000_000 + it * 62.5) }
        windows.flush()
        assertEquals(2, chunks.size)
        assertEquals(0, chunks.first().startMs)
        assertEquals(5000, chunks.last().startMs)
        assertTrue(chunks.first().samples.all { it == 1f })
        assertTrue(chunks.last().samples.all { it == -1f })
    }
}
