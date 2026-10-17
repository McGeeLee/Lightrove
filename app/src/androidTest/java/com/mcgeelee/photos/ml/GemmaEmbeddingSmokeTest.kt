package com.mcgeelee.photos.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcgeelee.photos.data.IndexConverters
import com.mcgeelee.photos.data.VectorMath
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-model smoke test. Install the checksum-pinned bundle using the app, or push it to
 * no_backup/models/embeddinggemma-2-740m.litertlm before running connectedDebugAndroidTest.
 * A missing model is an explicit JUnit assumption skip, never a fabricated successful inference.
 */
@RunWith(AndroidJUnit4::class)
class GemmaEmbeddingSmokeTest {
    @Test fun translatedQueriesRetrieveTheMatchingEnglishDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "models/${ModelManager.MODEL_FILE_NAME}")
        assumeTrue("Official 485 MB model is not installed; cross-language retrieval test skipped", file.isFile)
        val manager = ModelManager(context)
        val status = withTimeout(120_000) { manager.status.first { it.phase == ModelPhase.READY || it.phase == ModelPhase.ERROR } }
        assertEquals(status.message, ModelPhase.READY, status.phase)
        val engine = GemmaEmbeddingEngine(context, manager)
        try {
            val sunset = engine.embedDocument("A photograph of a beautiful sunset at the beach, with orange sunlight reflecting on the ocean waves.")
            val receipt = engine.embedDocument("A grocery store receipt listing the prices of milk, bread, eggs and vegetables, with a total payment amount.")
            val storedSunset = storedVector("sunset document", sunset)
            val storedReceipt = storedVector("receipt document", receipt)
            val queries = mapOf(
                "en" to "sunset at the beach",
                "zh-CN" to "海边的日落",
                "zh-TW" to "海邊的日落",
                "ja" to "海辺の夕日",
                "ko" to "해변의 일몰",
                "hi" to "समुद्र तट पर सूर्यास्त",
            )
            queries.forEach { (language, text) ->
                val query = engine.embedQuery(text)
                // Quantization need not preserve unit length, so exercise the search cosine path.
                val matchingScore = checkNotNull(VectorMath.cosine(query, sunset))
                val unrelatedScore = checkNotNull(VectorMath.cosine(query, receipt))
                val storedMatchingScore = checkNotNull(VectorMath.cosine(query, storedSunset))
                val storedUnrelatedScore = checkNotNull(VectorMath.cosine(query, storedReceipt))
                assertTrue("$language should rank the English sunset document above the receipt: $matchingScore <= $unrelatedScore",
                    matchingScore > unrelatedScore)
                assertTrue("$language should retain the same ranking after storage: $storedMatchingScore <= $storedUnrelatedScore",
                    storedMatchingScore > storedUnrelatedScore)
                assertEquals("$language sunset query score after storage", matchingScore.toDouble(),
                    storedMatchingScore.toDouble(), 0.002)
                assertEquals("$language receipt query score after storage", unrelatedScore.toDouble(),
                    storedUnrelatedScore.toDouble(), 0.002)
            }
        } finally {
            engine.close()
        }
    }

    @Test fun officialModelEmbedsTextImageAndAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "models/${ModelManager.MODEL_FILE_NAME}")
        assumeTrue("Official 485 MB model is not installed; real inference test skipped", file.isFile)
        val manager = ModelManager(context)
        val status = withTimeout(120_000) { manager.status.first { it.phase == ModelPhase.READY || it.phase == ModelPhase.ERROR } }
        assertEquals(status.message, ModelPhase.READY, status.phase)

        val engine = GemmaEmbeddingEngine(context, manager)
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            val query = engine.embedQuery("a red image")
            val repeated = engine.embedQuery("a red image")
            val document = engine.embedDocument("A photograph of a red square.")
            val image = engine.embedImage(bitmap)
            val tone = FloatArray(16_000) { (sin(2 * PI * 440 * it / 16_000) * 0.2).toFloat() }
            val audio = engine.embedAudio(tone)
            listOf("query" to query, "repeated query" to repeated, "document" to document,
                "image" to image, "audio" to audio).forEach { (modality, vector) ->
                assertEquals(768, vector.size)
                assertTrue(vector.all { it.isFinite() })
                assertEquals(1.0, sqrt(vector.sumOf { it.toDouble() * it }), 0.001)
                val stored = storedVector(modality, vector)
                assertEquals("$modality query score after storage",
                    checkNotNull(VectorMath.cosine(query, vector)).toDouble(),
                    checkNotNull(VectorMath.cosine(query, stored)).toDouble(), 0.002)
            }
            val similarity = checkNotNull(VectorMath.cosine(query, repeated))
            assertTrue("Repeated text should produce the same embedding", similarity > 0.999)
        } finally {
            bitmap.recycle()
            engine.close()
        }
    }

    private fun storedVector(label: String, vector: FloatArray): FloatArray {
        // Use the actual Room converter; both tests reuse inference results already computed above.
        val converters = IndexConverters()
        val bytes = checkNotNull(converters.encodeVector(vector)) { "$label could not be encoded" }
        assertTrue("$label should occupy fewer bytes than float32", bytes.size < vector.size * Float.SIZE_BYTES)
        val restored = checkNotNull(converters.decodeVector(bytes)) { "$label could not be decoded" }
        assertEquals("$label stored dimensions", vector.size, restored.size)
        val similarity = checkNotNull(VectorMath.cosine(vector, restored))
        assertTrue("$label should retain its direction after storage: cosine=$similarity", similarity > 0.999)
        return restored
    }
}
