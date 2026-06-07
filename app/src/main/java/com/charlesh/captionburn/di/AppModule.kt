package com.charlesh.captionburn.di

import android.content.Context
import com.charlesh.captionburn.data.translation.MlKitTranslator
import com.charlesh.captionburn.domain.usecase.TranslateTranscriptUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // resumable downloads can be long
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton @AppFiles
    fun provideAppFilesDir(@ApplicationContext ctx: Context): java.io.File = ctx.filesDir

    @Provides
    fun provideTranslateTranscriptUseCase(
        translator: MlKitTranslator,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): TranslateTranscriptUseCase = TranslateTranscriptUseCase(
        translator = translator,
        ioDispatcher = ioDispatcher,
    )

    @Provides @Singleton
    fun provideTelemetryTracker(
        telemetryManager: com.charlesh.captionburn.data.telemetry.TelemetryManager
    ): com.charlesh.captionburn.data.telemetry.TelemetryTracker = telemetryManager
}
