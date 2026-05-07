package com.charlesh.captionburn.data.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.UnderlineSpan
import com.charlesh.captionburn.domain.model.CaptionPosition
import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.HighlightMode
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Renders one caption-only PNG per segment using Android Canvas.
 *
 * The PNG is sized just-large-enough to fit the wrapped text plus padding, so
 * disk usage stays modest even for long videos. The overlay filter places the
 * PNG at the right (x,y) on the video frame at burn time.
 *
 * We use Canvas instead of FFmpeg's drawtext filter because the Android
 * FFmpegKit fork we ship omits drawtext and every libass filter from
 * libavfilter — only `overlay` and a handful of basics survive.
 */
@Singleton
class CaptionImageRenderer @Inject constructor() {

    data class RenderResult(
        val file: File,
        val widthPx: Int,
        val heightPx: Int,
        /** Top-left x of where to overlay this PNG on a videoWidthPx × videoHeightPx frame. */
        val xPx: Int,
        /** Top-left y of where to overlay this PNG on a videoWidthPx × videoHeightPx frame. */
        val yPx: Int,
    )

    /**
     * @param videoWidthPx,videoHeightPx the source video's pixel dimensions; used
     *   to position the caption bitmap on the frame.
     */
    fun render(
        text: String,
        style: CaptionStyle,
        outputFile: File,
        videoWidthPx: Int,
        videoHeightPx: Int,
    ): RenderResult = renderCaptionText(
        text = SpannableString(text.cleanCaptionText().ifEmpty { " " }),
        style = style,
        outputFile = outputFile,
        videoWidthPx = videoWidthPx,
        videoHeightPx = videoHeightPx,
    )

    fun renderWords(
        words: List<String>,
        activeWordIndex: Int?,
        style: CaptionStyle,
        outputFile: File,
        videoWidthPx: Int,
        videoHeightPx: Int,
    ): RenderResult {
        val cleanWords = words
            .map { it.cleanCaptionText() }
            .filter { it.isNotEmpty() }
        val text = if (cleanWords.isEmpty()) {
            SpannableString(" ")
        } else {
            cleanWords.toStyledCaptionText(activeWordIndex, style)
        }
        return renderCaptionText(
            text = text,
            style = style,
            outputFile = outputFile,
            videoWidthPx = videoWidthPx,
            videoHeightPx = videoHeightPx,
        )
    }

    private fun renderCaptionText(
        text: CharSequence,
        style: CaptionStyle,
        outputFile: File,
        videoWidthPx: Int,
        videoHeightPx: Int,
    ): RenderResult {
        val scale = computeScale(videoWidthPx, videoHeightPx)
        val fontSizePx = (style.fontSizePx * scale).coerceAtLeast(MIN_FONT_PX).toFloat()
        val outlinePx = (style.outlineWidthPx * scale).coerceAtLeast(0.0).toFloat()
        val paddingPx = (fontSizePx * 0.45f).roundToInt().coerceAtLeast(8)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argbToPaintColor(style.primaryColor)
            textSize = fontSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
            isSubpixelText = true
        }
        val outlinePaint = TextPaint(textPaint).apply {
            color = argbToPaintColor(style.outlineColor)
            this.style = Paint.Style.STROKE
            strokeWidth = outlinePx
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 10f
        }
        val boxPaint = Paint().apply {
            color = Color.argb(120, 0, 0, 0)
            isAntiAlias = true
        }

        // 70% of the video width is the soft cap on caption width; lets us wrap
        // long lines naturally without bumping into the frame edge.
        val maxTextWidth = (videoWidthPx * MAX_LINE_FRACTION).roundToInt()
            .coerceAtLeast((fontSizePx * 4).toInt())
        val layoutWidth = textWidthHint(text, textPaint, maxTextWidth)
        val layout: StaticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()

        // Tighten the bitmap to the actual rendered text width.
        val measuredWidth = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val tightTextWidth = measuredWidth.roundToInt().coerceAtLeast(1)
        val tightTextHeight = layout.height

        val bitmapW = (tightTextWidth + 2 * paddingPx)
        val bitmapH = (tightTextHeight + 2 * paddingPx)
        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Rounded background box for legibility on bright frames.
        val cornerRadius = paddingPx.toFloat() * 0.6f
        canvas.drawRoundRect(
            0f,
            0f,
            bitmapW.toFloat(),
            bitmapH.toFloat(),
            cornerRadius,
            cornerRadius,
            boxPaint,
        )

        canvas.save()
        canvas.translate(paddingPx.toFloat(), paddingPx.toFloat())
        // Translate the content to center the (potentially narrower) layout
        // inside the layoutWidth used for line breaking.
        val centerOffset = ((layoutWidth - tightTextWidth) / 2f).coerceAtLeast(0f)
        canvas.translate(-centerOffset, 0f)
        if (outlinePx > 0f) layout.drawOutline(canvas, outlinePaint)
        layout.draw(canvas)
        canvas.restore()

        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val (x, y) = computeOverlayPosition(
            position = style.position,
            videoWidthPx = videoWidthPx,
            videoHeightPx = videoHeightPx,
            bitmapW = bitmapW,
            bitmapH = bitmapH,
            marginHorizontal = (style.marginHorizontalPx * scale).roundToInt(),
            marginVertical = (style.marginVerticalPx * scale).roundToInt(),
        )

        return RenderResult(
            file = outputFile,
            widthPx = bitmapW,
            heightPx = bitmapH,
            xPx = x,
            yPx = y,
        )
    }

    private fun computeOverlayPosition(
        position: CaptionPosition,
        videoWidthPx: Int,
        videoHeightPx: Int,
        bitmapW: Int,
        bitmapH: Int,
        marginHorizontal: Int,
        marginVertical: Int,
    ): Pair<Int, Int> {
        val x = when (position) {
            CaptionPosition.BottomLeft, CaptionPosition.MiddleLeft, CaptionPosition.TopLeft ->
                marginHorizontal
            CaptionPosition.BottomRight, CaptionPosition.MiddleRight, CaptionPosition.TopRight ->
                videoWidthPx - bitmapW - marginHorizontal
            else -> (videoWidthPx - bitmapW) / 2
        }
        val y = when (position) {
            CaptionPosition.TopLeft, CaptionPosition.TopCenter, CaptionPosition.TopRight ->
                marginVertical
            CaptionPosition.MiddleLeft, CaptionPosition.MiddleCenter, CaptionPosition.MiddleRight ->
                (videoHeightPx - bitmapH) / 2
            else -> videoHeightPx - bitmapH - marginVertical
        }
        return x.coerceAtLeast(0) to y.coerceAtLeast(0)
    }

    /** [CaptionStyle] sizes are calibrated for a 1080p canvas. Scale linearly. */
    private fun computeScale(videoWidthPx: Int, videoHeightPx: Int): Double {
        val refLong = 1920.0
        val longSide = maxOf(videoWidthPx, videoHeightPx).coerceAtLeast(720)
        return longSide / refLong
    }

    private fun textWidthHint(text: String, paint: TextPaint, max: Int): Int {
        val measured = paint.measureText(text).roundToInt()
        return measured.coerceIn(8, max)
    }

    private fun textWidthHint(text: CharSequence, paint: TextPaint, max: Int): Int =
        textWidthHint(text.toString(), paint, max)

    private fun List<String>.toStyledCaptionText(activeWordIndex: Int?, style: CaptionStyle): SpannableString {
        val captionText = joinToString(" ")
        val spannable = SpannableString(captionText)
        if (
            activeWordIndex == null ||
            activeWordIndex !in indices ||
            style.highlightMode == HighlightMode.None
        ) {
            return spannable
        }

        var cursor = 0
        forEachIndexed { index, word ->
            val start = cursor
            val end = start + word.length
            if (index == activeWordIndex) {
                spannable.setSpan(
                    ForegroundColorSpan(argbToPaintColor(style.highlightColor)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                when (style.highlightMode) {
                    HighlightMode.None -> Unit
                    HighlightMode.FillCurrent -> Unit
                    HighlightMode.ScaleCurrent -> spannable.setSpan(
                        RelativeSizeSpan(1.12f),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    HighlightMode.UnderlineCurrent -> spannable.setSpan(
                        UnderlineSpan(),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            cursor = end + 1
        }
        return spannable
    }

    private fun String.cleanCaptionText(): String =
        trim()
            .replace('\n', ' ')
            .replace('\r', ' ')

    /** Convert ARGB Long (0xAARRGGBB) → Android Paint color int. */
    private fun argbToPaintColor(argb: Long): Int = argb.toInt()

    private fun StaticLayout.drawOutline(canvas: Canvas, outline: Paint) {
        val savedPaint = paint
        try {
            // StaticLayout draws using its own paint; we copy text glyphs by
            // re-drawing with the outline paint via Canvas's text APIs.
            for (i in 0 until lineCount) {
                val lineStart = getLineStart(i)
                val lineEnd = getLineEnd(i)
                val lineText = text.subSequence(lineStart, lineEnd).toString().trimEnd('\n')
                val baseline = getLineBaseline(i).toFloat()
                val xLeft = getLineLeft(i)
                canvas.drawText(lineText, xLeft, baseline, outline)
            }
        } finally {
            // no-op; we don't replace the layout's paint
            @Suppress("UNUSED_EXPRESSION") savedPaint
        }
    }

    private companion object {
        const val MAX_LINE_FRACTION = 0.78
        const val MIN_FONT_PX = 18.0
    }
}
