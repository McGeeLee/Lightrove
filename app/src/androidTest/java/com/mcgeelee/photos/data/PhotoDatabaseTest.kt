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
}
