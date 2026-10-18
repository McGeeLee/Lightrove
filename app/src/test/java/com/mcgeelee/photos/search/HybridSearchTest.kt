package com.mcgeelee.photos.search

import com.mcgeelee.photos.data.*
import org.junit.Assert.*
import org.junit.Test

class HybridSearchTest {
    private fun asset(id: Long, video: Boolean = false) = MediaAsset("content://media/$id", id,
        "IMG_$id", if (video) "video/mp4" else "image/jpeg", 1, id, 20000, 100, 100, "相机", video)
    private fun segment(a: MediaAsset, mode: IndexModality, text: String = "", time: Long = -1,
                        vector: FloatArray? = null, model: String = "v1") =
        IndexedSegment(a.uri, mode, time, text, vector, model)

    @Test fun exactOcrIdentifierOutranksSemanticNeighbor() {
        val a = asset(1); val b = asset(2)
        val results = HybridSearch.search("订单 AB-123", floatArrayOf(1f, 0f), listOf(a,b), listOf(
            segment(a, IndexModality.TEXT, "订单 AB-123 已支付", vector=floatArrayOf(0f,1f)),
            segment(b, IndexModality.IMAGE, vector=floatArrayOf(1f,0f))), modelId="v1")
        assertEquals(a.uri, results.first().asset.uri)
        assertTrue(results.first().exactMatch)
    }
    @Test fun bestAudioSegmentCarriesTimestampAndFileIsDeduplicated() {
        val a = asset(1, true)
        val results = HybridSearch.search("", floatArrayOf(1f,0f), listOf(a), listOf(
            segment(a, IndexModality.AUDIO, time=0, vector=floatArrayOf(.5f,.5f)),
            segment(a, IndexModality.AUDIO, time=9000, vector=floatArrayOf(1f,0f))), modelId="v1")
        assertEquals(1, results.size)
        assertEquals(9000L, results.single().segment!!.timestampMs)
    }
    @Test fun mismatchedModelAndInvalidVectorsCannotBecomeMatches() {
        val a = asset(1)
        val segments = listOf(segment(a,IndexModality.IMAGE,vector=floatArrayOf(1f,0f),model="old"),
            segment(a,IndexModality.TEXT,vector=floatArrayOf(Float.NaN,0f)))
        assertTrue(HybridSearch.search("",floatArrayOf(1f,0f),listOf(a),segments,modelId="v1").isEmpty())
    }
    @Test fun audioFilterDoesNotMatchFilenameOrVisualFrame() {
        val a=asset(1,true)
        assertTrue(HybridSearch.search("IMG_1",floatArrayOf(1f,0f),listOf(a),
            listOf(segment(a,IndexModality.VIDEO,vector=floatArrayOf(1f,0f))),SearchScope.AUDIO,"v1").isEmpty())
    }
    @Test fun chineseAndFullWidthIdentifiersWorkWithoutModel() {
        val a=asset(1)
        val results=HybridSearch.search("ＡＢ－１２３",null,listOf(a),
            listOf(segment(a,IndexModality.TEXT,"发票 AB-123")),modelId="v1")
        assertEquals(a.uri,results.single().asset.uri)
    }
    @Test fun unrelatedQueryReturnsNoResults() {
        val a=asset(1)
        assertTrue(HybridSearch.search("不相关",floatArrayOf(1f,0f),listOf(a),
            listOf(segment(a,IndexModality.IMAGE,vector=floatArrayOf(0f,1f))),modelId="v1").isEmpty())
    }

    @Test fun defaultThresholdExcludesWeakNeighborsWhileKeepingScoreOrder() {
        val weak = asset(1); val strong = asset(2); val strongest = asset(3)
        val results = HybridSearch.search("sunset", floatArrayOf(1f, 0f), listOf(weak, strong, strongest), listOf(
            segment(weak, IndexModality.IMAGE, vector = floatArrayOf(.3f, .9539392f)),
            segment(strong, IndexModality.IMAGE, vector = floatArrayOf(.6f, .8f)),
            segment(strongest, IndexModality.IMAGE, vector = floatArrayOf(.8f, .6f))), modelId = "v1")
        assertEquals(listOf(strongest.uri, strong.uri), results.map { it.asset.uri })
    }

    @Test fun raisingThresholdFiltersTextAndAttachmentQueriesWithoutDroppingExactText() {
        val exact = asset(1); val neighbor = asset(2)
        val assets = listOf(exact, neighbor)
        val segments = listOf(
            segment(exact, IndexModality.TEXT, "receipt AB-123", vector = floatArrayOf(0f, 1f)),
            segment(neighbor, IndexModality.IMAGE, vector = floatArrayOf(.6f, .8f)))
        assertEquals(2, HybridSearch.search("AB-123", floatArrayOf(1f, 0f), assets, segments,
            modelId = "v1", semanticThreshold = .55f).size)
        val strict = HybridSearch.search("AB-123", floatArrayOf(1f, 0f), assets, segments,
            modelId = "v1", semanticThreshold = .75f)
        assertEquals(exact.uri, strict.single().asset.uri)
        assertTrue(strict.single().exactMatch)
        assertEquals(neighbor.uri, HybridSearch.search("", floatArrayOf(1f, 0f), assets, segments,
            modelId = "v1", semanticThreshold = .55f).single().asset.uri)
        assertTrue(HybridSearch.search("", floatArrayOf(1f, 0f), assets, segments,
            modelId = "v1", semanticThreshold = .75f).isEmpty())
    }

    @Test fun thresholdIsInclusiveAndInvalidValuesUseTheDefault() {
        val a = asset(1)
        val segments = listOf(segment(a, IndexModality.IMAGE, vector = floatArrayOf(.6f, .8f)))
        assertEquals(1, HybridSearch.search("", floatArrayOf(1f, 0f), listOf(a), segments,
            modelId = "v1", semanticThreshold = .6f).size)
        assertEquals(HybridSearch.DEFAULT_THRESHOLD, HybridSearch.normalizeThreshold(Float.NaN), 0f)
        assertEquals(HybridSearch.DEFAULT_THRESHOLD, HybridSearch.normalizeThreshold(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, HybridSearch.normalizeThreshold(-.2f), 0f)
        assertEquals(1f, HybridSearch.normalizeThreshold(1.2f), 0f)
    }
}
