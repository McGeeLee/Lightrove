package com.mcgeelee.photos.data

import android.content.Context
import android.graphics.Bitmap
import com.mcgeelee.photos.R
import com.mcgeelee.photos.localization.AppLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * All five bundled ML Kit scripts are available offline on first launch, independently of the
 * UI language. Every image/frame runs all five recognizers sequentially: image script cannot
 * reliably be inferred from UI language or from language identification before OCR.
 */
class OcrIndexer(
    context: Context,
    private val library: MediaLibrary = MediaLibrary(context),
) : Closeable {
    private val appContext = context.applicationContext
    private val recognitionMutex = Mutex()
    private val lifecycleLock = Any()
    private var activeCalls = 0
    private var closeRequested = false
    private val recognizers = listOf(
        RecognizerSlot(OcrScript.LATIN) { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) },
        RecognizerSlot(OcrScript.CHINESE) { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) },
        RecognizerSlot(OcrScript.JAPANESE) { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) },
        RecognizerSlot(OcrScript.KOREAN) { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) },
        RecognizerSlot(OcrScript.DEVANAGARI) { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) },
    )

    /** Compatibility API; detailed results also expose partial failures so indexing can retry. */
    suspend fun recognize(bitmap: Bitmap): String = recognizeDetailed(bitmap).text

    suspend fun recognizeDetailed(bitmap: Bitmap): OcrRecognitionResult {
        currentCoroutineContext().ensureActive()
        synchronized(lifecycleLock) {
            check(!closeRequested) { localizedString(R.string.ocr_closed) }
            activeCalls++
        }
        try {
            return recognitionMutex.withLock {
                currentCoroutineContext().ensureActive()
                val input = InputImage.fromBitmap(bitmap, 0)
                val result = recognizeAllScripts(recognizers.map { slot ->
                    OcrReader(slot.script) {
                        // ML Kit tasks are not cancellable. Wait for this one active read before
                        // allowing the caller to recycle its bitmap, then check cancellation.
                        val text = withContext(NonCancellable) { slot.client().process(input).await().text }
                        currentCoroutineContext().ensureActive()
                        text
                    }
                })
                if (result.successfulScripts.isEmpty()) {
                    val failure = IOException(localizedString(R.string.ocr_all_scripts_failed), result.failures.firstOrNull()?.cause)
                    result.failures.drop(1).forEach { failure.addSuppressed(it.cause) }
                    throw failure
                }
                result
            }
        } finally {
            synchronized(lifecycleLock) {
                activeCalls--
                if (closeRequested && activeCalls == 0) closeRecognizers()
            }
        }
    }

    /** A bounded default pass. The caller can recognize every videoFrame for exhaustive indexing. */
    suspend fun extract(asset: MediaAsset, maxVideoFrames: Int = 8): List<OcrSegment> {
        if (!asset.isVideo) {
            val bitmap = library.imageBitmap(asset, 2048)
                ?: throw IOException(localizedString(R.string.ocr_image_unavailable))
            return try {
                val text = recognize(bitmap)
                if (text.isBlank()) emptyList() else listOf(OcrSegment(text))
            } finally { bitmap.recycle() }
        }
        val result = mutableListOf<OcrSegment>()
        library.forEachVideoFrame(asset, VideoSampling.timestamps(asset.durationMs, maxVideoFrames), 1280) { frame ->
            val text = recognize(frame.bitmap)
            if (text.isNotBlank()) result += OcrSegment(text, frame.timestampMs)
        }
        return result
    }

    override fun close() {
        synchronized(lifecycleLock) {
            closeRequested = true
            // A concurrent close cannot invalidate a native task still reading a bitmap.
            if (activeCalls == 0) closeRecognizers()
        }
    }

    private fun closeRecognizers() {
        recognizers.forEach { slot -> runCatching { slot.close() } }
    }

    private fun localizedString(id: Int) = AppLanguage.localizedContext(appContext).getString(id)

    private class RecognizerSlot(val script: OcrScript, private val create: () -> TextRecognizer) {
        private var recognizer: TextRecognizer? = null
        fun client(): TextRecognizer = recognizer ?: create().also { recognizer = it }
        fun close() {
            recognizer?.close()
            recognizer = null
        }
    }
}
