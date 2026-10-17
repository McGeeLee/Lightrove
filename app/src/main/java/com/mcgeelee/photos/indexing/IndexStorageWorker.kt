package com.mcgeelee.photos.indexing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mcgeelee.photos.PhotosApplication
import com.mcgeelee.photos.data.IndexStorageMaintenance
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Short background batches compact existing vectors without decoding media or running ML. */
class IndexStorageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val database = (applicationContext as PhotosApplication).graph.database
        val result = IndexStorageMaintenance(applicationContext, database).run()
        if (result.retry) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Busy readers/writers or temporary IO problems do not fail the user's indexing task.
        Result.retry()
    }

    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
            "index-storage-compaction-v1",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexStorageWorker>()
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
