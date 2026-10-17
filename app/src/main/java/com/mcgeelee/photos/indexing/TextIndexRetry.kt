package com.mcgeelee.photos.indexing

import com.mcgeelee.photos.data.OcrTextMerge

/** The caller must supply previous content only from the same source, model and timestamp. */
internal data class TextIndexRetry(val text: String, val reusableVector: FloatArray?) {
    companion object {
        fun prepare(
            previousText: String?,
            previousVector: FloatArray?,
            recognizedText: String,
            partialRecognition: Boolean,
        ): TextIndexRetry {
            // A failed script cannot retract text that the same source yielded on an earlier pass.
            // A fully successful pass replaces old recognition, including an empty result.
            val text = if (partialRecognition) {
                OcrTextMerge.merge(listOf(previousText.orEmpty(), recognizedText))
            } else recognizedText
            // Never attach a vector for an older/different text to the newly merged document.
            return TextIndexRetry(text, previousVector?.takeIf { previousText == text })
        }
    }
}
