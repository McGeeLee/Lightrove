package com.mcgeelee.photos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

// One application-scoped instance for this file, including across Activity recreation.
private val Context.onboardingDataStore by preferencesDataStore(
    name = "onboarding",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class OnboardingPreferences internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.onboardingDataStore)

    val completed: Flow<Boolean> = dataStore.data
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            // A failed read must never skip onboarding. Keep observing after transient I/O
            // errors so a later successful write can still dismiss it without restarting.
            emit(emptyPreferences())
            delay(1_000L * (attempt.coerceAtMost(29) + 1))
            true
        }
        .map { preferences -> preferences[COMPLETED] ?: false }
        .distinctUntilChanged()

    /** Returns only after completion has been persisted; write errors reach the caller. */
    suspend fun complete() {
        dataStore.edit { preferences -> preferences[COMPLETED] = true }
    }

    private companion object {
        val COMPLETED = booleanPreferencesKey("completed")
    }
}
