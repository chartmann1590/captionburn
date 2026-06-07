package com.charlesh.captionburn

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.charlesh.captionburn.data.logging.LocalFileLoggingTree
import com.charlesh.captionburn.data.logging.TelemetryTimberTree
import com.charlesh.captionburn.data.telemetry.TelemetryTracker
import com.charlesh.captionburn.data.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import com.google.android.gms.ads.MobileAds
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class CaptionBurnApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var telemetryTracker: TelemetryTracker
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ADMOB_APP_ID.isNotBlank()) {
            MobileAds.initialize(this)
        }
        val localTree = LocalFileLoggingTree(logDir = File(filesDir, "logs"))
        Timber.plant(localTree)
        Timber.plant(TelemetryTimberTree(telemetryTracker))
        
        // Retrieve or generate anonymous UUID and register user context asynchronously
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val anonId = settingsRepository.getOrCreateAnonymousId()
            telemetryTracker.setUserId(anonId)
            telemetryTracker.setUserProperty("anonymous_user_id", anonId)
            
            // Bind WiFi download constraint updates dynamically as a User Property
            settingsRepository.wifiOnlyDownloads.collect { wifiOnly ->
                telemetryTracker.setUserProperty("wifi_only_downloads", wifiOnly.toString())
            }
        }
        scope.launch {
            // Bind model choice updates dynamically as a User Property
            settingsRepository.installedModel.collect { model ->
                telemetryTracker.setUserProperty("installed_model", model?.name ?: "None")
            }
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            localTree.logCrash(thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
