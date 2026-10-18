package com.mcgeelee.photos.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcgeelee.photos.search.HybridSearch
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchPreferencesTest {
    private lateinit var directory: File
    private val jobs = mutableListOf<Job>()

    @Before fun createDirectory() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "search-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After fun closeStores() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        directory.deleteRecursively()
        Unit
    }

    @Test fun defaultIsStricterAndSavedThresholdSurvivesReopening() = runBlocking {
        withTimeout(10_000) {
            val file = File(directory, "settings.preferences_pb")
            val (store, job) = createStore(file)
            val preferences = SearchPreferences(store)
            assertEquals(.45f, preferences.threshold.first(), 0f)
            preferences.setThreshold(.75f)
            job.cancelAndJoin()

            val reopened = SearchPreferences(createStore(file).first)
            assertEquals(.75f, reopened.threshold.first(), 0f)
        }
    }

    @Test fun invalidSavedValuesAreSanitizedBeforeSearch() = runBlocking {
        val (store) = createStore(File(directory, "invalid.preferences_pb"))
        val preferences = SearchPreferences(store)
        for ((raw, expected) in listOf(Float.NaN to HybridSearch.DEFAULT_THRESHOLD,
            Float.POSITIVE_INFINITY to HybridSearch.DEFAULT_THRESHOLD, -1f to 0f, 2f to 1f)) {
            store.edit { it[floatPreferencesKey("semantic_threshold")] = raw }
            assertEquals(expected, preferences.threshold.first(), 0f)
        }
    }

    @Test fun writesAreClampedToTheSliderRange() = runBlocking {
        val (store) = createStore(File(directory, "range.preferences_pb"))
        val preferences = SearchPreferences(store)
        preferences.setThreshold(2f)
        assertEquals(1f, preferences.threshold.first(), 0f)
        preferences.setThreshold(-1f)
        assertEquals(0f, preferences.threshold.first(), 0f)
    }

    @Test fun temporaryReadFailureDoesNotReplaceASavedThreshold() = runBlocking {
        withTimeout(10_000) {
            val (store) = createStore(File(directory, "read.preferences_pb"))
            SearchPreferences(store).setThreshold(.8f)
            var reads = 0
            val intermittent = object : DataStore<Preferences> by store {
                override val data: Flow<Preferences> = flow {
                    if (reads++ == 0) throw IOException("Temporary fixture read failure")
                    emitAll(store.data)
                }
            }
            assertEquals(.8f, SearchPreferences(intermittent).threshold.first(), 0f)
            assertTrue(reads >= 2)
        }
    }

    @Test fun failedWriteDoesNotReportANewThreshold() = runBlocking {
        val failure = IOException("Fixture write failure")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
        }
        val preferences = SearchPreferences(store)
        assertSame(failure, runCatching { preferences.setThreshold(.8f) }.exceptionOrNull())
        assertEquals(HybridSearch.DEFAULT_THRESHOLD, preferences.threshold.first(), 0f)
    }

    private fun createStore(file: File): Pair<DataStore<Preferences>, Job> {
        val job = SupervisorJob().also(jobs::add)
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }) to job
    }
}
