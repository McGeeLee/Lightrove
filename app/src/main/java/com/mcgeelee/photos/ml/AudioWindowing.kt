package com.mcgeelee.photos.ml

import kotlin.math.abs
import kotlin.math.ceil

data class AudioChunk(val startMs: Long, val endMs: Long, val samples: FloatArray)

/** Streaming linear resampling; keeps one input sample, independent of recording length. */
internal class MonoResampler(
    private val startUs: Long,
    private val endUs: Long,
    private val outputRate: Int = PcmWave.SAMPLE_RATE,
) {
    private var previousTime = Double.NaN
    private var previousSample = 0f
    private var nextTime = startUs.toDouble()
    private val stepUs = 1_000_000.0 / outputRate

    suspend fun accept(sample: Float, timeUs: Double, emit: suspend (Float, Double) -> Unit) {
        if (timeUs < previousTime) return // Some decoders repeat priming samples after seeking.
        if (previousTime.isNaN()) {
            previousTime = timeUs
            previousSample = sample
            if (nextTime < timeUs) nextTime = startUs + ceil((timeUs - startUs) / stepUs) * stepUs
            if (abs(timeUs - nextTime) < 0.01 && nextTime < endUs) {
                emit(sample, nextTime)
                nextTime += stepUs
            }
            return
        }
        // Do not fabricate audio across gaps, or allocate an arbitrarily long silence buffer.
        if (timeUs - previousTime > 100_000.0) {
            nextTime = maxOf(nextTime, startUs + ceil((timeUs - startUs) / stepUs) * stepUs)
        }
        while (nextTime <= timeUs && nextTime < endUs) {
            if (nextTime >= previousTime) {
                val fraction = ((nextTime - previousTime) / (timeUs - previousTime).coerceAtLeast(1.0)).toFloat()
                emit(previousSample + (sample - previousSample) * fraction, nextTime)
            }
            nextTime += stepUs
        }
        previousTime = timeUs
        previousSample = sample
    }
}

/** Bounded, overlapping windows so words crossing a boundary remain searchable. */
internal class AudioWindowAccumulator(
    durationMs: Int,
    overlapMs: Int,
    private val onChunk: suspend (AudioChunk) -> Unit,
) {
    private val capacity = PcmWave.SAMPLE_RATE * durationMs / 1000
    private val overlap = PcmWave.SAMPLE_RATE * overlapMs / 1000
    private val buffer = FloatArray(capacity)
    private var size = 0
    private var freshSamples = 0
    private var startTimeUs = 0.0
    private var lastTimeUs = Double.NaN
    var count: Int = 0
        private set

    init {
        require(durationMs in 1000..30_000)
        require(overlapMs in 0 until durationMs)
    }

    suspend fun accept(sample: Float, timeUs: Double) {
        if (!lastTimeUs.isNaN() && timeUs - lastTimeUs > 2_000.0) flush()
        if (size == 0) startTimeUs = timeUs
        buffer[size++] = sample
        freshSamples++
        lastTimeUs = timeUs
        if (size == capacity) {
            emit(size)
            if (overlap > 0) buffer.copyInto(buffer, 0, capacity - overlap, capacity)
            startTimeUs += (capacity - overlap) * 1_000_000.0 / PcmWave.SAMPLE_RATE
            size = overlap
            freshSamples = 0
        }
    }

    suspend fun flush() {
        // An overlap-only remainder is already present in the last full window.
        if (freshSamples > 0) emit(size)
        size = 0
        freshSamples = 0
        lastTimeUs = Double.NaN
    }

    private suspend fun emit(length: Int) {
        onChunk(
            AudioChunk(
                startMs = (startTimeUs / 1000.0).toLong(),
                endMs = ((startTimeUs + length * 1_000_000.0 / PcmWave.SAMPLE_RATE) / 1000.0).toLong(),
                samples = buffer.copyOf(length),
            ),
        )
        count++
    }
}
