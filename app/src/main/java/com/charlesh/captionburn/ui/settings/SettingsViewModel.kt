package com.charlesh.captionburn.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charlesh.captionburn.data.feedback.FeedbackRepository
import com.charlesh.captionburn.data.feedback.GithubCommentResponse
import com.charlesh.captionburn.data.feedback.GithubIssueReporter
import com.charlesh.captionburn.data.feedback.SavedFeedbackIssue
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.charlesh.captionburn.data.transcription.DownloadEvent
import com.charlesh.captionburn.data.transcription.ModelDownloadHttpException
import com.charlesh.captionburn.data.transcription.ModelDownloader
import com.charlesh.captionburn.data.transcription.ModelIntegrityException
import com.charlesh.captionburn.data.transcription.WhisperModelCatalog
import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val modelDownloader: ModelDownloader,
    private val githubIssueReporter: GithubIssueReporter,
    private val feedbackRepository: FeedbackRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.installedModel,
                settingsRepository.wifiOnlyDownloads,
                settingsRepository.telemetryEnabled,
                feedbackRepository.savedIssues,
            ) { installedModel, wifiOnly, telemetryEnabled, savedIssues ->
                SettingsCombineResult(installedModel, wifiOnly, telemetryEnabled, savedIssues)
            }.collect { combined ->
                _state.update {
                    it.copy(
                        installedModel = combined.installedModel,
                        wifiOnly = combined.wifiOnly,
                        telemetryEnabled = combined.telemetryEnabled,
                        savedIssues = combined.savedIssues,
                    )
                }
            }
        }
    }

    fun toggleWifiOnly() {
        viewModelScope.launch {
            settingsRepository.setWifiOnly(!_state.value.wifiOnly)
        }
    }

    fun toggleTelemetry() {
        viewModelScope.launch {
            settingsRepository.setTelemetryEnabled(!_state.value.telemetryEnabled)
        }
    }

    fun installOrSwitchModel(choice: WhisperModelChoice) {
        if (_state.value.isDownloading) return
        viewModelScope.launch {
            val spec = WhisperModelCatalog.byChoice(choice)
            if (_state.value.installedModel == choice && modelDownloader.isInstalled(spec)) {
                _state.update { it.copy(statusMessage = "This model is already installed.") }
                return@launch
            }
            _state.update {
                it.copy(
                    isDownloading = true,
                    activeModelChoice = choice,
                    downloadProgress = 0f,
                    errorMessage = null,
                    statusMessage = null,
                )
            }
            modelDownloader.deleteAll()
            modelDownloader.download(spec).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> _state.update {
                        val progress = if (event.total > 0) {
                            event.bytesRead.toFloat() / event.total.toFloat()
                        } else {
                            0f
                        }
                        it.copy(downloadProgress = progress.coerceIn(0f, 1f))
                    }
                    is DownloadEvent.Complete -> {
                        settingsRepository.setInstalledModel(choice)
                        settingsRepository.setOnboardingComplete()
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                activeModelChoice = null,
                                downloadProgress = 1f,
                                statusMessage = "${choice.displayName} model is ready.",
                                errorMessage = null,
                            )
                        }
                    }
                    is DownloadEvent.Failed -> _state.update {
                        val message = when (val c = event.cause) {
                            is ModelIntegrityException ->
                                "Model integrity check failed. Please retry."
                            is ModelDownloadHttpException ->
                                "Model download failed (HTTP ${c.code}). Try again."
                            else -> c.message ?: "Model download failed."
                        }
                        it.copy(
                            isDownloading = false,
                            activeModelChoice = null,
                            errorMessage = message,
                        )
                    }
                }
            }
        }
    }

    fun deleteInstalledModel() {
        if (_state.value.isDownloading) return
        viewModelScope.launch {
            _state.value.installedModel?.let { model ->
                modelDownloader.delete(WhisperModelCatalog.byChoice(model))
            }
            modelDownloader.deleteAll()
            settingsRepository.clearInstalledModel()
            _state.update {
                it.copy(
                    installedModel = null,
                    downloadProgress = 0f,
                    statusMessage = "Installed model removed.",
                    errorMessage = null,
                )
            }
        }
    }

    fun showFeedbackDialog(show: Boolean) {
        _state.update {
            it.copy(
                showFeedbackDialog = show,
                feedbackScreenshotUri = null
            )
        }
    }

    fun setFeedbackScreenshotUri(uri: Uri?) {
        _state.update { it.copy(feedbackScreenshotUri = uri) }
    }

    fun setCommentScreenshotUri(uri: Uri?) {
        _state.update { it.copy(commentScreenshotUri = uri) }
    }

    private fun readUriAsBase64(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun submitFeedback(title: String, description: String, name: String, email: String, includeDeviceInfo: Boolean) {
        if (title.isBlank() || description.isBlank()) return
        
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isSendingFeedback = true,
                    errorMessage = null,
                    statusMessage = null,
                )
            }

            var screenshotMarkdown = ""
            val uri = _state.value.feedbackScreenshotUri
            if (uri != null) {
                val base64 = readUriAsBase64(uri)
                if (base64 != null) {
                    val filename = "feedback_${System.currentTimeMillis()}.jpg"
                    val uploadResult = githubIssueReporter.uploadImage(filename, base64)
                    if (uploadResult.isSuccess) {
                        val imageUrl = uploadResult.getOrThrow()
                        screenshotMarkdown = "\n\n### Screenshot\n![Screenshot]($imageUrl)"
                    } else {
                        val error = uploadResult.exceptionOrNull()
                        _state.update {
                            it.copy(
                                isSendingFeedback = false,
                                errorMessage = "Failed to upload screenshot: ${error?.message}"
                            )
                        }
                        return@launch
                    }
                } else {
                    _state.update {
                        it.copy(
                            isSendingFeedback = false,
                            errorMessage = "Failed to read screenshot file."
                        )
                    }
                    return@launch
                }
            }

            val bodyBuilder = StringBuilder().apply {
                if (name.isNotBlank() || email.isNotBlank()) {
                    append("### Submitter Contact Info\n")
                    if (name.isNotBlank()) append("- **Name**: $name\n")
                    if (email.isNotBlank()) append("- **Email**: $email\n")
                    append("\n")
                }

                append("### Description\n")
                append(description)
                append("\n\n")

                if (screenshotMarkdown.isNotEmpty()) {
                    append(screenshotMarkdown)
                    append("\n\n")
                }

                if (includeDeviceInfo) {
                    append("### Device & System Info\n")
                    append("- **Device Model**: ${android.os.Build.BRAND} ${android.os.Build.MODEL} (${android.os.Build.MANUFACTURER})\n")
                    append("- **Android Version**: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                    append("- **App Version**: ${com.charlesh.captionburn.BuildConfig.VERSION_NAME} (${com.charlesh.captionburn.BuildConfig.VERSION_CODE})\n")
                    append("- **Active Transcription Model**: ${_state.value.installedModel?.displayName ?: "None"}\n")
                    append("- **System Locale**: ${java.util.Locale.getDefault()}\n")

                    // Disk Space
                    try {
                        val freeBytes = context.filesDir.freeSpace
                        val totalBytes = context.filesDir.totalSpace
                        val freeGb = freeBytes / (1024 * 1024 * 1024f)
                        val totalGb = totalBytes / (1024 * 1024 * 1024f)
                        append("- **Storage Space**: ${String.format(java.util.Locale.US, "%.2f GB free of %.2f GB total", freeGb, totalGb)}\n")
                    } catch (e: Exception) {
                        append("- **Storage Space**: Error reading space info\n")
                    }

                    // System Memory
                    try {
                        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val memInfo = android.app.ActivityManager.MemoryInfo()
                        actManager.getMemoryInfo(memInfo)
                        val availGb = memInfo.availMem / (1024 * 1024 * 1024f)
                        val totalGb = memInfo.totalMem / (1024 * 1024 * 1024f)
                        append("- **System RAM**: ${String.format(java.util.Locale.US, "%.2f GB available of %.2f GB total (Low Memory: %b)", availGb, totalGb, memInfo.lowMemory)}\n")
                    } catch (e: Exception) {
                        append("- **System RAM**: Error reading memory info\n")
                    }
                }
            }

            val result = githubIssueReporter.createIssue(title, bodyBuilder.toString())
            _state.update { state ->
                if (result.isSuccess) {
                    val issueRes = result.getOrThrow()
                    viewModelScope.launch {
                        feedbackRepository.saveIssue(
                            SavedFeedbackIssue(
                                number = issueRes.number,
                                title = issueRes.title,
                                dateCreated = System.currentTimeMillis(),
                                htmlUrl = issueRes.html_url,
                                status = issueRes.state
                            )
                        )
                    }
                    state.copy(
                        isSendingFeedback = false,
                        showFeedbackDialog = false,
                        feedbackScreenshotUri = null,
                        statusMessage = "Feedback submitted successfully! Issue #${issueRes.number} created."
                    )
                } else {
                    val error = result.exceptionOrNull()
                    state.copy(
                        isSendingFeedback = false,
                        errorMessage = error?.message ?: "Failed to submit feedback."
                    )
                }
            }
        }
    }

    fun selectIssue(issue: SavedFeedbackIssue?) {
        _state.update {
            it.copy(
                selectedIssue = issue,
                issueComments = emptyList(),
                errorMessage = null,
                statusMessage = null,
                commentScreenshotUri = null,
            )
        }
        if (issue != null) {
            fetchCommentsAndStatus(issue.number)
        }
    }

    fun fetchCommentsAndStatus(number: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isFetchingComments = true, errorMessage = null) }
            
            // 1. Fetch latest issue details to sync status (Open/Closed)
            val issueResult = githubIssueReporter.fetchIssue(number)
            if (issueResult.isSuccess) {
                val issueDetails = issueResult.getOrThrow()
                feedbackRepository.updateIssueStatus(number, issueDetails.state)
                _state.update {
                    if (it.selectedIssue?.number == number) {
                        it.copy(selectedIssue = it.selectedIssue.copy(status = issueDetails.state))
                    } else {
                        it
                    }
                }
            }

            // 2. Fetch comments list
            val commentsResult = githubIssueReporter.fetchComments(number)
            _state.update {
                it.copy(
                    isFetchingComments = false,
                    issueComments = commentsResult.getOrDefault(emptyList()),
                    errorMessage = commentsResult.exceptionOrNull()?.message
                )
            }
        }
    }

    fun postComment(number: Int, commentText: String) {
        if (commentText.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isPostingComment = true,
                    errorMessage = null,
                )
            }

            var finalCommentText = "**[User Reply from App]**\n\n$commentText"

            val uri = _state.value.commentScreenshotUri
            if (uri != null) {
                val base64 = readUriAsBase64(uri)
                if (base64 != null) {
                    val filename = "reply_comment_${number}_${System.currentTimeMillis()}.jpg"
                    val uploadResult = githubIssueReporter.uploadImage(filename, base64)
                    if (uploadResult.isSuccess) {
                        val imageUrl = uploadResult.getOrThrow()
                        finalCommentText += "\n\n![Screenshot]($imageUrl)"
                    } else {
                        val error = uploadResult.exceptionOrNull()
                        _state.update {
                            it.copy(
                                isPostingComment = false,
                                errorMessage = "Failed to upload screenshot: ${error?.message}"
                            )
                        }
                        return@launch
                    }
                } else {
                    _state.update {
                        it.copy(
                            isPostingComment = false,
                            errorMessage = "Failed to read screenshot file."
                        )
                    }
                    return@launch
                }
            }

            val result = githubIssueReporter.addComment(number, finalCommentText)
            if (result.isSuccess) {
                _state.update {
                    it.copy(
                        isPostingComment = false,
                        commentScreenshotUri = null
                    )
                }
                fetchCommentsAndStatus(number)
            } else {
                val error = result.exceptionOrNull()
                _state.update {
                    it.copy(
                        isPostingComment = false,
                        errorMessage = error?.message ?: "Failed to post comment."
                    )
                }
            }
        }
    }
}

private data class SettingsCombineResult(
    val installedModel: WhisperModelChoice?,
    val wifiOnly: Boolean,
    val telemetryEnabled: Boolean,
    val savedIssues: List<SavedFeedbackIssue>
)

data class SettingsState(
    val installedModel: WhisperModelChoice? = null,
    val wifiOnly: Boolean = true,
    val telemetryEnabled: Boolean = true,
    val isDownloading: Boolean = false,
    val activeModelChoice: WhisperModelChoice? = null,
    val downloadProgress: Float = 0f,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isSendingFeedback: Boolean = false,
    val showFeedbackDialog: Boolean = false,
    val savedIssues: List<SavedFeedbackIssue> = emptyList(),
    val selectedIssue: SavedFeedbackIssue? = null,
    val issueComments: List<GithubCommentResponse> = emptyList(),
    val isFetchingComments: Boolean = false,
    val isPostingComment: Boolean = false,
    val feedbackScreenshotUri: Uri? = null,
    val commentScreenshotUri: Uri? = null,
)
