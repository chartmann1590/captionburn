package com.charlesh.captionburn.service.workers

import androidx.work.CoroutineWorker
import com.charlesh.captionburn.service.ProcessingNotifications

/**
 * Promote the worker to a foreground service with the shared processing notification.
 *
 * Wrapped in [runCatching] because starting a foreground service can be disallowed
 * on Android 12+ when the work happens to begin while the app is in the background
 * (`ForegroundServiceStartNotAllowedException`). In that case we let the work keep
 * running without the notification rather than crashing the pipeline.
 */
suspend fun CoroutineWorker.updateProcessingForeground(stage: String?, progress: Float) {
    runCatching {
        setForeground(ProcessingNotifications.foregroundInfo(applicationContext, id, stage, progress))
    }
}

/** Non-suspending variant for use inside progress callbacks. */
fun CoroutineWorker.updateProcessingForegroundAsync(stage: String?, progress: Float) {
    runCatching {
        setForegroundAsync(ProcessingNotifications.foregroundInfo(applicationContext, id, stage, progress))
    }
}
