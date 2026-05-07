package com.charlesh.captionburn.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.charlesh.captionburn.data.transcription.DownloadEvent
import com.charlesh.captionburn.data.transcription.ModelDownloadHttpException
import com.charlesh.captionburn.data.transcription.ModelIntegrityException
import com.charlesh.captionburn.data.transcription.ModelDownloader
import com.charlesh.captionburn.data.transcription.WhisperModelCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WhisperModelChoice(val displayName: String, val sizeMb: Int, val filename: String) {
    Tiny("Tiny", 75, "ggml-tiny.bin"),
    Base("Base", 142, "ggml-base.bin"),
    Small("Small", 466, "ggml-small.bin"),
}

data class OnboardingState(
    val selectedModel: WhisperModelChoice = WhisperModelChoice.Base,
    val wifiOnly: Boolean = true,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val downloader: ModelDownloader,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // If a model is already installed, skip onboarding.
            if (settings.onboardingComplete.first()) {
                _state.update { it.copy(isComplete = true) }
            }
            settings.wifiOnlyDownloads.collect { wifi ->
                _state.update { it.copy(wifiOnly = wifi) }
            }
        }
    }

    fun selectModel(choice: WhisperModelChoice) {
        _state.update { it.copy(selectedModel = choice) }
    }

    fun toggleWifiOnly() {
        viewModelScope.launch {
            val next = !_state.value.wifiOnly
            settings.setWifiOnly(next)
        }
    }

    fun startDownload() {
        if (_state.value.isDownloading) return
        val spec = WhisperModelCatalog.byChoice(_state.value.selectedModel)
        if (downloader.isInstalled(spec)) {
            viewModelScope.launch {
                settings.setInstalledModel(_state.value.selectedModel)
                settings.setOnboardingComplete()
                _state.update { it.copy(isComplete = true) }
            }
            return
        }

        _state.update { it.copy(isDownloading = true, downloadProgress = 0f, errorMessage = null) }
        viewModelScope.launch {
            downloader.download(spec).collect { ev ->
                when (ev) {
                    is DownloadEvent.Progress -> _state.update {
                        val pct = if (ev.total > 0) ev.bytesRead.toFloat() / ev.total.toFloat() else 0f
                        it.copy(downloadProgress = pct.coerceIn(0f, 1f))
                    }
                    is DownloadEvent.Complete -> {
                        settings.setInstalledModel(_state.value.selectedModel)
                        settings.setOnboardingComplete()
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = 1f,
                                isComplete = true,
                            )
                        }
                    }
                    is DownloadEvent.Failed -> _state.update {
                        val message = when (val c = ev.cause) {
                            is ModelIntegrityException ->
                                "Model integrity check failed. Please retry the download."
                            is ModelDownloadHttpException ->
                                "Model download failed (HTTP ${c.code}). Check your connection and try again."
                            else -> c.message ?: "Download failed"
                        }
                        it.copy(
                            isDownloading = false,
                            errorMessage = message,
                        )
                    }
                }
            }
        }
    }
}
