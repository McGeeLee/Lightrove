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
    @Test fun versionOneKeepsLegacyVectorsAndTracksVideoOcrWithoutReindexingImages() = runBlocking {
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
            .addMigrations(PhotoDatabase.MIGRATION_1_2).build()
        try {
            val dao = database.dao()
            assertEquals(2, dao.assets().size)
            assertTrue(requireNotNull(dao.indexRecord(video.uri)).videoOcrEnabled)
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
