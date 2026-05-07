package com.charlesh.captionburn.data.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperEngineInstrumentedTest {

    @Test
    fun transcribe_fixture_validates_language_word_count_and_word_durations() {
        runBlocking<Unit> {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelSpec = WhisperModelCatalog.Base
        val modelFile = File(appCtx.filesDir, "models/${modelSpec.filename}")
        assumeTrue("Install base model before running this test", modelFile.exists())

        val wav = runCatching {
            copyAssetToFiles(
                assetName = "whisper_sample_30s_en.wav",
                outName = "androidTest-whisper-sample.wav",
            )
        }.getOrNull()
        assumeTrue("Place app/src/androidTest/assets/whisper_sample_30s_en.wav", wav != null && wav.exists())

        val engine = WhisperEngine(
            io = Dispatchers.IO,
            json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
        try {
            engine.ensureLoaded(modelFile.absolutePath)
            val transcript = engine.transcribe(
                wavPath = wav!!.absolutePath,
                languageHint = "en",
                nThreads = 0,
            ).getOrThrow()

            val words = transcript.segments.flatMap { it.words }
            assertThat(transcript.detectedLanguage).isEqualTo("en")
            assertThat(words.size).isAtLeast(50)

            // Plan acceptance: per-word duration positive and not absurdly long.
            words.forEach { w ->
                assertThat(w.endMs - w.startMs).isGreaterThan(0L)
                assertThat(w.endMs - w.startMs).isLessThan(2_000L)
            }

            // Keep tolerance broad because spoken fixture can evolve.
            assertThat(abs(words.size - 90)).isAtMost(45)
        } finally {
            engine.close()
        }
        }
    }

    private fun copyAssetToFiles(assetName: String, outName: String): File {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(appCtx.filesDir, outName)
        appCtx.assets.open(assetName).use { input ->
            out.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }
        return out
    }
}
