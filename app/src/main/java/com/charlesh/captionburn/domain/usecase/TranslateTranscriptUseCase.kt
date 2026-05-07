package com.charlesh.captionburn.domain.usecase

import com.charlesh.captionburn.data.translation.MlKitTranslator
import com.charlesh.captionburn.data.translation.TranslationException
import com.charlesh.captionburn.di.IoDispatcher
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.domain.model.Word
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

class TranslateTranscriptUseCase @Inject constructor(
    private val translator: MlKitTranslator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(
        transcript: Transcript,
        targetLanguage: String,
        sourceLanguage: String = transcript.detectedLanguage,
        whileDownloadingModel: suspend CoroutineScope.() -> Unit = {},
        onProgress: ((TranslateProgress) -> Unit)? = null,
    ): Transcript = withContext(ioDispatcher) {
        try {
            if (transcript.segments.isEmpty()) return@withContext transcript
            val normalizedTarget = targetLanguage.normalizeLanguageTag()
            val normalizedSource = sourceLanguage.normalizeLanguageTag()
            if (normalizedTarget == normalizedSource) return@withContext transcript

            val translatedSegments = translator.translateBatch(
                texts = transcript.segments.map { it.text },
                sourceLanguage = normalizedSource,
                targetLanguage = normalizedTarget,
                whileDownloadingModel = whileDownloadingModel,
            ) { completed, total ->
                onProgress?.invoke(TranslateProgress(completed = completed, total = total))
            }

            transcript.copy(
                segments = transcript.segments.mapIndexed { index, segment ->
                    val translatedText = translatedSegments.getOrNull(index).orEmpty()
                    segment.withTranslation(translatedText)
                }
            )
        } catch (error: Throwable) {
            throw error.toUseCaseError(targetLanguage)
        }
    }

    internal fun Segment.withTranslation(translatedText: String): Segment {
        if (translatedText.isBlank()) {
            return copy(translation = translatedText, translatedWords = emptyList())
        }
        return copy(
            translation = translatedText,
            translatedWords = redistributeTranslatedWords(
                translatedText = translatedText,
                segmentStartMs = startMs,
                segmentEndMs = endMs,
            ),
        )
    }

    internal fun redistributeTranslatedWords(
        translatedText: String,
        segmentStartMs: Long,
        segmentEndMs: Long,
    ): List<Word> {
        val tokens = translatedText
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val totalDuration = (segmentEndMs - segmentStartMs).coerceAtLeast(1L)
        val weights = tokens.map { token -> token.count { !it.isWhitespace() }.coerceAtLeast(1) }
        val weightSum = weights.sum().coerceAtLeast(tokens.size)

        var currentStart = segmentStartMs
        return tokens.mapIndexed { index, token ->
            val allocated = if (index == tokens.lastIndex) {
                (segmentEndMs - currentStart).coerceAtLeast(1L)
            } else {
                ((totalDuration * weights[index].toLong()) / weightSum.toLong()).coerceAtLeast(1L)
            }
            val proposedEnd = (currentStart + allocated).coerceAtMost(segmentEndMs)
            val end = if (index == tokens.lastIndex) {
                segmentEndMs
            } else {
                proposedEnd.coerceAtLeast(currentStart + 1L)
            }
            val word = Word(
                startMs = currentStart,
                endMs = end,
                text = token,
                confidence = 1f,
            )
            currentStart = end.coerceAtMost(segmentEndMs)
            word
        }
    }
}

data class TranslateProgress(
    val completed: Int,
    val total: Int,
)

sealed class TranslateTranscriptException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class InvalidTargetLanguage(language: String) :
        TranslateTranscriptException("Invalid target language: $language")

    object ModelDownloadRequiresWifi : TranslateTranscriptException(
        "Translation model download needs Wi-Fi. Connect to Wi-Fi or turn off Wi-Fi-only downloads in Settings."
    )

    class TranslationFailed(cause: Throwable) :
        TranslateTranscriptException(cause.toTranslationFailureMessage(), cause)
}

private fun String.normalizeLanguageTag(): String =
    lowercase()
        .replace('_', '-')
        .substringBefore('-')

private fun Throwable.toUseCaseError(targetLanguage: String): Throwable =
    when (this) {
        is TranslationException.UnsupportedLanguage ->
            TranslateTranscriptException.InvalidTargetLanguage(targetLanguage)
        is TranslationException.ModelDownloadRequiresWifi ->
            TranslateTranscriptException.ModelDownloadRequiresWifi
        is TranslationException.ModelOrTranslationFailed ->
            TranslateTranscriptException.TranslationFailed(this)
        else -> this
    }

private fun Throwable.toTranslationFailureMessage(): String {
    val root = generateSequence(this) { it.cause }
        .lastOrNull()
        ?.message
        ?.takeIf { it.isNotBlank() }
    return if (root == null) {
        "Transcript translation failed"
    } else {
        "Transcript translation failed: $root"
    }
}
