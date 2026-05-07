package com.charlesh.captionburn.domain.usecase

import com.charlesh.captionburn.data.translation.MlKitTranslator
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.domain.model.Word
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TranslateTranscriptUseCaseTest {

    @Test
    fun redistributeTranslatedWords_singleToken_spansFullSegment() {
        val useCase = TranslateTranscriptUseCase(
            translator = mockk(),
            ioDispatcher = StandardTestDispatcher(),
        )

        val words = useCase.redistributeTranslatedWords(
            translatedText = "hola",
            segmentStartMs = 1_000,
            segmentEndMs = 2_000,
        )

        assertThat(words).hasSize(1)
        assertThat(words.first().startMs).isEqualTo(1_000)
        assertThat(words.first().endMs).isEqualTo(2_000)
        assertThat(words.first().text).isEqualTo("hola")
    }

    @Test
    fun redistributeTranslatedWords_rounding_keepsLastWordAtSegmentEnd() {
        val useCase = TranslateTranscriptUseCase(
            translator = mockk(),
            ioDispatcher = StandardTestDispatcher(),
        )

        val words = useCase.redistributeTranslatedWords(
            translatedText = "uno dos tres",
            segmentStartMs = 0,
            segmentEndMs = 1_000,
        )

        assertThat(words).hasSize(3)
        assertThat(words.last().endMs).isEqualTo(1_000)
        assertThat(words.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }).isTrue()
    }

    @Test
    fun withTranslation_blankText_setsEmptyTranslatedWords() {
        val useCase = TranslateTranscriptUseCase(
            translator = mockk(),
            ioDispatcher = StandardTestDispatcher(),
        )
        val segment = Segment(
            id = "s1",
            startMs = 10,
            endMs = 110,
            language = "es",
            words = listOf(Word(10, 110, "hola")),
        )

        val updated = useCase.run { segment.withTranslation("  ") }

        assertThat(updated.translation).isEqualTo("  ")
        assertThat(updated.translatedWords).isEmpty()
    }

    @Test
    fun invoke_translatesSegments_andReportsProgress() = runTest {
        val translator = mockk<MlKitTranslator>()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val useCase = TranslateTranscriptUseCase(
            translator = translator,
            ioDispatcher = testDispatcher,
        )
        val transcript = Transcript(
            detectedLanguage = "es",
            segments = listOf(
                Segment(
                    id = "s1",
                    startMs = 0,
                    endMs = 1_000,
                    language = "es",
                    words = listOf(Word(0, 300, "hola"), Word(301, 1_000, "mundo")),
                ),
                Segment(
                    id = "s2",
                    startMs = 1_001,
                    endMs = 2_000,
                    language = "es",
                    words = listOf(Word(1_001, 2_000, "gracias")),
                ),
            ),
        )
        val progress = mutableListOf<TranslateProgress>()

        coEvery {
            translator.translateBatch(any(), any(), any(), any(), any())
        } coAnswers {
            val callback = arg<((Int, Int) -> Unit)?>(4)
            callback?.invoke(1, 2)
            callback?.invoke(2, 2)
            listOf("hello world", "thanks")
        }

        val result = useCase(
            transcript = transcript,
            targetLanguage = "en",
            onProgress = { progress += it },
        )

        assertThat(result.segments.map { it.translation }).containsExactly("hello world", "thanks").inOrder()
        assertThat(result.segments[0].translatedWords).isNotEmpty()
        assertThat(result.segments[0].translatedWords.last().endMs).isEqualTo(1_000)
        assertThat(result.segments[1].translatedWords.last().endMs).isEqualTo(2_000)
        assertThat(progress).containsExactly(
            TranslateProgress(completed = 1, total = 2),
            TranslateProgress(completed = 2, total = 2),
        ).inOrder()
    }
}
