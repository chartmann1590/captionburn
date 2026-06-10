package com.charlesh.captionburn.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.charlesh.captionburn.domain.usecase.RunPipelineUseCase
import com.charlesh.captionburn.service.workers.BurnPublishWorker
import com.charlesh.captionburn.service.workers.PipelineWorkData
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pipeline foreground lifecycle is owned by WorkManager (no standalone Service).
 * Cancellation is driven through WorkManager — the notification's Cancel action uses
 * [WorkManager.createCancelPendingIntent]. This verifies that cancelling the unique
 * pipeline work transitions it to CANCELLED.
 */
@RunWith(AndroidJUnit4::class)
class ProcessingServiceInstrumentedTest {

    @Test
    fun cancelUniqueWork_cancelsPipeline() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val workManager = WorkManager.getInstance(context)
            val projectId = "service-cancel-test"
            val uniqueName = RunPipelineUseCase.uniqueWorkName(projectId)

            // Queue long-delayed work so the test can deterministically cancel it.
            workManager.beginUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BurnPublishWorker>()
                    .setInputData(PipelineWorkData.input(projectId))
                    .setInitialDelay(10, TimeUnit.MINUTES)
                    .build(),
            ).enqueue()

            workManager.cancelUniqueWork(uniqueName)

            withTimeout(10_000) {
                while (true) {
                    val states = workManager.getWorkInfosForUniqueWork(uniqueName).get().map { info -> info.state }
                    if (states.isNotEmpty() && states.all { it == WorkInfo.State.CANCELLED }) {
                        break
                    }
                    delay(200)
                }
            }

            val terminalStates = workManager.getWorkInfosForUniqueWork(uniqueName).get().map { info -> info.state }
            assertThat(terminalStates).isNotEmpty()
            assertThat(terminalStates).containsExactly(WorkInfo.State.CANCELLED)
        }
    }
}
