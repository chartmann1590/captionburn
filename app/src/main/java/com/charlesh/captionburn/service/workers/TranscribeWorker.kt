package com.charlesh.captionburn.service.workers

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.charlesh.captionburn.data.project.ProjectRepository
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.charlesh.captionburn.data.transcription.TranscriptionProgress
import com.charlesh.captionburn.data.transcription.TranscriptionService
import com.charlesh.captionburn.domain.model.ProjectStatus

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class TranscribeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val projects: ProjectRepository,
    private val settings: SettingsRepository,
    private val transcriptionService: TranscriptionService,
    private val telemetry: com.charlesh.captionburn.data.telemetry.TelemetryTracker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(PipelineWorkData.KEY_PROJECT_ID)
            ?: return Result.failure(PipelineWorkData.failure("Missing project id", retryable = false))
        val project = projects.getProject(projectId)
            ?: return Result.failure(PipelineWorkData.failure("Project not found", retryable = false))

        projects.updateProject(projectId) { it.copy(status = ProjectStatus.Transcribing, errorMessage = null) }
        setProgress(PipelineWorkData.progress(stage = "transcribe", progress = 0.05f))

        val model = settings.installedModel.first()
            ?: return Result.failure(PipelineWorkData.failure("No Whisper model configured. Complete onboarding first.", retryable = false))

        telemetry.logEvent("transcribe_started", mapOf(
            "projectId" to projectId,
            "model" to model.name
        ))
        val trace = telemetry.startTrace("transcribe_job_duration")
        trace.putAttribute("model", model.name)

        val lastProgress = transcriptionService.transcribe(
            sourceUri = Uri.parse(project.sourceUri),
            projectId = projectId,
            modelChoice = model,
        ).first { progress ->
            when (progress) {
                is TranscriptionProgress.ExtractingAudio -> setProgress(PipelineWorkData.progress("extract-audio", 0.12f))
                is TranscriptionProgress.LoadingModel -> setProgress(PipelineWorkData.progress("load-model", 0.2f))
                is TranscriptionProgress.Transcribing -> setProgress(PipelineWorkData.progress("transcribe", 0.35f))
                else -> Unit
            }
            progress is TranscriptionProgress.Done || progress is TranscriptionProgress.Failed
        }

        return when (lastProgress) {
            is TranscriptionProgress.Done -> {
                telemetry.logEvent("transcribe_success", mapOf(
                    "projectId" to projectId,
                    "model" to model.name
                ))
                trace.putAttribute("status", "success")
                trace.stop()

                projects.updateProject(projectId) {
                    it.copy(
                        status = ProjectStatus.Ready,
                        transcript = lastProgress.transcript,
                        errorMessage = null,
                    )
                }
                Result.success()
            }
            is TranscriptionProgress.Failed -> {
                telemetry.logEvent("transcribe_failed", mapOf(
                    "projectId" to projectId,
                    "model" to model.name,
                    "stage" to lastProgress.stage.name,
                    "error" to lastProgress.userMessage
                ))
                trace.putAttribute("status", "failed")
                trace.putAttribute("stage", lastProgress.stage.name)
                trace.putAttribute("error_type", lastProgress.cause.javaClass.simpleName)
                trace.stop()

                projects.updateProject(projectId) {
                    it.copy(status = ProjectStatus.Failed, errorMessage = lastProgress.userMessage)
                }
                if (runAttemptCount < 2) {
                    Result.retry()
                } else {
                    Result.failure(
                        PipelineWorkData.failure(
                            message = lastProgress.userMessage,
                            retryable = true,
                        )
                    )
                }
            }
            else -> {
                telemetry.logEvent("transcribe_failed", mapOf(
                    "projectId" to projectId,
                    "model" to model.name,
                    "error" to "Unknown state"
                ))
                trace.putAttribute("status", "failed")
                trace.putAttribute("error_type", "UnknownState")
                trace.stop()
                Result.failure(PipelineWorkData.failure("Transcription failed", retryable = true))
            }
        }
    }
}
