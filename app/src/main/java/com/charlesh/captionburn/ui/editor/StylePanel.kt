package com.charlesh.captionburn.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.charlesh.captionburn.domain.model.CaptionPosition
import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.HighlightMode
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StylePanel(
    style: CaptionStyle,
    onFontSizeChanged: (Int) -> Unit,
    onPositionChanged: (CaptionPosition) -> Unit,
    onHighlightModeChanged: (HighlightMode) -> Unit,
    onOutlineWidthChanged: (Int) -> Unit,
    onPrimaryColorChanged: (Long) -> Unit,
    onHighlightColorChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColors = listOf(
        ColorChoice("White", 0xFFFFFFFF),
        ColorChoice("Gold", 0xFFFFE066),
        ColorChoice("Sky", 0xFF8BD3FF),
        ColorChoice("Mint", 0xFF8FF0B3),
        ColorChoice("Pink", 0xFFFF8AC7),
    )
    val highlightColors = listOf(
        ColorChoice("Gold", 0xFFFFE066),
        ColorChoice("Cyan", 0xFF75E6FF),
        ColorChoice("Lime", 0xFFB7FF6A),
        ColorChoice("Pink", 0xFFFF8AC7),
        ColorChoice("White", 0xFFFFFFFF),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                )
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel(
            title = "Text",
            description = "Size and outline apply to the preview and the exported video.",
        )
        LabeledSlider(
            label = "Size",
            valueText = "${style.fontSizePx}px",
            value = style.fontSizePx.toFloat(),
            valueRange = 36f..110f,
            onValueChange = { onFontSizeChanged(it.roundToInt()) },
        )
        LabeledSlider(
            label = "Outline",
            valueText = "${style.outlineWidthPx}px",
            value = style.outlineWidthPx.toFloat(),
            valueRange = 0f..8f,
            onValueChange = { onOutlineWidthChanged(it.roundToInt()) },
        )

        SectionLabel(
            title = "Placement",
            description = "Pick the anchor point for the caption box.",
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            visualPositionOrder.forEach { position ->
                ChoiceChip(
                    selected = style.position == position,
                    onClick = { onPositionChanged(position) },
                    label = position.label,
                )
            }
        }

        SectionLabel(
            title = "Word highlight",
            description = style.highlightMode.description,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HighlightMode.entries.forEach { mode ->
                ChoiceChip(
                    selected = style.highlightMode == mode,
                    onClick = { onHighlightModeChanged(mode) },
                    label = mode.label,
                )
            }
        }

        ColorChoiceRow(
            title = "Text color",
            selectedColor = style.primaryColor,
            colors = textColors,
            onColorChanged = onPrimaryColorChanged,
        )
        ColorChoiceRow(
            title = "Highlight color",
            selectedColor = style.highlightColor,
            colors = highlightColors,
            onColorChanged = onHighlightColorChanged,
        )
    }
}

@Composable
private fun SectionLabel(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ColorChoiceRow(
    title: String,
    selectedColor: Long,
    colors: List<ColorChoice>,
    onColorChanged: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colors.forEach { color ->
                ChoiceChip(
                    selected = selectedColor == color.argb,
                    onClick = { onColorChanged(color.argb) },
                    label = color.label,
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

private data class ColorChoice(
    val label: String,
    val argb: Long,
)

private val visualPositionOrder = listOf(
    CaptionPosition.TopLeft,
    CaptionPosition.TopCenter,
    CaptionPosition.TopRight,
    CaptionPosition.MiddleLeft,
    CaptionPosition.MiddleCenter,
    CaptionPosition.MiddleRight,
    CaptionPosition.BottomLeft,
    CaptionPosition.BottomCenter,
    CaptionPosition.BottomRight,
)

private val CaptionPosition.label: String
    get() = when (this) {
        CaptionPosition.TopLeft -> "Top left"
        CaptionPosition.TopCenter -> "Top"
        CaptionPosition.TopRight -> "Top right"
        CaptionPosition.MiddleLeft -> "Left"
        CaptionPosition.MiddleCenter -> "Center"
        CaptionPosition.MiddleRight -> "Right"
        CaptionPosition.BottomLeft -> "Bottom left"
        CaptionPosition.BottomCenter -> "Bottom"
        CaptionPosition.BottomRight -> "Bottom right"
    }

private val HighlightMode.label: String
    get() = when (this) {
        HighlightMode.None -> "Off"
        HighlightMode.FillCurrent -> "Color word"
        HighlightMode.ScaleCurrent -> "Grow word"
        HighlightMode.UnderlineCurrent -> "Underline"
    }

private val HighlightMode.description: String
    get() = when (this) {
        HighlightMode.None -> "No per-word effect; each caption appears as one steady line."
        HighlightMode.FillCurrent -> "The word being spoken switches to the highlight color."
        HighlightMode.ScaleCurrent -> "The word being spoken grows slightly and uses the highlight color."
        HighlightMode.UnderlineCurrent -> "The word being spoken is underlined and uses the highlight color."
    }
