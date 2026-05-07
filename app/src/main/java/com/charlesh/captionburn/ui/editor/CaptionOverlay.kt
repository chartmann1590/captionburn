package com.charlesh.captionburn.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.charlesh.captionburn.domain.model.CaptionPosition
import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.DisplayMode
import com.charlesh.captionburn.domain.model.HighlightMode
import com.charlesh.captionburn.domain.model.Word

@Composable
fun CaptionOverlay(
    style: CaptionStyle,
    activeWords: List<Word>,
    activeTranslationWords: List<Word>,
    playbackPositionMs: Long,
    modifier: Modifier = Modifier,
) {
    val (vAlign, hAlign) = style.position.toAlignment()
    val textAlign = style.position.toTextAlign()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = hAlign,
        verticalArrangement = vAlign,
    ) {
        when (style.displayMode) {
            DisplayMode.Original -> OverlayLine(
                words = activeWords,
                playbackPositionMs = playbackPositionMs,
                style = style,
                textAlign = textAlign,
            )
            DisplayMode.Translated -> OverlayLine(
                words = activeTranslationWords.ifEmpty { activeWords },
                playbackPositionMs = playbackPositionMs,
                style = style,
                textAlign = textAlign,
            )
            DisplayMode.Both -> {
                OverlayLine(
                    words = activeWords,
                    playbackPositionMs = playbackPositionMs,
                    style = style,
                    textAlign = textAlign,
                )
                if (activeTranslationWords.isNotEmpty()) {
                    OverlayLine(
                        words = activeTranslationWords,
                        playbackPositionMs = playbackPositionMs,
                        style = style,
                        textAlign = textAlign,
                        fontScale = 0.82f,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayLine(
    words: List<Word>,
    playbackPositionMs: Long,
    style: CaptionStyle,
    textAlign: TextAlign,
    fontScale: Float = 1f,
) {
    if (words.isEmpty()) return

    val fontSize = (style.fontSizePx / 3f * fontScale).coerceAtLeast(14f).sp
    val activeIndex = words.activeWordIndex(playbackPositionMs)
    val captionText = words.toCaptionText(
        activeIndex = activeIndex,
        style = style,
        baseColor = Color(style.primaryColor),
        highlightColor = Color(style.highlightColor),
        fontSize = fontSize,
    )
    val outlineText = words.toCaptionText(
        activeIndex = activeIndex,
        style = style,
        baseColor = Color(style.outlineColor),
        highlightColor = Color(style.outlineColor),
        fontSize = fontSize,
    )
    val textStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
    )

    Box(
        modifier = Modifier.fillMaxWidth(0.94f),
        contentAlignment = Alignment.Center,
    ) {
        if (style.outlineWidthPx > 0) {
            val offsetDp = (style.outlineWidthPx / 3f).coerceIn(1f, 4f).dp
            listOf(
                -offsetDp to 0.dp,
                offsetDp to 0.dp,
                0.dp to -offsetDp,
                0.dp to offsetDp,
            ).forEach { (x, y) ->
                Text(
                    text = outlineText,
                    color = Color(style.outlineColor),
                    style = textStyle,
                    textAlign = textAlign,
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = x, y = y),
                )
            }
        }
        Text(
            text = captionText,
            color = Color(style.primaryColor),
            style = textStyle,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun CaptionPosition.toAlignment(): Pair<Arrangement.Vertical, Alignment.Horizontal> {
    val vertical = when (this) {
        CaptionPosition.BottomLeft,
        CaptionPosition.BottomCenter,
        CaptionPosition.BottomRight -> Arrangement.Bottom
        CaptionPosition.MiddleLeft,
        CaptionPosition.MiddleCenter,
        CaptionPosition.MiddleRight -> Arrangement.Center
        CaptionPosition.TopLeft,
        CaptionPosition.TopCenter,
        CaptionPosition.TopRight -> Arrangement.Top
    }
    val horizontal = when (this) {
        CaptionPosition.BottomLeft,
        CaptionPosition.MiddleLeft,
        CaptionPosition.TopLeft -> Alignment.Start
        CaptionPosition.BottomCenter,
        CaptionPosition.MiddleCenter,
        CaptionPosition.TopCenter -> Alignment.CenterHorizontally
        CaptionPosition.BottomRight,
        CaptionPosition.MiddleRight,
        CaptionPosition.TopRight -> Alignment.End
    }
    return vertical to horizontal
}

private fun CaptionPosition.toTextAlign(): TextAlign = when (this) {
    CaptionPosition.BottomLeft,
    CaptionPosition.MiddleLeft,
    CaptionPosition.TopLeft -> TextAlign.Start
    CaptionPosition.BottomRight,
    CaptionPosition.MiddleRight,
    CaptionPosition.TopRight -> TextAlign.End
    else -> TextAlign.Center
}

private fun List<Word>.activeWordIndex(playbackPositionMs: Long): Int? {
    val directIndex = indexOfFirst { word -> playbackPositionMs in word.startMs..word.endMs }
    if (directIndex >= 0) return directIndex
    val previousIndex = indexOfLast { word -> playbackPositionMs >= word.startMs }
    return previousIndex.takeIf { it >= 0 }
}

private fun List<Word>.toCaptionText(
    activeIndex: Int?,
    style: CaptionStyle,
    baseColor: Color,
    highlightColor: Color,
    fontSize: TextUnit,
): AnnotatedString = buildAnnotatedString {
    forEachIndexed { index, word ->
        if (index > 0) append(" ")
        val spanStyle = wordSpanStyle(
            isActive = activeIndex == index,
            style = style,
            baseColor = baseColor,
            highlightColor = highlightColor,
            fontSize = fontSize,
        )
        withStyle(spanStyle) {
            append(word.text)
        }
    }
}

private fun wordSpanStyle(
    isActive: Boolean,
    style: CaptionStyle,
    baseColor: Color,
    highlightColor: Color,
    fontSize: TextUnit,
): SpanStyle {
    if (!isActive || style.highlightMode == HighlightMode.None) {
        return SpanStyle(color = baseColor)
    }
    return when (style.highlightMode) {
        HighlightMode.None -> SpanStyle(color = baseColor)
        HighlightMode.FillCurrent -> SpanStyle(color = highlightColor)
        HighlightMode.ScaleCurrent -> SpanStyle(
            color = highlightColor,
            fontSize = (fontSize.value * 1.12f).sp,
        )
        HighlightMode.UnderlineCurrent -> SpanStyle(
            color = highlightColor,
            textDecoration = TextDecoration.Underline,
        )
    }
}
