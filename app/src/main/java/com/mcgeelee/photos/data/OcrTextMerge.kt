package com.mcgeelee.photos.data

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class OcrScript { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

data class OcrScriptFailure(val script: OcrScript, val cause: Exception)

data class OcrRecognitionResult(
    val text: String,
    val successfulScripts: Set<OcrScript>,
    val failures: List<OcrScriptFailure>,
) {
    val isPartial: Boolean get() = successfulScripts.isNotEmpty() && failures.isNotEmpty()
}

/** Android-free normalization shared by real OCR and its JVM regression tests. */
object OcrTextMerge {
    private val whitespace = Regex("[\\p{Z}\\s]+")

    fun merge(recognizedTexts: Iterable<String>): String {
        val lines = LinkedHashMap<String, String>()
        for (text in recognizedTexts) {
            for (rawLine in text.lineSequence()) {
                // Width/kana variants and canonically equivalent glyphs become one search line.
                // Preserve Indic vowel marks, nukta, ZWJ/ZWNJ, punctuation, and word boundaries.
                val line = Normalizer.normalize(rawLine, Normalizer.Form.NFKC)
                    .replace("\uFEFF", "").replace("\u200B", "")
                    .replace(whitespace, " ").trim()
                if (line.isNotEmpty()) lines.putIfAbsent(line.lowercase(Locale.ROOT), line)
            }
        }
        return lines.values.joinToString("\n")
    }
}

internal data class OcrReader(val script: OcrScript, val read: suspend () -> String)

/** Each script is independent: an unavailable model cannot discard other scripts' results. */
internal suspend fun recognizeAllScripts(readers: Iterable<OcrReader>): OcrRecognitionResult {
    val texts = mutableListOf<String>()
    val successfulScripts = linkedSetOf<OcrScript>()
    val failures = mutableListOf<OcrScriptFailure>()
    for (reader in readers) {
        currentCoroutineContext().ensureActive()
        try {
            texts += reader.read()
            successfulScripts += reader.script
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures += OcrScriptFailure(reader.script, error)
        }
    }
    currentCoroutineContext().ensureActive()
    return OcrRecognitionResult(OcrTextMerge.merge(texts), successfulScripts, failures)
}
