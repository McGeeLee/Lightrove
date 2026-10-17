package com.mcgeelee.photos.ml

import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.runtimeText
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Standard little-endian PCM16 WAV, which InputData.Audio accepts. */
internal object PcmWave {
    const val SAMPLE_RATE = 16_000

    fun encode(samples: FloatArray): ByteArray {
        require(samples.isNotEmpty() && samples.size <= SAMPLE_RATE * 30) { runtimeText(RuntimeMessage.AUDIO_LENGTH).encode() }
        require(samples.all { it.isFinite() }) { runtimeText(RuntimeMessage.AUDIO_VALUES).encode() }
        val bytes = samples.size * 2
        return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + bytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(bytes)
            samples.forEach { sample -> putShort((sample.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()) }
        }.array()
    }
}
