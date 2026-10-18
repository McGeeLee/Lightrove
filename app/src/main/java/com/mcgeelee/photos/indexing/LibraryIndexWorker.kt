package com.mcgeelee.photos.indexing

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.RuntimeText
import com.mcgeelee.photos.localization.runtimeText
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
            var lastError: String? = null
            val repeatedFailure = RepeatedIndexFailure()
            OcrIndexer(applicationContext, graph.library).use { ocr ->
                for (asset in plan.pending) {
                    currentCoroutineContext().ensureActive()
                    val claim = graph.dao.claimIndex(asset, modelId, videoOcrEnabled)
                    if (claim == null) {
                        total--
                        continue
                    }
                    val reusable = claim.previous?.modelId == modelId && claim.previous.sourceModified == asset.dateModified
                    var audioComplete = reusable && claim.previous?.audioComplete == true
                    var audioResumeMs = if (reusable) claim.previous?.audioResumeMs ?: 0 else 0
                    val segments = linkedMapOf<Pair<IndexModality, Long>, IndexedSegment>()
                    claim.segments.forEach { segments[it.modality to it.timestampMs] = it }
                    val dirtyKeys = linkedSetOf<Pair<IndexModality, Long>>()
                    if (asset.isVideo && !videoOcrEnabled) {
                        val textKeys = segments.keys.filter { it.first == IndexModality.TEXT }
                        textKeys.forEach { segments.remove(it) }
                        dirtyKeys += textKeys
                    }
                    fun saveSegment(segment: IndexedSegment) {
                        val key = segment.modality to segment.timestampMs
                        val previous = segments[key]
                        if (previous?.text == segment.text && previous.modelId == segment.modelId &&
                            previous.ocrScripts == segment.ocrScripts &&
                            (previous.vector === segment.vector ||
                                (previous.vector != null && segment.vector != null && previous.vector.contentEquals(segment.vector)))) return
                        segments[key] = segment
                        dirtyKeys += key
                    }
                    suspend fun saveCheckpoint(state: IndexState = IndexState.PROCESSING, error: String? = null): Boolean {
                        val saved = graph.dao.checkpointIndex(asset, segments.values.toList(), modelId, state,
                            changedSegments = dirtyKeys.mapNotNull(segments::get),
                            removedKeys = dirtyKeys.filter { it !in segments }, videoOcrEnabled = videoOcrEnabled,
                            audioComplete = audioComplete, audioResumeMs = audioResumeMs, error = error)
                        if (saved) dirtyKeys.clear()
                        return saved
                    }
                    var lastCheckpointAt = SystemClock.elapsedRealtime()
                    suspend fun checkpoint() {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) {
                            if (!saveCheckpoint()) throw SourceChangedException()
                            lastCheckpointAt = now
                        }
                    }
                    val errors = linkedSetOf<String>()
                    val sharedFailures = linkedSetOf<String>()
                    val unitFailures = linkedSetOf<String>()
                    fun recordFailure(label: RuntimeMessage, error: Exception, script: OcrScript? = null) {
                        val detail = RuntimeText.stageFailure(label, error).encode()
                        if (errors.size < 8) errors += detail
                        lastError = detail
                        // Per-file codec/access errors should not block other files. A repeating
                        // OCR/inference error commonly means a shared runtime/model is unavailable.
                        if (label in SHARED_STAGES) {
                            val signature = "${script?.name.orEmpty()}:$detail"
                            sharedFailures += signature
                            unitFailures += signature
                        }
                    }
                    suspend fun <T> attempt(label: RuntimeMessage, block: suspend () -> T): T? {
                        currentCoroutineContext().ensureActive()
                        return try { block() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (changed: SourceChangedException) { throw changed }
                        catch (repeated: RepeatedSharedFailure) { throw repeated }
                        catch (error: Exception) { recordFailure(label, error); null }
                    }
                    suspend fun embedText(segment: IndexedSegment) {
                        if (!semantic || segment.text.isBlank() || segment.vector != null) return
                        usedEmbeddingEngine = true
                        attempt(RuntimeMessage.STAGE_TEXT_SEMANTIC) {
                            saveSegment(segment.copy(vector = graph.engine.embedDocument(segment.text)))
                        }
                    }
                    suspend fun recognize(bitmap: android.graphics.Bitmap, time: Long, label: RuntimeMessage) {
                        val key = IndexModality.TEXT to time
                        val previous = segments[key]
                        val missing = IndexResume.missingScripts(previous)
                        if (missing.isEmpty()) return
                        val recognized = attempt(label) { ocr.recognizeDetailed(bitmap, missing) }
                        if (recognized == null && previous != null) embedText(previous)
                        recognized?.let { result ->
                            // Retrying a subset cannot retract successful text from the other scripts.
                            val content = TextIndexRetry.prepare(previous?.text, previous?.vector, result.text,
                                result.isPartial || (previous?.ocrScripts ?: 0) != 0)
                            val segment = IndexedSegment(asset.uri, IndexModality.TEXT, time,
                                content.text, content.reusableVector, modelId,
                                (previous?.ocrScripts ?: 0) or IndexResume.scriptsMask(result.successfulScripts))
                            // Empty text is a durable success too. Search ignores this marker.
                            saveSegment(segment)
                            result.failures.forEach { recordFailure(label, it.cause, it.script) }
                            embedText(segment)
                        }
                    }
                    try {
                        updateProgress(completed, total, runtimeText(RuntimeMessage.INDEX_PROCESSING, asset.name), failed, error = lastError)
                        // Missing text vectors can be rebuilt directly from saved OCR without a bitmap.
                        segments.values.filter { it.modality == IndexModality.TEXT &&
                            IndexResume.missingScripts(it).isEmpty() }.toList().forEach { embedText(it); checkpoint() }
                        if (!asset.isVideo) {
                            if (IndexResume.needsFrame(segments, -1, IndexModality.IMAGE, semantic, ocrEnabled = true)) {
                                attempt(RuntimeMessage.STAGE_IMAGE_READING) {
                                    val bitmap = graph.library.imageBitmap(asset, 1536)
                                        ?: error(runtimeText(RuntimeMessage.IMAGE_UNREADABLE).encode())
                                    try {
                                        recognize(bitmap, -1, RuntimeMessage.STAGE_OCR)
                                        if (semantic && segments[IndexModality.IMAGE to -1L]?.vector == null) {
                                            usedEmbeddingEngine = true
                                            attempt(RuntimeMessage.STAGE_IMAGE_SEMANTIC) {
                                                saveSegment(IndexedSegment(asset.uri, IndexModality.IMAGE,
                                                    vector = graph.engine.embedImage(bitmap), modelId = modelId))
                                            }
                                        }
                                    } finally { bitmap.recycle() }
                                }
                            }
                        } else {
                            val times = VideoSampling.intervalTimestamps(asset.durationMs, VIDEO_INTERVAL_MS)
                                .filter { IndexResume.needsFrame(segments, it, IndexModality.VIDEO, semantic, videoOcrEnabled) }
                            if (times.any()) attempt(RuntimeMessage.STAGE_VIDEO_READING) {
                                val frameFailures = RepeatedIndexFailure()
                                graph.library.forEachVideoFrame(asset, times.asIterable(), 1024) { frame ->
                                    currentCoroutineContext().ensureActive()
                                    unitFailures.clear()
                                    if (videoOcrEnabled) recognize(frame.bitmap, frame.timestampMs, RuntimeMessage.STAGE_VIDEO_OCR)
                                    if (semantic && segments[IndexModality.VIDEO to frame.timestampMs]?.vector == null) {
                                        usedEmbeddingEngine = true
                                        attempt(RuntimeMessage.STAGE_VIDEO_SEMANTIC) {
                                            saveSegment(IndexedSegment(asset.uri, IndexModality.VIDEO,
                                                timestampMs = frame.timestampMs, vector = graph.engine.embedImage(frame.bitmap), modelId = modelId))
                                        }
                                    }
                                    checkpoint()
                                    if (frameFailures.shouldStop(unitFailures)) throw RepeatedSharedFailure()
                                    updateProgress(completed, total, runtimeText(RuntimeMessage.INDEX_FRAME, asset.name, frame.timestampMs / 1000), failed, error = lastError)
                                }
                            }
                            if (semantic && !audioComplete) {
                                var prefixComplete = true
                                val audioFailures = RepeatedIndexFailure()
                                val decoded = attempt(RuntimeMessage.STAGE_AUDIO_DECODING) {
                                    graph.audio.decode(Uri.parse(asset.uri), startMs = audioResumeMs) { audio ->
                                        currentCoroutineContext().ensureActive()
                                        unitFailures.clear()
                                        val key = IndexModality.AUDIO to audio.startMs
                                        if (key !in segments) {
                                            val audible = audio.samples.any { kotlin.math.abs(it) > 0.00001f }
                                            val vector = if (audible) {
                                                usedEmbeddingEngine = true
                                                attempt(RuntimeMessage.STAGE_AUDIO_SEMANTIC) { graph.engine.embedAudio(audio.samples) }
                                            } else null
                                            if (!audible || vector != null) saveSegment(IndexedSegment(asset.uri, IndexModality.AUDIO,
                                                timestampMs = audio.startMs, vector = vector, modelId = modelId))
                                        }
                                        if (key !in segments) prefixComplete = false
                                        // Replay the last successful window, retaining its overlap and handling
                                        // nonzero track timestamps/gaps without assuming a fixed 9s grid.
                                        if (prefixComplete) audioResumeMs = audio.startMs
                                        checkpoint()
                                        if (audioFailures.shouldStop(unitFailures)) throw RepeatedSharedFailure()
                                        updateProgress(completed, total, runtimeText(RuntimeMessage.INDEX_AUDIO, asset.name, audio.startMs / 1000), failed, error = lastError)
                                    }
                                }
                                audioComplete = decoded != null && prefixComplete
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        val committed = if (errors.isEmpty()) {
                            graph.dao.completeIndex(asset, segments.values.toList(), modelId, videoOcrEnabled,
                                audioComplete = audioComplete, audioResumeMs = audioResumeMs)
                        } else saveCheckpoint(IndexState.FAILED, errors.joinToString("\n"))
                        if (committed) {
                            if (errors.isEmpty()) completed++ else failed++
                        } else total--
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { saveCheckpoint(IndexState.PENDING) }
                        throw cancelled
                    } catch (_: SourceChangedException) {
                        total--
                    } catch (_: RepeatedSharedFailure) {
                        failed++
                        saveCheckpoint(IndexState.FAILED, errors.joinToString("\n"))
                        return@withContext Result.failure(workDataOf("completed" to completed, "failed" to failed,
                            "error" to listOfNotNull(runtimeText(RuntimeMessage.INDEX_PAUSED_ERRORS).encode(), lastError).joinToString("\n")))
                    } catch (error: Exception) {
                        failed++
                        lastError = RuntimeText.stageFailure(RuntimeMessage.MEDIA_FAILED, error).encode()
                        saveCheckpoint(IndexState.FAILED, lastError)
                    }
                    if (repeatedFailure.shouldStop(sharedFailures)) {
                        return@withContext Result.failure(workDataOf("completed" to completed, "failed" to failed,
                            "error" to listOfNotNull(runtimeText(RuntimeMessage.INDEX_PAUSED_ERRORS).encode(), lastError).joinToString("\n")))
                    }
                }
            }
            updateProgress(completed, total, if (failed > 0) runtimeText(RuntimeMessage.INDEX_PARTIAL, failed) else runtimeText(RuntimeMessage.INDEX_COMPLETE), failed, force = true, error = lastError)
            // A run that disables video OCR can free many pages even after the startup compaction.
            IndexStorageWorker.enqueue(applicationContext)
            Result.success(workDataOf("completed" to completed, "failed" to failed, "error" to lastError))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(workDataOf("error" to (RuntimeText.fromError(error, RuntimeMessage.INDEX_FAILED).encode()))) }
        finally {
            // The app-scoped engine can reopen lazily for a later search; release its large native allocation now.
            if (usedEmbeddingEngine) withContext(NonCancellable) { runCatching { graph.engine.close() } }
        }
    }

    private class SourceChangedException : Exception()
    private class RepeatedSharedFailure : Exception()

    private val progressGate = IndexUpdateGate(1_000)
    private val notificationGate = IndexUpdateGate(3_000)

    private suspend fun updateProgress(
        completed: Int, total: Int, message: RuntimeText, failed: Int = 0, force: Boolean = false, error: String? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        // Each setProgress persists a WorkManager DB transaction. Bound both database and
        // notification updates, including fast OCR passes, while always publishing completion.
        if (progressGate.shouldUpdate(now, force)) {
            setProgress(workDataOf("completed" to completed, "total" to total, "failed" to failed, "message" to message.encode(), "error" to error))
        }
        if (notificationGate.shouldUpdate(now, force)) {
            setForeground(IndexNotification.build(applicationContext, id, message, completed, total, failed))
        }
    }

    companion object {
        // WorkManager marks cancellation before native work finishes unwinding. Serialize old
        // cleanup and a newly requested run so neither can overwrite the other's checkpoint.
        private val SHARED_STAGES = setOf(RuntimeMessage.STAGE_OCR, RuntimeMessage.STAGE_VIDEO_OCR,
            RuntimeMessage.STAGE_TEXT_SEMANTIC, RuntimeMessage.STAGE_IMAGE_SEMANTIC,
            RuntimeMessage.STAGE_VIDEO_SEMANTIC, RuntimeMessage.STAGE_AUDIO_SEMANTIC)
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
