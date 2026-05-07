package com.charlesh.captionburn.data.render

import android.content.Context
import com.charlesh.captionburn.data.media.MediaStorePublisher
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.junit.Test

class FFmpegBurnerTest {

    private val burner = FFmpegBurner(
        context = mockk<Context>(relaxed = true),
        mediaStorePublisher = mockk<MediaStorePublisher>(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
        io = Dispatchers.Unconfined,
    )

    @Test
    fun parseFfmpegTimeMs_extractsExpectedMilliseconds() {
        val parsed = burner.parseFfmpegTimeMs("frame=  51 fps=22.1 time=00:01:02.34 bitrate=1200kbits/s")
        assertThat(parsed).isEqualTo(62_340.0)
    }

    @Test
    fun parseFfmpegTimeMs_returnsNullWhenNoTimestamp() {
        val parsed = burner.parseFfmpegTimeMs("just some ffmpeg log text")
        assertThat(parsed).isNull()
    }

    @Test
    fun buildBurnCommand_emptyPlan_passesVideoThroughWithoutFilter() {
        val out = File("/data/exports/out.mp4")
        val plan = CaptionOverlayPlan(videoWidthPx = 1920, videoHeightPx = 1080, items = emptyList())

        val cmd = burner.buildBurnCommand(
            mappedSource = "content://media/video/123",
            plan = plan,
            outputFile = out,
        )

        assertThat(cmd).contains("-i \"content://media/video/123\"")
        assertThat(cmd).contains("-map 0:v:0 -map 0:a?")
        assertThat(cmd).doesNotContain("-filter_complex")
        assertThat(cmd).contains("-pix_fmt yuv420p")
        assertThat(cmd).contains("-c:a aac -b:a 160k")
        assertThat(cmd).contains("-movflags +faststart")
        assertThat(cmd).contains("\"${out.absolutePath}\"")
    }

    @Test
    fun buildBurnCommand_chainsOverlayPerItem() {
        val out = File("/data/exports/out.mp4")
        val plan = CaptionOverlayPlan(
            videoWidthPx = 1920,
            videoHeightPx = 1080,
            items = listOf(
                OverlayItem(pngPath = "/cache/seg-0001.png", startMs = 1200, endMs = 7200, xPx = 100, yPx = 900),
                OverlayItem(pngPath = "/cache/seg-0002.png", startMs = 7200, endMs = 12_200, xPx = 100, yPx = 900),
            ),
        )

        val cmd = burner.buildBurnCommand(
            mappedSource = "content://media/video/123",
            plan = plan,
            outputFile = out,
        )

        // One -i per overlay PNG plus the source video.
        assertThat(cmd).contains("-i \"content://media/video/123\"")
        assertThat(cmd).contains("-i \"/cache/seg-0001.png\"")
        assertThat(cmd).contains("-i \"/cache/seg-0002.png\"")
        // Filter chain wires each input into a numbered video output.
        assertThat(cmd).contains("[0:v][1:v]overlay=x=100:y=900:enable='between(t,1.200,7.200)'[v1]")
        assertThat(cmd).contains("[v1][2:v]overlay=x=100:y=900:enable='between(t,7.200,12.200)'[v2]")
        // Final mapped video stream is the last numbered overlay output.
        assertThat(cmd).contains("-map \"[v2]\"")
        assertThat(cmd).contains("-map 0:a?")
        assertThat(cmd).contains("-pix_fmt yuv420p")
        assertThat(cmd).contains("-movflags +faststart")
    }
}
