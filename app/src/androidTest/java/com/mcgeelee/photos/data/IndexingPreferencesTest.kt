package com.mcgeelee.photos.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexingPreferencesTest {
    private lateinit var directory: File
    private val jobs = mutableListOf<Job>()

    @Before fun createDirectory() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "indexing-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After fun closeStores() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        directory.deleteRecursively()
        Unit
    }

    @Test fun defaultIsOffAndBothChoicesSurviveReopening() = runBlocking {
        withTimeout(10_000) {
            val file = File(directory, "settings.preferences_pb")
            val (store, job) = createStore(file)
            val preferences = IndexingPreferences(store)
            assertFalse(preferences.videoOcrEnabled.first())
            preferences.setVideoOcrEnabled(true)
            assertTrue(IndexingPreferences(store).snapshotVideoOcrEnabled())
            job.cancelAndJoin()

            val (reopened, reopenedJob) = createStore(file)
            assertTrue(IndexingPreferences(reopened).snapshotVideoOcrEnabled())
            IndexingPreferences(reopened).setVideoOcrEnabled(false)
            reopenedJob.cancelAndJoin()
            assertFalse(IndexingPreferences(createStore(file).first).snapshotVideoOcrEnabled())
        }
    }

    @Test fun transientReadErrorDoesNotEmitFalseInsteadOfTheSavedChoice() = runBlocking {
        withTimeout(10_000) {
            val (store) = createStore(File(directory, "read.preferences_pb"))
            IndexingPreferences(store).setVideoOcrEnabled(true)
            var reads = 0
            val intermittent = object : DataStore<Preferences> by store {
                override val data: Flow<Preferences> = flow {
                    if (reads++ == 0) throw IOException("Temporary fixture read failure")
                    emitAll(store.data)
                }
            }
            assertTrue(IndexingPreferences(intermittent).videoOcrEnabled.first())
            assertTrue(reads >= 2)
        }
    }

    @Test fun workerSnapshotPropagatesReadFailureImmediately() = runBlocking {
        val failure = IOException("Fixture read failure")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
        }
        withTimeout(1_000) {
            assertSame(failure, runCatching { IndexingPreferences(store).snapshotVideoOcrEnabled() }.exceptionOrNull())
        }
    }

    @Test fun failedWriteIsNotReportedAsSaved() = runBlocking {
        val failure = IOException("Fixture write failure")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
        }
        val preferences = IndexingPreferences(store)
        assertSame(failure, runCatching { preferences.setVideoOcrEnabled(true) }.exceptionOrNull())
        assertFalse(preferences.snapshotVideoOcrEnabled())
    }

    private fun createStore(file: File): Pair<DataStore<Preferences>, Job> {
        val job = SupervisorJob().also(jobs::add)
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }) to job
    }
}
