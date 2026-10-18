package com.mcgeelee.photos.data

import android.content.ContentValues
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcgeelee.photos.ml.AudioChunk
import com.mcgeelee.photos.ml.AudioDecoder
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Self-contained platform integration checks. Every test creates app-owned MediaStore rows
 * from bundled, explicitly synthetic fixtures and deletes exactly those URIs afterwards.
 * Android 10+ lets the app read/write its own media without library read permission.
 */
@RunWith(AndroidJUnit4::class)
class MediaLibraryOcrTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val createdUris = mutableListOf<Uri>()

    @After fun deleteOnlyMediaCreatedByThisTest() {
        val failures = createdUris.mapNotNull { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }.exceptionOrNull()
        }
        createdUris.clear()
        assertTrue("Could not remove owned test media: $failures", failures.isEmpty())
    }

    private fun importFixture(name: String, mimeType: String): Uri {
        val video = mimeType.startsWith("video/")
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "codex_test_${UUID.randomUUID()}_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                "${if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/PhotosIntegrationTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        createdUris += uri
        instrumentation.context.assets.open(name).use { input ->
            requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output -> input.copyTo(output) }
        }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    }

    @Test fun ownedTicketIsReadAndRecognizedInEnglishAndChinese() = runBlocking {
        val uri = importFixture("codex_test_marina_ticket.png", "image/png")
        val library = MediaLibrary(context)
        val ticket = requireNotNull(library.assetFromUri(uri))
        assertEquals(uri.toString(), ticket.uri)
        assertTrue(ticket.sizeBytes > 0)
        assertTrue(!ticket.isVideo)
        OcrIndexer(context, library).use { ocr ->
            val text = ocr.extract(ticket).joinToString("\n") { it.text }
            assertTrue("English ticket text missing: $text", text.contains("MARINA TICKET 2026"))
            assertTrue("Chinese ticket text missing: $text", text.contains("花园") || text.contains("门票"))
        }
    }

    @Test fun videoOcrRecognizesDistinctScenesAtTheirOffsets() = runBlocking {
        val uri = importFixture("codex_test_scenes.mp4", "video/mp4")
        val library = MediaLibrary(context)
        val video = requireNotNull(library.assetFromUri(uri))
        assertTrue(video.isVideo)
        assertTrue(video.durationMs >= 11_000)
        val texts = mutableListOf<Pair<Long, String>>()
        OcrIndexer(context, library).use { ocr ->
            library.forEachVideoFrame(video, listOf(0L, 5_000L, 10_000L)) { frame ->
                texts += frame.timestampMs to ocr.recognize(frame.bitmap)
            }
        }
        assertEquals(listOf(0L, 5_000L, 10_000L), texts.map { it.first })
        assertTrue(texts[0].second.contains("RED CIRCLE"))
        assertTrue(texts[1].second.contains("BLUE SQUARE"))
        assertTrue(texts[2].second.contains("GREEN TRIANGLE"))
    }

    @Test fun japaneseTextIsRecognizedWithEveryBundledScriptEnabled() = runBlocking {
        val text = recognizeBundledScriptFixture("codex_test_japanese.png")
        assertTrue("Japanese travel text missing: $text", text.contains("東京の旅行"))
        assertTrue("Japanese kana/kanji text missing: $text", text.contains("桜の写真"))
    }

    @Test fun koreanTextIsRecognizedWithEveryBundledScriptEnabled() = runBlocking {
        val text = recognizeBundledScriptFixture("codex_test_korean.png")
        assertTrue("Korean location text missing: $text", text.contains("서울"))
        assertTrue("Korean family text missing: $text", text.contains("가족 사진"))
    }

    @Test fun hindiTextIsRecognizedWithEveryBundledScriptEnabled() = runBlocking {
        val text = recognizeBundledScriptFixture("codex_test_hindi.png")
        assertTrue("Hindi location text missing: $text", text.contains("दिल्ली"))
        assertTrue("Hindi family text missing: $text", text.contains("परिवार"))
    }

    @Test fun retryRecognizesOnlyTheRequestedMissingKoreanScript() = runBlocking {
        val uri = importFixture("codex_test_korean.png", "image/png")
        val library = MediaLibrary(context)
        val bitmap = requireNotNull(library.imageBitmap(uri, 2048))
        try {
            OcrIndexer(context, library).use { ocr ->
                val result = ocr.recognizeDetailed(bitmap, setOf(OcrScript.KOREAN))
                assertEquals(setOf(OcrScript.KOREAN), result.successfulScripts)
                assertTrue("Unexpected retry failures: ${result.failures}", result.failures.isEmpty())
                assertTrue("Korean location text missing: ${result.text}", result.text.contains("서울"))
                assertTrue("Korean family text missing: ${result.text}", result.text.contains("가족 사진"))
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun recognizeBundledScriptFixture(name: String): String {
        val uri = importFixture(name, "image/png")
        val library = MediaLibrary(context)
        val bitmap = requireNotNull(library.imageBitmap(uri, 2048))
        try {
            return OcrIndexer(context, library).use { ocr ->
                val result = ocr.recognizeDetailed(bitmap)
                assertEquals("Every bundled model must be available without a script selection", OcrScript.entries.toSet(), result.successfulScripts)
                assertTrue("Unexpected script failures: ${result.failures.map { it.script }}", result.failures.isEmpty())
                result.text
            }
        } finally { bitmap.recycle() }
    }

    @Test fun videoSoundtrackDecodesIntoFinite16KhzOverlappingWindows() = runBlocking {
        val uri = importFixture("codex_test_scenes.mp4", "video/mp4")
        val firstCompressedTimestampUs = inspectAudioTrack(uri)
        val chunks = mutableListOf<AudioChunk>()
        val count = AudioDecoder(context).decode(uri) { chunks += it }
        assertEquals("Valid AAC samples (initial PTS $firstCompressedTimestampUs us) must decode into two windows", 2, count)
        assertEquals(count, chunks.size)
        val first = chunks[0]
        val second = chunks[1]
        assertTrue("Unexpected AAC start time: ${first.startMs}", first.startMs in 0..64)
        assertEquals(10_000L, first.endMs - first.startMs)
        assertEquals(160_000, first.samples.size)
        assertEquals(9_000L, second.startMs - first.startMs)
        assertEquals(1_000L, first.endMs - second.startMs)
        assertTrue("Expected approximately 12 seconds of audio", second.endMs in 11_800..12_200)
        assertArrayEquals(first.samples.copyOfRange(144_000, 160_000), second.samples.copyOfRange(0, 16_000), 0f)
        for (chunk in chunks) {
            assertTrue("Resampled sample count must agree with 16 kHz timecodes",
                abs(chunk.samples.size - (chunk.endMs - chunk.startMs) * 16) <= 16)
            assertTrue(chunk.samples.all { it.isFinite() && it in -1f..1f })
            assertTrue("The fixture's audible tone must survive decoding", chunk.samples.any { abs(it) > .02f })
        }
    }

    @Test fun audioResumeReplaysLastCompletedWindowWithoutLeavingATimestampGap() = runBlocking {
        val uri = importFixture("codex_test_scenes.mp4", "video/mp4")
        val decoder = AudioDecoder(context)
        val baseline = mutableListOf<AudioChunk>()
        decoder.decode(uri) { baseline += it }
        assertEquals(2, baseline.size)
        for (completedIndex in baseline.indices) {
            // Persist the last completed window's start, retaining its overlap on restart.
            val resumeMs = baseline[completedIndex].startMs
            val resumed = mutableListOf<AudioChunk>()
            val count = decoder.decode(uri, startMs = resumeMs) { resumed += it }
            val expected = baseline.drop(completedIndex)
            assertEquals("Resume from $resumeMs must replay the completed window", expected.size, count)
            assertEquals(expected.size, resumed.size)
            expected.zip(resumed).forEach { (original, replayed) ->
                assertTrue("Window start drift at $resumeMs: ${original.startMs} vs ${replayed.startMs}", abs(original.startMs - replayed.startMs) <= 1)
                assertTrue("Window end drift at $resumeMs", abs(original.endMs - replayed.endMs) <= 1)
                assertTrue("Resume lost audio samples at $resumeMs", abs(original.samples.size - replayed.samples.size) <= 16)
                assertTrue("Resumed waveform differs at $resumeMs",
                    alignedWaveformError(original.samples, replayed.samples) < .005)
            }
            resumed.zipWithNext().forEach { (previous, next) ->
                assertTrue("Resume lost the one-second overlap", abs(previous.endMs - next.startMs - 1000) <= 1)
            }
            if (completedIndex > 0) assertTrue("Resume must overlap audio completed before cancellation",
                baseline[completedIndex - 1].endMs - resumed.first().startMs >= 999)
        }
    }

    private fun alignedWaveformError(original: FloatArray, resumed: FloatArray): Double {
        // AAC seeking restarts decoder overlap state; compare after 128 ms of warmup.
        // Millisecond checkpoint timestamps can shift a 16 kHz sample grid by at most 16 samples.
        val end = minOf(original.size, resumed.size) - 16
        assertTrue("Fixture must retain enough audio to compare", end > 2048)
        return (-16..16).minOf { offset ->
            (2048 until end step 16).map { index -> abs(original[index] - resumed[index + offset]).toDouble() }.average()
        }
    }

    private fun inspectAudioTrack(uri: Uri): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            assertTrue("The bundled audiovisual fixture must have an audio track", audioTrack != null)
            extractor.selectTrack(requireNotNull(audioTrack))
            assertTrue("The AAC track must contain compressed samples", extractor.sampleSize > 0)
            return extractor.sampleTime
        } finally { extractor.release() }
    }
}
