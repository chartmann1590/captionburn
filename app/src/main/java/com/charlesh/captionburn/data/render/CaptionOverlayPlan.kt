package com.charlesh.captionburn.data.render

import kotlinx.serialization.Serializable

/**
 * Recipe written by [BuildSubtitleWorker][com.charlesh.captionburn.service.workers.BuildSubtitleWorker]
 * and consumed by [FFmpegBurner]. Each item is one PNG to overlay on the
 * source video for a specific time window.
 */
@Serializable
data class CaptionOverlayPlan(
    val videoWidthPx: Int,
    val videoHeightPx: Int,
    val items: List<OverlayItem>,
)

@Serializable
data class OverlayItem(
    val pngPath: String,
    val startMs: Long,
    val endMs: Long,
    val xPx: Int,
    val yPx: Int,
)
