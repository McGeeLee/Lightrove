package com.mcgeelee.photos.data

import android.content.Context
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class IndexStorageResult(
    val converted: Int = 0,
    val bytesSaved: Long = 0,
    val retry: Boolean = false,
    val vacuumDeferred: Boolean = false,
)

/** Resumable data-format maintenance; the Room schema and completed ML results are unchanged. */
internal class IndexStorageMaintenance(context: Context, private val database: PhotoDatabase) {
    private val preferences = context.applicationContext.getSharedPreferences("index-storage-v1", Context.MODE_PRIVATE)
    // Keep state separate for each database, including isolated instrumentation test databases.
    private val vacuumKey = "pending-vacuum:${database.openHelper.databaseName}"

    suspend fun run(timeBudgetMs: Long = 120_000): IndexStorageResult = maintenanceMutex.withLock { withContext(Dispatchers.IO) {
        require(timeBudgetMs > 0)
        val started = SystemClock.elapsedRealtime()
        val dao = database.dao()
        var converted = 0
        var bytesSaved = 0L
        if (dao.hasLegacyVectors(VectorStorage.magicBytes)) {
            // Persist before any rewrite: a killed process must still reclaim pages after its last batch.
            check(preferences.edit().putBoolean(vacuumKey, true).commit())
            var afterRowId = Long.MIN_VALUE
            while (true) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() - started >= timeBudgetMs) {
                    return@withContext IndexStorageResult(converted, bytesSaved, retry = true)
                }
                val batch = dao.compactVectorBatch(afterRowId)
                converted += batch.count
                bytesSaved += batch.bytesSaved
                if (batch.count == 0) break
                afterRowId = batch.lastRowId
                yield()
            }
        }
        currentCoroutineContext().ensureActive()
        val sqlite = database.openHelper.writableDatabase
        val pending = preferences.getBoolean(vacuumKey, false)
        val pageBytes = sqlite.pragmaLong("page_size")
        val pages = sqlite.pragmaLong("page_count")
        val freePages = sqlite.pragmaLong("freelist_count")
        if (!pending && (freePages * pageBytes < 1024 * 1024 || freePages * 4 < pages)) {
            return@withContext IndexStorageResult(converted, bytesSaved)
        }
        // VACUUM must run outside every Room transaction and with all cursors closed.
        check(!sqlite.inTransaction())
        // Also cover routine page reclamation: cancellation after VACUUM can leave a large WAL.
        check(preferences.edit().putBoolean(vacuumKey, true).commit())
        if (SystemClock.elapsedRealtime() - started >= timeBudgetMs || !sqlite.truncateWal()) {
            return@withContext IndexStorageResult(converted, bytesSaved, retry = true)
        }
        val path = sqlite.path?.let(::File)
        val databaseBytes = sqlite.pragmaLong("page_count") * pageBytes
        // SQLite can need twice the original database size while rebuilding. Leave an extra reserve.
        if (path == null || (sqlite.path != ":memory:" && path.usableSpace < databaseBytes * 2 + 16L * 1024 * 1024)) {
            check(preferences.edit().putBoolean(vacuumKey, true).commit())
            // App startup will schedule another attempt; low disk space must not cause endless retries.
            return@withContext IndexStorageResult(converted, bytesSaved, vacuumDeferred = true)
        }
        currentCoroutineContext().ensureActive()
        sqlite.execSQL("VACUUM")
        currentCoroutineContext().ensureActive()
        if (!sqlite.truncateWal()) return@withContext IndexStorageResult(converted, bytesSaved, retry = true)
        check(preferences.edit().remove(vacuumKey).commit())
        IndexStorageResult(converted, bytesSaved)
    } }

    private fun SupportSQLiteDatabase.pragmaLong(name: String): Long = query("PRAGMA $name").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    /** Reading the result is required: SQLite can return SQLITE_BUSY without throwing. */
    private fun SupportSQLiteDatabase.truncateWal(): Boolean = query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
        cursor.moveToFirst() && cursor.getInt(0) == 0
    }

    companion object {
        // Native VACUUM may outlive coroutine cancellation. Keep a restarted worker behind cleanup.
        private val maintenanceMutex = Mutex()
    }
}
