package com.mcgeelee.photos.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Versioned symmetric int8 storage; callers continue to work with ordinary float vectors. */
internal object VectorStorage {
    // This little-endian float bit pattern is NaN, so it cannot prefix a valid legacy vector.
    private const val MAGIC = 0x7fc05653
    private const val VERSION = 1
    private const val SIGNED_INT8 = 1
    private const val HEADER_BYTES = 12
    private const val MAX_DIMENSIONS = 65_535
    val magicBytes: ByteArray get() = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).array()

    fun encode(vector: FloatArray?): ByteArray? {
        if (vector == null || vector.isEmpty() || vector.size > MAX_DIMENSIONS || vector.any { !it.isFinite() }) return null
        val maximum = vector.maxOf { abs(it) }
        return ByteBuffer.allocate(HEADER_BYTES + vector.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(MAGIC)
            put(VERSION.toByte())
            put(SIGNED_INT8.toByte())
            putShort(vector.size.toShort())
            // Store maxAbs, not maxAbs / 127: even subnormal float scales remain representable.
            putFloat(maximum)
            vector.forEach { value ->
                val quantized = if (maximum == 0f) 0 else (value.toDouble() / maximum * 127).roundToInt().coerceIn(-127, 127)
                put(quantized.toByte())
            }
        }.array()
    }

    fun decode(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.size < Float.SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC) {
            if (bytes.size % Float.SIZE_BYTES != 0 || bytes.size / Float.SIZE_BYTES > MAX_DIMENSIONS) return null
            buffer.position(0)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }.takeIf { vector -> vector.all { it.isFinite() } }
        }
        if (bytes.size < HEADER_BYTES || buffer.get().toInt() != VERSION || buffer.get().toInt() != SIGNED_INT8) return null
        val dimensions = buffer.short.toInt() and 0xffff
        if (dimensions == 0 || bytes.size != HEADER_BYTES + dimensions) return null
        val maximum = buffer.float
        if (!maximum.isFinite() || maximum < 0f) return null
        val result = FloatArray(dimensions)
        for (index in result.indices) {
            val quantized = buffer.get().toInt()
            if (quantized == -128 || (maximum == 0f && quantized != 0)) return null
            result[index] = (quantized / 127.0 * maximum).toFloat()
        }
        return result
    }
}
