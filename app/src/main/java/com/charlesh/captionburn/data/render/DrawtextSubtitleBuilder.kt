package com.charlesh.captionburn.data.render

import com.charlesh.captionburn.domain.model.CaptionPosition
import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.DisplayMode
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Transcript
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces an FFmpeg `-filter_script:v` chain that burns segment-level captions
 * into a video using the `drawtext` filter.
 *
 * We use `drawtext` instead of the `ass` / `subtitles` filters because the
 * Android FFmpegKit fork we ship doesn't compile in libass. `drawtext` only
 * needs libfreetype, which is included.
 *
 * Trade-offs vs the libass-backed AssSubtitleBuilder:
 *   - No per-word karaoke highlight (would need a drawtext per word, with
 *     pixel-accurate x offsets we can't easily compute without font metrics).
 *   - Highlight modes other than `None` degrade to plain segment text.
 *   - `Both` display mode renders original at the user's chosen position and
 *     translation at the mirrored position above/below.
 */
@Singleton
class DrawtextSubtitleBuilder @Inject constructor() {

    /**
     * Build the filter chain string. Pass it to FFmpeg as the contents of a
     * `-filter_script:v <file>` argument so we don't blow command-line limits.
     */
    fun build(transcript: Transcript, style: CaptionStyle): String {
        if (transcript.segments.isEmpty()) {
            // null filter is a pass-through; FFmpeg requires *some* filter when
            // -filter_script:v is set, so we provide a no-op rather than "".
            return "null"
        }
        val drawtexts = transcript.segments.flatMap { segment ->
            buildSegmentEntries(segment, style)
        }
        if (drawtexts.isEmpty()) return "null"
        return drawtexts.joinToString(",")
    }

    private fun buildSegmentEntries(segment: Segment, style: CaptionStyle): List<String> = when (style.displayMode) {
        DisplayMode.Original -> listOfNotNull(
            buildDrawtext(segment.text, segment, style, mirrored = false),
        )

        DisplayMode.Translated -> listOfNotNull(
            buildDrawtext(
                text = segment.translation?.takeIf { it.isNotBlank() } ?: segment.text,
                segment = segment,
                style = style,
                mirrored = false,
            ),
        )

        DisplayMode.Both -> listOfNotNull(
            buildDrawtext(segment.text, segment, style, mirrored = false),
            segment.translation
                ?.takeIf { it.isNotBlank() && it != segment.text }
                ?.let { buildDrawtext(it, segment, style, mirrored = true) },
        )
    }

    private fun buildDrawtext(
        text: String,
        segment: Segment,
        style: CaptionStyle,
        mirrored: Boolean,
    ): String? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null
        val escaped = escapeDrawtextValue(cleaned)

        val position = if (mirrored) mirrorPosition(style.position) else style.position
        val (x, y) = positionExpressions(position, style)

        val startSec = segment.startMs / 1000.0
        val endSec = (segment.endMs.coerceAtLeast(segment.startMs + 50L)) / 1000.0
        val enable = "between(t,${formatSeconds(startSec)},${formatSeconds(endSec)})"

        val parts = buildList {
            add("fontfile=$DEFAULT_FONT_FILE")
            add("text='$escaped'")
            // expansion=none disables drawtext's %{...} expression evaluator so
            // we don't have to worry about transcript text containing `%`.
            add("expansion=none")
            add("fontcolor=${toFfmpegColor(style.primaryColor)}")
            add("fontsize=${style.fontSizePx}")
            add("borderw=${style.outlineWidthPx}")
            add("bordercolor=${toFfmpegColor(style.outlineColor)}")
            if (style.shadowPx > 0) {
                add("shadowx=${style.shadowPx}")
                add("shadowy=${style.shadowPx}")
                add("shadowcolor=black@0.6")
            }
            add("box=1")
            add("boxcolor=black@0.35")
            add("boxborderw=${(style.fontSizePx / 4).coerceAtLeast(8)}")
            add("line_spacing=4")
            add("x=$x")
            add("y=$y")
            add("enable='$enable'")
        }
        return "drawtext=" + parts.joinToString(":")
    }

    private fun positionExpressions(position: CaptionPosition, style: CaptionStyle): Pair<String, String> {
        val mh = style.marginHorizontalPx
        val mv = style.marginVerticalPx

        val x = when (position) {
            CaptionPosition.BottomLeft, CaptionPosition.MiddleLeft, CaptionPosition.TopLeft ->
                "$mh"
            CaptionPosition.BottomRight, CaptionPosition.MiddleRight, CaptionPosition.TopRight ->
                "w-text_w-$mh"
            CaptionPosition.BottomCenter, CaptionPosition.MiddleCenter, CaptionPosition.TopCenter ->
                "(w-text_w)/2"
        }
        val y = when (position) {
            CaptionPosition.TopLeft, CaptionPosition.TopCenter, CaptionPosition.TopRight ->
                "$mv"
            CaptionPosition.MiddleLeft, CaptionPosition.MiddleCenter, CaptionPosition.MiddleRight ->
                "(h-text_h)/2"
            CaptionPosition.BottomLeft, CaptionPosition.BottomCenter, CaptionPosition.BottomRight ->
                "h-text_h-$mv"
        }
        return x to y
    }

    private fun mirrorPosition(position: CaptionPosition): CaptionPosition = when (position) {
        CaptionPosition.BottomLeft -> CaptionPosition.TopLeft
        CaptionPosition.BottomCenter -> CaptionPosition.TopCenter
        CaptionPosition.BottomRight -> CaptionPosition.TopRight
        CaptionPosition.TopLeft -> CaptionPosition.BottomLeft
        CaptionPosition.TopCenter -> CaptionPosition.BottomCenter
        CaptionPosition.TopRight -> CaptionPosition.BottomRight
        CaptionPosition.MiddleLeft -> CaptionPosition.TopLeft
        CaptionPosition.MiddleCenter -> CaptionPosition.TopCenter
        CaptionPosition.MiddleRight -> CaptionPosition.TopRight
    }

    /**
     * Escape a string for use inside `drawtext=text='<here>'`.
     *
     * FFmpeg's filter-graph syntax: inside a `'…'` quoted scalar, **no
     * escapes are recognised** — the quote ends at the next `'`, full stop.
     * Backslashes are literal, percent is literal (combined with our
     * `expansion=none` option below). The only character we have to handle
     * is `'` itself, and the standard sequence is:
     *   close-quote → escaped apostrophe at outer level → re-open-quote
     *   = `'` + `\'` + `'` = `'\''`
     *
     * So `we're` becomes `we'\''re`, and the surrounding `text='…'` wrap
     * makes it `'we'\''re'`, which the parser concatenates back to `we're`.
     *
     * Newlines / CRs would terminate the option line in -filter_script:v
     * mode, so we squash those to spaces.
     */
    internal fun escapeDrawtextValue(raw: String): String = buildString(raw.length + 8) {
        for (c in raw) {
            when (c) {
                '\'' -> append("'\\''")
                '\n', '\r' -> append(' ')
                else -> append(c)
            }
        }
    }

    /** Convert ARGB long → FFmpeg `0xRRGGBB@A.AA` style color string. */
    internal fun toFfmpegColor(argb: Long): String {
        val a = ((argb shr 24) and 0xFF).toInt()
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        val alphaFloat = a / 255.0
        return "0x%02X%02X%02X@%.3f".format(r, g, b, alphaFloat)
    }

    private fun formatSeconds(seconds: Double): String =
        "%.3f".format(seconds)

    private companion object {
        // Roboto-Regular.ttf is bundled on every Android device since API 21.
        // We pin to the absolute system path so libfreetype can fopen() it
        // without needing a fontconfig setup we don't have.
        const val DEFAULT_FONT_FILE = "/system/fonts/Roboto-Regular.ttf"
    }
}
