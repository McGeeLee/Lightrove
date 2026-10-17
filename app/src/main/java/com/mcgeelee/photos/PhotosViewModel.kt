package com.mcgeelee.photos

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import com.mcgeelee.photos.localization.AppLanguage
import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.RuntimeText
import com.mcgeelee.photos.localization.runtimeText
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mcgeelee.photos.data.*
import com.mcgeelee.photos.indexing.LibraryIndexWorker
import com.mcgeelee.photos.ml.ModelManager
import com.mcgeelee.photos.ml.ModelPhase
import com.mcgeelee.photos.search.*
import com.mcgeelee.photos.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PhotosViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val graph = (application as PhotosApplication).graph
    private val mutableState = MutableStateFlow(PhotosUiState())
    val state = mutableState.asStateFlow()
    val onboardingReview = savedState.getStateFlow("onboarding_review", false)
    val onboardingVisible: StateFlow<Boolean?> = combine(graph.onboarding.completed, onboardingReview) { completed, requested ->
        !completed || requested
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var onboardingJob: Job? = null
    private var indexingPreferenceJob: Job? = null
    private var assets = emptyList<MediaAsset>()
    private var queryVector: FloatArray? = null
    private var searchJob: Job? = null
    private var attachmentJob: Job? = null
    private var observedWorkId: java.util.UUID? = null
    private var modelJob: Job? = null
    private var refreshJob: Job? = null
    private var records = emptyList<IndexRecord>()
    private var permissionChecked = false
    private var accessRevision = 0L
    private var queryRevision = 0L
    private var lastWork = emptyList<WorkInfo>()
    private var attachmentLabel: RuntimeText? = null
    private var errorText: RuntimeText? = null
    private var errorResource: Int? = null
    private var matchSources = emptyMap<String, RuntimeText>()
    private var selectedMatchSource: RuntimeText? = null
    private val localizedContext get() = AppLanguage.localizedContext(getApplication())

    init {
        viewModelScope.launch {
            graph.indexingPreferences.videoOcrEnabled.collect { enabled ->
                mutableState.update { it.copy(videoOcrEnabled = enabled) }
                updateIndexedCount()
            }
        }
        viewModelScope.launch {
            graph.dao.observeAssets().collect { media ->
                assets = media
                if (permissionChecked) mutableState.update { it.copy(media = media.map { it.toPhoto() }, totalCount = media.size) }
                if (!state.value.isIndexing) search(debounce = false)
            }
        }
        viewModelScope.launch {
            graph.dao.observeIndexRecords().collect { value ->
                records = value
                updateIndexedCount()
                if (!state.value.isIndexing) search(debounce = false)
            }
        }
        viewModelScope.launch {
            // Vector-only commits need to refresh results too. Observe table invalidation instead
            // of loading every vector twice, and coalesce the stream while indexing is busy.
            graph.database.invalidationTracker.createFlow("indexed_segments", emitInitialState = false)
                .conflate().collect {
                    delay(1_000)
                    // A refresh must not repeatedly cancel an initial, slower native query.
                    if (searchJob?.isActive != true) search(debounce = false)
                }
        }
        viewModelScope.launch {
            graph.models.status.collect { model ->
                updateModelPresentation()
                updateIndexedCount()
                if (model.phase == ModelPhase.READY) search(debounce = false)
            }
        }
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfosForUniqueWorkFlow(LibraryIndexWorker.UNIQUE_NAME).collect { work ->
                lastWork = work
                updateWorkPresentation()
                if (work.none { !it.state.isFinished }) search(debounce = false)
            }
        }
        viewModelScope.launch { graph.library.observeChanges().collect { refreshLibrary() } }
    }

    fun refreshLibrary() {
        refreshLocalizedText()
        accessRevision++
        searchJob?.cancel()
        refreshJob?.cancel()
        // Hide stale OCR/search/detail immediately while permissions are being revalidated.
        permissionChecked = false
        selectedMatchSource = null
        mutableState.update { it.copy(media = emptyList(), results = emptyList(), selectedItem = null,
            hasMediaPermission = false, totalCount = 0, indexedCount = 0) }
        refreshJob = viewModelScope.launch {
            try {
                val snapshot = graph.refreshLibrary()
                if (!snapshot.complete) error((RuntimeText.decode(snapshot.error) ?: runtimeText(RuntimeMessage.LIBRARY_FAILED)).encode())
                assets = graph.dao.assets()
                permissionChecked = true
                mutableState.update { it.copy(hasMediaPermission = snapshot.access != MediaAccess.NONE,
                    isLimitedAccess = snapshot.access == MediaAccess.LIMITED,
                    media = assets.map { it.toPhoto() }, totalCount = assets.size) }
                updateIndexedCount()
                search(debounce = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(RuntimeText.fromError(error, RuntimeMessage.LIBRARY_FAILED).encode()) }
        }
    }

    fun queryChanged(query: String) {
        queryRevision++
        attachmentJob?.cancel()
        queryVector = null
        attachmentLabel = null
        mutableState.update { it.copy(query = query, queryAttachmentLabel = null) }
        search()
    }
    fun filterChanged(filter: SearchFilter) { mutableState.update { it.copy(filter = filter) }; search(debounce = false) }
    fun selected(item: PhotoItem?) {
        selectedMatchSource = item?.let { matchSources[it.id] }
        mutableState.update { it.copy(selectedItem = item) }
    }
    fun clearError() {
        errorText = null
        errorResource = null
        mutableState.update { it.copy(error = null) }
    }
    fun showOnboarding() {
        clearError()
        savedState["onboarding_review"] = true
    }
    fun dismissOnboardingReview() { savedState["onboarding_review"] = false }
    fun completeOnboarding() {
        if (onboardingJob?.isActive == true) return
        onboardingJob = viewModelScope.launch {
            mutableState.update { it.copy(isCompletingOnboarding = true) }
            try {
                graph.onboarding.complete()
                savedState["onboarding_review"] = false
                clearError()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError(R.string.activity_onboarding_save_failed) }
            finally { mutableState.update { it.copy(isCompletingOnboarding = false) } }
        }
    }
    fun showError(message: String) {
        errorResource = null
        errorText = RuntimeText.decode(message) ?: runtimeText(RuntimeMessage.QUERY_FAILED)
        mutableState.update { it.copy(error = errorText?.render(localizedContext)) }
    }
    fun showError(@StringRes messageRes: Int) {
        errorText = null
        errorResource = messageRes
        mutableState.update { it.copy(error = localizedContext.getString(messageRes)) }
    }
    fun startIndexing() {
        if (state.value.hasMediaPermission) LibraryIndexWorker.enqueue(getApplication())
        else showError(runtimeText(RuntimeMessage.MEDIA_REQUIRED).encode())
    }
    fun cancelIndexing() { LibraryIndexWorker.cancel(getApplication()) }

    fun setVideoOcrEnabled(enabled: Boolean) {
        if (indexingPreferenceJob?.isActive == true) return
        indexingPreferenceJob = viewModelScope.launch {
            mutableState.update { it.copy(isSavingVideoOcrPreference = true) }
            try { graph.indexingPreferences.setVideoOcrEnabled(enabled) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError(R.string.ui_video_ocr_save_failed) }
            finally { mutableState.update { it.copy(isSavingVideoOcrPreference = false) } }
        }
    }

    fun downloadModel() { installModel { graph.models.downloadOfficial() } }
    fun importModel(uri: Uri) { installModel { graph.models.importModel(uri) } }
    fun cancelModelDownload() { modelJob?.cancel() }
    private fun installModel(action: suspend () -> Unit) {
        if (modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            // Installing a model does not implicitly authorize a potentially long library scan.
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(RuntimeText.fromError(error, RuntimeMessage.MODEL_INSTALL_FAILED).encode()) }
        }
    }

    fun imageQuery(uri: Uri) = attachmentQuery(runtimeText(RuntimeMessage.IMAGE_QUERY_LABEL)) {
        val bitmap = graph.library.imageBitmap(uri) ?: error(runtimeText(RuntimeMessage.IMAGE_UNREADABLE).encode())
        try { graph.engine.embedImage(bitmap) } finally { bitmap.recycle() }
    }
    fun audioQuery(uri: Uri) = attachmentQuery(runtimeText(RuntimeMessage.AUDIO_QUERY_LABEL)) {
        var vector: FloatArray? = null
        graph.audio.decode(uri, endMs = 10_000, overlapMs = 0) { chunk ->
            if (vector == null) vector = graph.engine.embedAudio(chunk.samples)
        }
        vector ?: error(runtimeText(RuntimeMessage.AUDIO_UNREADABLE).encode())
    }
    private fun attachmentQuery(label: RuntimeText, encode: suspend () -> FloatArray) {
        if (!graph.models.modelReady) { showError(runtimeText(RuntimeMessage.MULTIMODAL_REQUIRED).encode()); return }
        val revision = ++queryRevision
        searchJob?.cancel()
        attachmentJob?.cancel()
        queryVector = null
        attachmentLabel = label
        mutableState.update { it.copy(query = "", queryAttachmentLabel = label.render(localizedContext), isSearching = true, results = emptyList()) }
        attachmentJob = viewModelScope.launch {
            try {
                val vector = encode()
                if (revision == queryRevision) queryVector = vector
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(RuntimeText.fromError(error, RuntimeMessage.QUERY_FAILED).encode()); mutableState.update { it.copy(isSearching = false) } }
            finally {
                if (revision == queryRevision) {
                    attachmentJob = null
                    if (queryVector != null) search(debounce = false)
                }
            }
        }
    }

    private fun search(debounce: Boolean = true) {
        if (attachmentJob?.isActive == true) return
        searchJob?.cancel()
        if (!permissionChecked) return
        val current = state.value
        if (current.query.isBlank() && queryVector == null) {
            mutableState.update { it.copy(results = emptyList(), isSearching = false) }; return
        }
        searchJob = viewModelScope.launch {
            mutableState.update { it.copy(isSearching = true) }
            try {
                if (debounce) delay(300)
                if (state.value.query.isNotBlank()) {
                    queryVector = if (graph.models.modelReady) queryVector ?: graph.engine.embedQuery(state.value.query) else null
                }
                performSearch()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // OCR exact search remains useful even if the device cannot initialize inference.
                queryVector = null
                if (state.value.query.isNotBlank()) performSearch()
                showError(RuntimeText.fromError(error, RuntimeMessage.SEARCH_FAILED).encode())
                mutableState.update { it.copy(isSearching = false) }
            }
        }
    }

    private suspend fun performSearch() {
        val revision = accessRevision
        val currentAssets = assets
        val current = state.value
        val query = queryVector
        val result = withContext(Dispatchers.Default) {
            HybridSearch.search(current.query, query, currentAssets, graph.dao.segments(),
                SearchScope.valueOf(current.filter.name), ModelManager.MODEL_ID).map { hit ->
                val segment = hit.segment
                val match = runtimeText(when (segment?.modality) {
                    IndexModality.TEXT -> if (hit.exactMatch) RuntimeMessage.MATCH_TEXT_EXACT else RuntimeMessage.MATCH_TEXT_SEMANTIC
                    IndexModality.IMAGE -> RuntimeMessage.MATCH_IMAGE
                    IndexModality.VIDEO -> RuntimeMessage.MATCH_VIDEO
                    IndexModality.AUDIO -> RuntimeMessage.MATCH_AUDIO
                    null -> RuntimeMessage.MATCH_FILENAME
                })
                hit.asset.toPhoto().copy(matchSource = match.render(localizedContext),
                    matchText = segment?.text.orEmpty().take(240), timestampMillis = segment?.timestampMs?.takeIf { it >= 0 }) to match
            }
        }
        currentCoroutineContext().ensureActive()
        if (permissionChecked && revision == accessRevision) {
            matchSources = result.associate { it.first.id to it.second }
            // Render again on the main thread in case the app language changed during retrieval.
            mutableState.update { state -> state.copy(results = result.map { (photo, match) ->
                photo.copy(matchSource = match.render(localizedContext))
            }, isSearching = false) }
        }
    }

    /** Called after an app-language change; query text and embeddings are deliberately retained. */
    fun refreshLocalizedText() {
        updateModelPresentation()
        updateWorkPresentation()
        mutableState.update { current ->
            current.copy(
                queryAttachmentLabel = attachmentLabel?.render(localizedContext),
                error = errorResource?.let { localizedContext.getString(it) } ?: errorText?.render(localizedContext),
                results = current.results.map { photo ->
                    photo.copy(matchSource = matchSources[photo.id]?.render(localizedContext) ?: photo.matchSource)
                },
                selectedItem = current.selectedItem?.let { photo ->
                    photo.copy(matchSource = selectedMatchSource?.render(localizedContext) ?: photo.matchSource)
                },
            )
        }
    }

    private fun updateModelPresentation() {
        val model = graph.models.status.value
        val status = when (model.phase) {
            ModelPhase.READY -> ModelStatus.READY
            ModelPhase.ERROR -> ModelStatus.ERROR
            ModelPhase.MISSING -> ModelStatus.NOT_INSTALLED
            else -> ModelStatus.DOWNLOADING
        }
        mutableState.update { it.copy(modelStatus = status, modelProgress = model.progress,
            modelMessage = RuntimeText.renderStored(model.message, localizedContext, RuntimeMessage.MODEL_MISSING)) }
    }

    private fun updateWorkPresentation() {
        val active = lastWork.firstOrNull { !it.state.isFinished }
        if (active != null) observedWorkId = active.id
        val latest = lastWork.firstOrNull { it.id == observedWorkId }
        val message = when {
            // A system interruption can leave the previous running message in progress data.
            active?.state == WorkInfo.State.ENQUEUED -> runtimeText(RuntimeMessage.INDEX_WAITING).render(localizedContext)
            active != null && active.progress.getString("message") != null -> {
                val progress = RuntimeText.decode(active.progress.getString("message"))
                    ?: runtimeText(RuntimeMessage.INDEX_PREPARING)
                val failed = active.progress.getInt("failed", 0)
                if (failed > 0 && progress.key != RuntimeMessage.INDEX_PARTIAL) {
                    runtimeText(RuntimeMessage.INDEX_WITH_RETRIES, progress.render(localizedContext),
                        runtimeText(RuntimeMessage.INDEX_RETRY_COUNT, failed).render(localizedContext)).render(localizedContext)
                } else progress.render(localizedContext)
            }
            active != null -> runtimeText(RuntimeMessage.INDEX_PREPARING).render(localizedContext)
            latest?.state == WorkInfo.State.FAILED -> RuntimeText.renderStored(latest.outputData.getString("error"), localizedContext, RuntimeMessage.INDEX_FAILED)
            latest?.state == WorkInfo.State.CANCELLED -> runtimeText(RuntimeMessage.INDEX_CANCELLED).render(localizedContext)
            latest?.state == WorkInfo.State.SUCCEEDED -> {
                val failed = latest.outputData.getInt("failed", 0)
                if (failed > 0) runtimeText(RuntimeMessage.INDEX_PARTIAL, failed).render(localizedContext)
                else runtimeText(RuntimeMessage.INDEX_COMPLETE).render(localizedContext)
            }
            else -> runtimeText(RuntimeMessage.INDEX_IDLE).render(localizedContext)
        }
        mutableState.update { it.copy(isIndexing = active != null, indexStatus = message) }
    }

    private fun updateIndexedCount() {
        if (!permissionChecked) return
        val modelId = if (graph.models.modelReady) ModelManager.MODEL_ID else LibraryIndexWorker.OCR_MODEL_ID
        val videoUris = assets.asSequence().filter { it.isVideo }.map { it.uri }.toHashSet()
        mutableState.update { state -> state.copy(indexedCount = records.count { record ->
            record.state == IndexState.READY && record.modelId == modelId &&
                (record.assetUri !in videoUris || record.videoOcrEnabled == state.videoOcrEnabled)
        }) }
    }

    private fun MediaAsset.toPhoto() = PhotoItem(uri, uri, name, isVideo, durationMs, ocrText = ocrText)
}
