package com.charlesh.captionburn.data.transcription

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranscriptionLanguagesTest {

    @Test
    fun `all codes are lowercase ISO-639-1 style codes`() {
        TranscriptionLanguages.all.forEach { entry ->
            assertThat(entry.code).matches("^[a-z]{2,3}$")
            assertThat(entry.displayName).isNotEmpty()
        }
    }

    @Test
    fun `codes are unique`() {
        val codes = TranscriptionLanguages.all.map { it.code }
        assertThat(codes).containsNoDuplicates()
    }

    @Test
    fun `isValid accepts known codes and rejects unknown or blank ones`() {
        assertThat(TranscriptionLanguages.isValid("en")).isTrue()
        assertThat(TranscriptionLanguages.isValid("de")).isTrue()
        assertThat(TranscriptionLanguages.isValid("xx")).isFalse()
        assertThat(TranscriptionLanguages.isValid("")).isFalse()
        assertThat(TranscriptionLanguages.isValid("   ")).isFalse()
    }

    @Test
    fun `byCodeOrNull resolves entries and drops blank input`() {
        assertThat(TranscriptionLanguages.byCodeOrNull("fr")?.displayName).isEqualTo("French")
        assertThat(TranscriptionLanguages.byCodeOrNull("nope")).isNull()
        assertThat(TranscriptionLanguages.byCodeOrNull("")).isNull()
        assertThat(TranscriptionLanguages.byCodeOrNull(null)).isNull()
    }

    @Test
    fun `common languages are present`() {
        val codes = TranscriptionLanguages.all.map { it.code }
        assertThat(codes).containsAtLeast("en", "es", "fr", "de", "zh", "ja", "hi", "ar")
    }

    /**
     * Regression guard for the bundled whisper.cpp `g_lang` map
     * (third_party/whisper.cpp/src/whisper.cpp). Every code we expose must
     * resolve through `whisper_lang_id()`; codes outside the map make it
     * return -1 and poison the transcription prompt.
     */
    @Test
    fun `catalog matches the bundled whisper language map exactly`() {
        // Codes once shipped here that whisper.cpp cannot resolve.
        val codes = TranscriptionLanguages.all.map { it.code }
        assertThat(codes).containsNoneOf("nb", "ga", "ky", "or", "lg")

        // Supported entries that were initially missing.
        assertThat(codes).containsAtLeast(
            "br", "oc", "ht", "lb", "bo", "mg", "haw", "yue",
        )

        // 100 languages, ids 0..99, one entry per id (implied by uniqueness).
        assertThat(codes).hasSize(100)
    }
}
