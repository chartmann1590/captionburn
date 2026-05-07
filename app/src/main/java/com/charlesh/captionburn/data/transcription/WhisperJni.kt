package com.charlesh.captionburn.data.transcription

/**
 * Thin JNI surface for whisper.cpp. Implementation lives in
 * [app/src/main/cpp/whisper-jni.cpp].
 *
 * One [WhisperJni] holds one whisper context. Constructing is cheap; loading a
 * model via [init] is expensive (memory-maps + decodes the GGML file) and
 * should happen at most once per app session.
 */
internal class WhisperJni {

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        handle: Long,
        wavPath: String,
        languageHint: String,
        nThreads: Int,
    ): String
    private external fun nativeRelease(handle: Long)

    @Volatile private var handle: Long = 0L
    val isInitialised: Boolean get() = handle != 0L

    /** @throws IllegalStateException if the model file cannot be loaded. */
    fun init(modelPath: String) {
        check(handle == 0L) { "WhisperJni already initialised" }
        val h = nativeInit(modelPath)
        check(h != 0L) { "whisper_init_from_file failed for $modelPath" }
        handle = h
    }

    /**
     * @param languageHint ISO-639-1 code or empty string for auto-detect.
     * @param nThreads pass 0 to let the native side pick.
     * @return JSON document; see whisper-jni.cpp header comment for the schema.
     */
    fun transcribe(wavPath: String, languageHint: String = "", nThreads: Int = 0): String {
        val h = handle
        check(h != 0L) { "WhisperJni not initialised" }
        return nativeTranscribe(h, wavPath, languageHint, nThreads)
    }

    fun release() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeRelease(h)
        }
    }

    companion object {
        init { System.loadLibrary("captionburn") }
    }
}
