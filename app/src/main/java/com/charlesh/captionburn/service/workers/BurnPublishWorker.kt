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
