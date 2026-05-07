package com.charlesh.captionburn.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charlesh.captionburn.data.project.ProjectRepository
import com.charlesh.captionburn.di.IoDispatcher
import com.charlesh.captionburn.domain.model.Project
import com.charlesh.captionburn.domain.usecase.RunPipelineUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class HomeState(
    val projects: List<Project> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val projects: ProjectRepository,
    private val runPipeline: RunPipelineUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            projects.projects.collect { list ->
                _state.update { it.copy(projects = list.sortedByDescending(Project::createdAt)) }
            }
        }
    }

    /** Called when the user picks a video from the system Photo Picker. */
    fun onVideoPicked(uri: String): String {
        val id = "project-${System.currentTimeMillis()}"
        viewModelScope.launch {
            val stableUri = runCatching { copyPickedVideoToPrivateStorage(uri, id) }
                .onFailure { Timber.e(it, "Failed to copy picked video, using original uri") }
                .getOrDefault(uri)
            projects.createImportedProject(
                id = id,
                sourceUri = stableUri,
                displayName = "Imported ${_state.value.projects.size + 1}",
            )
        }
        return id
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch(io) {
            runPipeline.cancelTranscription(projectId)
            runPipeline.cancel(projectId)
            projects.deleteProject(projectId)
        }
    }

    private suspend fun copyPickedVideoToPrivateStorage(sourceUri: String, projectId: String): String =
        withContext(io) {
            val parsed = Uri.parse(sourceUri)
            val sourceExt = parsed.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() }
                ?.lowercase()
                ?: "mp4"
            val dir = File(context.filesDir, "imports").apply { mkdirs() }
            val dest = File(dir, "$projectId.$sourceExt")

            context.contentResolver.openInputStream(parsed).use { input ->
                requireNotNull(input) { "Unable to open input stream for picked video" }
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            Uri.fromFile(dest).toString()
        }
}
