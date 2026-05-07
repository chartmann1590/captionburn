package com.charlesh.captionburn.data.render

import com.charlesh.captionburn.domain.model.CaptionPosition
import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.DisplayMode
import com.charlesh.captionburn.domain.model.HighlightMode
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.domain.model.Word
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class AssSubtitleBuilder @Inject constructor() {

    fun build(transcript: Transcript, style: CaptionStyle): String {
        val header = buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: 1920")
            appendLine("PlayResY: 1080")
            appendLine("WrapStyle: 2")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine()
            appendLine("[V4+ Styles]")
            appendLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, " +
                    "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, " +
                    "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                    "Alignment, MarginL, MarginR, MarginV, Encoding"
            )
            appendLine(
                "Style: Default,${style.fontFamily},${style.fontSizePx}," +
                    "${toAssColor(style.primaryColor)},${toAssColor(style.highlightColor)}," +
                    "${toAssColor(style.outlineColor)},&H64000000,0,0,0,0,100,100,0,0,1," +
                    "${style.outlineWidthPx},${style.shadowPx},${style.position.ass}," +
                    "${style.marginHorizontalPx},${style.marginHorizontalPx},${style.marginVerticalPx},1"
            )
            appendLine()
            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        }

        val events = buildString {
            transcript.segments.forEach { segment ->
                val start = formatAssTime(segment.startMs)
                val end = formatAssTime(segment.endMs)
                when (style.displayMode) {
                    DisplayMode.Original -> {
                        appendLine(dialogue(0, start, end, buildKaraoke(segment.words, style.highlightMode)))
                    }
                    DisplayMode.Translated -> {
                        val translatedWords = segment.translatedWords.ifEmpty {
                            buildFallbackWords(segment.translation.orEmpty(), segment.startMs, segment.endMs)
                        }.ifEmpty { segment.words }
                        appendLine(dialogue(0, start, end, buildKaraoke(translatedWords, style.highlightMode)))
                    }
                    DisplayMode.Both -> {
                        val translatedWords = segment.translatedWords.ifEmpty {
                            buildFallbackWords(segment.translation.orEmpty(), segment.startMs, segment.endMs)
                        }.ifEmpty { segment.words }
                        appendLine(
                            dialogue(
                                layer = 0,
                                start = start,
                                end = end,
                                text = "{\\an${style.position.ass}}" + buildKaraoke(segment.words, style.highlightMode),
                            )
                        )
                        appendLine(
                            dialogue(
                                layer = 1,
                                start = start,
                                end = end,
                                text = "{\\an${alternateAlignment(style.position)}}" +
                                    buildKaraoke(translatedWords, style.highlightMode),
                            )
                        )
                    }
                }
            }
        }
        return header + events
    }

    private fun dialogue(layer: Int, start: String, end: String, text: String): String =
        "Dialogue: $layer,$start,$end,Default,,0,0,0,,$text"

    private fun buildKaraoke(words: List<Word>, mode: HighlightMode): String {
        val prefix = when (mode) {
            HighlightMode.None -> ""
            HighlightMode.FillCurrent -> ""
            HighlightMode.ScaleCurrent -> "{\\fscx110\\fscy110}"
            HighlightMode.UnderlineCurrent -> "{\\u1}"
        }
        if (words.isEmpty()) return prefix + " "
        return prefix + words.joinToString(" ") { word ->
            val centiseconds = max(1L, (word.endMs - word.startMs) / 10L)
            "{\\k$centiseconds}${escapeAssText(word.text)}"
        }
    }

    private fun buildFallbackWords(text: String, startMs: Long, endMs: Long): List<Word> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val step = (duration / tokens.size).coerceAtLeast(1L)
        return tokens.mapIndexed { idx, token ->
            val start = startMs + idx * step
            val end = if (idx == tokens.lastIndex) endMs else (start + step).coerceAtMost(endMs)
            Word(startMs = start, endMs = end, text = token)
        }
    }

    private fun toAssColor(argb: Long): String {
        val a = (argb shr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val assAlpha = 0xFF - a
        return "&H%02X%02X%02X%02X".format(assAlpha, b, g, r)
    }

    private fun formatAssTime(ms: Long): String {
        val totalCs = ms.coerceAtLeast(0L) / 10L
        val cs = totalCs % 100
        val totalSec = totalCs / 100
        val sec = totalSec % 60
        val totalMin = totalSec / 60
        val min = totalMin % 60
        val hour = totalMin / 60
        return "%d:%02d:%02d.%02d".format(hour, min, sec, cs)
    }

    private fun alternateAlignment(position: CaptionPosition): Int = when (position) {
        CaptionPosition.BottomLeft -> CaptionPosition.TopLeft.ass
        CaptionPosition.BottomCenter -> CaptionPosition.TopCenter.ass
        CaptionPosition.BottomRight -> CaptionPosition.TopRight.ass
        CaptionPosition.MiddleLeft -> CaptionPosition.TopLeft.ass
        CaptionPosition.MiddleCenter -> CaptionPosition.TopCenter.ass
        CaptionPosition.MiddleRight -> CaptionPosition.TopRight.ass
        CaptionPosition.TopLeft -> CaptionPosition.BottomLeft.ass
        CaptionPosition.TopCenter -> CaptionPosition.BottomCenter.ass
        CaptionPosition.TopRight -> CaptionPosition.BottomRight.ass
    }

    private fun escapeAssText(raw: String): String =
        raw
            .replace("\\", "\\\\")
            .replace("\r\n", "\\N")
            .replace("\n", "\\N")
            .replace("\r", "")
            .replace("{", "\\{")
            .replace("}", "\\}")
}
