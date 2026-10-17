package com.mcgeelee.photos.indexing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.RuntimeText
import com.mcgeelee.photos.localization.runtimeText
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.*
import com.mcgeelee.photos.PhotosApplication
import com.mcgeelee.photos.R
import com.mcgeelee.photos.data.*
import com.mcgeelee.photos.ml.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** User-started, cancellable work. Each file commits atomically and completed files survive restarts. */
class LibraryIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as PhotosApplication).graph
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(notification(runtimeText(RuntimeMessage.INDEX_PREPARING)))
            graph.models.refresh()
            val snapshot = graph.refreshLibrary()
            if (!snapshot.complete) return@withContext Result.failure(workDataOf("error" to ((RuntimeText.decode(snapshot.error) ?: runtimeText(RuntimeMessage.LIBRARY_FAILED)).encode())))
            val semantic = graph.models.modelReady
            val modelId = if (semantic) ModelManager.MODEL_ID else OCR_MODEL_ID
            val assets = graph.dao.assets()
            var completed = 0
            var failed = 0
            OcrIndexer(applicationContext, graph.library).use { ocr ->
                for (asset in assets) {
                    currentCoroutineContext().ensureActive()
                    val old = graph.dao.indexRecord(asset.uri)
                    if (old?.state == IndexState.READY && old.sourceModified == asset.dateModified && old.modelId == modelId) {
                        completed++
                        continue
                    }
                    updateProgress(completed, assets.size, runtimeText(RuntimeMessage.INDEX_PROCESSING, asset.name), failed)
                    try {
                        if (graph.dao.asset(asset.uri) == null) continue
                        graph.dao.upsertIndexRecord(IndexRecord(asset.uri, IndexState.PROCESSING, asset.dateModified, modelId = modelId))
                        val segments = linkedMapOf<Pair<IndexModality, Long>, IndexedSegment>()
                        // Keep successful modalities from an earlier partial run of this exact source/model.
                        if (old?.sourceModified == asset.dateModified && old.modelId == modelId) {
                            graph.dao.segments(asset.uri).forEach { segments[it.modality to it.timestampMs] = it }
                        }
                        val errors = linkedSetOf<String>()
                        suspend fun <T> attempt(label: RuntimeMessage, block: suspend () -> T): T? {
                            currentCoroutineContext().ensureActive()
                            return try { block() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                // Bound diagnostics for long recordings while retaining distinct failed modalities.
                                if (errors.size < 8) errors += runtimeText(label).encode()
                                null
                            }
                        }
                        suspend fun addText(recognized: OcrRecognitionResult, time: Long) {
                            val key = IndexModality.TEXT to time
                            val previous = segments[key]
                            val content = TextIndexRetry.prepare(previous?.text, previous?.vector,
                                recognized.text, recognized.isPartial)
                            if (content.text.isBlank()) {
                                segments.remove(key)
                                return
                            }
                            val vector = if (semantic) content.reusableVector ?: attempt(RuntimeMessage.STAGE_TEXT_SEMANTIC) {
                                graph.engine.embedDocument(content.text)
                            } else null
                            // OCR remains searchable even when its semantic embedding could not be computed.
                            segments[key] = IndexedSegment(
                                asset.uri, IndexModality.TEXT, time, content.text, vector, modelId)
                        }
                        if (!asset.isVideo) {
                            attempt(RuntimeMessage.STAGE_IMAGE_READING) {
                                val bitmap = graph.library.imageBitmap(asset, 1536) ?: error(runtimeText(RuntimeMessage.IMAGE_UNREADABLE).encode())
                                try {
                                    attempt(RuntimeMessage.STAGE_OCR) { ocr.recognizeDetailed(bitmap) }?.let { recognized ->
                                        addText(recognized, -1)
                                        if (recognized.isPartial) errors += runtimeText(RuntimeMessage.OCR_PARTIAL).encode()
                                    }
                                    if (semantic) attempt(RuntimeMessage.STAGE_IMAGE_SEMANTIC) {
                                        segments[IndexModality.IMAGE to -1L] = IndexedSegment(asset.uri, IndexModality.IMAGE,
                                            vector = graph.engine.embedImage(bitmap), modelId = modelId)
                                    }
                                } finally { bitmap.recycle() }
                            }
                        } else {
                            val times = VideoSampling.intervalTimestamps(asset.durationMs, VIDEO_INTERVAL_MS)
                            attempt(RuntimeMessage.STAGE_VIDEO_READING) {
                                graph.library.forEachVideoFrame(asset, times.asIterable(), 1024) { frame ->
                                    currentCoroutineContext().ensureActive()
                                    attempt(RuntimeMessage.STAGE_VIDEO_OCR) { ocr.recognizeDetailed(frame.bitmap) }
                                        ?.let { recognized ->
                                            addText(recognized, frame.timestampMs)
                                            if (recognized.isPartial) errors += runtimeText(RuntimeMessage.OCR_PARTIAL).encode()
                                        }
                                    if (semantic) attempt(RuntimeMessage.STAGE_VIDEO_SEMANTIC) {
                                        segments[IndexModality.VIDEO to frame.timestampMs] = IndexedSegment(asset.uri, IndexModality.VIDEO,
                                            timestampMs = frame.timestampMs, vector = graph.engine.embedImage(frame.bitmap), modelId = modelId)
                                    }
                                    updateProgress(completed, assets.size, runtimeText(RuntimeMessage.INDEX_FRAME, asset.name, frame.timestampMs / 1000), failed)
                                }
                            }
                            if (semantic) {
                                attempt(RuntimeMessage.STAGE_AUDIO_DECODING) {
                                    // No audio track returns zero normally. Entirely silent windows need no vector.
                                    graph.audio.decode(Uri.parse(asset.uri)) { audio ->
                                        currentCoroutineContext().ensureActive()
                                        if (audio.samples.any { kotlin.math.abs(it) > 0.00001f }) {
                                            attempt(RuntimeMessage.STAGE_AUDIO_SEMANTIC) {
                                                segments[IndexModality.AUDIO to audio.startMs] = IndexedSegment(asset.uri, IndexModality.AUDIO,
                                                    timestampMs = audio.startMs, vector = graph.engine.embedAudio(audio.samples), modelId = modelId)
                                            }
                                        }
                                        updateProgress(completed, assets.size, runtimeText(RuntimeMessage.INDEX_AUDIO, asset.name, audio.startMs / 1000), failed)
                                    }
                                }
                            }
                        }
                        val committed = graph.database.withTransaction {
                            val saved = graph.dao.completeIndex(asset, segments.values.toList(), modelId)
                            if (saved && errors.isNotEmpty()) graph.dao.upsertIndexRecord(IndexRecord(
                                asset.uri, IndexState.FAILED, asset.dateModified,
                                error = errors.joinToString("\n"), modelId = modelId))
                            saved
                        }
                        if (committed) {
                            if (errors.isEmpty()) completed++ else failed++
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            graph.database.withTransaction {
                                val current = graph.dao.asset(asset.uri)
                                val record = graph.dao.indexRecord(asset.uri)
                                if (current?.copy(ocrText = asset.ocrText) == asset && record?.state == IndexState.PROCESSING &&
                                    record.sourceModified == asset.dateModified && record.modelId == modelId) {
                                    graph.dao.upsertIndexRecord(record.copy(state = IndexState.PENDING, error = null))
                                }
                            }
                        }
                        throw cancelled
                    }
                    catch (error: Exception) {
                        failed++
                        graph.database.withTransaction {
                            if (graph.dao.asset(asset.uri)?.copy(ocrText = asset.ocrText) == asset) {
                                graph.dao.upsertIndexRecord(IndexRecord(
                                    asset.uri, IndexState.FAILED, asset.dateModified,
                                    error = RuntimeText.fromError(error, RuntimeMessage.MEDIA_FAILED).encode(), modelId = modelId))
                            }
                        }
                    }
                }
            }
            updateProgress(completed, assets.size, if (failed > 0) runtimeText(RuntimeMessage.INDEX_PARTIAL, failed) else runtimeText(RuntimeMessage.INDEX_COMPLETE), failed)
            Result.success(workDataOf("completed" to completed, "failed" to failed))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(workDataOf("error" to (RuntimeText.fromError(error, RuntimeMessage.INDEX_FAILED).encode()))) }
        finally {
            // The app-scoped engine can reopen lazily for a later search; release its large native allocation now.
            withContext(NonCancellable) { runCatching { graph.engine.close() } }
        }
    }

    private var lastNotificationAt = 0L
    private suspend fun updateProgress(completed: Int, total: Int, message: RuntimeText, failed: Int = 0) {
        // Persist stable keys and typed arguments. An activity recreated in another language can re-render immediately.
        setProgress(workDataOf("completed" to completed, "total" to total, "failed" to failed, "message" to message.encode()))
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt > 3000) { setForeground(notification(message, failed)); lastNotificationAt = now }
    }

    private fun notification(message: RuntimeText, failed: Int = 0): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            runtimeText(RuntimeMessage.INDEX_CHANNEL).render(applicationContext), NotificationManager.IMPORTANCE_LOW))
        val content = if (failed > 0 && message.key != RuntimeMessage.INDEX_PARTIAL) {
            runtimeText(RuntimeMessage.INDEX_WITH_RETRIES, message.render(applicationContext),
                runtimeText(RuntimeMessage.INDEX_RETRY_COUNT, failed).render(applicationContext)).render(applicationContext)
        } else message.render(applicationContext)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_index_notification)
            .setContentTitle(runtimeText(RuntimeMessage.INDEX_NOTIFICATION).render(applicationContext))
            .setContentText(content)
            .setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, runtimeText(RuntimeMessage.STOP).render(applicationContext), cancel).build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(101, notification, type)
    }

    companion object {
        const val UNIQUE_NAME = "library-index"
        const val OCR_MODEL_ID = "mlkit-five-scripts-v2"
        const val VIDEO_INTERVAL_MS = 5000L
        private const val CHANNEL = "library-index"
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LibraryIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
                .build(),
        )
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
