package com.mcgeelee.photos.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A content URI, rather than a filesystem path, is the stable identity of a library item. */
@Entity(tableName = "media_assets")
data class MediaAsset(
    @PrimaryKey val uri: String,
    val id: Long,
    val name: String,
    val mimeType: String,
    val dateModified: Long,
    val dateTaken: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bucketName: String,
    val isVideo: Boolean,
    val ocrText: String = "",
    val sizeBytes: Long = 0,
)

enum class IndexModality { TEXT, IMAGE, VIDEO, AUDIO }
enum class IndexState { PENDING, PROCESSING, READY, FAILED }

/** -1 denotes a still image/document; all video/audio offsets are milliseconds. */
@Entity(
    tableName = "indexed_segments",
    primaryKeys = ["assetUri", "modality", "timestampMs"],
    foreignKeys = [ForeignKey(
        entity = MediaAsset::class,
        parentColumns = ["uri"],
        childColumns = ["assetUri"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("assetUri")],
)
data class IndexedSegment(
    val assetUri: String,
    val modality: IndexModality,
    val timestampMs: Long = -1,
    val text: String = "",
    val vector: FloatArray? = null,
    val modelId: String = "",
    /** Completed OCR scripts, including successful reads that found no text. */
    @ColumnInfo(defaultValue = "0") val ocrScripts: Int = 0,
)

@Entity(
    tableName = "index_records",
    foreignKeys = [ForeignKey(
        entity = MediaAsset::class,
        parentColumns = ["uri"],
        childColumns = ["assetUri"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class IndexRecord(
    @PrimaryKey val assetUri: String,
    val state: IndexState = IndexState.PENDING,
    val sourceModified: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    /** Tracks the embedding implementation so a model change can trigger reindexing. */
    val modelId: String = "",
    /** Historical indexes ran video OCR; new work records the setting captured when it starts. */
    @ColumnInfo(defaultValue = "1") val videoOcrEnabled: Boolean = true,
    /** A successful pass includes silence and media without an audio track. */
    @ColumnInfo(defaultValue = "0") val audioComplete: Boolean = false,
    /** Start of the last contiguous completed window; replay it to preserve overlap on resume. */
    @ColumnInfo(defaultValue = "0") val audioResumeMs: Long = 0,
)

enum class MediaAccess { NONE, LIMITED, FULL }

data class MediaScanResult(
    val assets: List<MediaAsset>,
    val access: MediaAccess,
    /** An incomplete provider query must never be used to remove cached index records. */
    val complete: Boolean = true,
    val error: String? = null,
)

data class OcrSegment(val text: String, val timestampMs: Long = -1)
