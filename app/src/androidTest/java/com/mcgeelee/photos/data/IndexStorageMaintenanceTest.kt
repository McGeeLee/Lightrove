package com.mcgeelee.photos.data

import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcgeelee.photos.search.HybridSearch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexStorageMaintenanceTest {
    @Test fun oldVectorsCompactInResumableBatchesAndReturnDatabasePagesToDisk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage-maintenance-${System.nanoTime()}.db"
        fun openDatabase() = Room.databaseBuilder(context, PhotoDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        var database = openDatabase()
        try {
            val sources = (1..8).map { index ->
                MediaAsset("content://test/$index", index.toLong(), "video-$index", "video/mp4", 1, 1, 400_000, 100, 100, "", true)
            }
            val query = FloatArray(768) { (1 / sqrt(768.0)).toFloat() }
            database.dao().reconcileSnapshot(MediaScanResult(sources, MediaAccess.FULL))
            database.withTransaction {
                sources.forEachIndexed { index, source ->
                    val similarity = 0.94 - index * 0.1
                    val vector = FloatArray(768) { dimension ->
                        (query[dimension] * (similarity + (if (dimension % 2 == 0) 1 else -1) * sqrt(1 - similarity * similarity))).toFloat()
                    }
                    val legacy = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach(::putFloat) }.array()
                    repeat(80) { frame ->
                        database.openHelper.writableDatabase.execSQL(
                            "INSERT INTO indexed_segments (assetUri, modality, timestampMs, text, vector, modelId) VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf(source.uri, "VIDEO", frame * 5000L, "", legacy, "current"),
                        )
                    }
                    database.dao().upsertIndexRecord(IndexRecord(source.uri, IndexState.READY, source.dateModified, modelId = "current"))
                }
            }
            checkpoint(database)
            val originalSize = storageBytes(context.getDatabasePath(name))
            val records = database.dao().records()
            val before = HybridSearch.search("", query, sources, database.dao().segments(), modelId = "current")

            val firstBatch = database.dao().compactVectorBatch(Long.MIN_VALUE)
            assertEquals(64, firstBatch.count)
            assertEquals(64L * (3072 - 780), firstBatch.bytesSaved)
            assertTrue(database.dao().hasLegacyVectors(VectorStorage.magicBytes))
            // A restarted app can continue mixed-format data without rebuilding any media.
            database.close()
            database = openDatabase()
            val result = IndexStorageMaintenance(context, database).run()

            assertFalse(result.retry)
            assertFalse(result.vacuumDeferred)
            assertEquals(576, result.converted)
            assertFalse(database.dao().hasLegacyVectors(VectorStorage.magicBytes))
            assertEquals(records.associateBy { it.assetUri }, database.dao().records().associateBy { it.assetUri })
            val segments = database.dao().segments()
            assertEquals(640, segments.size)
            val after = HybridSearch.search("", query, sources, segments, modelId = "current")
            assertEquals(before.map { it.asset.uri }, after.map { it.asset.uri })
            before.zip(after).forEach { (old, packed) -> assertEquals(old.score, packed.score, 0.003f) }
            val compactedSize = storageBytes(context.getDatabasePath(name))
            assertTrue("Expected actual DB + WAL shrink: $originalSize -> $compactedSize", compactedSize < originalSize / 2)
            Log.i("IndexStorageTest", "DB+WAL bytes: $originalSize -> $compactedSize; vectors=640")
            database.openHelper.readableDatabase.query("SELECT SUM(length(vector)) FROM indexed_segments").use {
                assertTrue(it.moveToFirst())
                assertEquals(640L * 780, it.getLong(0))
            }
            assertEquals(0, IndexStorageMaintenance(context, database).run().converted)
        } finally {
            database.close()
            context.deleteDatabase(name)
            context.getSharedPreferences("index-storage-v1", 0).edit().remove("pending-vacuum:$name").commit()
        }
    }

    private fun checkpoint(database: PhotoDatabase) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    private fun storageBytes(file: File): Long = file.length() + File(file.path + "-wal").length()
}
