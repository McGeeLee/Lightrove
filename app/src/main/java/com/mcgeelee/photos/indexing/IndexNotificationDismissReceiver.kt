package com.mcgeelee.photos.indexing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID

/** Dismissing the live update changes visibility only; indexing keeps running. */
class IndexNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IndexNotification.ACTION_DISMISS) return
        val workId = intent.data?.lastPathSegment?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        IndexNotification.recordDismissal(context, workId)
    }
}
