package com.charlesh.captionburn.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charlesh.captionburn.domain.usecase.PipelineState
import com.charlesh.captionburn.domain.usecase.RunPipelineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class ExportState(
    val projectId: String? = null,
    val progress: Float = 0f,
    val stage: String? = null,
    val isExporting: Boolean = false,
    val isDone: Boolean = false,
    val outputUri: String? = null,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val runPipeline: RunPipelineUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()
    private var observeJob: Job? = null

    fun bindProject(projectId: String) {
        if (_state.value.projectId == projectId && (_state.value.isExporting || _state.value.isDone)) return
        _state.value = ExportState(projectId = projectId)
        startOrObserve(projectId = projectId, retry = false)
    }

    fun retry() {
        val projectId = _state.value.projectId ?: return
        startOrObserve(projectId = projectId, retry = true)
    }

    private fun startOrObserve(projectId: String, retry: Boolean) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            runPipeline.start(projectId = projectId, retry = retry).collectLatest { pipelineState ->
                _state.value = when (pipelineState) {
                    is PipelineState.Idle -> _state.value.copy(
                        projectId = projectId,
                        stage = null,
                        isExporting = false,
                        isDone = false,
                    )
                    is PipelineState.Running -> _state.value.copy(
                        projectId = projectId,
                        progress = pipelineState.progress,
                        stage = pipelineState.stage,
                        isExporting = true,
                        isDone = false,
                        errorMessage = null,
                        canRetry = false,
                    )
                    is PipelineState.Succeeded -> _state.value.copy(
                        projectId = projectId,
                        progress = 1f,
                        stage = "done",
                        isExporting = false,
                        isDone = true,
                        outputUri = pipelineState.outputUri,
                        errorMessage = null,
                        canRetry = false,
                    )
                    is PipelineState.Failed -> _state.value.copy(
                        projectId = projectId,
                        stage = null,
                        isExporting = false,
                        isDone = false,
                        errorMessage = pipelineState.message,
                        canRetry = pipelineState.retryable,
                    )
                    is PipelineState.Cancelled -> _state.value.copy(
                        projectId = projectId,
                        stage = null,
                        isExporting = false,
                        isDone = false,
                        errorMessage = "Export cancelled",
                        canRetry = true,
                    )
                }
            }
        }
    }
}
