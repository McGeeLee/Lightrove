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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow

class IndexConverters {
    @TypeConverter fun encodeVector(value: FloatArray?): ByteArray? = value?.let {
        ByteBuffer.allocate(it.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            it.forEach(::putFloat)
        }.array()
    }

    @TypeConverter fun decodeVector(value: ByteArray?): FloatArray? {
        if (value == null || value.isEmpty() || value.size % Float.SIZE_BYTES != 0) return null
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(value.size / Float.SIZE_BYTES) { buffer.float }
    }

    @TypeConverter fun encodeModality(value: IndexModality): String = value.name
    @TypeConverter fun decodeModality(value: String): IndexModality = IndexModality.valueOf(value)
    @TypeConverter fun encodeState(value: IndexState): String = value.name
    @TypeConverter fun decodeState(value: String): IndexState = IndexState.valueOf(value)
}

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

    @Query("UPDATE media_assets SET ocrText = :text WHERE uri = :uri")
    abstract suspend fun setOcrText(uri: String, text: String)

    @Query("DELETE FROM media_assets WHERE uri IN (:uris)")
    abstract suspend fun deleteAssets(uris: List<String>)

    @Query("DELETE FROM indexed_segments WHERE assetUri = :uri")
    abstract suspend fun deleteSegments(uri: String)

    @Query("DELETE FROM index_records WHERE assetUri = :uri")
    abstract suspend fun deleteIndexRecord(uri: String)

    @Query("DELETE FROM media_assets")
    abstract suspend fun clearLibrary()

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
        val merged = snapshot.assets.map { fresh ->
            val old = previous[fresh.uri]
            if (old != null && old.sameSourceAs(fresh)) {
                fresh.copy(ocrText = old.ocrText)
            } else {
                deleteSegments(fresh.uri)
                deleteIndexRecord(fresh.uri)
                fresh
            }
        }
        merged.chunked(400).forEach { upsertAssets(it) }
    }

    /** Commits an item's result atomically, and rejects work for a deleted/revoked item. */
    @Transaction
    open suspend fun completeIndex(
        source: MediaAsset,
        segments: List<IndexedSegment>,
        modelId: String = "",
    ): Boolean {
        val current = asset(source.uri) ?: return false
        if (!current.sameSourceAs(source)) return false
        require(segments.all { it.assetUri == source.uri })
        deleteSegments(source.uri)
        upsertSegments(segments)
        setOcrText(source.uri, segments.filter { it.modality == IndexModality.TEXT }
            .map { it.text }.filter(String::isNotBlank).distinct().joinToString("\n"))
        upsertIndexRecord(IndexRecord(
            assetUri = source.uri,
            state = IndexState.READY,
            sourceModified = source.dateModified,
            modelId = modelId,
        ))
        return true
    }
}

private fun MediaAsset.sameSourceAs(other: MediaAsset): Boolean =
    dateModified == other.dateModified && durationMs == other.durationMs &&
        width == other.width && height == other.height && mimeType == other.mimeType &&
        name == other.name && sizeBytes == other.sizeBytes

@Database(
    entities = [MediaAsset::class, IndexedSegment::class, IndexRecord::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(IndexConverters::class)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun dao(): PhotoDao

    companion object {
        @Volatile private var instance: PhotoDatabase? = null

        fun create(context: Context): PhotoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PhotoDatabase::class.java,
                "photos-index.db",
            ).build().also { instance = it }
        }
    }
}
