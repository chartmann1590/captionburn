package com.charlesh.captionburn.data.transcription

import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun download_missingRemoteSidecar_acceptsSizeAndGgmlHeaderOnly() = runTest {
        val modelBytes = "ggml".toByteArray(Charsets.US_ASCII)
        val downloader = ModelDownloader(
            client = fakeClient(modelBytes = modelBytes, sidecarCode = 404),
            filesDir = temp.newFolder(),
            io = Dispatchers.Unconfined,
            telemetry = io.mockk.mockk(relaxed = true),
        )

        val events = downloader.download(testSpec(sha256 = null)).toList()

        assertThat(events.filterIsInstance<DownloadEvent.Failed>()).isEmpty()
        val complete = events.filterIsInstance<DownloadEvent.Complete>().single()
        assertThat(complete.file.exists()).isTrue()
        assertThat(complete.file.readBytes().asList()).containsExactlyElementsIn(modelBytes.asList()).inOrder()
    }

    @Test
    fun download_remoteSidecarHashValid_cachesChecksum() = runTest {
        val modelBytes = "ggml".toByteArray(Charsets.US_ASCII)
        val expectedSha = sha256Hex(modelBytes)
        val filesDir = temp.newFolder()
        val downloader = ModelDownloader(
            client = fakeClient(
                modelBytes = modelBytes,
                sidecarCode = 200,
                sidecarBody = "$expectedSha  test.bin\n",
            ),
            filesDir = filesDir,
            io = Dispatchers.Unconfined,
            telemetry = io.mockk.mockk(relaxed = true),
        )

        val events = downloader.download(testSpec(sha256 = null)).toList()

        assertThat(events.filterIsInstance<DownloadEvent.Complete>()).hasSize(1)
        assertThat(filesDir.resolve("models/test.bin.sha256").readText()).isEqualTo("$expectedSha  test.bin\n")
    }

    private fun fakeClient(
        modelBytes: ByteArray,
        sidecarCode: Int,
        sidecarBody: String = "",
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val isSidecar = chain.request().url.encodedPath.endsWith(".sha256")
            val code = if (isSidecar) sidecarCode else 200
            val body = if (isSidecar) {
                sidecarBody.toResponseBody("text/plain".toMediaType())
            } else {
                modelBytes.toResponseBody("application/octet-stream".toMediaType())
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Not Found")
                .body(body)
                .build()
        }
        .build()

    private fun testSpec(sha256: String?): WhisperModelSpec = WhisperModelSpec(
        id = "test",
        choice = WhisperModelChoice.Tiny,
        filename = "test.bin",
        url = "https://example.test/test.bin",
        approxSizeBytes = 4L,
        sha256 = sha256,
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
