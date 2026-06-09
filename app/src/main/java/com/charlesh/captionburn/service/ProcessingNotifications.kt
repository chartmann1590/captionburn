package com.charlesh.captionburn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.charlesh.captionburn.MainActivity
import com.charlesh.captionburn.R
import java.util.UUID

/**
 * Single source of truth for the long-running pipeline notification.
 *
 * The pipeline runs entirely inside WorkManager workers, which promote
 * themselves to a foreground service via [androidx.work.CoroutineWorker.setForeground].
 * WorkManager owns the foreground service lifecycle, so there is no separate
 * [android.app.Service] to start (and therefore no `startForegroundService` call
 * that could throw `ForegroundServiceStartNotAllowedException` on Android 14+).
 */
object ProcessingNotifications {
    const val CHANNEL_ID = "captionburn.processing"
    const val NOTIF_ID = 1001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_processing),
                    NotificationManager.IMPORTANCE_LOW,
                )
                mgr.createNotificationChannel(ch)
            }
        }
    }

    /** Maps internal pipeline stage keys to user-facing wording. */
    fun friendlyStage(ctx: Context, stage: String?): String = when (stage) {
        "extract-audio" -> "Extracting audio"
        "load-model" -> "Loading AI model"
        "transcribe" -> "Transcribing audio"
        "download-translation-model" -> "Downloading translation model"
        "translate" -> "Translating captions"
        "build-subtitles" -> "Building subtitles"
        "burn-publish" -> "Burning captions into video"
        else -> ctx.getString(R.string.notif_processing_title)
    }

    /**
     * Foreground info for a running worker. Includes live stage + percent and a
     * Cancel action wired to WorkManager so the user can stop processing from the
     * notification shade.
     */
    fun foregroundInfo(
        ctx: Context,
        workId: UUID,
        stage: String? = null,
        progress: Float = 0f,
    ): ForegroundInfo {
        ensureChannel(ctx)

        val title = if (stage == null) {
            ctx.getString(R.string.notif_processing_title)
        } else {
            val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
            "${friendlyStage(ctx, stage)} ($percent%)"
        }

        val tap = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(workId)

        val notification: Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.notif_processing_body))
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, ctx.getString(R.string.notif_processing_cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }
}
