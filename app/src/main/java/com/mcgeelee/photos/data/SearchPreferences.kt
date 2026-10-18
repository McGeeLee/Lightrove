package com.mcgeelee.photos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mcgeelee.photos.search.HybridSearch
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private val Context.searchDataStore by preferencesDataStore(name = "search")

class SearchPreferences internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.searchDataStore)

    val threshold: Flow<Float> = dataStore.data
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            delay(1_000L * (attempt.coerceAtMost(29) + 1))
            true
        }
        .map { preferences -> HybridSearch.normalizeThreshold(preferences[THRESHOLD] ?: HybridSearch.DEFAULT_THRESHOLD) }
        .distinctUntilChanged()

    suspend fun setThreshold(value: Float) {
        dataStore.edit { preferences -> preferences[THRESHOLD] = HybridSearch.normalizeThreshold(value) }
    }

    private companion object {
        val THRESHOLD = floatPreferencesKey("semantic_threshold")
    }
}
