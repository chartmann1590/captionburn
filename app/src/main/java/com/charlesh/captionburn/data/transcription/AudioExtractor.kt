package com.charlesh.captionburn.data.transcription

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.charlesh.captionburn.di.AppFiles
import com.charlesh.captionburn.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val FFMPEG_LOG_TAIL_MAX_CHARS = 48 * 1024

/**
 * Extracts a 16 kHz mono PCM WAV from any video / audio source URI. The
 * resulting file is what whisper.cpp expects; everything that touches the
 * model goes through this.
 */
@Singleton
class AudioExtractor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    @AppFiles private val filesDir: File,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private fun audioCacheDir(): File = File(filesDir, "audio").apply { mkdirs() }

    /**
     * @param sourceUri content:// or file:// uri of the user's video.
     * @param projectId stable id used as filename so cleanup is straightforward.
     */
    suspend fun extract(sourceUri: Uri, projectId: String): Result<File> = withContext(io) {
        runCatching {
            val out = File(audioCacheDir(), "$projectId.wav")
            if (out.exists()) out.delete()

            // FFmpegKit can read SAF/content:// URIs by mapping them to a "saf:" path.
            val mapped = FFmpegKitConfig.getSafParameterForRead(ctx, sourceUri)
                ?: sourceUri.toString()

            // -y                 overwrite output
            // -i <input>         input
            // -vn                drop video
            // -ac 1              mono
            // -ar 16000          16 kHz
            // -c:a pcm_s16le     16-bit signed little-endian PCM (uncompressed WAV)
            val cmd = buildString {
                append("-y ")
                append("-i \"").append(mapped).append("\" ")
                append("-vn -ac 1 -ar 16000 -c:a pcm_s16le ")
                append("\"").append(out.absolutePath).append("\"")
            }

            val outcome = runFfmpeg(cmd)
            if (outcome.returnCode != 0) {
                Timber.e("FFmpeg audio extract failed (rc=%s)\n%s", outcome.returnCode, outcome.logTail)
                error(
                    buildString {
                        append("FFmpeg audio extract failed (rc=${outcome.returnCode})")
                        if (outcome.logTail.isNotBlank()) {
                            append("\n")
                            append(outcome.logTail)
                        }
                    },
                )
            }
            check(out.exists() && out.length() > 44) { "WAV output empty" }
            Timber.i("Extracted audio: %s (%d bytes)", out.name, out.length())
            out
        }
    }

    private data class FfmpegOutcome(val returnCode: Int, val logTail: String)

    private suspend fun runFfmpeg(command: String): FfmpegOutcome =
        suspendCancellableCoroutine { cont ->
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
                { log -> appendLogLine(log.message) },
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
}
