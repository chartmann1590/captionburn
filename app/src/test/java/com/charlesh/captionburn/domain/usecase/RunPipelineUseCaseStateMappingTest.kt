package com.charlesh.captionburn.domain.usecase

import androidx.work.Data
import androidx.work.WorkInfo
import com.charlesh.captionburn.service.workers.PipelineWorkData
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class RunPipelineUseCaseStateMappingTest {

    @Test
    fun toPipelineState_emptyList_returnsIdle() {
        val state = emptyList<WorkInfo>().toPipelineState(projectId = "p1")

        assertThat(state).isEqualTo(PipelineState.Idle(projectId = "p1"))
    }

    @Test
    fun toPipelineState_running_usesProgressData() {
        val running = workInfo(
            state = WorkInfo.State.RUNNING,
            progress = Data.Builder()
                .putString(PipelineWorkData.KEY_STAGE, "burn-publish")
                .putFloat(PipelineWorkData.KEY_PROGRESS, 0.82f)
                .build(),
        )

        val state = listOf(running).toPipelineState(projectId = "p1")

        assertThat(state).isEqualTo(
            PipelineState.Running(
                projectId = "p1",
                stage = "burn-publish",
                progress = 0.82f,
            )
        )
    }

    @Test
    fun toPipelineState_failed_returnsRetryMetadata() {
        val failed = workInfo(
            state = WorkInfo.State.FAILED,
            output = Data.Builder()
                .putString(PipelineWorkData.KEY_ERROR_MESSAGE, "Export failed")
                .putBoolean(PipelineWorkData.KEY_RETRYABLE, true)
                .build(),
        )

        val state = listOf(failed).toPipelineState(projectId = "p1")

        assertThat(state).isEqualTo(
            PipelineState.Failed(
                projectId = "p1",
                message = "Export failed",
                retryable = true,
            )
        )
    }

    @Test
    fun toPipelineState_allSucceeded_returnsOutputUri() {
        val done1 = workInfo(state = WorkInfo.State.SUCCEEDED)
        val done2 = workInfo(
            state = WorkInfo.State.SUCCEEDED,
            output = Data.Builder()
                .putString(PipelineWorkData.KEY_OUTPUT_URI, "content://result")
                .build(),
        )

        val state = listOf(done1, done2).toPipelineState(projectId = "p1")

        assertThat(state).isEqualTo(
            PipelineState.Succeeded(
                projectId = "p1",
                outputUri = "content://result",
            )
        )
    }

    @Test
    fun toPipelineState_cancelled_returnsCancelled() {
        val cancelled = workInfo(state = WorkInfo.State.CANCELLED)

        val state = listOf(cancelled).toPipelineState(projectId = "p1")

        assertThat(state).isEqualTo(PipelineState.Cancelled(projectId = "p1"))
    }

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
        tags: Set<String> = setOf("queued"),
    ): WorkInfo {
        val info = mockk<WorkInfo>()
        every { info.state } returns state
        every { info.progress } returns progress
        every { info.outputData } returns output
        every { info.tags } returns tags
        return info
    }
}
