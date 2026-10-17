package com.mcgeelee.photos.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
                // These are L2-normalized real model vectors, so the dot product is cosine similarity.
                val matchingScore = query.indices.sumOf { query[it].toDouble() * sunset[it] }
                val unrelatedScore = query.indices.sumOf { query[it].toDouble() * receipt[it] }
                assertTrue("$language should rank the English sunset document above the receipt: $matchingScore <= $unrelatedScore",
                    matchingScore > unrelatedScore)
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
            listOf(query, repeated, document, image, audio).forEach { vector ->
                assertEquals(768, vector.size)
                assertTrue(vector.all { it.isFinite() })
                assertEquals(1.0, sqrt(vector.sumOf { it.toDouble() * it }), 0.001)
            }
            val similarity = query.indices.sumOf { query[it].toDouble() * repeated[it] }
            assertTrue("Repeated text should produce the same embedding", similarity > 0.999)
        } finally {
            bitmap.recycle()
            engine.close()
        }
    }
}
