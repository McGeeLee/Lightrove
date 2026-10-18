package com.mcgeelee.photos.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

class IndexConverters {
    @TypeConverter fun encodeVector(value: FloatArray?): ByteArray? = VectorStorage.encode(value)
    @TypeConverter fun decodeVector(value: ByteArray?): FloatArray? = VectorStorage.decode(value)

    @TypeConverter fun encodeModality(value: IndexModality): String = value.name
    @TypeConverter fun decodeModality(value: String): IndexModality = IndexModality.valueOf(value)
    @TypeConverter fun encodeState(value: IndexState): String = value.name
    @TypeConverter fun decodeState(value: String): IndexState = IndexState.valueOf(value)
}

/** A consistent view of the library with already completed items excluded from the work queue. */
data class IndexPlan(val total: Int, val ready: Int, val pending: List<MediaAsset>)

/** Previous results belong to the exact source and model claimed by the worker. */
data class IndexClaim(val previous: IndexRecord?, val segments: List<IndexedSegment>)

data class StoredVector(val rowId: Long, val vector: ByteArray)
data class VectorCompactionBatch(val lastRowId: Long, val count: Int, val bytesSaved: Long)

@Dao
abstract class PhotoDao {
    @Query("SELECT * FROM media_assets ORDER BY dateTaken DESC, dateModified DESC")
    abstract fun observeAssets(): Flow<List<MediaAsset>>

    @Query("SELECT * FROM indexed_segments")
    abstract fun observeSegments(): Flow<List<IndexedSegment>>

    @Query("SELECT * FROM index_records")
    abstract fun observeIndexRecords(): Flow<List<IndexRecord>>

    @Query("SELECT * FROM media_assets ORDER BY dateTaken DESC, dateModified DESC")
    abstract suspend fun assets(): List<MediaAsset>

    @Query("SELECT COUNT(*) FROM media_assets")
    protected abstract suspend fun assetCount(): Int

    @Query("""
        SELECT media_assets.* FROM media_assets
        LEFT JOIN index_records ON index_records.assetUri = media_assets.uri
        WHERE index_records.assetUri IS NULL OR index_records.state != 'READY'
            OR index_records.sourceModified != media_assets.dateModified OR index_records.modelId != :modelId
            OR (media_assets.isVideo = 1 AND index_records.videoOcrEnabled != :videoOcrEnabled)
        ORDER BY media_assets.dateTaken DESC, media_assets.dateModified DESC
    """)
    protected abstract suspend fun pendingAssets(modelId: String, videoOcrEnabled: Boolean): List<MediaAsset>

    @Query("SELECT * FROM media_assets WHERE uri = :uri")
    abstract suspend fun asset(uri: String): MediaAsset?

    @Query("SELECT * FROM indexed_segments")
    abstract suspend fun segments(): List<IndexedSegment>

    @Query("SELECT * FROM indexed_segments WHERE assetUri = :uri")
    abstract suspend fun segments(uri: String): List<IndexedSegment>

    @Query("SELECT * FROM index_records WHERE assetUri = :uri")
    abstract suspend fun indexRecord(uri: String): IndexRecord?

    @Query("SELECT * FROM index_records")
    abstract suspend fun records(): List<IndexRecord>

    suspend fun allSegments(): List<IndexedSegment> = segments()
    suspend fun record(uri: String): IndexRecord? = indexRecord(uri)

    @Upsert abstract suspend fun upsertAssets(assets: List<MediaAsset>)
    @Upsert abstract suspend fun upsertSegments(segments: List<IndexedSegment>)
    @Upsert abstract suspend fun upsertIndexRecord(record: IndexRecord)

    @Query("UPDATE media_assets SET ocrText = :text WHERE uri = :uri AND ocrText != :text")
    abstract suspend fun setOcrText(uri: String, text: String)

    @Query("DELETE FROM media_assets WHERE uri IN (:uris)")
    abstract suspend fun deleteAssets(uris: List<String>)

    @Query("DELETE FROM indexed_segments WHERE assetUri = :uri")
    abstract suspend fun deleteSegments(uri: String)

    @Query("DELETE FROM indexed_segments WHERE assetUri = :uri AND modelId != :modelId")
    protected abstract suspend fun deleteOtherModelSegments(uri: String, modelId: String)

    @Query("DELETE FROM indexed_segments WHERE assetUri = :uri AND modality = :modality AND timestampMs = :timestampMs")
    protected abstract suspend fun deleteSegment(uri: String, modality: IndexModality, timestampMs: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM indexed_segments WHERE vector IS NOT NULL AND substr(vector, 1, 4) != :magic)")
    abstract suspend fun hasLegacyVectors(magic: ByteArray): Boolean

    @Query("""
        SELECT rowid AS rowId, vector FROM indexed_segments
        WHERE rowid > :afterRowId AND vector IS NOT NULL AND substr(vector, 1, 4) != :magic
        ORDER BY rowid LIMIT :limit
    """)
    protected abstract suspend fun legacyVectors(afterRowId: Long, magic: ByteArray, limit: Int): List<StoredVector>

    @Query("UPDATE indexed_segments SET vector = :replacement WHERE rowid = :rowId AND vector = :previous")
    protected abstract suspend fun replaceStoredVector(rowId: Long, previous: ByteArray, replacement: ByteArray?): Int

    /** Keyset pagination bounds each transaction and avoids rescanning already converted rows. */
    @Transaction
    open suspend fun compactVectorBatch(afterRowId: Long, limit: Int = 64): VectorCompactionBatch {
        require(limit in 1..256)
        val rows = legacyVectors(afterRowId, VectorStorage.magicBytes, limit)
        var saved = 0L
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val packed = VectorStorage.encode(VectorStorage.decode(row.vector))
            if (replaceStoredVector(row.rowId, row.vector, packed) > 0) saved += row.vector.size - (packed?.size ?: 0)
        }
        return VectorCompactionBatch(rows.lastOrNull()?.rowId ?: afterRowId, rows.size, saved)
    }

    @Query("DELETE FROM index_records WHERE assetUri = :uri")
    abstract suspend fun deleteIndexRecord(uri: String)

    @Query("DELETE FROM media_assets")
    abstract suspend fun clearLibrary()

    @Transaction
    open suspend fun indexingPlan(modelId: String, videoOcrEnabled: Boolean = true): IndexPlan {
        val total = assetCount()
        val pending = pendingAssets(modelId, videoOcrEnabled)
        return IndexPlan(total, total - pending.size, pending)
    }

    /** Revalidate a queued source before touching its progress or reusing earlier results. */
    @Transaction
    open suspend fun claimIndex(source: MediaAsset, modelId: String, videoOcrEnabled: Boolean = true): IndexClaim? {
        val current = asset(source.uri) ?: return null
        if (!current.sameSourceAs(source)) return null
        val previous = indexRecord(source.uri)
        val sameSource = previous?.sourceModified == source.dateModified
        val reusable = sameSource && previous?.modelId == modelId
        if (reusable && previous?.state == IndexState.READY &&
            (!source.isVideo || previous.videoOcrEnabled == videoOcrEnabled)) return null
        val retaggedText = mutableListOf<IndexedSegment>()
        val retained = if (sameSource) segments(source.uri).mapNotNull { segment ->
            when {
                reusable && segment.modelId == modelId -> segment
                segment.modality == IndexModality.TEXT && (segment.text.isNotBlank() || segment.ocrScripts != 0) -> {
                    // OCR is still useful while switching models; its old semantic vector is not.
                    segment.copy(vector = null, modelId = modelId).also(retaggedText::add)
                }
                else -> null
            }
        } else emptyList()
        // Commit these with the claim so even cancellation before the first checkpoint keeps OCR.
        upsertSegments(retaggedText)
        upsertIndexRecord(IndexRecord(source.uri, IndexState.PROCESSING, source.dateModified, modelId = modelId,
            videoOcrEnabled = videoOcrEnabled,
            audioComplete = reusable && previous?.audioComplete == true,
            audioResumeMs = if (reusable) previous?.audioResumeMs ?: 0 else 0))
        return IndexClaim(previous, retained)
    }

    @Transaction
    open suspend fun replaceSegments(uri: String, segments: List<IndexedSegment>) {
        require(segments.all { it.assetUri == uri })
        deleteSegments(uri)
        upsertSegments(segments)
    }

    /**
     * Call after every successful fresh MediaStore scan, including limited-access scans.
     * Removed/revoked URIs cascade to their OCR, vector and progress records. Avoid SQL's
     * variable limit by deleting in batches, rather than a giant NOT IN statement.
     */
    @Transaction
    open suspend fun reconcileSnapshot(snapshot: MediaScanResult) {
        if (!snapshot.complete) return
        val previous = assets().associateBy(MediaAsset::uri)
        val currentUris = snapshot.assets.mapTo(HashSet(), MediaAsset::uri)
        (previous.keys - currentUris).chunked(400).forEach { deleteAssets(it) }
        val changed = snapshot.assets.mapNotNull { fresh ->
            val old = previous[fresh.uri]
            val merged = when {
                old == null -> fresh
                old.sameSourceAs(fresh) -> fresh.copy(ocrText = old.ocrText)
                else -> {
                    deleteSegments(fresh.uri)
                    deleteIndexRecord(fresh.uri)
                    fresh
                }
            }
            merged.takeIf { it != old }
        }
        changed.chunked(400).forEach { upsertAssets(it) }
    }

    /** Save resumable results without claiming that every modality has completed. */
    @Transaction
    open suspend fun checkpointIndex(
        source: MediaAsset,
        segments: List<IndexedSegment>,
        modelId: String,
        state: IndexState = IndexState.PROCESSING,
        changedSegments: List<IndexedSegment> = segments,
        removedKeys: List<Pair<IndexModality, Long>> = emptyList(),
        videoOcrEnabled: Boolean = true,
        audioComplete: Boolean = false,
        audioResumeMs: Long = 0,
        error: String? = null,
    ): Boolean {
        require(state == IndexState.PROCESSING || state == IndexState.PENDING || state == IndexState.FAILED)
        require(audioResumeMs >= 0)
        val current = asset(source.uri) ?: return false
        if (!current.sameSourceAs(source)) return false
        val record = indexRecord(source.uri) ?: return false
        if (record.state != IndexState.PROCESSING || record.sourceModified != source.dateModified || record.modelId != modelId) return false
        if (source.isVideo && record.videoOcrEnabled != videoOcrEnabled) return false
        require(segments.all { it.assetUri == source.uri && it.modelId == modelId })
        require(changedSegments.all { it.assetUri == source.uri && it.modelId == modelId })
        val keys = segments.mapTo(HashSet()) { it.modality to it.timestampMs }
        require(changedSegments.all { (it.modality to it.timestampMs) in keys } && removedKeys.none { it in keys })
        deleteOtherModelSegments(source.uri, modelId)
        removedKeys.forEach { (modality, time) -> deleteSegment(source.uri, modality, time) }
        upsertSegments(changedSegments)
        setOcrText(source.uri, segments.ocrText())
        upsertIndexRecord(record.copy(state = state, updatedAt = System.currentTimeMillis(), error = error,
            audioComplete = audioComplete, audioResumeMs = audioResumeMs))
        return true
    }

    /** Commits an item's result atomically, and rejects work for a deleted/revoked item. */
    @Transaction
    open suspend fun completeIndex(
        source: MediaAsset,
        segments: List<IndexedSegment>,
        modelId: String = "",
        videoOcrEnabled: Boolean = true,
        audioComplete: Boolean = false,
        audioResumeMs: Long = 0,
    ): Boolean {
        val current = asset(source.uri) ?: return false
        if (!current.sameSourceAs(source)) return false
        val record = indexRecord(source.uri)
        if (record != null &&
            (record.modelId != modelId || record.sourceModified != source.dateModified)) return false
        if (source.isVideo && record?.let { it.videoOcrEnabled != videoOcrEnabled } == true) return false
        require(audioResumeMs >= 0)
        require(segments.all { it.assetUri == source.uri })
        deleteSegments(source.uri)
        upsertSegments(segments)
        setOcrText(source.uri, segments.ocrText())
        upsertIndexRecord(IndexRecord(
            assetUri = source.uri,
            state = IndexState.READY,
            sourceModified = source.dateModified,
            modelId = modelId,
            videoOcrEnabled = videoOcrEnabled,
            audioComplete = audioComplete,
            audioResumeMs = audioResumeMs,
        ))
        return true
    }
}

internal fun MediaAsset.sameSourceAs(other: MediaAsset): Boolean =
    uri == other.uri && isVideo == other.isVideo &&
        dateModified == other.dateModified && durationMs == other.durationMs &&
        width == other.width && height == other.height && mimeType == other.mimeType &&
        name == other.name && sizeBytes == other.sizeBytes

private fun List<IndexedSegment>.ocrText(): String = asSequence()
    .filter { it.modality == IndexModality.TEXT }.map { it.text }
    .filter(String::isNotBlank).distinct().joinToString("\n")

@Database(
    entities = [MediaAsset::class, IndexedSegment::class, IndexRecord::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(IndexConverters::class)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun dao(): PhotoDao

    companion object {
        @Volatile private var instance: PhotoDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE index_records ADD COLUMN videoOcrEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE indexed_segments ADD COLUMN ocrScripts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE index_records ADD COLUMN audioComplete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE index_records ADD COLUMN audioResumeMs INTEGER NOT NULL DEFAULT 0")
                // Only READY proves the old pipeline finished. Interrupted/failed rows remain resumable.
                db.execSQL("""UPDATE indexed_segments SET ocrScripts = 31 WHERE modality = 'TEXT'
                    AND assetUri IN (SELECT assetUri FROM index_records WHERE state = 'READY')""")
                db.execSQL("""UPDATE index_records SET audioComplete = 1 WHERE state = 'READY'
                    AND modelId != 'mlkit-five-scripts-v2' AND assetUri IN
                    (SELECT uri FROM media_assets WHERE isVideo = 1)""")
            }
        }

        fun create(context: Context): PhotoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PhotoDatabase::class.java,
                "photos-index.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
