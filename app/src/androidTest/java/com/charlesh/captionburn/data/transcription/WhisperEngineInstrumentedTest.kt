package com.charlesh.captionburn.data.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperEngineInstrumentedTest {

    private val noOpTelemetry = object : com.charlesh.captionburn.data.telemetry.TelemetryTracker {
        override fun isTelemetryEnabled(): Boolean = false
        override fun logEvent(name: String, params: Map<String, Any?>) {}
        override fun recordException(throwable: Throwable) {}
        override fun logBreadcrumb(message: String) {}
        override fun setCustomKey(key: String, value: String) {}
        override fun setCustomKey(key: String, value: Boolean) {}
        override fun setUserId(userId: String) {}
        override fun setUserProperty(name: String, value: String) {}
        override fun startTrace(name: String): com.charlesh.captionburn.data.telemetry.PerformanceTraceTracker =
            object : com.charlesh.captionburn.data.telemetry.PerformanceTraceTracker {
                override fun putAttribute(name: String, value: String) {}
                override fun incrementMetric(name: String, value: Long) {}
                override fun stop() {}
            }
    }

    private suspend fun ensureModelInstalled(spec: WhisperModelSpec) {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val downloader = ModelDownloader(
            client = OkHttpClient(),
            filesDir = appCtx.filesDir,
            io = Dispatchers.IO,
            telemetry = noOpTelemetry
        )
        if (!downloader.isInstalled(spec)) {
            downloader.download(spec).collect { event ->
                if (event is DownloadEvent.Failed) {
                    throw event.cause
                }
            }
        }
    }

    private fun runTranscriptionTestForModel(spec: WhisperModelSpec) {
        runBlocking {
            val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
            ensureModelInstalled(spec)

            val modelFile = File(appCtx.filesDir, "models/${spec.filename}")
            assertThat(modelFile.exists()).isTrue()

            val wav = copyAssetToFiles(
                assetName = "whisper_sample_30s_en.wav",
                outName = "androidTest-whisper-sample-${spec.id}.wav",
            )
            assertThat(wav.exists()).isTrue()

            val engine = WhisperEngine(
                io = Dispatchers.IO,
                json = Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
            try {
                engine.ensureLoaded(modelFile.absolutePath)
                val startTime = System.currentTimeMillis()
                val transcript = engine.transcribe(
                    wavPath = wav.absolutePath,
                    languageHint = "en",
                    nThreads = 0,
                ).getOrThrow()
                val duration = System.currentTimeMillis() - startTime
                android.util.Log.i("WhisperEngineInstrumentedTest", "Transcribed using ${spec.id} model in ${duration}ms")

                val words = transcript.segments.flatMap { it.words }
                assertThat(transcript.detectedLanguage).isEqualTo("en")
                assertThat(words).isNotEmpty()

                // Sanity check word timings: endMs > startMs and not absurdly long
                words.forEach { w ->
                    assertThat(w.endMs).isGreaterThan(w.startMs)
                    assertThat(w.endMs - w.startMs).isLessThan(5000L)
                }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun transcribe_tiny_model() {
        runTranscriptionTestForModel(WhisperModelCatalog.Tiny)
    }

    @Test
    fun transcribe_base_model() {
        runTranscriptionTestForModel(WhisperModelCatalog.Base)
    }

    @Test
    fun transcribe_small_model() {
        runTranscriptionTestForModel(WhisperModelCatalog.Small)
    }

    private fun copyAssetToFiles(assetName: String, outName: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(appCtx.filesDir, outName)
        testContext.assets.open(assetName).use { input ->
            out.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }
        return out
    }
}
