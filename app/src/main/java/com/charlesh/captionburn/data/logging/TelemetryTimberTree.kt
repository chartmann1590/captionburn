package com.charlesh.captionburn.data.logging

import android.util.Log
import com.charlesh.captionburn.data.telemetry.TelemetryTracker
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Timber Tree that automatically forwards logs (INFO and above) to Firebase Crashlytics.
 */
class TelemetryTimberTree(
    private val telemetryTracker: TelemetryTracker
) : Timber.Tree() {

    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        // Only log Info, Warn, and Error levels to Crashlytics to avoid bloat.
        return priority >= Log.INFO
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!telemetryTracker.isTelemetryEnabled()) return

        val priorityStr = when (priority) {
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "U"
        }

        // Write breadcrumb log to Crashlytics
        val formattedMessage = "$priorityStr/${tag ?: "CaptionBurn"}: $message"
        crashlytics.log(formattedMessage)

        // For warnings or errors, record exceptions
        if (priority >= Log.WARN) {
            if (t != null) {
                crashlytics.recordException(t)
            } else if (priority == Log.ERROR) {
                // Record synthetic exception for errors without throwables to ensure we get stacks in console
                crashlytics.recordException(Exception("Synthetic error: $message"))
            }
        }
    }
}
