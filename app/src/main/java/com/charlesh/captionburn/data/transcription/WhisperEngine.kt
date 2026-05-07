package com.charlesh.captionburn.data.transcription

import com.charlesh.captionburn.di.IoDispatcher
import com.charlesh.captionburn.domain.model.Transcript
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * High-level coroutine-friendly wrapper around [WhisperJni].
 *
 * The native context is single-threaded — concurrent transcribe calls would
 * race the same KV cache. We serialise calls behind a mutex; the public API
 * is suspending so callers don't have to think about it.
 */
@Singleton
class WhisperEngine @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val json: Json,
) {
    private val jni = WhisperJni()
    private val lock = Mutex()
    private var loadedModelPath: String? = null

    val isReady: Boolean get() = jni.isInitialised

    suspend fun ensureLoaded(modelPath: String) = lock.withLock {
        if (loadedModelPath == modelPath && jni.isInitialised) return@withLock
        if (jni.isInitialised) {
            withContext(io) { jni.release() }
            loadedModelPath = null
        }
        withContext(io) {
            Timber.i("Loading Whisper model %s", modelPath)
            runCatching { jni.init(modelPath) }
                .onFailure { Timber.e(it, "Whisper init failed for %s", modelPath) }
                .getOrThrow()
        }
        loadedModelPath = modelPath
    }

    suspend fun transcribe(
        wavPath: String,
        languageHint: String? = null,
        nThreads: Int = 0,
    ): Result<Transcript> = lock.withLock {
        check(jni.isInitialised) { "Call ensureLoaded() first" }
        runCatching {
            val raw = withContext(io) {
                jni.transcribe(wavPath, languageHint.orEmpty(), nThreads)
            }
            val parsed = json.decodeFromString<WhisperJsonResponse>(raw)
            if (!parsed.error.isNullOrEmpty()) error("whisper: ${parsed.error}")
            parsed.toTranscript()
        }.onFailure { Timber.e(it, "Transcription failed") }
    }

    suspend fun close() = lock.withLock {
        if (jni.isInitialised) withContext(io) { jni.release() }
        loadedModelPath = null
    }
}
