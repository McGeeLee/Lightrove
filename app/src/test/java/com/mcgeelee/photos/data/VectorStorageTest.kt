package com.mcgeelee.photos.data

import com.mcgeelee.photos.search.HybridSearch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorStorageTest {
    @Test fun compactEmbeddingUsesOneBytePerDimensionAndHasBoundedError() {
        val random = Random(71)
        repeat(100) {
            val source = requireNotNull(VectorMath.normalize(FloatArray(768) { random.nextDouble(-1.0, 1.0).toFloat() }))
            val packed = requireNotNull(VectorStorage.encode(source))
            val decoded = requireNotNull(VectorStorage.decode(packed))
            assertEquals(780, packed.size)
            assertEquals(source.size, decoded.size)
            val halfStep = source.maxOf { abs(it) } / 254f + 1e-8f
            assertArrayEquals(source, decoded, halfStep)
            assertTrue(requireNotNull(VectorMath.cosine(source, decoded)) > 0.9999f)
        }
    }

    @Test fun oldFloat32BlobsStillDecodeExactly() {
        val source = floatArrayOf(.12345678f, -4f, 0f, Float.MIN_VALUE, Float.MAX_VALUE)
        assertArrayEquals(source, VectorStorage.decode(legacy(source)), 0f)
    }

    @Test fun zeroAndExtremeFiniteVectorsPreserveTheirShape() {
        val zero = FloatArray(768)
        assertArrayEquals(zero, VectorStorage.decode(VectorStorage.encode(zero)), 0f)
        for (source in listOf(floatArrayOf(Float.MIN_VALUE, -Float.MIN_VALUE), floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE))) {
            assertArrayEquals(source, VectorStorage.decode(VectorStorage.encode(source)), 0f)
        }
        val largestSupported = FloatArray(65_535) { 1f }
        assertEquals(largestSupported.size, VectorStorage.decode(VectorStorage.encode(largestSupported))?.size)
        assertNull(VectorStorage.encode(FloatArray(65_536)))
    }

    @Test fun invalidVectorsAndMalformedVersionedPayloadsAreRejected() {
        assertNull(VectorStorage.encode(null))
        assertNull(VectorStorage.encode(floatArrayOf()))
        assertNull(VectorStorage.encode(floatArrayOf(Float.NaN)))
        assertNull(VectorStorage.encode(floatArrayOf(Float.POSITIVE_INFINITY)))
        assertNull(VectorStorage.decode(null))
        assertNull(VectorStorage.decode(byteArrayOf(1, 2, 3)))
        assertNull(VectorStorage.decode(legacy(floatArrayOf(Float.NaN))))
        assertNull(VectorStorage.decode(legacy(floatArrayOf(Float.NEGATIVE_INFINITY))))
        val valid = requireNotNull(VectorStorage.encode(floatArrayOf(1f, 0f)))
        assertNull(VectorStorage.decode(valid.copyOf(valid.size - 1)))
        assertNull(VectorStorage.decode(valid.copyOf(valid.size + 1)))
        assertNull(VectorStorage.decode(valid.copyOf().apply { this[4] = 2 }))
        assertNull(VectorStorage.decode(valid.copyOf().apply { this[5] = 2 }))
        assertNull(VectorStorage.decode(valid.copyOf().apply { this[6] = 0; this[7] = 0 }))
        assertNull(VectorStorage.decode(valid.copyOf().apply { this[12] = (-128).toByte() }))
        for (scale in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 0f)) {
            val invalid = valid.copyOf()
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(8, scale)
            assertNull(VectorStorage.decode(invalid))
        }
    }

    @Test fun repeatedCheckpointsDoNotAccumulateQuantizationLoss() {
        val source = FloatArray(768) { Random(it).nextFloat() * 2 - 1 }
        val original = requireNotNull(VectorStorage.encode(source))
        var packed = original
        repeat(100) { packed = requireNotNull(VectorStorage.encode(VectorStorage.decode(packed))) }
        assertArrayEquals(original, packed)
    }

    @Test fun semanticRankingAndScoresArePreservedForSeparatedMatches() {
        val random = Random(981)
        val query = requireNotNull(VectorMath.normalize(FloatArray(768) { random.nextDouble(-1.0, 1.0).toFloat() }))
        val noise = FloatArray(768) { random.nextDouble(-1.0, 1.0).toFloat() }
        val projection = noise.indices.sumOf { noise[it].toDouble() * query[it] }
        val orthogonal = requireNotNull(VectorMath.normalize(FloatArray(768) { (noise[it] - projection * query[it]).toFloat() }))
        val assets = (1..8).map { index -> MediaAsset("content://test/$index", index.toLong(), "item-$index", "image/jpeg", 1, 1, 0, 100, 100, "", false) }
        val segments = assets.mapIndexed { index, asset ->
            val similarity = 0.94 - index * 0.1
            IndexedSegment(asset.uri, IndexModality.IMAGE, vector = FloatArray(768) {
                (query[it] * similarity + orthogonal[it] * sqrt(1 - similarity * similarity)).toFloat()
            }, modelId = "current")
        }
        val before = HybridSearch.search("", query, assets, segments, modelId = "current")
        val after = HybridSearch.search("", query, assets,
            segments.map { it.copy(vector = VectorStorage.decode(VectorStorage.encode(it.vector))) }, modelId = "current")
        assertEquals(before.map { it.asset.uri }, after.map { it.asset.uri })
        before.zip(after).forEach { (original, compacted) -> assertEquals(original.score, compacted.score, 0.002f) }
    }

    private fun legacy(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        vector.forEach(::putFloat)
    }.array()
}
