package com.charlesh.captionburn.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charlesh.captionburn.data.project.ProjectRepository
import com.charlesh.captionburn.data.translation.MlKitTranslator
import com.charlesh.captionburn.data.translation.TranslationLanguage
import com.charlesh.captionburn.domain.model.CaptionPosition
import com.charlesh.captionburn.domain.model.CaptionStyle
import com.charlesh.captionburn.domain.model.DisplayMode
import com.charlesh.captionburn.domain.model.HighlightMode
import com.charlesh.captionburn.domain.model.ProjectStatus
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.domain.model.Transcript
import com.charlesh.captionburn.domain.model.Word
import com.charlesh.captionburn.domain.usecase.PipelineState
import com.charlesh.captionburn.domain.usecase.RunPipelineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EditorState(
    val projectId: String? = null,
    val sourceUri: String? = null,
    val projectName: String = "",
    val projectStatus: ProjectStatus? = null,
    val isLoading: Boolean = true,
    val isTranscribing: Boolean = false,
    val transcriptionProgress: Float = 0f,
    val transcriptionStage: String? = null,
    val canRetryTranscription: Boolean = false,
    val canStartTranscription: Boolean = false,
    val showTranslationModeHint: Boolean = false,
    val transcript: Transcript? = null,
    val style: CaptionStyle = CaptionStyle(),
    val supportedTargetLanguages: List<TranslationLanguage> = emptyList(),
    val playbackPositionMs: Long = 0L,
    val selectedSegmentId: String? = null,
    val activeWords: List<Word> = emptyList(),
    val activeTranslationWords: List<Word> = emptyList(),
    val errorMessage: String? = null,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val runPipeline: RunPipelineUseCase,
    translator: MlKitTranslator,
) : ViewModel() {

    private val _state = MutableStateFlow(
        EditorState(supportedTargetLanguages = translator.supportedLanguages())
    )
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var projectJob: Job? = null
    private var transcriptionJob: Job? = null
    private var saveJob: Job? = null
    private val autoStartedTranscription = mutableSetOf<String>()

    fun bindProject(projectId: String) {
        if (_state.value.projectId == projectId && projectJob?.isActive == true) return

        projectJob?.cancel()
        transcriptionJob?.cancel()
        _state.value = _state.value.copy(
            projectId = projectId,
            isLoading = true,
            errorMessage = null,
            canRetryTranscription = false,
        )

        projectJob = viewModelScope.launch {
            projects.projects
                .map { list -> list.firstOrNull { it.id == projectId } }
                .distinctUntilChanged()
                .collectLatest { project ->
                    if (project == null) {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            isTranscribing = false,
                            errorMessage = "Project not found",
                            canRetryTranscription = false,
                        )
                        return@collectLatest
                    }

                    val currentSelectedId = _state.value.selectedSegmentId
                    val selectedId = currentSelectedId
                        ?.takeIf { id -> project.transcript?.segments?.any { it.id == id } == true }
                        ?: project.transcript?.segments?.firstOrNull()?.id
                    val failedWithoutTranscript =
                        project.status == ProjectStatus.Failed && project.transcript == null
                    val importedWithoutTranscript =
                        project.status == ProjectStatus.Imported && project.transcript == null
                    val targetLanguageSet = !project.style.targetLanguage.isNullOrBlank()

                    _state.value = _state.value.copy(
                        isLoading = false,
                        sourceUri = project.sourceUri,
                        projectName = project.displayName,
                        projectStatus = project.status,
                        transcript = project.transcript,
                        style = project.style,
                        selectedSegmentId = selectedId,
                        isTranscribing = project.status == ProjectStatus.Transcribing,
                        errorMessage = project.errorMessage?.takeIf { project.status == ProjectStatus.Failed },
                        canRetryTranscription = failedWithoutTranscript,
                        canStartTranscription = importedWithoutTranscript,
                        showTranslationModeHint =
                            targetLanguageSet && project.style.displayMode == DisplayMode.Original,
                    )
                    updatePlaybackPosition(_state.value.playbackPositionMs)

                    when {
                        project.transcript == null &&
                            project.status == ProjectStatus.Imported &&
                            autoStartedTranscription.add(projectId) ->
                            startTranscription(projectId = projectId, retry = false)

                        project.status == ProjectStatus.Transcribing &&
                            transcriptionJob?.isActive != true ->
                            observeTranscription(projectId)
                    }
                }
        }
    }

    fun retryTranscription() {
        val projectId = _state.value.projectId ?: return
        autoStartedTranscription.add(projectId)
        startTranscription(projectId = projectId, retry = true)
    }

    fun startTranscriptionManually() {
        val projectId = _state.value.projectId ?: return
        autoStartedTranscription.add(projectId)
        startTranscription(projectId = projectId, retry = false)
    }

    private fun startTranscription(projectId: String, retry: Boolean) {
        transcriptionJob?.cancel()
        transcriptionJob = viewModelScope.launch {
            runPipeline.startTranscription(projectId = projectId, retry = retry)
                .collectLatest(::handleTranscriptionState)
        }
    }

    private fun observeTranscription(projectId: String) {
        transcriptionJob?.cancel()
        transcriptionJob = viewModelScope.launch {
            runPipeline.observeTranscription(projectId)
                .collectLatest(::handleTranscriptionState)
        }
    }

    private fun handleTranscriptionState(pipelineState: PipelineState) {
        _state.value = when (pipelineState) {
            is PipelineState.Running -> _state.value.copy(
                isTranscribing = true,
                transcriptionStage = pipelineState.stage,
                transcriptionProgress = pipelineState.progress,
                errorMessage = null,
                canRetryTranscription = false,
            )
            is PipelineState.Succeeded -> _state.value.copy(
                isTranscribing = false,
                transcriptionStage = "done",
                transcriptionProgress = 1f,
                canRetryTranscription = false,
            )
            is PipelineState.Failed -> _state.value.copy(
                isTranscribing = false,
                transcriptionStage = null,
                errorMessage = pipelineState.message,
                canRetryTranscription = pipelineState.retryable,
            )
            is PipelineState.Cancelled -> _state.value.copy(
                isTranscribing = false,
                transcriptionStage = null,
                errorMessage = "Transcription cancelled",
                canRetryTranscription = true,
            )
            is PipelineState.Idle -> _state.value.copy(
                isTranscribing = false,
                transcriptionStage = null,
            )
        }
        if (pipelineState is PipelineState.Failed || pipelineState is PipelineState.Cancelled) {
            _state.value.projectId?.let(autoStartedTranscription::remove)
        }
    }

    fun updatePlaybackPosition(positionMs: Long) {
        val transcript = _state.value.transcript
        val activeSegment = transcript?.segments?.firstOrNull { positionMs in it.startMs..it.endMs }
        _state.value = _state.value.copy(
            playbackPositionMs = positionMs,
            selectedSegmentId = activeSegment?.id ?: _state.value.selectedSegmentId,
            activeWords = activeSegment?.words.orEmpty(),
            activeTranslationWords = activeSegment?.translatedWords.orEmpty(),
        )
    }

    fun setDisplayMode(mode: DisplayMode) {
        val current = _state.value.style
        val targetLanguage = if (mode != DisplayMode.Original && current.targetLanguage.isNullOrBlank()) {
            defaultTargetLanguage()
        } else {
            current.targetLanguage
        }
        _state.value = _state.value.copy(
            style = current.copy(displayMode = mode, targetLanguage = targetLanguage)
        )
        persistStyle()
    }

    fun setTargetLanguage(language: String?) {
        _state.value = _state.value.copy(
            style = _state.value.style.copy(targetLanguage = language?.ifBlank { null })
        )
        persistStyle()
    }

    fun setHighlightMode(mode: HighlightMode) {
        _state.value = _state.value.copy(style = _state.value.style.copy(highlightMode = mode))
        persistStyle()
    }

    fun setFontSize(px: Int) {
        _state.value = _state.value.copy(style = _state.value.style.copy(fontSizePx = px))
        persistStyle()
    }

    fun setOutlineWidth(px: Int) {
        _state.value = _state.value.copy(style = _state.value.style.copy(outlineWidthPx = px))
        persistStyle()
    }

    fun setPosition(position: CaptionPosition) {
        _state.value = _state.value.copy(style = _state.value.style.copy(position = position))
        persistStyle()
    }

    fun setColor(primaryColor: Long) {
        _state.value = _state.value.copy(style = _state.value.style.copy(primaryColor = primaryColor))
        persistStyle()
    }

    fun setHighlightColor(highlightColor: Long) {
        _state.value = _state.value.copy(style = _state.value.style.copy(highlightColor = highlightColor))
        persistStyle()
    }

    fun selectSegment(segmentId: String) {
        _state.value = _state.value.copy(selectedSegmentId = segmentId)
    }

    fun editSegmentText(segmentId: String, text: String) {
        val transcript = _state.value.transcript ?: return
        val updated = transcript.copy(
            segments = transcript.segments.map { segment ->
                if (segment.id != segmentId) segment else {
                    val words = text.split(" ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val duration = (segment.endMs - segment.startMs).coerceAtLeast(1L)
                    val step = (duration / words.size.coerceAtLeast(1)).coerceAtLeast(1L)
                    segment.copy(
                        words = words.mapIndexed { idx, value ->
                            val start = segment.startMs + idx * step
                            val end = if (idx == words.lastIndex) segment.endMs else start + step - 1
                            Word(startMs = start, endMs = end, text = value)
                        }
                    )
                }
            }
        )
        _state.value = _state.value.copy(transcript = updated)
        persistTranscript(updated)
    }

    fun nudgeSegment(segmentId: String, deltaMs: Long) {
        val transcript = _state.value.transcript ?: return
        val updated = transcript.copy(
            segments = transcript.segments.map { segment ->
                if (segment.id != segmentId) segment else segment.shift(deltaMs)
            }
        )
        _state.value = _state.value.copy(transcript = updated)
        persistTranscript(updated)
    }

    private fun defaultTargetLanguage(): String? {
        val source = _state.value.transcript?.detectedLanguage
        val languages = _state.value.supportedTargetLanguages
        return languages.firstOrNull { it.code == "en" && it.code != source }?.code
            ?: languages.firstOrNull { it.code != source }?.code
    }

    private fun Segment.shift(deltaMs: Long): Segment {
        val shiftedWords = words.map {
            it.copy(
                startMs = (it.startMs + deltaMs).coerceAtLeast(0L),
                endMs = (it.endMs + deltaMs).coerceAtLeast(1L),
            )
        }
        val shiftedTranslatedWords = translatedWords.map {
            it.copy(
                startMs = (it.startMs + deltaMs).coerceAtLeast(0L),
                endMs = (it.endMs + deltaMs).coerceAtLeast(1L),
            )
        }
        return copy(
            startMs = (startMs + deltaMs).coerceAtLeast(0L),
            endMs = (endMs + deltaMs).coerceAtLeast(1L),
            words = shiftedWords,
            translatedWords = shiftedTranslatedWords,
        )
    }

    private fun persistStyle() {
        val projectId = _state.value.projectId ?: return
        val style = _state.value.style
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(200)
            projects.updateStyle(projectId) { it.copy(style = style) }
        }
    }

    private fun persistTranscript(transcript: Transcript) {
        val projectId = _state.value.projectId ?: return
        viewModelScope.launch {
            projects.updateProject(projectId) { it.copy(transcript = transcript) }
        }
    }
}
