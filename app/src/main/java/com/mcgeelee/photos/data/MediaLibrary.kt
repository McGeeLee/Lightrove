package com.mcgeelee.photos.data

import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.runtimeText
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

data class VideoFrame(val timestampMs: Long, val bitmap: Bitmap)

/** Reads only currently granted content URIs; no filesystem storage paths are retained. */
class MediaLibrary(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    fun access(): MediaAccess {
        if (Build.VERSION.SDK_INT < 33) {
            return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL
            else if (persistedMediaUris().isNotEmpty()) MediaAccess.LIMITED else MediaAccess.NONE
        }
        val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
        val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
        return when {
            images && videos -> MediaAccess.FULL
            images || videos || hasSelectedAccess() || persistedMediaUris().isNotEmpty() -> MediaAccess.LIMITED
            else -> MediaAccess.NONE
        }
    }

    /** Call on resume as well: Android can revoke selected-photo grants while backgrounded. */
    suspend fun scan(): MediaScanResult = withContext(Dispatchers.IO) {
        val access = access()
        if (access == MediaAccess.NONE) return@withContext MediaScanResult(emptyList(), access)
        try {
            val assets = buildList {
                if (hasLegacyAccess() || (Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES)) || hasSelectedAccess()) {
                    addAll(queryCollection(isVideo = false))
                }
                if (hasLegacyAccess() || (Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO)) || hasSelectedAccess()) {
                    addAll(queryCollection(isVideo = true))
                }
                for (uri in persistedMediaUris()) {
                    currentCoroutineContext().ensureActive()
                    assetFromUri(uri)?.let(::add)
                }
            }.distinctBy(MediaAsset::uri)
                .sortedWith(compareByDescending<MediaAsset> { it.dateTaken }.thenByDescending { it.dateModified })
            // Never publish a snapshot gathered across a permission transition.
            if (access() != access) return@withContext MediaScanResult(emptyList(), access(), complete = false)
            MediaScanResult(assets, access)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            val latest = access()
            MediaScanResult(emptyList(), latest, complete = latest == MediaAccess.NONE,
                error = runtimeText(RuntimeMessage.LIBRARY_PERMISSION_CHANGED).encode())
        } catch (_: Exception) {
            MediaScanResult(emptyList(), access, complete = false, error = runtimeText(RuntimeMessage.LIBRARY_UNAVAILABLE).encode())
        }
    }

    /** Supports Android Photo Picker and SAF document URIs, including cloud-backed providers. */
    suspend fun assetFromUri(uri: Uri): MediaAsset? = withContext(Dispatchers.IO) {
        readOrNull {
            val mime = resolver.getType(uri) ?: return@readOrNull null
            val isVideo = mime.startsWith("video/")
            if (!isVideo && !mime.startsWith("image/")) return@readOrNull null
            var name = uri.lastPathSegment.orEmpty()
            var modified = 0L
            var taken = 0L
            var width = 0
            var height = 0
            var duration = 0L
            var sizeBytes = 0L
            var bucket = "" // Provider album names are user data; do not persist a translated fallback.
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@readOrNull null
                fun string(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 }?.let(cursor::getString)
                fun number(column: String): Long = cursor.getColumnIndex(column).takeIf { it >= 0 }?.let(cursor::getLong) ?: 0
                name = string(OpenableColumns.DISPLAY_NAME) ?: name
                modified = number(MediaStore.MediaColumns.DATE_MODIFIED)
                if (modified == 0L) modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED) / 1000
                taken = number(MediaStore.MediaColumns.DATE_TAKEN).takeIf { it > 0 } ?: modified * 1000
                width = number(MediaStore.MediaColumns.WIDTH).toInt()
                height = number(MediaStore.MediaColumns.HEIGHT).toInt()
                duration = number(MediaStore.Video.Media.DURATION)
                sizeBytes = number(OpenableColumns.SIZE)
                bucket = string(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) ?: bucket
            } ?: return@readOrNull null
            if (isVideo && (duration <= 0 || width <= 0 || height <= 0)) {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, uri)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: width
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: height
                }
            }
            MediaAsset(
                uri = uri.toString(),
                id = uri.lastPathSegment?.toLongOrNull() ?: uri.toString().hashCode().toLong(),
                name = name,
                mimeType = mime,
                dateModified = modified,
                dateTaken = taken,
                durationMs = duration.coerceAtLeast(0),
                width = width.coerceAtLeast(0),
                height = height.coerceAtLeast(0),
                bucketName = bucket,
                isVideo = isVideo,
                sizeBytes = sizeBytes.coerceAtLeast(0),
            )
        }.also { currentCoroutineContext().ensureActive() }
    }

    fun observeChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    suspend fun thumbnail(asset: MediaAsset, size: Int = 384): Bitmap? = withContext(Dispatchers.IO) {
        require(size in 1..2048)
        readOrNull {
            resolver.loadThumbnail(Uri.parse(asset.uri), Size(size, size), null)
        }.checkedBitmap()
    }

    /** Caller owns and recycles the returned software bitmap. ImageDecoder applies EXIF orientation. */
    suspend fun imageBitmap(asset: MediaAsset, maxDimension: Int = 1024): Bitmap? =
        imageBitmap(Uri.parse(asset.uri), maxDimension)

    suspend fun imageBitmap(uri: Uri, maxDimension: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        require(maxDimension in 64..4096)
        readOrNull {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxDimension) {
                    val scale = maxDimension.toDouble() / longest
                    decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
                }
            }
        }.checkedBitmap()
    }

    /** Returns the decoded frame nearest the requested timestamp; offsets are approximate frame times. */
    suspend fun videoFrame(asset: MediaAsset, timestampMs: Long, maxDimension: Int = 1024): Bitmap? =
        withContext(Dispatchers.IO) {
            require(timestampMs >= 0)
            require(maxDimension in 64..4096)
            readOrNull {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, Uri.parse(asset.uri))
                    decodeFrame(retriever, timestampMs, maxDimension)
                }
            }.checkedBitmap()
        }

    /** Streaming decode reuses one retriever, keeping only one frame in memory at a time. */
    suspend fun forEachVideoFrame(
        asset: MediaAsset,
        timestampsMs: Iterable<Long> = VideoSampling.timestamps(asset.durationMs),
        maxDimension: Int = 1024,
        onFrame: suspend (VideoFrame) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(maxDimension in 64..4096)
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(asset.uri))
            var decodedFrames = 0
            for (timestamp in timestampsMs) {
                currentCoroutineContext().ensureActive()
                require(timestamp >= 0)
                val bitmap = decodeFrame(retriever, timestamp, maxDimension)
                    ?: throw IOException(runtimeText(RuntimeMessage.VIDEO_UNREADABLE).encode())
                decodedFrames++
                try { onFrame(VideoFrame(timestamp, bitmap)) } finally { bitmap.recycle() }
            }
            if (decodedFrames == 0) throw IOException(runtimeText(RuntimeMessage.VIDEO_UNREADABLE).encode())
        }
    }

    private fun decodeFrame(retriever: MediaMetadataRetriever, timestampMs: Long, maxDimension: Int): Bitmap? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxDimension
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxDimension
        val scale = min(1.0, maxDimension.toDouble() / max(1, max(width, height)))
        return retriever.getScaledFrameAtTime(
            timestampMs.coerceAtMost(Long.MAX_VALUE / 1000) * 1000,
            MediaMetadataRetriever.OPTION_CLOSEST,
            max(1, (width * scale).toInt()),
            max(1, (height * scale).toInt()),
        )
    }

    private suspend fun queryCollection(isVideo: Boolean): List<MediaAsset> {
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        ).apply { if (isVideo) add(MediaStore.Video.Media.DURATION) }
        val cursor = resolver.query(
            collection,
            columns.toTypedArray(),
            "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.IS_TRASHED} = 0",
            null,
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
        ) ?: throw IllegalStateException("MediaStore query returned no cursor")
        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val modifiedIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val widthIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val bucketIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val durationIndex = it.getColumnIndex(MediaStore.Video.Media.DURATION)
            buildList {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.getLong(idIndex)
                    val modified = it.getLong(modifiedIndex)
                    add(MediaAsset(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        id = id,
                        name = it.getString(nameIndex).orEmpty(),
                        mimeType = it.getString(mimeIndex) ?: if (isVideo) "video/*" else "image/*",
                        dateModified = modified,
                        dateTaken = it.getLong(takenIndex).takeIf { date -> date > 0 } ?: modified * 1000,
                        durationMs = if (durationIndex >= 0) it.getLong(durationIndex).coerceAtLeast(0) else 0,
                        width = it.getInt(widthIndex),
                        height = it.getInt(heightIndex),
                        bucketName = it.getString(bucketIndex).orEmpty(),
                        isVideo = isVideo,
                        sizeBytes = it.getLong(sizeIndex).coerceAtLeast(0),
                    ))
                }
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasSelectedAccess(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun hasLegacyAccess(): Boolean = Build.VERSION.SDK_INT < 33 &&
        granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun persistedMediaUris(): List<Uri> = resolver.persistedUriPermissions
        .asSequence().filter { it.isReadPermission }.map { it.uri }
        .filter { uri ->
            readOrNull { resolver.getType(uri) }?.let { it.startsWith("image/") || it.startsWith("video/") } == true
        }.toList()

    private suspend fun Bitmap?.checkedBitmap(): Bitmap? {
        try {
            currentCoroutineContext().ensureActive()
            return this
        } catch (cancelled: CancellationException) {
            this?.recycle()
            throw cancelled
        }
    }

    private inline fun <T> readOrNull(read: () -> T): T? = try { read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { null }
        catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
        catch (_: IllegalStateException) { null }
}
