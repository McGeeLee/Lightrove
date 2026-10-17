package com.mcgeelee.photos.data

import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextMergeTest {
    @Test fun repeatedLatinLinesAcrossModelsCollapseWidthWhitespaceAndCaseVariants() {
        val text = OcrTextMerge.merge(listOf(
            "\uFEFFＭＡＲＩＮＡ　ＴＩＣＫＥＴ\r\n  Admit\tOne  ",
            "marina\u00a0ticket\nAdmit One\n\u200B",
        ))
        assertEquals("MARINA TICKET\nAdmit One", text)
    }

    @Test fun mixedScriptsKeepMeaningfulMarksAndWordsWhileEquivalentGlyphsDeduplicate() {
        val hindi = "क्\u200Dष परिवार की तस्वीर"
        val korean = "가족 사진"
        val decomposedKorean = Normalizer.normalize(korean, Normalizer.Form.NFD)
        val text = OcrTextMerge.merge(listOf(
            "ｶﾀｶﾅ\n東京の旅行\n$korean\n$hindi",
            "カタカナ\n$decomposedKorean\n$hindi\n滨海湾花园门票",
        ))
        assertEquals(listOf("カタカナ", "東京の旅行", korean, hindi, "滨海湾花园门票"), text.lines())
    }

    @Test fun duplicateDetectionDoesNotChangeWithDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("TICKET", OcrTextMerge.merge(listOf("TICKET", "ticket")))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun scriptFailurePreservesOtherScriptsAndStillAttemptsEveryModel() = runBlocking {
        val attempted = mutableListOf<OcrScript>()
        val result = recognizeAllScripts(OcrScript.entries.map { script ->
            OcrReader(script) {
                attempted += script
                when (script) {
                    OcrScript.CHINESE -> throw IOException("Unavailable model")
                    OcrScript.LATIN -> "TRAVEL TICKET"
                    OcrScript.JAPANESE -> "東京の旅行\nTRAVEL TICKET"
                    OcrScript.KOREAN -> "서울 여행"
                    OcrScript.DEVANAGARI -> "दिल्ली की यात्रा"
                }
            }
        })
        assertEquals(OcrScript.entries, attempted)
        assertEquals(setOf(OcrScript.LATIN, OcrScript.JAPANESE, OcrScript.KOREAN, OcrScript.DEVANAGARI), result.successfulScripts)
        assertEquals(listOf(OcrScript.CHINESE), result.failures.map { it.script })
        assertTrue(result.isPartial)
        assertEquals("TRAVEL TICKET\n東京の旅行\n서울 여행\nदिल्ली की यात्रा", result.text)
    }

    @Test fun emptySuccessfulImageIsDifferentFromAllModelsFailing() = runBlocking {
        val empty = recognizeAllScripts(OcrScript.entries.map { OcrReader(it) { "" } })
        val failed = recognizeAllScripts(OcrScript.entries.map { OcrReader(it) { throw IOException("Unavailable") } })
        assertEquals("", empty.text)
        assertEquals(OcrScript.entries.toSet(), empty.successfulScripts)
        assertTrue(empty.failures.isEmpty())
        assertTrue(failed.successfulScripts.isEmpty())
        assertEquals(5, failed.failures.size)
        assertFalse(failed.isPartial)
    }

    @Test fun cancellationStopsBeforeStartingTheNextNativeReader() = runBlocking {
        val attempted = mutableListOf<OcrScript>()
        var cancelled = false
        try {
            recognizeAllScripts(OcrScript.entries.map { script ->
                OcrReader(script) {
                    attempted += script
                    if (script == OcrScript.CHINESE) throw CancellationException("Stopped")
                    "TRAVEL TICKET"
                }
            })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(listOf(OcrScript.LATIN, OcrScript.CHINESE), attempted)
    }
}
