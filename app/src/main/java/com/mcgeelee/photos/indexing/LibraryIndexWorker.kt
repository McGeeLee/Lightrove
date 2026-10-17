package com.mcgeelee.photos.indexing

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.RuntimeText
import com.mcgeelee.photos.localization.runtimeText
import androidx.room.withTransaction
import androidx.work.*
import com.mcgeelee.photos.PhotosApplication
import com.mcgeelee.photos.data.*
import com.mcgeelee.photos.ml.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User-started, cancellable work. Each file commits atomically and completed files survive restarts. */
class LibraryIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as PhotosApplication).graph
    override suspend fun doWork(): Result = indexMutex.withLock { indexLibrary() }

    private suspend fun indexLibrary(): Result = withContext(Dispatchers.IO) {
        var usedEmbeddingEngine = false
        try {
            setForeground(IndexNotification.build(applicationContext, id, runtimeText(RuntimeMessage.INDEX_PREPARING)))
            // Settings are a snapshot for this run. A change in Settings applies on the next run.
            val videoOcrEnabled = graph.indexingPreferences.snapshotVideoOcrEnabled()
            graph.models.refresh()
            val snapshot = graph.refreshLibrary()
            if (!snapshot.complete) return@withContext Result.failure(workDataOf("error" to ((RuntimeText.decode(snapshot.error) ?: runtimeText(RuntimeMessage.LIBRARY_FAILED)).encode())))
            val semantic = graph.models.modelReady
            val modelId = if (semantic) ModelManager.MODEL_ID else OCR_MODEL_ID
            val plan = graph.dao.indexingPlan(modelId, videoOcrEnabled)
            var total = plan.total
            var completed = plan.ready
            var failed = 0
            OcrIndexer(applicationContext, graph.library).use { ocr ->
                for (asset in plan.pending) {
                    currentCoroutineContext().ensureActive()
                    // Claim and source validation are atomic with respect to a concurrent library scan.
                    val claim = graph.dao.claimIndex(asset, modelId, videoOcrEnabled)
                    if (claim == null) {
                        total--
                        continue
                    }
                    // Turning OCR off on a completed video only removes text; no frame/audio decode is needed.
                    val reuseCompletedVideo = asset.isVideo && !videoOcrEnabled &&
                        claim.previous?.state == IndexState.READY && claim.previous.modelId == modelId &&
                        claim.previous.sourceModified == asset.dateModified
                    usedEmbeddingEngine = usedEmbeddingEngine || (semantic && !reuseCompletedVideo)
                    val segments = linkedMapOf<Pair<IndexModality, Long>, IndexedSegment>()
                    claim.segments.forEach { segments[it.modality to it.timestampMs] = it }
                    val dirtyKeys = linkedSetOf<Pair<IndexModality, Long>>()
                    if (asset.isVideo && !videoOcrEnabled) {
                        // Retain visual/audio vectors but remove old video OCR on this requested run.
                        val textKeys = segments.keys.filter { it.first == IndexModality.TEXT }
                        textKeys.forEach { segments.remove(it) }
                        dirtyKeys += textKeys
                    }
                    fun saveSegment(segment: IndexedSegment) {
                        val key = segment.modality to segment.timestampMs
                        val previous = segments[key]
                        if (previous?.text == segment.text && previous.modelId == segment.modelId &&
                            (previous.vector === segment.vector ||
                                (previous.vector != null && segment.vector != null && previous.vector.contentEquals(segment.vector)))) return
                        segments[key] = segment
                        dirtyKeys += key
                    }
                    suspend fun saveCheckpoint(state: IndexState = IndexState.PROCESSING): Boolean {
                        val saved = graph.dao.checkpointIndex(asset, segments.values.toList(), modelId, state,
                            changedSegments = dirtyKeys.mapNotNull(segments::get),
                            removedKeys = dirtyKeys.filter { it !in segments }, videoOcrEnabled = videoOcrEnabled)
                        if (saved) dirtyKeys.clear()
                        return saved
                    }
                    var lastCheckpointAt = SystemClock.elapsedRealtime()
                    suspend fun checkpoint() {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) {
                            if (!saveCheckpoint()) {
                                throw SourceChangedException()
                            }
                            lastCheckpointAt = now
                        }
                    }
                    try {
                        updateProgress(completed, total, runtimeText(RuntimeMessage.INDEX_PROCESSING, asset.name), failed)
                        val errors = linkedSetOf<String>()
                        suspend fun <T> attempt(label: RuntimeMessage, block: suspend () -> T): T? {
                            currentCoroutineContext().ensureActive()
                            return try { block() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (changed: SourceChangedException) { throw changed }
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
                                if (segments.remove(key) != null) dirtyKeys += key
                                return
                            }
                            val vector = if (semantic) content.reusableVector ?: attempt(RuntimeMessage.STAGE_TEXT_SEMANTIC) {
                                graph.engine.embedDocument(content.text)
                            } else null
                            // OCR remains searchable even when its semantic embedding could not be computed.
                            saveSegment(IndexedSegment(
                                asset.uri, IndexModality.TEXT, time, content.text, vector, modelId))
                        }
                        if (!asset.isVideo) {
                            attempt(RuntimeMessage.STAGE_IMAGE_READING) {
                                val bitmap = graph.library.imageBitmap(asset, 1536) ?: error(runtimeText(RuntimeMessage.IMAGE_UNREADABLE).encode())
                                try {
                                    attempt(RuntimeMessage.STAGE_OCR) { ocr.recognizeDetailed(bitmap) }?.let { recognized ->
                                        addText(recognized, -1)
                                        if (recognized.isPartial) errors += runtimeText(RuntimeMessage.OCR_PARTIAL).encode()
                                    }
                                    if (semantic && segments[IndexModality.IMAGE to -1L]?.vector == null) attempt(RuntimeMessage.STAGE_IMAGE_SEMANTIC) {
                                        saveSegment(IndexedSegment(asset.uri, IndexModality.IMAGE,
                                            vector = graph.engine.embedImage(bitmap), modelId = modelId))
                                    }
                                } finally { bitmap.recycle() }
                            }
                        } else if ((semantic || videoOcrEnabled) && !reuseCompletedVideo) {
                            val times = VideoSampling.intervalTimestamps(asset.durationMs, VIDEO_INTERVAL_MS)
                            attempt(RuntimeMessage.STAGE_VIDEO_READING) {
                                graph.library.forEachVideoFrame(asset, times.asIterable(), 1024) { frame ->
                                    currentCoroutineContext().ensureActive()
                                    if (videoOcrEnabled) attempt(RuntimeMessage.STAGE_VIDEO_OCR) { ocr.recognizeDetailed(frame.bitmap) }
                                        ?.let { recognized ->
                                            addText(recognized, frame.timestampMs)
                                            if (recognized.isPartial) errors += runtimeText(RuntimeMessage.OCR_PARTIAL).encode()
                                        }
                                    if (semantic && segments[IndexModality.VIDEO to frame.timestampMs]?.vector == null) attempt(RuntimeMessage.STAGE_VIDEO_SEMANTIC) {
                                        saveSegment(IndexedSegment(asset.uri, IndexModality.VIDEO,
                                            timestampMs = frame.timestampMs, vector = graph.engine.embedImage(frame.bitmap), modelId = modelId))
                                    }
                                    checkpoint()
                                    updateProgress(completed, total, runtimeText(RuntimeMessage.INDEX_FRAME, asset.name, frame.timestampMs / 1000), failed)
                                }
                            }
                            if (semantic) {
                                attempt(RuntimeMessage.STAGE_AUDIO_DECODING) {
                                    // No audio track returns zero normally. Entirely silent windows need no vector.
                                    graph.audio.decode(Uri.parse(asset.uri)) { audio ->
                                        currentCoroutineContext().ensureActive()
                                        if (segments[IndexModality.AUDIO to audio.startMs]?.vector == null &&
                                            audio.samples.any { kotlin.math.abs(it) > 0.00001f }) {
                                            attempt(RuntimeMessage.STAGE_AUDIO_SEMANTIC) {
                                                saveSegment(IndexedSegment(asset.uri, IndexModality.AUDIO,
                                                    timestampMs = audio.startMs, vector = graph.engine.embedAudio(audio.samples), modelId = modelId))
                                            }
                                        }
                                        checkpoint()
                                        updateProgress(completed, total, runtimeText(RuntimeMessage.INDEX_AUDIO, asset.name, audio.startMs / 1000), failed)
                                    }
                                }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        val committed = graph.database.withTransaction {
                            val saved = graph.dao.completeIndex(asset, segments.values.toList(), modelId, videoOcrEnabled)
                            if (saved && errors.isNotEmpty()) graph.dao.upsertIndexRecord(IndexRecord(
                                asset.uri, IndexState.FAILED, asset.dateModified,
                                error = errors.joinToString("\n"), modelId = modelId, videoOcrEnabled = videoOcrEnabled))
                            saved
                        }
                        if (committed) {
                            if (errors.isEmpty()) completed++ else failed++
                        } else total--
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            // Save this video's partial results as resumable work, never as READY.
                            saveCheckpoint(IndexState.PENDING)
                        }
                        throw cancelled
                    }
                    catch (_: SourceChangedException) {
                        // The next scan/run owns the changed source. Stop decoding stale media now.
                        total--
                    }
                    catch (error: Exception) {
                        failed++
                        graph.database.withTransaction {
                            if (graph.dao.asset(asset.uri)?.sameSourceAs(asset) == true) {
                                graph.dao.upsertIndexRecord(IndexRecord(
                                    asset.uri, IndexState.FAILED, asset.dateModified,
                                    error = RuntimeText.fromError(error, RuntimeMessage.MEDIA_FAILED).encode(), modelId = modelId,
                                    videoOcrEnabled = videoOcrEnabled))
                            }
                        }
                    }
                }
            }
            updateProgress(completed, total, if (failed > 0) runtimeText(RuntimeMessage.INDEX_PARTIAL, failed) else runtimeText(RuntimeMessage.INDEX_COMPLETE), failed, force = true)
            // A run that disables video OCR can free many pages even after the startup compaction.
            IndexStorageWorker.enqueue(applicationContext)
            Result.success(workDataOf("completed" to completed, "failed" to failed))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(workDataOf("error" to (RuntimeText.fromError(error, RuntimeMessage.INDEX_FAILED).encode()))) }
        finally {
            // The app-scoped engine can reopen lazily for a later search; release its large native allocation now.
            if (usedEmbeddingEngine) withContext(NonCancellable) { runCatching { graph.engine.close() } }
        }
    }

    private class SourceChangedException : Exception()

    private val progressGate = IndexUpdateGate(1_000)
    private val notificationGate = IndexUpdateGate(3_000)

    private suspend fun updateProgress(
        completed: Int, total: Int, message: RuntimeText, failed: Int = 0, force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        // Each setProgress persists a WorkManager DB transaction. Bound both database and
        // notification updates, including fast OCR passes, while always publishing completion.
        if (progressGate.shouldUpdate(now, force)) {
            setProgress(workDataOf("completed" to completed, "total" to total, "failed" to failed, "message" to message.encode()))
        }
        if (notificationGate.shouldUpdate(now, force)) {
            setForeground(IndexNotification.build(applicationContext, id, message, completed, total, failed))
        }
    }

    companion object {
        // WorkManager marks cancellation before native work finishes unwinding. Serialize old
        // cleanup and a newly requested run so neither can overwrite the other's checkpoint.
        private val indexMutex = Mutex()
        const val UNIQUE_NAME = "library-index"
        const val OCR_MODEL_ID = "mlkit-five-scripts-v2"
        const val VIDEO_INTERVAL_MS = 5000L
        private const val CHECKPOINT_INTERVAL_MS = 30_000L
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LibraryIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
                .build(),
        )
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
