package com.charlesh.captionburn.data.transcription

import com.charlesh.captionburn.di.AppFiles
import com.charlesh.captionburn.di.IoDispatcher
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import timber.log.Timber
import com.charlesh.captionburn.data.telemetry.TelemetryTracker

sealed interface DownloadEvent {
    data class Progress(val bytesRead: Long, val total: Long) : DownloadEvent
    data class Complete(val file: File) : DownloadEvent
    data class Failed(val cause: Throwable) : DownloadEvent
}

class ModelDownloadHttpException(val code: Int) : IOException("Model download HTTP error $code")

class ModelIntegrityException(
    val expectedSha256: String,
    val actualSha256: String,
) : IOException("Model SHA-256 mismatch (expected=$expectedSha256, actual=$actualSha256)")

/**
 * Resumable model downloader. On retry, requests `Range: bytes=N-` and
 * appends to the partial file. Cancellation closes the network stream and
 * leaves the partial on disk so the next attempt resumes.
 */
@Singleton
class ModelDownloader @Inject constructor(
    private val client: OkHttpClient,
    @AppFiles private val filesDir: File,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val telemetry: TelemetryTracker,
) {
    fun modelsDir(): File = File(filesDir, "models").apply { mkdirs() }

    fun fileFor(spec: WhisperModelSpec): File = File(modelsDir(), spec.filename)

    private fun checksumFileFor(spec: WhisperModelSpec): File = File(modelsDir(), "${spec.filename}.sha256")

    fun isInstalled(spec: WhisperModelSpec): Boolean {
        val f = fileFor(spec)
        if (!f.exists()) return false
        if (!isLikelyExpectedSize(f, spec)) return false
        if (!hasValidGgmlHeader(f)) return false
        val expectedSha = runCatching { resolveExpectedSha(spec, allowNetwork = true) }.getOrNull()
        if (expectedSha.isNullOrBlank()) return true
        return runCatching { sha256Hex(f).equals(expectedSha, ignoreCase = true) }.getOrDefault(false)
    }

    private fun isLikelyExpectedSize(file: File, spec: WhisperModelSpec): Boolean {
        val ratio = file.length().toDouble() / spec.approxSizeBytes.toDouble()
        return ratio in 0.95..1.05
    }

    fun download(spec: WhisperModelSpec): Flow<DownloadEvent> = callbackFlow {
        val target = fileFor(spec)
        val partial = File(modelsDir(), "${spec.filename}.part")
        val haveBytes = if (partial.exists()) partial.length() else 0L

        val trace = telemetry.startTrace("model_download_duration")
        trace.putAttribute("model_choice", spec.choice.name)
        trace.putAttribute("filename", spec.filename)
        telemetry.logEvent("model_download_started", mapOf(
            "model_choice" to spec.choice.name,
            "filename" to spec.filename,
            "resuming" to (haveBytes > 0L)
        ))

        try {
            val req = Request.Builder()
                .url(spec.url)
                .apply { if (haveBytes > 0) header("Range", "bytes=$haveBytes-") }
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw ModelDownloadHttpException(resp.code)
                }
                val body = resp.body ?: throw IOException("empty body")
                val contentLength = body.contentLength()

                val resuming = haveBytes > 0 && resp.code == 206
                if (haveBytes > 0 && resp.code != 206) {
                    Timber.w("Server ignored Range header (HTTP %d); discarding partial", resp.code)
                    partial.delete()
                }

                val total = if (resuming && contentLength > 0) contentLength + haveBytes
                            else if (contentLength > 0) contentLength
                            else spec.approxSizeBytes

                val sink = partial.sink(append = resuming).buffer()
                var read = if (resuming) haveBytes else 0L
                trySend(DownloadEvent.Progress(read, total))

                body.source().use { src ->
                    val buf = okio.Buffer()
                    while (isActive) {
                        val n = src.read(buf, 64 * 1024L)
                        if (n == -1L) break
                        sink.write(buf, n)
                        read += n
                        trySend(DownloadEvent.Progress(read, total))
                    }
                    sink.flush()
                    sink.close()
                }
            }

            // Move .part into place atomically.
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("rename failed")

            if (!isLikelyExpectedSize(target, spec)) {
                target.delete()
                throw IOException("Model size is outside expected range")
            }
            if (!hasValidGgmlHeader(target)) {
                target.delete()
                throw IOException("Model does not appear to be a GGML binary")
            }

            val expectedSha = resolveExpectedSha(spec, allowNetwork = true)
            if (!expectedSha.isNullOrBlank()) {
                val actualSha = sha256Hex(target)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    target.delete()
                    throw ModelIntegrityException(expectedSha256 = expectedSha, actualSha256 = actualSha)
                }
            } else {
                Timber.w(
                    "No checksum available for %s; accepted model after size and GGML header validation",
                    spec.filename,
                )
            }

            Timber.i("Model downloaded: %s (%d bytes)", target.name, target.length())
            telemetry.logEvent("model_download_success", mapOf(
                "model_choice" to spec.choice.name,
                "filename" to spec.filename,
                "size_bytes" to target.length()
            ))
            trace.putAttribute("status", "success")
            trace.stop()
            trySend(DownloadEvent.Complete(target))
            close()
        } catch (t: Throwable) {
            Timber.e(t, "Model download failed")
            telemetry.logEvent("model_download_failed", mapOf(
                "model_choice" to spec.choice.name,
                "filename" to spec.filename,
                "error" to (t.message ?: t.javaClass.simpleName)
            ))
            trace.putAttribute("status", "failed")
            trace.putAttribute("error_type", t.javaClass.simpleName)
            trace.stop()
            trySend(DownloadEvent.Failed(t))
            close(t)
        }
        awaitClose { /* no-op; OkHttp call closes when scope cancels */ }
    }.flowOn(io)

    fun deleteAll() {
        modelsDir().listFiles()?.forEach { it.delete() }
    }

    fun delete(spec: WhisperModelSpec) {
        fileFor(spec).delete()
        File(modelsDir(), "${spec.filename}.part").delete()
        checksumFileFor(spec).delete()
    }

    private fun resolveExpectedSha(spec: WhisperModelSpec, allowNetwork: Boolean): String? {
        val embedded = spec.sha256?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
        if (!embedded.isNullOrBlank()) return embedded

        val cached = readCachedSha(spec)
        if (!cached.isNullOrBlank()) return cached

        if (!allowNetwork) return null

        return try {
            val fetched = fetchRemoteSha(spec)
            cacheSha(spec, fetched)
            fetched
        } catch (e: IOException) {
            Timber.w(e, "Could not resolve checksum for %s; falling back to size and GGML header checks", spec.filename)
            null
        }
    }

    private fun readCachedSha(spec: WhisperModelSpec): String? {
        val f = checksumFileFor(spec)
        if (!f.exists()) return null
        return runCatching { parseShaLine(f.readText()) }.getOrNull()
    }

    private fun cacheSha(spec: WhisperModelSpec, sha: String) {
        checksumFileFor(spec).writeText("$sha  ${spec.filename}\n")
    }

    private fun fetchRemoteSha(spec: WhisperModelSpec): String {
        val req = Request.Builder()
            .url("${spec.url}.sha256")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ModelDownloadHttpException(resp.code)
            val body = resp.body?.string()?.trim().orEmpty()
            if (body.isBlank()) throw IOException("Empty checksum sidecar for ${spec.filename}")
            return parseShaLine(body)
        }
    }

    private fun parseShaLine(raw: String): String {
        val sha = raw
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase()
            ?: throw IOException("Malformed checksum sidecar")
        if (!sha.matches(Regex("[0-9a-f]{64}"))) throw IOException("Malformed checksum hash")
        return sha
    }

    private fun hasValidGgmlHeader(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4L) return@use false
            val header = ByteArray(4)
            raf.readFully(header)
            val text = String(header, Charsets.US_ASCII)
            text == "ggml" || text == "lmgg"
        }
    }.getOrDefault(false)

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
