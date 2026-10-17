package com.mcgeelee.photos.ml

import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.runtimeText
import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Real LiteRT-LM inference. There is deliberately no substitute vector if inference fails. */
class GemmaEmbeddingEngine(context: Context, private val models: ModelManager) {
    private val appContext = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "photos-embedding").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var nativeEngine: EmbeddingEngine? = null

    val modelReady: Boolean get() = models.modelReady
    val modelPath: String? get() = models.modelPath
    val status get() = models.status

    suspend fun embedQuery(text: String): FloatArray {
        require(text.isNotBlank()) { runtimeText(RuntimeMessage.QUERY_EMPTY).encode() }
        // Task prefixes follow the actual model card, including its asymmetric document format.
        return embed(listOf(InputData.Text("task: search result | query: ${text.trim().take(2000)}")))
    }

    suspend fun embedDocument(text: String): FloatArray {
        require(text.isNotBlank()) { runtimeText(RuntimeMessage.DOCUMENT_EMPTY).encode() }
        // Bound OCR input well below the model's 8192-token window, including CJK byte fallback.
        return embed(listOf(InputData.Text("title: none | text: ${text.trim().take(2000)}")))
    }

    suspend fun embedImage(bitmap: Bitmap): FloatArray = withContext(dispatcher) {
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { runtimeText(RuntimeMessage.IMAGE_ENCODING_FAILED).encode() }
            output.toByteArray()
        }
        embed(listOf(InputData.Image(bytes)))
    }

    /** Samples must be 16 kHz mono PCM. The released Kotlin API accepts a WAV byte stream. */
    suspend fun embedAudio(samples: FloatArray): FloatArray =
        embed(listOf(InputData.Audio(PcmWave.encode(samples))))

    private suspend fun embed(inputs: List<InputData>): FloatArray = mutex.withLock {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            val modelPath = models.modelPath ?: error(runtimeText(RuntimeMessage.MODEL_REQUIRED).encode())
            var engine = nativeEngine
            if (engine == null) {
                val cpu = Backend.CPU(threadCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                val candidate = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = modelPath,
                        backend = cpu,
                        visionBackend = cpu,
                        audioBackend = cpu,
                        cacheDir = appContext.cacheDir.resolve("embeddinggemma").apply { mkdirs() }.absolutePath,
                        maxInputLength = 8192,
                        visionTokensPerImage = 140,
                        activationDataType = ActivationDataType.FLOAT32,
                    ),
                )
                try {
                    candidate.initialize()
                } catch (error: Throwable) {
                    if (candidate.isInitialized()) candidate.close()
                    throw error
                }
                nativeEngine = candidate
                engine = candidate
            }
            val vector = engine.computeEmbedding(inputs, EmbeddingOptions(normalize = true, outputSize = DIMENSIONS)).embedding
            currentCoroutineContext().ensureActive()
            require(vector.size == DIMENSIONS && vector.all { it.isFinite() }) { runtimeText(RuntimeMessage.VECTOR_INVALID).encode() }
            val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
            require(norm > 1e-8) { runtimeText(RuntimeMessage.VECTOR_EMPTY).encode() }
            FloatArray(vector.size) { (vector[it] / norm).toFloat() }
        }
    }

    /** Release native model memory; subsequent requests lazily re-open it. */
    suspend fun close() = mutex.withLock {
        withContext(dispatcher) {
            nativeEngine?.close()
            nativeEngine = null
        }
    }

    companion object { const val DIMENSIONS = 768 }
}
