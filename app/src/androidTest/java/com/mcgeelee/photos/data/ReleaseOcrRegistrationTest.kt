package com.mcgeelee.photos.data

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run against the minified release APK as well as debug. Registrar names come from the
 * installed manifest: direct constructor references here would hide the reflection defect.
 * The OCR fixture is test-owned and never added to, or removed from, the user's library.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseOcrRegistrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Suppress("DEPRECATION")
    @Test fun manifestRegistrarsRetainTheirReflectiveConstructors() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SERVICES or PackageManager.GET_META_DATA,
        )
        val prefix = "com.google.firebase.components:"
        val names = packageInfo.services.orEmpty().flatMap { service ->
            val metadata = service.metaData
            metadata?.keySet().orEmpty().filter { key ->
                key.startsWith(prefix) && metadata?.getString(key) == "com.google.firebase.components.ComponentRegistrar"
            }.map { it.removePrefix(prefix) }
        }.distinct()
        val required = setOf("CommonComponentRegistrar", "VisionCommonRegistrar", "TextRegistrar")
        assertTrue("ML Kit registrars must be declared in the installed manifest: $names",
            names.map { it.substringAfterLast('.') }.containsAll(required))

        names.forEach { name ->
            val registrar = Class.forName(name, true, context.classLoader)
            // This is the same constructor lookup used by Firebase ComponentDiscovery.
            // NoSuchMethodException identifies the original release-only shrink regression.
            val instance = registrar.getDeclaredConstructor().newInstance()
            assertEquals(name, instance.javaClass.name)
        }
    }

    @Test fun installedOcrRecognizesFixtureWithAllFiveScripts() = runBlocking {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = instrumentation.context.assets.open("codex_test_marina_ticket.png").use { input ->
            requireNotNull(BitmapFactory.decodeStream(input, null, options))
        }
        try {
            OcrIndexer(context).use { ocr ->
                val result = ocr.recognizeDetailed(bitmap)
                assertEquals("All bundled OCR scripts must initialize in the installed build",
                    OcrScript.entries.toSet(), result.successfulScripts)
                assertTrue("OCR initialization/processing failures: ${result.failures.map { it.script to it.cause }}",
                    result.failures.isEmpty())
                assertTrue("English fixture text missing: ${result.text}", result.text.contains("MARINA TICKET 2026"))
                assertTrue("Chinese fixture text missing: ${result.text}",
                    result.text.contains("花园") || result.text.contains("门票"))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
