package com.mcgeelee.photos.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoDatabaseMigrationTest {
    @Test fun versionOneToThreeKeepsLegacyVectorsAndTracksVideoOcrWithoutReindexingImages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        val photo = MediaAsset("content://test/photo", 1, "photo", "image/jpeg", 10, 1000, 0, 100, 100, "", false)
        val video = MediaAsset("content://test/video", 2, "video", "video/mp4", 10, 2000, 10_000, 100, 100, "", true)
        val vector = FloatArray(768) { it / 768f }
        val legacyVector = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach(::putFloat) }.array()
        context.getDatabasePath(name).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { legacy ->
            createVersionOne(legacy)
            for (source in listOf(photo, video)) {
                legacy.execSQL("INSERT INTO media_assets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(source.uri, source.id, source.name, source.mimeType, source.dateModified, source.dateTaken,
                        source.durationMs, source.width, source.height, source.bucketName, if (source.isVideo) 1 else 0, "", 0))
                legacy.execSQL("INSERT INTO index_records VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(source.uri, "READY", source.dateModified, 123L, null, "current"))
                val modalities = if (source.isVideo) listOf("VIDEO", "AUDIO") else listOf("IMAGE")
                modalities.forEach { modality ->
                    legacy.execSQL("INSERT INTO indexed_segments VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(source.uri, modality, if (source.isVideo) 0L else -1L, "", legacyVector, "current"))
                }
            }
            legacy.version = 1
        }
        val database = Room.databaseBuilder(context, PhotoDatabase::class.java, name)
            .addMigrations(PhotoDatabase.MIGRATION_1_2, PhotoDatabase.MIGRATION_2_3).build()
        try {
            val dao = database.dao()
            assertEquals(2, dao.assets().size)
            assertTrue(requireNotNull(dao.indexRecord(video.uri)).videoOcrEnabled)
            assertTrue(requireNotNull(dao.indexRecord(video.uri)).audioComplete)
            assertFalse(requireNotNull(dao.indexRecord(photo.uri)).audioComplete)
            assertArrayEquals(vector, dao.segments(photo.uri).single().vector, 0f)
            assertEquals(2, dao.indexingPlan("current", videoOcrEnabled = true).ready)

            val disabled = dao.indexingPlan("current", videoOcrEnabled = false)
            assertEquals(listOf(video), disabled.pending)
            assertEquals(1, disabled.ready)
            assertNull(dao.claimIndex(photo, "current", videoOcrEnabled = false))
            val claim = requireNotNull(dao.claimIndex(video, "current", videoOcrEnabled = false))
            assertEquals(setOf(IndexModality.VIDEO, IndexModality.AUDIO), claim.segments.map { it.modality }.toSet())
            claim.segments.forEach { assertArrayEquals(vector, it.vector, 0f) }
            assertFalse(dao.checkpointIndex(video, claim.segments, "current", videoOcrEnabled = true))
            assertFalse(dao.completeIndex(video, claim.segments, "current", videoOcrEnabled = true))
            assertTrue(dao.checkpointIndex(video, claim.segments, "current", videoOcrEnabled = false))
            assertTrue(dao.completeIndex(video, claim.segments, "current", videoOcrEnabled = false))

            assertEquals(2, dao.indexingPlan("current", videoOcrEnabled = false).ready)
            assertEquals(listOf(video), dao.indexingPlan("current", videoOcrEnabled = true).pending)

            // Physical vector maintenance has no authority to change either choice or completion state.
            val records = dao.records().associateBy { it.assetUri }
            dao.compactVectorBatch(Long.MIN_VALUE)
            assertEquals(records, dao.records().associateBy { it.assetUri })

            val reenabled = requireNotNull(dao.claimIndex(video, "current", videoOcrEnabled = true))
            assertEquals(2, reenabled.segments.size)
            assertTrue(dao.completeIndex(video, reenabled.segments, "current", videoOcrEnabled = true))
            assertEquals(2, dao.indexingPlan("current", videoOcrEnabled = true).ready)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionTwoToThreeMarksOnlyReadyOcrAndCompletedSemanticVideoAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v2-${System.nanoTime()}.db"
        val vector = floatArrayOf(0.25f, -0.5f, 1f)
        val legacyVector = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach(::putFloat) }.array()
        val cases = listOf(
            LegacyCase("ready-video", IndexState.READY, isVideo = true, modelId = "current", videoOcrEnabled = true),
            LegacyCase("ready-ocr-video", IndexState.READY, isVideo = true, modelId = "mlkit-five-scripts-v2", videoOcrEnabled = true),
            LegacyCase("ready-photo", IndexState.READY, isVideo = false, modelId = "current", videoOcrEnabled = true),
            LegacyCase("failed-video", IndexState.FAILED, isVideo = true, modelId = "current", videoOcrEnabled = true),
            LegacyCase("pending-video", IndexState.PENDING, isVideo = true, modelId = "current", videoOcrEnabled = false),
            LegacyCase("processing-video", IndexState.PROCESSING, isVideo = true, modelId = "current", videoOcrEnabled = true),
        )
        context.getDatabasePath(name).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { legacy ->
            createVersionOne(legacy)
            legacy.execSQL("ALTER TABLE index_records ADD COLUMN videoOcrEnabled INTEGER NOT NULL DEFAULT 1")
            cases.forEachIndexed { index, case ->
                legacy.execSQL("INSERT INTO media_assets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(case.uri, index.toLong(), case.name, if (case.isVideo) "video/mp4" else "image/jpeg",
                        10L, 1000L, if (case.isVideo) 25_000L else 0L, 100, 100, "", if (case.isVideo) 1 else 0, "", 4096L))
                legacy.execSQL("INSERT INTO index_records VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(case.uri, case.state.name, 10L, 123L,
                        if (case.state == IndexState.FAILED) "retry me" else null, case.modelId, if (case.videoOcrEnabled) 1 else 0))
                // Blank OCR is a valid completed result; migration must preserve its marker too.
                legacy.execSQL("INSERT INTO indexed_segments VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(case.uri, "TEXT", if (case.isVideo) 0L else -1L, "", null, case.modelId))
                legacy.execSQL("INSERT INTO indexed_segments VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(case.uri, if (case.isVideo) "VIDEO" else "IMAGE",
                        if (case.isVideo) 0L else -1L, "", legacyVector, case.modelId))
            }
            legacy.version = 2
        }
        val database = Room.databaseBuilder(context, PhotoDatabase::class.java, name)
            .addMigrations(PhotoDatabase.MIGRATION_1_2, PhotoDatabase.MIGRATION_2_3).build()
        try {
            val dao = database.dao()
            assertEquals(6, dao.assets().size)
            for (case in cases) {
                val record = requireNotNull(dao.indexRecord(case.uri))
                assertEquals(case.state, record.state)
                assertEquals(case.modelId, record.modelId)
                assertEquals(case.videoOcrEnabled, record.videoOcrEnabled)
                assertEquals(123L, record.updatedAt)
                assertEquals(if (case.state == IndexState.FAILED) "retry me" else null, record.error)
                assertEquals(case.state == IndexState.READY && case.isVideo && case.modelId != "mlkit-five-scripts-v2",
                    record.audioComplete)
                assertEquals(0L, record.audioResumeMs)

                val segments = dao.segments(case.uri)
                assertEquals(2, segments.size)
                val ocr = segments.single { it.modality == IndexModality.TEXT }
                assertEquals(if (case.state == IndexState.READY) 31 else 0, ocr.ocrScripts)
                assertEquals("", ocr.text)
                assertNull(ocr.vector)
                val visual = segments.single { it.modality != IndexModality.TEXT }
                assertEquals(0, visual.ocrScripts)
                assertArrayEquals(vector, visual.vector, 0f)
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private data class LegacyCase(
        val name: String,
        val state: IndexState,
        val isVideo: Boolean,
        val modelId: String,
        val videoOcrEnabled: Boolean,
    ) {
        val uri: String get() = "content://test/$name"
    }

    /** Frozen v1 DDL: opening this file through Room exercises schema validation and the actual migration. */
    private fun createVersionOne(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE media_assets (
                uri TEXT NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, mimeType TEXT NOT NULL,
                dateModified INTEGER NOT NULL, dateTaken INTEGER NOT NULL, durationMs INTEGER NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, bucketName TEXT NOT NULL,
                isVideo INTEGER NOT NULL, ocrText TEXT NOT NULL, sizeBytes INTEGER NOT NULL, PRIMARY KEY(uri))
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE indexed_segments (
                assetUri TEXT NOT NULL, modality TEXT NOT NULL, timestampMs INTEGER NOT NULL,
                text TEXT NOT NULL, vector BLOB, modelId TEXT NOT NULL,
                PRIMARY KEY(assetUri, modality, timestampMs),
                FOREIGN KEY(assetUri) REFERENCES media_assets(uri) ON UPDATE NO ACTION ON DELETE CASCADE)
        """.trimIndent())
        db.execSQL("CREATE INDEX index_indexed_segments_assetUri ON indexed_segments(assetUri)")
        db.execSQL("""
            CREATE TABLE index_records (
                assetUri TEXT NOT NULL, state TEXT NOT NULL, sourceModified INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL, error TEXT, modelId TEXT NOT NULL, PRIMARY KEY(assetUri),
                FOREIGN KEY(assetUri) REFERENCES media_assets(uri) ON UPDATE NO ACTION ON DELETE CASCADE)
        """.trimIndent())
        db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL("INSERT INTO room_master_table VALUES (42, '74c8d6a58995797cd03e8badf1f986e9')")
    }
}
