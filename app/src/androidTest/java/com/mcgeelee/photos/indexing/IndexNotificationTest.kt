package com.mcgeelee.photos.indexing

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mcgeelee.photos.MainActivity
import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.runtimeText
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val message = runtimeText(RuntimeMessage.INDEX_PROCESSING, "private-family-photo.jpg")

    @Test fun progressCountsFailuresAndUsesNativeOrLegacyStyleForTheDevice() {
        val info = IndexNotification.build(context, UUID.randomUUID(), message, completed = 3, total = 10, failed = 2)
        val notification = info.notification
        assertEquals(101, info.notificationId)
        assertEquals("library-index", notification.channelId)
        assertEquals(if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals("5 / 10", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertProgress(notification, expectedProgress = 5, expectedTotal = 10, indeterminate = false)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        val allowed = Build.VERSION.SDK_INT >= 36 &&
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        assertEquals(allowed, NotificationCompat.isRequestPromotedOngoing(notification))
        if (Build.VERSION.SDK_INT >= 36 && allowed) assertTrue(notification.hasPromotableCharacteristics())
    }

    @Test fun unknownTotalDoesNotInventAPercentage() {
        val notification = IndexNotification.build(context, UUID.randomUUID(),
            runtimeText(RuntimeMessage.INDEX_PREPARING)).notification
        assertProgress(notification, expectedProgress = 0, expectedTotal = 1, indeterminate = true)
        assertNull(NotificationCompat.getShortCriticalText(notification))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    }

    @Test fun invalidCountsAreClampedAndCannotOverflow() {
        val negative = IndexNotification.build(context, UUID.randomUUID(), message, -3, 10, -2).notification
        assertProgress(negative, expectedProgress = 0, expectedTotal = 10, indeterminate = false)
        val overflow = IndexNotification.build(context, UUID.randomUUID(), message,
            Int.MAX_VALUE, 10, Int.MAX_VALUE).notification
        assertProgress(overflow, expectedProgress = 10, expectedTotal = 10, indeterminate = false)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(overflow))
    }

    @Test fun waitingAndFinishedWorkAreNeverPromoted() {
        val states = listOf(RuntimeMessage.INDEX_WAITING, RuntimeMessage.INDEX_IDLE,
            RuntimeMessage.INDEX_COMPLETE, RuntimeMessage.INDEX_CANCELLED, RuntimeMessage.INDEX_FAILED)
        states.forEach { state ->
            val notification = IndexNotification.build(context, UUID.randomUUID(), runtimeText(state), 3, 10).notification
            assertFalse(state.name, NotificationCompat.isRequestPromotedOngoing(notification))
            assertEquals(state.name, 0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        }
        val partial = IndexNotification.build(context, UUID.randomUUID(),
            runtimeText(RuntimeMessage.INDEX_PARTIAL, 2), 8, 10, 2).notification
        assertFalse(NotificationCompat.isRequestPromotedOngoing(partial))
    }

    @Test fun lockScreenPublicVersionContainsNoFileNameAndActionsAreScopedCorrectly() {
        val workId = UUID.randomUUID()
        val notification = IndexNotification.build(context, workId, message, 3, 10).notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            .contains("private-family-photo.jpg"))
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        assertEquals(runtimeText(RuntimeMessage.INDEX_NOTIFICATION).render(context),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(publicVersion.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertTrue(notification.contentIntent.isActivity)
        assertTrue(notification.contentIntent.isImmutable)
        val expectedOpenApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(expectedOpenApp)
        assertEquals(expectedOpenApp, notification.contentIntent)
        assertEquals(WorkManager.getInstance(context).createCancelPendingIntent(workId),
            notification.actions.single().actionIntent)
        assertTrue(notification.deleteIntent.isBroadcast)
        assertTrue(notification.deleteIntent.isImmutable)
    }

    @Test fun dismissalPersistsForThisRunWithoutCancellingWorkOrAffectingAnotherRun() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<NotificationTestWorker>()
            .setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
        try {
            val notification = IndexNotification.build(context, request.id, message, 3, 10).notification
            val anotherId = UUID.randomUUID()
            val another = IndexNotification.build(context, anotherId, message, 1, 10).notification
            assertNotEquals(notification.deleteIntent, another.deleteIntent)
            notification.deleteIntent.send()
            withTimeout(5_000) {
                while (!IndexNotification.wasDismissed(context, request.id)) delay(10)
            }
            // Rebuild from another Context instance, as happens after a worker/process restart.
            val reopenedContext = context.createPackageContext(context.packageName, 0)
            assertTrue(IndexNotification.wasDismissed(reopenedContext, request.id))
            assertFalse(IndexNotification.wasDismissed(reopenedContext, anotherId))
            val updated = IndexNotification.build(reopenedContext, request.id, message, 4, 10).notification
            assertFalse(NotificationCompat.isRequestPromotedOngoing(updated))
            assertTrue(updated.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state)
        } finally {
            manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
        }
    }

    private fun assertProgress(notification: Notification, expectedProgress: Int, expectedTotal: Int,
        indeterminate: Boolean) {
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.Builder.recoverBuilder(context, notification).style
            assertTrue(style is Notification.ProgressStyle)
            style as Notification.ProgressStyle
            assertEquals(expectedProgress, style.progress)
            assertEquals(expectedTotal, style.progressMax)
            assertEquals(indeterminate, style.isProgressIndeterminate)
        } else {
            assertEquals(expectedProgress, notification.extras.getInt(Notification.EXTRA_PROGRESS))
            assertEquals(expectedTotal, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(indeterminate, notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertNotEquals("android.app.Notification\$ProgressStyle",
                notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertFalse(NotificationCompat.isRequestPromotedOngoing(notification))
        }
    }
}

/** Never runs during the test; a delayed no-op lets us detect accidental work cancellation. */
class NotificationTestWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}
