package com.charlesh.captionburn

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.charlesh.captionburn.data.logging.LocalFileLoggingTree
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import com.google.android.gms.ads.MobileAds
import timber.log.Timber

@HiltAndroidApp
class CaptionBurnApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ADMOB_APP_ID.isNotBlank()) {
            MobileAds.initialize(this)
        }
        val localTree = LocalFileLoggingTree(logDir = File(filesDir, "logs"))
        Timber.plant(localTree)
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
