package com.charlesh.captionburn.data.telemetry

import android.content.Context
import android.os.Bundle
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decoupled interface for telemetry operations to avoid loading Firebase classes on the JVM classpath during unit tests.
 */
interface TelemetryTracker {
    fun isTelemetryEnabled(): Boolean
    fun logEvent(name: String, params: Map<String, Any?> = emptyMap())
    fun recordException(throwable: Throwable)
    fun logBreadcrumb(message: String)
    fun setCustomKey(key: String, value: String)
    fun setCustomKey(key: String, value: Boolean)
    fun setUserId(userId: String)
    fun setUserProperty(name: String, value: String)
    fun startTrace(name: String): PerformanceTraceTracker
}

interface PerformanceTraceTracker {
    fun putAttribute(name: String, value: String)
    fun incrementMetric(name: String, value: Long = 1)
    fun stop()
}

@Singleton
class TelemetryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : TelemetryTracker {
    private val analytics = FirebaseAnalytics.getInstance(context)
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val performance = FirebasePerformance.getInstance()

    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var telemetryEnabled = true

    init {
        scope.launch {
            settings.telemetryEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    telemetryEnabled = enabled
                    syncTelemetryState(enabled)
                }
        }
    }

    override fun isTelemetryEnabled(): Boolean = telemetryEnabled

    private fun syncTelemetryState(enabled: Boolean) {
        Timber.d("Syncing telemetry collection state: enabled = $enabled")
        analytics.setAnalyticsCollectionEnabled(enabled)
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
        performance.isPerformanceCollectionEnabled = enabled
    }

    override fun logEvent(name: String, params: Map<String, Any?>) {
        if (!telemetryEnabled) return
        val bundle = Bundle().apply {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Boolean -> putBoolean(key, value)
                    null -> putString(key, null)
                    else -> putString(key, value.toString())
                }
            }
        }
        analytics.logEvent(name, bundle)
    }

    override fun recordException(throwable: Throwable) {
        if (!telemetryEnabled) return
        // Update device system resource metrics immediately prior to exception logging
        updateDeviceDiagnostics()
        crashlytics.recordException(throwable)
    }

    override fun logBreadcrumb(message: String) {
        if (!telemetryEnabled) return
        crashlytics.log(message)
    }

    override fun setCustomKey(key: String, value: String) {
        if (!telemetryEnabled) return
        crashlytics.setCustomKey(key, value)
    }

    override fun setCustomKey(key: String, value: Boolean) {
        if (!telemetryEnabled) return
        crashlytics.setCustomKey(key, value)
    }

    override fun setUserId(userId: String) {
        if (!telemetryEnabled) return
        crashlytics.setUserId(userId)
        analytics.setUserId(userId)
    }

    override fun setUserProperty(name: String, value: String) {
        if (!telemetryEnabled) return
        analytics.setUserProperty(name, value)
    }

    override fun startTrace(name: String): PerformanceTraceTracker {
        val trace = if (telemetryEnabled) performance.newTrace(name) else null
        trace?.start()
        return FirebasePerformanceTrace(trace)
    }

    /**
     * Captures free storage space, free memory, and network state dynamically.
     */
    private fun updateDeviceDiagnostics() {
        try {
            // Disk Space Diagnostics
            val stat = android.os.StatFs(context.filesDir.path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            val megabytesAvailable = bytesAvailable / (1024 * 1024)
            setCustomKey("disk_free_mb", megabytesAvailable.toString())

            // RAM Diagnostics
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (activityManager != null) {
                val memInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                val ramAvailable = memInfo.availMem / (1024 * 1024)
                setCustomKey("ram_free_mb", ramAvailable.toString())
                setCustomKey("ram_low_mode", memInfo.lowMemory.toString())
            }
        } catch (t: Throwable) {
            Timber.w(t, "Could not resolve device diagnostics")
        }
    }
}

class FirebasePerformanceTrace(private val trace: Trace?) : PerformanceTraceTracker {
    override fun putAttribute(name: String, value: String) {
        trace?.putAttribute(name, value)
    }

    override fun incrementMetric(name: String, value: Long) {
        trace?.incrementMetric(name, value)
    }

    override fun stop() {
        trace?.stop()
    }
}
