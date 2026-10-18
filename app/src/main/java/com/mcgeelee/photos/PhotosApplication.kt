package com.mcgeelee.photos

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.mcgeelee.photos.data.IndexingPreferences
import com.mcgeelee.photos.data.MediaLibrary
import com.mcgeelee.photos.data.OnboardingPreferences
import com.mcgeelee.photos.data.PhotoDatabase
import com.mcgeelee.photos.data.SearchPreferences
import com.mcgeelee.photos.indexing.IndexStorageWorker
import com.mcgeelee.photos.ml.AudioDecoder
import com.mcgeelee.photos.ml.GemmaEmbeddingEngine
import com.mcgeelee.photos.ml.ModelManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotosApplication : Application(), SingletonImageLoader.Factory {
    val graph by lazy { AppGraph(this) }
    override fun newImageLoader(context: Context) = ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }.build()
}

class AppGraph(context: Context) {
    val onboarding = OnboardingPreferences(context)
    val indexingPreferences = IndexingPreferences(context)
    val searchPreferences = SearchPreferences(context)
    val database = PhotoDatabase.create(context)
    val dao = database.dao()
    val library = MediaLibrary(context)
    val models = ModelManager(context)
    val engine = GemmaEmbeddingEngine(context, models)
    val audio = AudioDecoder(context)
    private val scanMutex = Mutex()
    init { IndexStorageWorker.enqueue(context) }
    suspend fun refreshLibrary() = scanMutex.withLock {
        val snapshot = library.scan()
        dao.reconcileSnapshot(snapshot)
        snapshot
    }
}
