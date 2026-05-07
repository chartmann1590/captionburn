package com.charlesh.captionburn.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DisplayMode { Original, Translated, Both }

@Serializable
enum class HighlightMode { None, FillCurrent, ScaleCurrent, UnderlineCurrent }

/** Maps directly to ASS `\an` alignment values 1..9. 2 = bottom-center, 5 = middle, 8 = top. */
@Serializable
enum class CaptionPosition(val ass: Int) {
    BottomLeft(1), BottomCenter(2), BottomRight(3),
    MiddleLeft(4), MiddleCenter(5), MiddleRight(6),
    TopLeft(7), TopCenter(8), TopRight(9),
}

@Serializable
data class CaptionStyle(
    val fontFamily: String = "Inter",
    /** Font size in ASS "PlayResY=1080" units (i.e. pixels of a 1080p canvas). */
    val fontSizePx: Int = 64,
    val position: CaptionPosition = CaptionPosition.BottomCenter,
    val displayMode: DisplayMode = DisplayMode.Original,
    val targetLanguage: String? = null,
    val highlightMode: HighlightMode = HighlightMode.FillCurrent,
    /** ARGB hex; converted to ASS &HBBGGRR& at render time. */
    val primaryColor: Long = 0xFFFFFFFF,
    val highlightColor: Long = 0xFFFFE066,
    val outlineColor: Long = 0xFF000000,
    val outlineWidthPx: Int = 3,
    val shadowPx: Int = 1,
    /** Bottom safe-area inset in 1080p px when position is bottom-anchored. */
    val marginVerticalPx: Int = 96,
    val marginHorizontalPx: Int = 64,
)
