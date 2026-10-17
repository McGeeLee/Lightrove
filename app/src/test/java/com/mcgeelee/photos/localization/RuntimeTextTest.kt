package com.mcgeelee.photos.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeTextTest {
    @Test fun progressArgumentsSurviveStorageWithoutTranslatedProse() {
        val name = "海边: Sunset\n2026.mp4"
        val stored = runtimeText(RuntimeMessage.INDEX_FRAME, name, 45L).encode()
        assertFalse(stored.contains(name))
        assertFalse(stored.contains('\n'))
        assertEquals(RuntimeText(RuntimeMessage.INDEX_FRAME, listOf(name, 45L)), RuntimeText.decode(stored))
    }

    @Test fun pluralCountsKeepTheirNumericType() {
        val decoded = RuntimeText.decode(runtimeText(RuntimeMessage.INDEX_PARTIAL, 3).encode())
        assertEquals(RuntimeText(RuntimeMessage.INDEX_PARTIAL, listOf(3L)), decoded)
    }

    @Test fun legacyOrMalformedMessagesUseTheLocalizedFallback() {
        assertNull(RuntimeText.decode("正在处理照片"))
        assertNull(RuntimeText.decode("@runtime:v1:UNKNOWN"))
        assertNull(RuntimeText.decode("@runtime:v1:INDEX_FRAME"))
        assertNull(RuntimeText.decode("@runtime:v1:INDEX_PARTIAL:%%%"))
        assertNull(RuntimeText.decode(runtimeText(RuntimeMessage.INDEX_PARTIAL, "3").encode()))
        assertNull(RuntimeText.decode(runtimeText(RuntimeMessage.MODEL_READY, 3).encode()))
        assertEquals(RuntimeText(RuntimeMessage.MEDIA_FAILED),
            RuntimeText.fromError(IllegalStateException("Untranslated SDK error"), RuntimeMessage.MEDIA_FAILED))
    }
}
