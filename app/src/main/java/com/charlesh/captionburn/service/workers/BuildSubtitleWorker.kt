package com.charlesh.captionburn.service.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.charlesh.captionburn.data.project.ProjectRepository
import com.charlesh.captionburn.data.render.CaptionImageRenderer
import com.charlesh.captionburn.data.render.CaptionOverlayPlan
import com.charlesh.captionburn.data.render.OverlayItem
import com.charlesh.captionburn.domain.model.CaptionPosition
import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.DisplayMode
import com.charlesh.captionburn.domain.model.HighlightMode
import com.charlesh.captionburn.domain.model.ProjectStatus
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Word
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltWorker
class BuildSubtitleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val projects: ProjectRepository,
    private val captionImageRenderer: CaptionImageRenderer,
    private val json: Json,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        updateProcessingForeground(stage = "build-subtitles", progress = 0.7f)

        val projectId = inputData.getString(PipelineWorkData.KEY_PROJECT_ID)
            ?: return Result.failure(PipelineWorkData.failure("Missing project id", retryable = false))
        val project = projects.getProject(projectId)
            ?: return Result.failure(PipelineWorkData.failure("Project not found", retryable = false))
        val transcript = project.transcript
            ?: return Result.failure(PipelineWorkData.failure("No transcript available", retryable = false))

        setProgress(PipelineWorkData.progress(stage = "build-subtitles", progress = 0.7f))
        projects.updateProject(projectId) { it.copy(status = ProjectStatus.Burning, errorMessage = null) }

        return runCatching {
            // The FFmpegKit fork we ship is missing every text-rendering filter
            // (drawtext, ass, subtitles), so we render captions to PNGs via
            // Android Canvas and composite them in FFmpeg via the `overlay`
            // filter — which IS in this build.
            //
            // Output: one PNG per visible caption + a JSON plan listing them.
            val outDir = File(applicationContext.cacheDir, "subtitles/$projectId").apply {
                deleteRecursively()
                mkdirs()
            }

            val items = mutableListOf<OverlayItem>()
            val style = project.style
            val (videoW, videoH) = orientedVideoDimensions(project.widthPx, project.heightPx)

            transcript.segments.forEachIndexed { index, segment ->
                renderSegment(segment, style, outDir, index, videoW, videoH)
                    .forEach { items += it }
            }

            val plan = CaptionOverlayPlan(
                videoWidthPx = videoW,
                videoHeightPx = videoH,
                items = items,
            )
            val planFile = File(outDir, "plan.json")
            planFile.writeText(json.encodeToString(plan))

            Result.success(
                Data.Builder()
                    .putString(PipelineWorkData.KEY_ASS_PATH, planFile.absolutePath)
                    .build()
            )
        }.getOrElse { error ->
            val message = error.message ?: "Failed to prepare subtitles"
            projects.updateProject(projectId) { it.copy(status = ProjectStatus.Failed, errorMessage = message) }
            Result.failure(PipelineWorkData.failure(message, retryable = false))
        }
    }

    private fun renderSegment(
        segment: Segment,
        style: CaptionStyle,
        outDir: File,
        index: Int,
        videoWidthPx: Int,
        videoHeightPx: Int,
    ): List<OverlayItem> {
        val items = mutableListOf<OverlayItem>()

        when (style.displayMode) {
            DisplayMode.Original -> {
                items += renderLine(
                    words = segment.words,
                    fallbackText = segment.text,
                    style = style,
                    outDir = outDir,
                    filePrefix = "seg-${"%04d".format(index)}-orig",
                    segment = segment,
                    videoWidthPx = videoWidthPx,
                    videoHeightPx = videoHeightPx,
                    mirrored = false,
                )
            }
            DisplayMode.Translated -> {
                items += renderLine(
                    words = segment.translatedWords,
                    fallbackText = segment.translation?.takeIf { it.isNotBlank() } ?: segment.text,
                    style = style,
                    outDir = outDir,
                    filePrefix = "seg-${"%04d".format(index)}-trans",
                    segment = segment,
                    videoWidthPx = videoWidthPx,
                    videoHeightPx = videoHeightPx,
                    mirrored = false,
                )
            }
            DisplayMode.Both -> {
                items += renderLine(
                    words = segment.words,
                    fallbackText = segment.text,
                    style = style,
                    outDir = outDir,
                    filePrefix = "seg-${"%04d".format(index)}-orig",
                    segment = segment,
                    videoWidthPx = videoWidthPx,
                    videoHeightPx = videoHeightPx,
                    mirrored = false,
                )
                segment.translation
                    ?.takeIf { it.isNotBlank() && it != segment.text }
                    ?.let { translated ->
                        items += renderLine(
                            words = segment.translatedWords,
                            fallbackText = translated,
                            style = style,
                            outDir = outDir,
                            filePrefix = "seg-${"%04d".format(index)}-trans",
                            segment = segment,
                            videoWidthPx = videoWidthPx,
                            videoHeightPx = videoHeightPx,
                            mirrored = true,
                        )
                    }
            }
        }
        return items
    }

    private fun renderLine(
        words: List<Word>,
        fallbackText: String,
        style: CaptionStyle,
        outDir: File,
        filePrefix: String,
        segment: Segment,
        videoWidthPx: Int,
        videoHeightPx: Int,
        mirrored: Boolean,
    ): List<OverlayItem> {
        val captionWords = words.ifEmpty {
            buildFallbackWords(fallbackText, segment.startMs, segment.endMs)
        }
        if (captionWords.isEmpty()) return emptyList()

        val effectiveStyle = if (mirrored) {
            style.copy(position = mirrorPosition(style.position))
        } else style
        val windows = if (style.highlightMode == HighlightMode.None) {
            listOf(CaptionWindow(activeWordIndex = null, startMs = segment.startMs, endMs = segment.endMs))
        } else {
            captionWords.highlightWindows(segment.startMs, segment.endMs)
        }

        return windows.map { window ->
            val suffix = window.activeWordIndex?.let { "w-${"%03d".format(it)}" } ?: "full"
            val rendered = captionImageRenderer.renderWords(
                words = captionWords.map { it.text },
                activeWordIndex = window.activeWordIndex,
                style = effectiveStyle,
                outputFile = File(outDir, "$filePrefix-$suffix.png"),
                videoWidthPx = videoWidthPx,
                videoHeightPx = videoHeightPx,
            )
            OverlayItem(
                pngPath = rendered.file.absolutePath,
                startMs = window.startMs,
                endMs = window.endMs.coerceAtLeast(window.startMs + 50L),
                xPx = rendered.xPx,
                yPx = rendered.yPx,
            )
        }
    }

    private data class CaptionWindow(
        val activeWordIndex: Int?,
        val startMs: Long,
        val endMs: Long,
    )

    private fun List<Word>.highlightWindows(segmentStartMs: Long, segmentEndMs: Long): List<CaptionWindow> {
        val safeSegmentEnd = segmentEndMs.coerceAtLeast(segmentStartMs + 50L)
        val latestStart = safeSegmentEnd - 1L
        return mapIndexed { index, word ->
            val start = if (index == 0) {
                segmentStartMs
            } else {
                word.startMs.coerceIn(segmentStartMs, latestStart)
            }
            val nextStart = getOrNull(index + 1)
                ?.startMs
                ?.coerceIn(segmentStartMs, safeSegmentEnd)
            val end = (nextStart?.minus(1L) ?: safeSegmentEnd)
                .coerceIn(start + 1L, safeSegmentEnd)
            CaptionWindow(
                activeWordIndex = index,
                startMs = start,
                endMs = end,
            )
        }
    }

    private fun buildFallbackWords(text: String, startMs: Long, endMs: Long): List<Word> {
        val tokens = text
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val duration = (endMs - startMs).coerceAtLeast(1L)
        val step = (duration / tokens.size).coerceAtLeast(1L)
        return tokens.mapIndexed { index, token ->
            val wordStart = startMs + index * step
            val wordEnd = if (index == tokens.lastIndex) {
                endMs
            } else {
                (wordStart + step - 1L).coerceAtMost(endMs)
            }
            Word(startMs = wordStart, endMs = wordEnd, text = token)
        }
    }

    private fun mirrorPosition(
        position: CaptionPosition,
    ): CaptionPosition = when (position) {
        CaptionPosition.BottomLeft ->
            CaptionPosition.TopLeft
        CaptionPosition.BottomCenter ->
            CaptionPosition.TopCenter
        CaptionPosition.BottomRight ->
            CaptionPosition.TopRight
        CaptionPosition.TopLeft ->
            CaptionPosition.BottomLeft
        CaptionPosition.TopCenter ->
            CaptionPosition.BottomCenter
        CaptionPosition.TopRight ->
            CaptionPosition.BottomRight
        CaptionPosition.MiddleLeft ->
            CaptionPosition.TopLeft
        CaptionPosition.MiddleCenter ->
            CaptionPosition.TopCenter
        CaptionPosition.MiddleRight ->
            CaptionPosition.TopRight
    }

    /**
     * MediaMetadataRetriever returns native (pre-rotation) dimensions on most
     * devices but already-rotated dimensions on others. ProjectRepository
     * normalises this for us, so we just use the values it stored. Returns
     * `(width, height)` of the rendered (post-rotation) frame.
     */
    private fun orientedVideoDimensions(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val w = widthPx.takeIf { it > 0 } ?: 1920
        val h = heightPx.takeIf { it > 0 } ?: 1080
        return w to h
    }
}
