package com.charlesh.captionburn.data.transcription

import android.net.Uri
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface TranscriptionProgress {
    data object ExtractingAudio : TranscriptionProgress
    data object LoadingModel : TranscriptionProgress
    data object Transcribing : TranscriptionProgress
    data class Done(val transcript: Transcript) : TranscriptionProgress
    data class Failed(
        val stage: Stage,
        val cause: Throwable,
        val userMessage: String,
    ) : TranscriptionProgress

    enum class Stage {
        AudioExtract,
        ModelLoad,
        Transcribe,
        Unknown,
    }
}

/**
 * End-to-end orchestrator for one transcription job: extract audio, ensure the
 * model is loaded, run Whisper, return a [Transcript]. Composes
 * [AudioExtractor], [ModelDownloader], and [WhisperEngine].
 */
@Singleton
class TranscriptionService @Inject constructor(
    private val audio: AudioExtractor,
    private val downloader: ModelDownloader,
    private val engine: WhisperEngine,
) {
    fun transcribe(
        sourceUri: Uri,
        projectId: String,
        modelChoice: WhisperModelChoice,
        languageHint: String? = null,
    ): Flow<TranscriptionProgress> = flow {
        var stage = TranscriptionProgress.Stage.Unknown
        try {
            stage = TranscriptionProgress.Stage.AudioExtract
            emit(TranscriptionProgress.ExtractingAudio)
            val wav = audio.extract(sourceUri, projectId).getOrThrow()

            val spec = WhisperModelCatalog.byChoice(modelChoice)
            if (!downloader.isInstalled(spec)) {
                downloader.delete(spec)
                error("Model ${spec.filename} is missing or corrupted. Re-download it from Settings.")
            }
            val modelFile = downloader.fileFor(spec)

            stage = TranscriptionProgress.Stage.ModelLoad
            emit(TranscriptionProgress.LoadingModel)
            engine.ensureLoaded(modelFile.absolutePath)

            stage = TranscriptionProgress.Stage.Transcribe
            emit(TranscriptionProgress.Transcribing)
            val transcript = engine.transcribe(
                wavPath = wav.absolutePath,
                languageHint = languageHint,
            ).getOrThrow()

            emit(TranscriptionProgress.Done(transcript))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            emit(
                TranscriptionProgress.Failed(
                    stage = stage,
                    cause = t,
                    userMessage = userFacingMessage(stage, t),
                )
            )
        }
    }

    private fun userFacingMessage(stage: TranscriptionProgress.Stage, cause: Throwable): String {
        return when {
            stage == TranscriptionProgress.Stage.AudioExtract ->
                "Could not extract audio from the selected video."
            stage == TranscriptionProgress.Stage.ModelLoad ->
                "Whisper model could not be loaded. Try re-downloading it from onboarding."
            stage == TranscriptionProgress.Stage.Transcribe ->
                "Transcription failed. Try again or choose a smaller model."
            else -> cause.message ?: "Transcription failed."
        }
    }
}
