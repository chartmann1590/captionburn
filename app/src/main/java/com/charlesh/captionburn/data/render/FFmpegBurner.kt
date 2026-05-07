package com.charlesh.captionburn.data.render

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.charlesh.captionburn.data.media.MediaStorePublisher
import com.charlesh.captionburn.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

private const val FFMPEG_LOG_TAIL_MAX_CHARS = 48 * 1024

@Singleton
class FFmpegBurner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStorePublisher: MediaStorePublisher,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    data class BurnResult(
        val publishedUri: Uri,
        val cachedFilePath: String,
    )

    internal data class FfmpegOutcome(
        val returnCode: Int,
        val logTail: String,
    )

    suspend fun burnAndPublish(
        sourceUri: Uri,
        assFile: File,
        projectId: String,
        outputDisplayName: String,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): Result<BurnResult> = withContext(io) {
        runCatching {
            val out = File(context.cacheDir, "exports").apply { mkdirs() }
                .resolve("${projectId}_${System.currentTimeMillis()}.mp4")
            if (out.exists()) out.delete()

            val mapped = FFmpegKitConfig.getSafParameterForRead(context, sourceUri)
                ?: sourceUri.toString()

            // BuildSubtitleWorker writes a CaptionOverlayPlan JSON next to the
            // rendered PNGs. Parse it and assemble the multi-input overlay
            // command.
            val plan: CaptionOverlayPlan = json.decodeFromString(assFile.readText())
            val cmd = buildBurnCommand(mapped, plan, out)

            val outcome = runFfmpeg(command = cmd, durationMs = durationMs, onProgress = onProgress)
            if (outcome.returnCode != 0) {
                Timber.e("FFmpeg burn failed (rc=%s)\n%s", outcome.returnCode, outcome.logTail)
                error(
                    buildString {
                        append("FFmpeg burn failed (rc=${outcome.returnCode})")
                        if (outcome.logTail.isNotBlank()) {
                            append("\n")
                            append(outcome.logTail)
                        }
                    },
                )
            }
            check(out.exists() && out.length() > 0) { "Rendered output missing" }
            val publishedUri = mediaStorePublisher.publishVideo(out, outputDisplayName)
            onProgress(1f)
            Timber.i("Burned and published captions: %s", publishedUri)
            BurnResult(
                publishedUri = publishedUri,
                cachedFilePath = out.absolutePath,
            ).also {
                out.delete()
            }
        }
    }

    internal suspend fun runFfmpeg(
        command: String,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): FfmpegOutcome = suspendCancellableCoroutine { cont ->
        val total = durationMs.coerceAtLeast(1L).toDouble()
        val logBuffer = StringBuilder()
        fun appendLogLine(line: String) {
            if (line.isEmpty()) return
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(line)
            val over = logBuffer.length - FFMPEG_LOG_TAIL_MAX_CHARS
            if (over > 0) logBuffer.delete(0, over)
        }
        val session = FFmpegKit.executeAsync(
            command,
            { s ->
                val tailFromSession = sessionLogTail(s)
                val merged = when {
                    tailFromSession.isNotBlank() -> tailFromSession
                    logBuffer.isNotEmpty() -> logBuffer.toString().trimEnd()
                    else -> ""
                }
                cont.resume(FfmpegOutcome(s.returnCode.value, merged))
            },
            { log ->
                appendLogLine(log.message)
                val timestampMs = parseFfmpegTimeMs(log.message)
                if (timestampMs != null) {
                    val p = (timestampMs / total).coerceIn(0.0, 1.0).toFloat()
                    onProgress(p)
                }
            },
            null,
        )
        cont.invokeOnCancellation {
            FFmpegKit.cancel(session.sessionId)
        }
    }

    private fun sessionLogTail(session: FFmpegSession): String {
        val fromGetter = session.allLogsAsString
        if (!fromGetter.isNullOrBlank()) return fromGetter.trimEnd().takeLast(FFMPEG_LOG_TAIL_MAX_CHARS)
        return session.logs
            .mapNotNull { it.message }
            .joinToString("\n")
            .trimEnd()
            .takeLast(FFMPEG_LOG_TAIL_MAX_CHARS)
    }

    /**
     * Build the FFmpeg invocation. The plan tells us which PNGs to overlay and
     * when. Each PNG becomes a separate `-i` input; the filter graph chains
     * an `overlay` filter per item with a time-bounded `enable=between(t,...)`.
     *
     * Why overlay-of-PNGs instead of `drawtext` or `subtitles`/`ass`: the
     * Android FFmpegKit binary we ship has had every text-rendering filter
     * stripped from libavfilter (verified by inspecting its symbol strings).
     * `overlay` survived, which is enough — we render captions to PNGs on
     * the Android side via Canvas and let FFmpeg composite them.
     */
    internal fun buildBurnCommand(
        mappedSource: String,
        plan: CaptionOverlayPlan,
        outputFile: File,
    ): String = buildString {
        append("-y ")
        append("-i \"").append(mappedSource).append("\" ")

        if (plan.items.isEmpty()) {
            // No captions to burn — passthrough re-encode for codec/container
            // hygiene. Audio mapped optionally so silent sources don't fail.
            append("-map 0:v:0 -map 0:a? ")
        } else {
            // One -i per overlay PNG. We don't add -loop 1 because overlay's
            // default eof_action=repeat keeps the single image frame available
            // throughout the timeline.
            plan.items.forEach { item ->
                append("-i \"").append(item.pngPath).append("\" ")
            }
            append("-filter_complex \"")
            append(buildOverlayFilterChain(plan.items))
            append("\" ")
            append("-map \"[v").append(plan.items.size).append("]\" ")
            append("-map 0:a? ")
        }

        // ffmpeg-kit-full-gpl ships 8-bit-only libx264; modern phones record
        // yuv420p10le, so force 8-bit output explicitly.
        append("-c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p ")
        append("-c:a aac -b:a 160k ")
        // Streamable MP4 — moov atom moves to the start so MediaStore playback
        // / share previews open instantly.
        append("-movflags +faststart ")
        append("\"").append(outputFile.absolutePath).append("\"")
    }

    private fun buildOverlayFilterChain(items: List<OverlayItem>): String = buildString {
        // [0:v][1:v]overlay=...:enable='...'[v1];
        // [v1][2:v]overlay=...:enable='...'[v2];
        // ...
        // [v{N-1}][N:v]overlay=...:enable='...'[vN]
        items.forEachIndexed { idx, item ->
            val prevLabel = if (idx == 0) "[0:v]" else "[v$idx]"
            val inputLabel = "[${idx + 1}:v]"
            val outLabel = "[v${idx + 1}]"
            append(prevLabel).append(inputLabel)
            append("overlay=x=").append(item.xPx)
            append(":y=").append(item.yPx)
            append(":enable='between(t,")
            append(formatSeconds(item.startMs / 1000.0))
            append(',')
            append(formatSeconds(item.endMs / 1000.0))
            append(")'")
            append(outLabel)
            if (idx != items.lastIndex) append(';')
        }
    }

    private fun formatSeconds(seconds: Double): String =
        "%.3f".format(seconds)

    internal fun parseFfmpegTimeMs(logLine: String): Double? {
        val match = TIME_REGEX.find(logLine) ?: return null
        val (hh, mm, ss, fraction) = match.destructured
        val h = hh.toDoubleOrNull() ?: return null
        val m = mm.toDoubleOrNull() ?: return null
        val s = ss.toDoubleOrNull() ?: return null
        val frac = fraction.toDoubleOrNull() ?: return null
        return (((h * 3600) + (m * 60) + s) * 1000.0) + (frac * 10.0)
    }

    private companion object {
        val TIME_REGEX = Regex("""time=(\d{2}):(\d{2}):(\d{2})\.(\d{2})""")
    }
}
