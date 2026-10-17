package com.mcgeelee.photos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private val Context.indexingDataStore by preferencesDataStore(name = "indexing")

class IndexingPreferences internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.indexingDataStore)

    val videoOcrEnabled: Flow<Boolean> = dataStore.data
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            // A temporary read error is not evidence that the user's saved choice is false.
            delay(1_000L * (attempt.coerceAtMost(29) + 1))
            true
        }
        .map { preferences -> preferences[VIDEO_OCR_ENABLED] ?: false }
        .distinctUntilChanged()

    /** A worker must fail a bad read rather than silently use false or wait indefinitely in foreground. */
    suspend fun snapshotVideoOcrEnabled(): Boolean = dataStore.data.first()[VIDEO_OCR_ENABLED] ?: false

    /** Write failures reach the caller; UI should acknowledge the change only after this returns. */
    suspend fun setVideoOcrEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[VIDEO_OCR_ENABLED] = enabled }
    }

    private companion object {
        val VIDEO_OCR_ENABLED = booleanPreferencesKey("video_ocr_enabled")
    }
}
