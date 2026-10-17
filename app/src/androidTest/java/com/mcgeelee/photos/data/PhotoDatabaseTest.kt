package com.mcgeelee.photos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoDatabaseTest {
    private lateinit var database: PhotoDatabase
    private val asset = MediaAsset("content://media/external/images/media/1", 1, "receipt.jpg", "image/jpeg", 10, 10_000, 0, 100, 100, "Camera", false)

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, PhotoDatabase::class.java,
        ).build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun permissionRevocationCascadesToPrivateIndex() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        assertTrue(dao.completeIndex(asset, listOf(IndexedSegment(asset.uri, IndexModality.TEXT, text = "receipt"))))
        dao.reconcileSnapshot(MediaScanResult(emptyList(), MediaAccess.NONE))
        assertTrue(dao.assets().isEmpty())
        assertTrue(dao.segments().isEmpty())
        assertTrue(dao.records().isEmpty())
    }

    @Test fun partialProviderFailureDoesNotEraseCachedWork() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.completeIndex(asset, listOf(IndexedSegment(asset.uri, IndexModality.TEXT, text = "receipt")))
        dao.reconcileSnapshot(MediaScanResult(emptyList(), MediaAccess.FULL, complete = false))
        assertEquals("receipt", dao.asset(asset.uri)?.ocrText)
        assertEquals(IndexState.READY, dao.indexRecord(asset.uri)?.state)
    }

    @Test fun editedSourceInvalidatesVectorsAndRejectsStaleWorkerResult() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.completeIndex(asset, listOf(IndexedSegment(asset.uri, IndexModality.IMAGE, vector = floatArrayOf(1f))))
        dao.reconcileSnapshot(MediaScanResult(listOf(asset.copy(dateModified = 20)), MediaAccess.FULL))
        assertTrue(dao.segments().isEmpty())
        assertNull(dao.indexRecord(asset.uri))
        assertFalse(dao.completeIndex(asset, emptyList()))
    }

    @Test fun unchangedSnapshotAndOcrDoNotRewriteAssets() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.completeIndex(asset, listOf(IndexedSegment(asset.uri, IndexModality.TEXT, text = "receipt")))
        val record = dao.indexRecord(asset.uri)
        trackAssetWrites()

        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.setOcrText(asset.uri, "receipt")

        assertEquals(0, assetWriteCount())
        assertEquals("receipt", dao.asset(asset.uri)?.ocrText)
        assertEquals(record, dao.indexRecord(asset.uri))

        // Album/date metadata can change without invalidating OCR or semantic results.
        dao.reconcileSnapshot(MediaScanResult(listOf(asset.copy(bucketName = "Receipts")), MediaAccess.FULL))
        assertEquals(1, assetWriteCount())
        assertEquals("Receipts", dao.asset(asset.uri)?.bucketName)
        assertEquals("receipt", dao.asset(asset.uri)?.ocrText)
        assertEquals(record, dao.indexRecord(asset.uri))
    }

    @Test fun indexingPlanIncludesEveryUnfinishedOrOutdatedSource() = runBlocking {
        val dao = database.dao()
        val sources = (0..6).map { index ->
            asset.copy(uri = "content://media/external/images/media/${index + 1}",
                id = index + 1L, dateTaken = index * 1000L)
        }
        dao.reconcileSnapshot(MediaScanResult(sources, MediaAccess.FULL))
        listOf(IndexState.PENDING, IndexState.PROCESSING, IndexState.FAILED, IndexState.READY).forEachIndexed { index, state ->
            dao.upsertIndexRecord(IndexRecord(sources[index + 1].uri, state, asset.dateModified, modelId = "current"))
        }
        dao.upsertIndexRecord(IndexRecord(sources[5].uri, IndexState.READY, asset.dateModified - 1, modelId = "current"))
        dao.upsertIndexRecord(IndexRecord(sources[6].uri, IndexState.READY, asset.dateModified, modelId = "old"))

        val plan = dao.indexingPlan("current")

        assertEquals(7, plan.total)
        assertEquals(1, plan.ready)
        assertEquals(sources.asReversed().filter { it != sources[4] }, plan.pending)
    }

    @Test fun claimRejectsChangedRevokedAndAlreadyCompletedSources() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.completeIndex(asset, emptyList(), "current")
        val completed = dao.indexRecord(asset.uri)
        assertNull(dao.claimIndex(asset, "current"))
        assertEquals(completed, dao.indexRecord(asset.uri))

        // A provider may change size without advancing its modification timestamp.
        dao.reconcileSnapshot(MediaScanResult(listOf(asset.copy(sizeBytes = 1024)), MediaAccess.FULL))
        assertNull(dao.claimIndex(asset, "current"))
        assertNull(dao.indexRecord(asset.uri))

        dao.reconcileSnapshot(MediaScanResult(emptyList(), MediaAccess.NONE))
        assertNull(dao.claimIndex(asset, "current"))
    }

    @Test fun interruptedCheckpointRemainsPendingAndCanBeResumed() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        val partial = listOf(IndexedSegment(asset.uri, IndexModality.TEXT, text = "receipt", modelId = "current"))
        assertTrue(dao.claimIndex(asset, "current") != null)
        assertTrue(dao.checkpointIndex(asset, partial, "current"))
        assertEquals(IndexState.PROCESSING, dao.indexRecord(asset.uri)?.state)

        trackAssetWrites()
        assertTrue(dao.checkpointIndex(asset, partial, "current", IndexState.PENDING))
        assertEquals(0, assetWriteCount())
        assertEquals(IndexState.PENDING, dao.indexRecord(asset.uri)?.state)
        assertEquals("receipt", dao.asset(asset.uri)?.ocrText)
        assertEquals(0, dao.indexingPlan("current").ready)

        val resumed = requireNotNull(dao.claimIndex(asset, "current"))
        assertEquals(IndexState.PENDING, resumed.previous?.state)
        assertEquals(partial, resumed.segments)
        assertEquals(IndexState.PROCESSING, dao.indexRecord(asset.uri)?.state)
    }

    @Test fun checkpointRejectsChangedAndRevokedSources() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.claimIndex(asset, "current")
        val changed = asset.copy(sizeBytes = 1024)
        dao.reconcileSnapshot(MediaScanResult(listOf(changed), MediaAccess.FULL))
        assertFalse(dao.checkpointIndex(asset, emptyList(), "current", IndexState.PENDING))
        assertNull(dao.indexRecord(asset.uri))
        assertTrue(dao.segments(asset.uri).isEmpty())

        dao.claimIndex(changed, "current")
        dao.reconcileSnapshot(MediaScanResult(emptyList(), MediaAccess.NONE))
        assertFalse(dao.checkpointIndex(changed, emptyList(), "current"))
        assertTrue(dao.records().isEmpty())
    }

    @Test fun checkpointCannotOverwriteACompletedOrDifferentModelClaim() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.claimIndex(asset, "current")
        assertFalse(dao.checkpointIndex(asset, emptyList(), "old"))
        dao.completeIndex(asset, emptyList(), "current")
        assertFalse(dao.checkpointIndex(asset, emptyList(), "current", IndexState.PENDING))
        assertEquals(IndexState.READY, dao.indexRecord(asset.uri)?.state)
    }

    @Test fun modelChangeAndImmediateCancellationPreserveTextButNeverOldVectors() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.completeIndex(asset, listOf(
            IndexedSegment(asset.uri, IndexModality.TEXT, text = "old", vector = floatArrayOf(1f), modelId = "old"),
            IndexedSegment(asset.uri, IndexModality.IMAGE, vector = floatArrayOf(1f), modelId = "old"),
        ), "old")
        val retained = listOf(IndexedSegment(asset.uri, IndexModality.TEXT, text = "old", modelId = "current"))
        assertEquals(retained, requireNotNull(dao.claimIndex(asset, "current")).segments)
        // Simulate process death after PROCESSING was saved, before the first new checkpoint.
        val resumed = requireNotNull(dao.claimIndex(asset, "current"))
        assertEquals("current", resumed.previous?.modelId)
        assertEquals(retained, resumed.segments)
        assertTrue(dao.checkpointIndex(asset, resumed.segments, "current", IndexState.PENDING, changedSegments = emptyList()))
        assertEquals("old", dao.asset(asset.uri)?.ocrText)
        assertEquals(retained, dao.segments(asset.uri))
        assertEquals(IndexState.PENDING, dao.indexRecord(asset.uri)?.state)
    }

    @Test fun checkpointWritesOnlyChangedSegmentsAndRemovesExplicitKeys() = runBlocking {
        val dao = database.dao()
        dao.reconcileSnapshot(MediaScanResult(listOf(asset), MediaAccess.FULL))
        dao.claimIndex(asset, "current")
        val image = IndexedSegment(asset.uri, IndexModality.IMAGE, modelId = "current")
        val oldText = IndexedSegment(asset.uri, IndexModality.TEXT, text = "old", modelId = "current")
        assertTrue(dao.checkpointIndex(asset, listOf(image, oldText), "current"))
        database.openHelper.writableDatabase.execSQL("CREATE TABLE segment_write_log (uri TEXT NOT NULL)")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER track_segment_updates AFTER UPDATE ON indexed_segments
            BEGIN INSERT INTO segment_write_log (uri) VALUES (NEW.assetUri); END
        """.trimIndent())

        val newText = oldText.copy(text = "new")
        assertTrue(dao.checkpointIndex(asset, listOf(image, newText), "current", changedSegments = listOf(newText)))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM segment_write_log").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        assertTrue(dao.checkpointIndex(asset, listOf(image), "current", changedSegments = emptyList(),
            removedKeys = listOf(IndexModality.TEXT to -1L)))
        assertEquals(listOf(image), dao.segments(asset.uri))
        assertEquals("", dao.asset(asset.uri)?.ocrText)
    }

    private fun trackAssetWrites() {
        database.openHelper.writableDatabase.execSQL("CREATE TABLE asset_write_log (uri TEXT NOT NULL)")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER track_asset_updates AFTER UPDATE ON media_assets
            BEGIN INSERT INTO asset_write_log (uri) VALUES (NEW.uri); END
        """.trimIndent())
    }

    private fun assetWriteCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM asset_write_log").use {
        check(it.moveToFirst())
        it.getInt(0)
    }
}
