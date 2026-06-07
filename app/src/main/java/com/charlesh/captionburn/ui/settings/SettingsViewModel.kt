package com.charlesh.captionburn.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.charlesh.captionburn.data.transcription.DownloadEvent
import com.charlesh.captionburn.data.transcription.ModelDownloadHttpException
import com.charlesh.captionburn.data.transcription.ModelDownloader
import com.charlesh.captionburn.data.transcription.ModelIntegrityException
import com.charlesh.captionburn.data.transcription.WhisperModelCatalog
import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelDownloader: ModelDownloader,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.installedModel,
                settingsRepository.wifiOnlyDownloads,
                settingsRepository.telemetryEnabled,
            ) { installedModel, wifiOnly, telemetryEnabled ->
                Triple(installedModel, wifiOnly, telemetryEnabled)
            }.collect { (installedModel, wifiOnly, telemetryEnabled) ->
                _state.update {
                    it.copy(
                        installedModel = installedModel,
                        wifiOnly = wifiOnly,
                        telemetryEnabled = telemetryEnabled,
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
}

data class SettingsState(
    val installedModel: WhisperModelChoice? = null,
    val wifiOnly: Boolean = true,
    val telemetryEnabled: Boolean = true,
    val isDownloading: Boolean = false,
    val activeModelChoice: WhisperModelChoice? = null,
    val downloadProgress: Float = 0f,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)
