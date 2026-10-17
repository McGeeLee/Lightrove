package com.mcgeelee.photos.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorMathTest {
    @Test fun normalizationPreservesDirectionWithoutMutatingInput() {
        val input = floatArrayOf(3f, 4f)
        assertArrayEquals(floatArrayOf(.6f, .8f), VectorMath.normalize(input), .00001f)
        assertArrayEquals(floatArrayOf(3f, 4f), input, 0f)
        assertEquals(1f, VectorMath.cosine(input, floatArrayOf(30f, 40f))!!, .00001f)
    }

    @Test fun invalidEmbeddingCannotBecomeASearchMatch() {
        assertNull(VectorMath.normalize(floatArrayOf(0f, 0f)))
        assertNull(VectorMath.normalize(floatArrayOf(Float.NaN)))
        assertNull(VectorMath.normalize(floatArrayOf(Float.POSITIVE_INFINITY)))
        assertNull(VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)))
        assertNull(VectorMath.cosine(floatArrayOf(), floatArrayOf()))
        assertNull(VectorMath.cosine(floatArrayOf(0f), floatArrayOf(1f)))
    }

    @Test fun doubleAccumulationHandlesLargeFiniteEmbeddings() {
        val huge = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        val tiny = floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE)
        assertEquals(1f, VectorMath.cosine(huge, tiny)!!, .00001f)
        assertEquals(-1f, VectorMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f))!!, .00001f)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!, .00001f)
    }

    @Test fun persistedVectorsRoundTripWithinQuantizationError() {
        val converter = IndexConverters()
        val input = floatArrayOf(.12345678f, -4f, 0f, Float.MIN_VALUE)
        assertArrayEquals(input, converter.decodeVector(converter.encodeVector(input)), 4f / 254)
        assertNull(converter.decodeVector(byteArrayOf(1, 2, 3)))
        assertNull(converter.decodeVector(null))
    }
}
