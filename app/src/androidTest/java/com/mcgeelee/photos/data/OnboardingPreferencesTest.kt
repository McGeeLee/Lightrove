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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingPreferencesTest {
    private lateinit var directory: File
    private val storeJobs = mutableListOf<Job>()

    @Before fun createDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "onboarding-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After fun closeStoresAndDeleteFixture() = runBlocking {
        storeJobs.forEach { it.cancelAndJoin() }
        directory.deleteRecursively()
        Unit
    }

    @Test fun completionIsObservedByAnotherInstanceAndSurvivesReopeningTheStore() = runBlocking {
        withTimeout(10_000) {
            val file = File(directory, "state.preferences_pb")
            val (store, job) = createStore(file)
            val firstInstance = OnboardingPreferences(store)
            val secondInstance = OnboardingPreferences(store)
            assertFalse(firstInstance.completed.first())
            assertFalse(secondInstance.completed.first())

            val observedCompletion = async(start = CoroutineStart.UNDISPATCHED) {
                secondInstance.completed.first { it }
            }
            firstInstance.complete()
            assertTrue(observedCompletion.await())
            assertTrue(file.exists() && file.length() > 0)

            // DataStore permits only one active store for a file; terminate it before
            // opening a fresh instance, which must load completion from disk.
            job.cancelAndJoin()
            val (reopenedStore) = createStore(file)
            assertTrue(OnboardingPreferences(reopenedStore).completed.first())
        }
    }

    @Test fun temporaryReadFailureDefaultsToIncompleteAndTheCollectorRecovers() = runBlocking {
        withTimeout(10_000) {
            val (store) = createStore(File(directory, "recovery.preferences_pb"))
            var reads = 0
            val temporaryFailure = object : DataStore<Preferences> by store {
                override val data: Flow<Preferences> = flow {
                    if (reads++ == 0) throw IOException("Transient fixture read failure")
                    emitAll(store.data)
                }
            }
            val preferences = OnboardingPreferences(temporaryFailure)
            val initialRead = CompletableDeferred<Unit>()
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                preferences.completed.onEach { if (!it) initialRead.complete(Unit) }.take(2).toList()
            }
            initialRead.await()
            preferences.complete()
            assertEquals(listOf(false, true), observed.await())
        }
    }

    @Test fun failedWriteDoesNotReportCompletion() = runBlocking {
        val failure = IOException("Fixture storage is unavailable")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw failure
            }
        }
        val preferences = OnboardingPreferences(store)
        assertSame(failure, runCatching { preferences.complete() }.exceptionOrNull())
        assertFalse(preferences.completed.first())
    }

    private fun createStore(file: File): Pair<DataStore<Preferences>, Job> {
        val job = SupervisorJob().also(storeJobs::add)
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return store to job
    }
}
