package com.charlesh.captionburn.data.transcription

import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.domain.model.Word
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class WhisperJsonResponse(
    val language: String = "",
    val segments: List<WhisperSegmentDto> = emptyList(),
    val error: String? = null,
)

@Serializable
internal data class WhisperSegmentDto(
    val id: Int,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
    val words: List<WhisperWordDto> = emptyList(),
)

@Serializable
internal data class WhisperWordDto(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
    val p: Float = 1f,
)

internal fun WhisperJsonResponse.toTranscript(): Transcript {
    val segs = segments.mapIndexed { idx, s ->
        Segment(
            id = "seg-$idx",
            startMs = s.startMs,
            endMs = s.endMs,
            language = language,
            words = s.words.map { w ->
                Word(
                    startMs = w.startMs,
                    endMs = w.endMs.coerceAtLeast(w.startMs + 1),
                    text = w.text.trim(),
                    confidence = w.p.coerceIn(0f, 1f),
                )
            }.filter { it.text.isNotEmpty() },
        )
    }
    return Transcript(detectedLanguage = language, segments = segs)
}
