package com.charlesh.captionburn.domain.model

import kotlinx.serialization.Serializable

/** A single recognised word with start/end in milliseconds since the start of the audio. */
@Serializable
data class Word(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Confidence 0..1. Whisper's avg-logprob mapped to a 0..1 scale. */
    val confidence: Float = 1f,
)

/** A contiguous run of words sharing one language and one rendering line. */
@Serializable
data class Segment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val language: String,
    val words: List<Word>,
    /** Optional translated text, populated after a translation pass. */
    val translation: String? = null,
    /** Word-level translation timing (linear redistribution by character count). */
    val translatedWords: List<Word> = emptyList(),
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

@Serializable
data class Transcript(
    val detectedLanguage: String,
    val segments: List<Segment>,
)
