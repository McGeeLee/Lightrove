package com.mcgeelee.photos.search

import com.mcgeelee.photos.data.IndexModality
import com.mcgeelee.photos.data.IndexedSegment
import com.mcgeelee.photos.data.MediaAsset
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

enum class SearchScope { ALL, IMAGE, VIDEO, TEXT, AUDIO }
data class SearchHit(val asset: MediaAsset, val segment: IndexedSegment?, val score: Float, val exactMatch: Boolean)

/** Exact strings rescue invoice numbers and OCR; semantic matches retain their own timecode. */
object HybridSearch {
    fun search(
        query: String,
        queryVector: FloatArray?,
        assets: List<MediaAsset>,
        segments: List<IndexedSegment>,
        scope: SearchScope = SearchScope.ALL,
        modelId: String,
        limit: Int = 100,
    ): List<SearchHit> {
        val text = normalize(query)
        if (text.isEmpty() && queryVector == null) return emptyList()
        val byUri = assets.filter {
            when (scope) { SearchScope.IMAGE -> !it.isVideo; SearchScope.VIDEO -> it.isVideo; else -> true }
        }.associateBy(MediaAsset::uri)
        val best = HashMap<String, SearchHit>()
        fun offer(hit: SearchHit) {
            if (hit.score > (best[hit.asset.uri]?.score ?: Float.NEGATIVE_INFINITY)) best[hit.asset.uri] = hit
        }
        if (scope != SearchScope.AUDIO && scope != SearchScope.TEXT && text.isNotBlank()) {
            byUri.values.forEach { asset ->
                val words = lexicalScore(text, "${asset.name} ${asset.bucketName}")
                if (words > 0f) offer(SearchHit(asset, null, words, true))
            }
        }
        segments.forEach { segment ->
            val asset = byUri[segment.assetUri] ?: return@forEach
            if (scope == SearchScope.TEXT && segment.modality != IndexModality.TEXT) return@forEach
            if (scope == SearchScope.AUDIO && segment.modality != IndexModality.AUDIO) return@forEach
            val words = if (text.isNotEmpty()) lexicalScore(text, segment.text) else 0f
            val semantic = if (queryVector != null && segment.modelId == modelId) {
                cosine(queryVector, segment.vector)
            } else null
            // Raw cosine is an ordering signal, never a claimed confidence percentage.
            if (words > 0f || (semantic != null && semantic >= 0.25f)) {
                offer(SearchHit(asset, segment, words + (semantic?.coerceAtLeast(0f) ?: 0f), words > 0f))
            }
        }
        return best.values.sortedWith(compareByDescending<SearchHit> { it.score }
            .thenByDescending { it.asset.dateTaken }.thenBy { it.asset.uri }).take(limit)
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun lexicalScore(query: String, value: String): Float {
        if (query.isBlank()) return 0f
        val haystack = normalize(value)
        if (haystack.contains(query)) return 2f
        val terms = query.split(' ').filter { it.length >= 2 }.distinct()
        if (terms.size < 2) return 0f
        val coverage = terms.count(haystack::contains).toFloat() / terms.size
        return if (coverage >= 0.5f) coverage else 0f
    }

    private fun cosine(a: FloatArray, b: FloatArray?): Float? {
        if (b == null || a.isEmpty() || a.size != b.size) return null
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) {
            if (!a[i].isFinite() || !b[i].isFinite()) return null
            dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i]
        }
        if (aa == 0.0 || bb == 0.0) return null
        return (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
    }
}
