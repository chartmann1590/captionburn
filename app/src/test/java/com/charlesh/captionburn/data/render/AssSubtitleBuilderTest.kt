package com.charlesh.captionburn.data.render

import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.HighlightMode
import com.charlesh.captionburn.domain.model.DisplayMode
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.domain.model.Word
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssSubtitleBuilderTest {

    private val builder = AssSubtitleBuilder()

    @Test
    fun build_defaultOpaqueWhite_primaryColourUsesInvertedAlpha() {
        val transcript = demoTranscript()
        val style = CaptionStyle(
            displayMode = DisplayMode.Original,
            highlightMode = HighlightMode.None,
            primaryColor = 0xFFFFFFFF,
        )

        val ass = builder.build(transcript, style)

        assertThat(ass).contains(",&H00FFFFFF,")
    }

    @Test
    fun build_originalMode_writesKaraokeDialogue() {
        val transcript = demoTranscript()
        val style = CaptionStyle(displayMode = DisplayMode.Original)

        val ass = builder.build(transcript, style)

        assertThat(ass).contains("[Script Info]")
        assertThat(ass).contains("Style: Default")
        assertThat(ass).contains("Dialogue: 0,0:00:00.00,0:00:01.20,Default")
        assertThat(ass).contains("{\\k60}hello {\\k55}world")
    }

    @Test
    fun build_bothMode_writesDualDialogueRows() {
        val transcript = demoTranscript()
        val style = CaptionStyle(displayMode = DisplayMode.Both)

        val ass = builder.build(transcript, style)

        assertThat(ass).contains("Dialogue: 0,0:00:00.00,0:00:01.20,Default")
        assertThat(ass).contains("Dialogue: 1,0:00:00.00,0:00:01.20,Default")
        assertThat(ass).contains("{\\an2}")
        assertThat(ass).contains("{\\an8}")
    }

    @Test
    fun build_translatedMode_usesFallbackTokensWhenTranslatedWordsMissing() {
        val transcript = Transcript(
            detectedLanguage = "en",
            segments = listOf(
                Segment(
                    id = "s2",
                    startMs = 2_000,
                    endMs = 3_000,
                    language = "en",
                    words = listOf(Word(startMs = 2_000, endMs = 3_000, text = "unused")),
                    translation = "hola mundo",
                    translatedWords = emptyList(),
                )
            ),
        )
        val ass = builder.build(transcript, CaptionStyle(displayMode = DisplayMode.Translated))
        assertThat(ass).contains("{\\k50}hola {\\k50}mundo")
    }

    @Test
    fun build_translatedMode_fallsBackToOriginalWhenTranslationMissing() {
        val transcript = Transcript(
            detectedLanguage = "en",
            segments = listOf(
                Segment(
                    id = "s2",
                    startMs = 2_000,
                    endMs = 3_000,
                    language = "en",
                    words = listOf(Word(startMs = 2_000, endMs = 3_000, text = "hello")),
                    translation = null,
                    translatedWords = emptyList(),
                )
            ),
        )
        val ass = builder.build(transcript, CaptionStyle(displayMode = DisplayMode.Translated))
        assertThat(ass).contains("{\\k100}hello")
    }

    @Test
    fun build_originalMode_escapesAssControlCharacters() {
        val transcript = Transcript(
            detectedLanguage = "en",
            segments = listOf(
                Segment(
                    id = "s3",
                    startMs = 0,
                    endMs = 800,
                    language = "en",
                    words = listOf(
                        Word(startMs = 0, endMs = 400, text = "{hello}"),
                        Word(startMs = 400, endMs = 800, text = "c:\\temp"),
                    ),
                )
            ),
        )
        val ass = builder.build(transcript, CaptionStyle(displayMode = DisplayMode.Original))
        assertThat(ass).contains("{\\k40}\\{hello\\}")
        assertThat(ass).contains("{\\k40}c:\\\\temp")
    }

    @Test
    fun build_originalMode_escapesLiteralLineBreaksInsideDialogueText() {
        val transcript = Transcript(
            detectedLanguage = "en",
            segments = listOf(
                Segment(
                    id = "s4",
                    startMs = 0,
                    endMs = 1_000,
                    language = "en",
                    words = listOf(
                        Word(startMs = 0, endMs = 500, text = "hello\nworld"),
                        Word(startMs = 500, endMs = 1_000, text = "again\r"),
                    ),
                )
            ),
        )

        val ass = builder.build(transcript, CaptionStyle(displayMode = DisplayMode.Original))

        assertThat(ass).contains("{\\k50}hello\\Nworld {\\k50}again")
        assertThat(ass.lines().filter { it.startsWith("Dialogue:") }).hasSize(1)
    }

    private fun demoTranscript(): Transcript = Transcript(
        detectedLanguage = "en",
        segments = listOf(
            Segment(
                id = "s1",
                startMs = 0,
                endMs = 1_200,
                language = "en",
                words = listOf(
                    Word(startMs = 0, endMs = 600, text = "hello"),
                    Word(startMs = 650, endMs = 1_200, text = "world"),
                ),
                translation = "hola mundo",
                translatedWords = listOf(
                    Word(startMs = 0, endMs = 600, text = "hola"),
                    Word(startMs = 650, endMs = 1_200, text = "mundo"),
                ),
            ),
        ),
    )
}
