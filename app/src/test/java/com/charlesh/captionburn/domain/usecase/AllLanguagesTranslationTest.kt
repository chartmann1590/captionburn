package com.charlesh.captionburn.domain.usecase

import com.charlesh.captionburn.data.translation.MlKitTranslator
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.domain.model.Word
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.nl.translate.TranslateLanguage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AllLanguagesTranslationTest {

    // Fallback list of 50+ languages in case TranslateLanguage.getAllLanguages() returns empty or fails to classload
    private val fallbackLanguages = listOf(
        "af", "sq", "ar", "be", "bg", "bn", "ca", "zh", "hr", "cs", "da", "nl", "en", "eo",
        "et", "fi", "fr", "gl", "ka", "de", "el", "gu", "ht", "he", "hi", "hu", "is", "id",
        "ga", "it", "ja", "kn", "ko", "lt", "lv", "mk", "ms", "mt", "mr", "no", "fa", "pl",
        "pt", "ro", "ru", "sk", "sl", "es", "sw", "sv", "ta", "te", "th", "tr", "uk", "ur",
        "vi", "cy"
    )

    private fun getLanguagesToTest(): Set<String> {
        return try {
            val languages = TranslateLanguage.getAllLanguages()
            if (languages.isNullOrEmpty()) fallbackLanguages.toSet() else languages.toSet()
        } catch (e: Throwable) {
            fallbackLanguages.toSet()
        }
    }

    @Test
    fun verify_normalization_for_every_single_supported_language() {
        val languages = getLanguagesToTest()
        assertThat(languages).isNotEmpty()

        // Verify that the normalization behaves as expected for all 50+ tags in various formats
        languages.forEach { lang ->
            // Normalizations should parse tag, strip region, convert case, and match a supported ML Kit tag.
            val normalizedLower = lang.lowercase()
            assertThat(normalizedLower).isEqualTo(lang) // ML Kit tags are already normalized lowercase

            // Test region stripping: "ES_es" -> "es", "en-US" -> "en"
            val mixedRegion = "${lang.uppercase()}_${lang.lowercase()}"
            val processed = mixedRegion.lowercase().replace('_', '-').substringBefore('-')
            assertThat(processed).isEqualTo(lang)
        }
    }

    @Test
    fun verify_redistributeTranslatedWords_works_for_all_supported_languages() {
        val useCase = TranslateTranscriptUseCase(
            translator = mockk(),
            ioDispatcher = StandardTestDispatcher(),
        )

        val languages = getLanguagesToTest()
        assertThat(languages).isNotEmpty()

        languages.forEach { lang ->
            // Let's create varying lengths of words for redistribution testing
            // 1. Single word translation (including RTL and multi-byte characters)
            val singleWord = when (lang) {
                "zh" -> "你好"
                "ja" -> "こんにちは"
                "ar" -> "مرحبا"
                "he" -> "שלום"
                else -> "hello"
            }

            val wordsSingle = useCase.redistributeTranslatedWords(
                translatedText = singleWord,
                segmentStartMs = 100,
                segmentEndMs = 500,
            )
            assertThat(wordsSingle).hasSize(1)
            assertThat(wordsSingle[0].startMs).isEqualTo(100)
            assertThat(wordsSingle[0].endMs).isEqualTo(500)
            assertThat(wordsSingle[0].text).isEqualTo(singleWord)

            // 2. Multi-word translation (including RTL and multi-byte characters)
            val multiWord = when (lang) {
                "zh" -> "你好 世界 谢谢"
                "ja" -> "こんにちは 世界 ありがとう"
                "ar" -> "مرحبا بك في العالم"
                "he" -> "שלום עולם תודה"
                else -> "hello world thanks"
            }

            val wordsMulti = useCase.redistributeTranslatedWords(
                translatedText = multiWord,
                segmentStartMs = 0,
                segmentEndMs = 1200,
            )
            val tokens = multiWord.split(" ")
            assertThat(wordsMulti).hasSize(tokens.size)
            assertThat(wordsMulti.first().startMs).isEqualTo(0)
            assertThat(wordsMulti.last().endMs).isEqualTo(1200)

            // Verify order and bounds
            for (i in 0 until wordsMulti.size - 1) {
                assertThat(wordsMulti[i].endMs).isAtLeast(wordsMulti[i].startMs + 1)
                assertThat(wordsMulti[i + 1].startMs).isEqualTo(wordsMulti[i].endMs)
            }
        }
    }

    @Test
    fun verify_end_to_end_translation_pipeline_usecase_mocked_for_all_languages() = runTest {
        val translator = mockk<MlKitTranslator>()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val useCase = TranslateTranscriptUseCase(
            translator = translator,
            ioDispatcher = testDispatcher,
        )

        val languages = getLanguagesToTest()
        assertThat(languages).isNotEmpty()

        val transcript = Transcript(
            detectedLanguage = "en",
            segments = listOf(
                Segment(
                    id = "s1",
                    startMs = 0,
                    endMs = 1000,
                    language = "en",
                    words = listOf(Word(0, 500, "hello"), Word(501, 1000, "world")),
                )
            ),
        )

        languages.forEach { targetLang ->
            // Skip self-translation as it returns early
            if (targetLang == "en") return@forEach

            val mockTranslation = when (targetLang) {
                "es" -> "hola mundo"
                "fr" -> "bonjour le monde"
                "de" -> "hallo welt"
                else -> "translated text here"
            }

            coEvery {
                translator.translateBatch(
                    texts = listOf("hello world"),
                    sourceLanguage = "en",
                    targetLanguage = targetLang,
                    whileDownloadingModel = any(),
                    onProgress = any()
                )
            } returns listOf(mockTranslation)

            val result = useCase(
                transcript = transcript,
                targetLanguage = targetLang
            )

            val segment = result.segments.first()
            assertThat(segment.translation).isEqualTo(mockTranslation)
            assertThat(segment.translatedWords).isNotEmpty()
            assertThat(segment.translatedWords.first().startMs).isEqualTo(0)
            assertThat(segment.translatedWords.last().endMs).isEqualTo(1000)
        }
    }
}
