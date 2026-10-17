package com.mcgeelee.photos.indexing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.mcgeelee.photos.MainActivity
import com.mcgeelee.photos.R
import com.mcgeelee.photos.localization.AppLanguage
import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.RuntimeText
import com.mcgeelee.photos.localization.runtimeText
import java.text.NumberFormat
import java.util.UUID

/** Notifications for the explicitly user-started library indexing operation. */
object IndexNotification {
    const val ID = 101
    const val CHANNEL = "library-index"
    private const val PREFERENCES = "index-notifications"
    private const val DISMISSED_WORK = "dismissed-work"
    internal const val ACTION_DISMISS = "com.mcgeelee.photos.DISMISS_INDEX_NOTIFICATION"

    fun build(
        context: Context,
        workId: UUID,
        message: RuntimeText,
        completed: Int = 0,
        total: Int = 0,
        failed: Int = 0,
    ): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            runtimeText(RuntimeMessage.INDEX_CHANNEL).render(context), NotificationManager.IMPORTANCE_LOW))
        // Failures are already attempted files too; use Long to avoid overflowing bad/stale input.
        val processed = (completed.coerceAtLeast(0).toLong() + failed.coerceAtLeast(0))
            .coerceAtMost(if (total > 0) total.toLong() else Int.MAX_VALUE.toLong()).toInt()
        val active = message.key in setOf(RuntimeMessage.INDEX_PREPARING, RuntimeMessage.INDEX_PROCESSING,
            RuntimeMessage.INDEX_FRAME, RuntimeMessage.INDEX_AUDIO) && (total <= 0 || processed < total)
        val title = runtimeText(RuntimeMessage.INDEX_NOTIFICATION).render(context)
        val content = if (failed > 0 && message.key != RuntimeMessage.INDEX_PARTIAL) {
            runtimeText(RuntimeMessage.INDEX_WITH_RETRIES, message.render(context),
                runtimeText(RuntimeMessage.INDEX_RETRY_COUNT, failed).render(context)).render(context)
        } else message.render(context)
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_index_notification)
            .setContentTitle(title)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_index_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openApp)
            .setDeleteIntent(PendingIntent.getBroadcast(context, 0, dismissIntent(context, workId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            // AndroidX uses platform ProgressStyle on API 36+, and setProgress below API 36.
            .setStyle(NotificationCompat.ProgressStyle()
                .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(total.coerceAtLeast(1))))
                .setProgress(if (total > 0) processed else 0)
                .setProgressIndeterminate(total <= 0 && active))
        if (total > 0) {
            val locale = AppLanguage.localizedContext(context).resources.configuration.locales[0]
            builder.setSubText("$processed / $total")
                .setShortCriticalText(NumberFormat.getPercentInstance(locale).format(processed.toDouble() / total))
        }
        if (active || message.key == RuntimeMessage.INDEX_WAITING) {
            builder.addAction(android.R.drawable.ic_media_pause, runtimeText(RuntimeMessage.STOP).render(context),
                WorkManager.getInstance(context).createCancelPendingIntent(workId))
        }
        // Permission can be revoked in Settings. Always keep a normal foreground notification
        // available, and never reopen Settings or re-promote a run the user dismissed.
        builder.setRequestPromotedOngoing(Build.VERSION.SDK_INT >= 36 && active &&
            !wasDismissed(context, workId) && manager.canPostPromotedNotifications())
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(ID, builder.build(), type)
    }

    internal fun dismissIntent(context: Context, workId: UUID): Intent =
        Intent(context, IndexNotificationDismissReceiver::class.java)
            .setAction(ACTION_DISMISS)
            // PendingIntent identity includes data, so a late dismissal cannot affect a newer run.
            .setData(Uri.Builder().scheme("lightrove").authority("index-notification")
                .appendPath(workId.toString()).build())

    internal fun wasDismissed(context: Context, workId: UUID): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(DISMISSED_WORK, emptySet()).orEmpty().contains(workId.toString())

    @Synchronized
    internal fun recordDismissal(context: Context, workId: UUID) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val dismissed = preferences.getStringSet(DISMISSED_WORK, emptySet()).orEmpty() + workId.toString()
        preferences.edit().putStringSet(DISMISSED_WORK, dismissed).apply()
    }
}
