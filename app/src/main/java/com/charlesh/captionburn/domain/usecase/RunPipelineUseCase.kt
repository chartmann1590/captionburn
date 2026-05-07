package com.charlesh.captionburn.domain.usecase

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.charlesh.captionburn.service.ProcessingService
import com.charlesh.captionburn.service.workers.BurnPublishWorker
import com.charlesh.captionburn.service.workers.BuildSubtitleWorker
import com.charlesh.captionburn.service.workers.PipelineWorkData
import com.charlesh.captionburn.service.workers.TranscribeWorker
import com.charlesh.captionburn.service.workers.TranslateWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class RunPipelineUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun start(projectId: String, retry: Boolean = false): Flow<PipelineState> {
        val workName = uniqueWorkName(projectId)
        val policy = if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.beginUniqueWork(
            workName,
            policy,
            OneTimeWorkRequestBuilder<TranslateWorker>()
                .setInputData(PipelineWorkData.input(projectId))
                .build()
        ).then(
            OneTimeWorkRequestBuilder<BuildSubtitleWorker>()
                .setInputData(PipelineWorkData.input(projectId))
                .build()
        ).then(
            OneTimeWorkRequestBuilder<BurnPublishWorker>()
                .setInputData(PipelineWorkData.input(projectId))
                .build()
        ).enqueue()

        startForegroundHost(projectId, workName)
        return observeWork(projectId, workName)
    }

    fun startTranscription(projectId: String, retry: Boolean = false): Flow<PipelineState> {
        val workName = uniqueTranscriptionWorkName(projectId)
        val policy = if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.beginUniqueWork(
            workName,
            policy,
            OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(PipelineWorkData.input(projectId))
                .build(),
        ).enqueue()

        startForegroundHost(projectId, workName)
        return observeWork(projectId, workName)
    }

    fun observe(projectId: String): Flow<PipelineState> =
        observeWork(projectId, uniqueWorkName(projectId))

    fun observeTranscription(projectId: String): Flow<PipelineState> =
        observeWork(projectId, uniqueTranscriptionWorkName(projectId))

    private fun observeWork(projectId: String, workName: String): Flow<PipelineState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName)
            .map { infos -> infos.toPipelineState(projectId) }

    fun cancel(projectId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(projectId))
    }

    fun cancelTranscription(projectId: String) {
        workManager.cancelUniqueWork(uniqueTranscriptionWorkName(projectId))
    }

    private fun startForegroundHost(projectId: String, workName: String) {
        val serviceIntent = Intent(context, ProcessingService::class.java)
            .setAction(ProcessingService.ACTION_START)
            .putExtra(ProcessingService.EXTRA_PROJECT_ID, projectId)
            .putExtra(ProcessingService.EXTRA_WORK_NAME, workName)
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        fun uniqueWorkName(projectId: String): String = "pipeline-$projectId"
        fun uniqueTranscriptionWorkName(projectId: String): String = "transcribe-$projectId"
    }
}

sealed interface PipelineState {
    val projectId: String

    data class Running(
        override val projectId: String,
        val stage: String,
        val progress: Float,
    ) : PipelineState

    data class Succeeded(
        override val projectId: String,
        val outputUri: String?,
    ) : PipelineState

    data class Failed(
        override val projectId: String,
        val message: String,
        val retryable: Boolean,
    ) : PipelineState

    data class Cancelled(
        override val projectId: String,
    ) : PipelineState

    data class Idle(
        override val projectId: String,
    ) : PipelineState
}

internal fun List<WorkInfo>.toPipelineState(projectId: String): PipelineState {
    if (isEmpty()) return PipelineState.Idle(projectId)

    val running = firstOrNull { it.state == WorkInfo.State.RUNNING }
    if (running != null) {
        val progress = running.progress.getFloat(PipelineWorkData.KEY_PROGRESS, 0f).coerceIn(0f, 1f)
        val stage = running.progress.getString(PipelineWorkData.KEY_STAGE) ?: running.tags.firstOrNull() ?: "processing"
        return PipelineState.Running(
            projectId = projectId,
            stage = stage,
            progress = progress,
        )
    }

    val failed = firstOrNull { it.state == WorkInfo.State.FAILED }
    if (failed != null) {
        return PipelineState.Failed(
            projectId = projectId,
            message = failed.outputData.getString(PipelineWorkData.KEY_ERROR_MESSAGE) ?: "Export failed",
            retryable = failed.outputData.getBoolean(PipelineWorkData.KEY_RETRYABLE, true),
        )
    }

    if (all { it.state == WorkInfo.State.SUCCEEDED }) {
        return PipelineState.Succeeded(
            projectId = projectId,
            outputUri = lastOrNull()?.outputData?.getString(PipelineWorkData.KEY_OUTPUT_URI),
        )
    }

    if (any { it.state == WorkInfo.State.CANCELLED }) {
        return PipelineState.Cancelled(projectId)
    }

    val stage = firstOrNull()?.tags?.firstOrNull() ?: "queued"
    return PipelineState.Running(
        projectId = projectId,
        stage = stage,
        progress = 0f,
    )
}
