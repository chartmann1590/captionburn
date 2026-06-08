package com.charlesh.captionburn.data.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealLanguagesTranslationTest {

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

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var translator: MlKitTranslator

    @Before
    fun setup() {
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        settingsRepository = SettingsRepository(appCtx)
        translator = MlKitTranslator(
            context = appCtx,
            settingsRepository = settingsRepository,
            telemetry = noOpTelemetry
        )
    }

    private fun testTranslationForLanguage(
        sourceLang: String,
        targetLang: String,
        sampleText: String
    ) {
        runBlocking {
            // Disable WiFi-only restriction for testing so it can download over standard test connection
            settingsRepository.setWifiOnly(false)

            android.util.Log.i("RealLanguagesTranslationTest", "Starting real translation from $sourceLang to $targetLang")
            val startTime = System.currentTimeMillis()
            val result = translator.translate(
                text = sampleText,
                sourceLanguage = sourceLang,
                targetLanguage = targetLang
            )
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.i(
                "RealLanguagesTranslationTest",
                "Translated '$sampleText' to '$result' ($targetLang) in ${duration}ms"
            )

            assertThat(result).isNotEmpty()
            assertThat(result.lowercase()).isNotEqualTo(sampleText.lowercase())
        }
    }

    @Test
    fun translate_spanish() {
        testTranslationForLanguage("en", "es", "Hello world, welcome to CaptionBurn!")
    }

    @Test
    fun translate_french() {
        testTranslationForLanguage("en", "fr", "Hello world, welcome to CaptionBurn!")
    }

    @Test
    fun translate_german() {
        testTranslationForLanguage("en", "de", "Hello world, welcome to CaptionBurn!")
    }

    @Test
    fun translate_chinese() {
        testTranslationForLanguage("en", "zh", "Hello world, welcome to CaptionBurn!")
    }

    @Test
    fun translate_japanese() {
        testTranslationForLanguage("en", "ja", "Hello world, welcome to CaptionBurn!")
    }
}
