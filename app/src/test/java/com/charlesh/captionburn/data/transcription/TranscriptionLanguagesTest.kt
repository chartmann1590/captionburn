package com.charlesh.captionburn.data.transcription

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranscriptionLanguagesTest {

    @Test
    fun `all codes are lowercase ISO-639-1 two-letter codes`() {
        TranscriptionLanguages.all.forEach { entry ->
            assertThat(entry.code).matches("^[a-z]{2}$")
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
}
