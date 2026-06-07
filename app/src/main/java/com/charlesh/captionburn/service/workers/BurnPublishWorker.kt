package com.charlesh.captionburn.service.workers

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.charlesh.captionburn.data.project.ProjectRepository
import com.charlesh.captionburn.data.render.FFmpegBurner
import com.charlesh.captionburn.domain.model.ProjectStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

@HiltWorker
class BurnPublishWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val projects: ProjectRepository,
    private val ffmpegBurner: FFmpegBurner,
    private val telemetry: com.charlesh.captionburn.data.telemetry.TelemetryTracker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(PipelineWorkData.KEY_PROJECT_ID)
            ?: return Result.failure(PipelineWorkData.failure("Missing project id", retryable = false))
        val assPath = inputData.getString(PipelineWorkData.KEY_ASS_PATH)
            ?: return Result.failure(PipelineWorkData.failure("Missing subtitle file", retryable = false))
        val project = projects.getProject(projectId)
            ?: return Result.failure(PipelineWorkData.failure("Project not found", retryable = false))

        projects.updateProject(projectId) { it.copy(status = ProjectStatus.Burning, errorMessage = null) }
        setProgress(PipelineWorkData.progress(stage = "burn-publish", progress = 0.8f))

        telemetry.logEvent("ffmpeg_burn_started", mapOf(
            "projectId" to projectId,
            "durationMs" to project.durationMs
        ))
        val trace = telemetry.startTrace("ffmpeg_burn_duration")
        trace.putAttribute("project_id", projectId)

        return ffmpegBurner.burnAndPublish(
            sourceUri = Uri.parse(project.sourceUri),
            assFile = File(assPath),
            projectId = projectId,
            outputDisplayName = "${project.displayName}-captioned.mp4",
            durationMs = project.durationMs,
        ) { stageProgress ->
            setProgressAsync(PipelineWorkData.progress("burn-publish", 0.8f + (stageProgress * 0.2f)))
        }.fold(
            onSuccess = { burnResult ->
                telemetry.logEvent("ffmpeg_burn_success", mapOf(
                    "projectId" to projectId,
                    "durationMs" to project.durationMs
                ))
                trace.putAttribute("status", "success")
                trace.stop()

                File(assPath).delete()
                projects.updateProject(projectId) {
                    it.copy(
                        status = ProjectStatus.Done,
                        outputUri = burnResult.publishedUri.toString(),
                        errorMessage = null,
                    )
                }
                Result.success(
                    Data.Builder()
                        .putString(PipelineWorkData.KEY_OUTPUT_URI, burnResult.publishedUri.toString())
                        .build()
                )
            },
            onFailure = { error ->
                val message = error.message ?: "Export failed"
                telemetry.logEvent("ffmpeg_burn_failed", mapOf(
                    "projectId" to projectId,
                    "error" to message
                ))
                trace.putAttribute("status", "failed")
                trace.putAttribute("error_type", error.javaClass.simpleName)
                trace.stop()

                projects.updateProject(projectId) { it.copy(status = ProjectStatus.Failed, errorMessage = message) }
                if (runAttemptCount < 2) {
                    Result.retry()
                } else {
                    Result.failure(PipelineWorkData.failure(message = message, retryable = true))
                }
            }
        )
    }
}
