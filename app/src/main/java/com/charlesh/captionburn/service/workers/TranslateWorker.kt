package com.charlesh.captionburn.service.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.charlesh.captionburn.data.project.ProjectRepository
import com.charlesh.captionburn.domain.model.DisplayMode
import com.charlesh.captionburn.domain.model.ProjectStatus
import com.charlesh.captionburn.domain.usecase.TranslateProgress
import com.charlesh.captionburn.domain.usecase.TranslateTranscriptException
import com.charlesh.captionburn.domain.usecase.TranslateTranscriptUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@HiltWorker
class TranslateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val projects: ProjectRepository,
    private val translateTranscript: TranslateTranscriptUseCase,
) : CoroutineWorker(appContext, params) {
    private fun normalizeLanguageTag(value: String?): String? =
        value
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.ifBlank { null }

    override suspend fun doWork(): Result {
        updateProcessingForeground(stage = "download-translation-model", progress = 0.45f)

        val projectId = inputData.getString(PipelineWorkData.KEY_PROJECT_ID)
            ?: return Result.failure(PipelineWorkData.failure("Missing project id", retryable = false))
        val project = projects.getProject(projectId)
            ?: return Result.failure(PipelineWorkData.failure("Project not found", retryable = false))
        val transcript = project.transcript
            ?: return Result.failure(PipelineWorkData.failure("No transcript available", retryable = false))
        val targetLanguage = project.style.targetLanguage
        val normalizedTarget = normalizeLanguageTag(targetLanguage)
        val normalizedDetected = normalizeLanguageTag(transcript.detectedLanguage)

        if (
            project.style.displayMode == DisplayMode.Original ||
            normalizedTarget == null ||
            normalizedTarget == normalizedDetected
        ) {
            return Result.success()
        }

        projects.updateProject(projectId) { it.copy(status = ProjectStatus.Translating, errorMessage = null) }
        setProgress(PipelineWorkData.progress(stage = "download-translation-model", progress = 0.45f))

        return runCatching {
            val translated = translateTranscript(
                transcript = transcript,
                targetLanguage = normalizedTarget,
                whileDownloadingModel = {
                    var p = 0.45f
                    while (isActive) {
                        delay(2_500)
                        p = (p + 0.008f).coerceAtMost(0.50f)
                        setProgressAsync(PipelineWorkData.progress("download-translation-model", p))
                        updateProcessingForegroundAsync("download-translation-model", p)
                    }
                },
                onProgress = { progress: TranslateProgress ->
                    val normalized = if (progress.total == 0) 1f else progress.completed.toFloat() / progress.total.toFloat()
                    val overall = 0.50f + (normalized * 0.15f)
                    setProgressAsync(PipelineWorkData.progress("translate", overall))
                    updateProcessingForegroundAsync("translate", overall)
                },
            )
            projects.updateProject(projectId) {
                it.copy(
                    status = ProjectStatus.Ready,
                    transcript = translated,
                    errorMessage = null,
                )
            }
            Result.success()
        }.getOrElse { error ->
            val message = error.message ?: "Translation failed"
            projects.updateProject(projectId) { it.copy(status = ProjectStatus.Failed, errorMessage = message) }
            if (error.shouldRetryAutomatically() && runAttemptCount < 2) {
                Result.retry()
            } else {
                Result.failure(PipelineWorkData.failure(message = message, retryable = error.isRetryableByUser()))
            }
        }
    }

    private fun Throwable.shouldRetryAutomatically(): Boolean =
        this !is TranslateTranscriptException.ModelDownloadRequiresWifi &&
            this !is TranslateTranscriptException.InvalidTargetLanguage

    private fun Throwable.isRetryableByUser(): Boolean =
        this !is TranslateTranscriptException.InvalidTargetLanguage
}
