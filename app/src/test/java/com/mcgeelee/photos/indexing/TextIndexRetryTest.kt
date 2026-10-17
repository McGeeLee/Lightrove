package com.mcgeelee.photos.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TextIndexRetryTest {
    @Test fun aFailedScriptCannotRemoveTextRecognizedOnAnEarlierAttempt() {
        val oldVector = floatArrayOf(0.5f, 0.5f)
        val retry = TextIndexRetry.prepare("京都の夕日\nBeach", oldVector,
            recognizedText = "Beach\nSummer", partialRecognition = true)

        assertEquals("京都の夕日\nBeach\nSummer", retry.text)
        // This text is a new document; a failure to embed it must not reuse the previous vector.
        assertNull(retry.reusableVector)
    }

    @Test fun anUnchangedPartialResultReusesItsSuccessfulEmbedding() {
        val oldVector = floatArrayOf(0.5f, 0.5f)
        val retry = TextIndexRetry.prepare("京都の夕日\nBeach", oldVector,
            recognizedText = "Beach", partialRecognition = true)

        assertEquals("京都の夕日\nBeach", retry.text)
        assertSame(oldVector, retry.reusableVector)
    }

    @Test fun aFullySuccessfulRetryReplacesEarlierRecognition() {
        val retry = TextIndexRetry.prepare("Wrong earlier recognition", floatArrayOf(0.5f, 0.5f),
            recognizedText = "Correct recognition", partialRecognition = false)

        assertEquals("Correct recognition", retry.text)
        assertNull(retry.reusableVector)
    }

    @Test fun anEmptyPartialReadPreservesTextButACompleteEmptyReadClearsIt() {
        val oldVector = floatArrayOf(0.5f, 0.5f)
        val partial = TextIndexRetry.prepare("京都の夕日", oldVector, "", partialRecognition = true)
        assertEquals("京都の夕日", partial.text)
        assertSame(oldVector, partial.reusableVector)

        val complete = TextIndexRetry.prepare("京都の夕日", oldVector, "", partialRecognition = false)
        assertEquals("", complete.text)
        assertNull(complete.reusableVector)
    }
}
