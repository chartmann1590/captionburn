package com.charlesh.captionburn.ui.editor

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.charlesh.captionburn.R
import com.charlesh.captionburn.domain.model.Segment
import com.charlesh.captionburn.ui.common.LowEndDeviceBanner
import com.charlesh.captionburn.ui.common.OnDeviceBanner
import com.charlesh.captionburn.util.DeviceCapability
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun VideoPlayerBox(
    player: ExoPlayer?,
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (player != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = true
                            this.player = player
                        }
                    },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                }
            }
            CaptionOverlay(
                style = state.style,
                activeWords = state.activeWords,
                activeTranslationWords = state.activeTranslationWords,
                playbackPositionMs = state.playbackPositionMs,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun TranscriptionStatusCard(
    state: EditorState,
    onRetry: () -> Unit,
    onStartManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.isLoading || state.isTranscribing || state.transcript == null || state.errorMessage != null,
        modifier = modifier,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.errorMessage == null) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                contentColor = if (state.errorMessage == null) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        state.errorMessage != null -> state.errorMessage.orEmpty()
                        state.isTranscribing -> "Transcribing on-device: ${state.transcriptionStage ?: "starting"}"
                        state.isLoading -> "Loading project..."
                        else -> "Waiting for the on-device transcript."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.isTranscribing) {
                    LinearProgressIndicator(
                        progress = { state.transcriptionProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.editor_transcribing_privacy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = stringResource(R.string.editor_transcribing_background_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
                if (state.canRetryTranscription) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.error_retry))
                    }
                }
                if (state.canStartTranscription) {
                    TextButton(onClick = onStartManual) {
                        Text(stringResource(R.string.editor_start_transcription))
                    }
                }
            }
        }
    }
}

@Composable
fun EditorScreen(
    projectId: String,
    viewModel: EditorViewModel,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourceUri = state.sourceUri
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isLowEndDevice = remember { DeviceCapability.isLowEndDevice(context) }
    val hasSegments = state.transcript?.segments?.isNotEmpty() == true

    LaunchedEffect(projectId) { viewModel.bindProject(projectId) }
    val player = remember(sourceUri, context) {
        sourceUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    LaunchedEffect(player) {
        while (player != null) {
            viewModel.updatePlaybackPosition(player.currentPosition.coerceAtLeast(0L))
            delay(33)
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showStyleSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Left column: Player
            Column(
                modifier = Modifier.weight(1.2f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Editor",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                VideoPlayerBox(
                    player = player,
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }

            // Right column: Controls & Transcript
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Project: ${state.projectName.ifBlank { projectId }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showStyleSheet = true }) {
                        Text("Style controls")
                    }
                }

                if (isLowEndDevice) {
                    LowEndDeviceBanner()
                }
                if (!hasSegments && !state.isTranscribing) {
                    OnDeviceBanner(compact = true)
                }

                TranscriptionStatusCard(
                    state = state,
                    onRetry = viewModel::retryTranscription,
                    onStartManual = viewModel::startTranscriptionManually,
                )

                LanguageBar(
                    displayMode = state.style.displayMode,
                    detectedLanguage = state.transcript?.detectedLanguage,
                    targetLanguage = state.style.targetLanguage,
                    supportedLanguages = state.supportedTargetLanguages,
                    onDisplayModeChange = viewModel::setDisplayMode,
                    onTargetLanguageChange = viewModel::setTargetLanguage,
                )

                AnimatedVisibility(visible = state.showTranslationModeHint) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.editor_translation_hint_original_mode),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                TranscriptList(
                    segments = state.transcript?.segments.orEmpty(),
                    selectedSegmentId = state.selectedSegmentId,
                    onSelectSegment = { segment: Segment ->
                        viewModel.selectSegment(segment.id)
                        player?.seekTo(segment.startMs)
                    },
                    onEditSegmentText = viewModel::editSegmentText,
                    onNudgeSegment = viewModel::nudgeSegment,
                    modifier = Modifier.weight(1f),
                )

                Button(
                    onClick = onExport,
                    enabled = state.transcript?.segments?.isNotEmpty() == true && !state.isTranscribing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        stringResource(R.string.editor_export),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    } else {
        // Portrait Layout
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Editor",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }

            VideoPlayerBox(
                player = player,
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    // Until a transcript exists the editing controls below are hidden,
                    // so the preview fills the freed space instead of being squeezed.
                    .weight(if (hasSegments) 1.1f else 1f)
                    .clip(RoundedCornerShape(20.dp)),
            )

            Text(
                "Project: ${state.projectName.ifBlank { projectId }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isLowEndDevice) {
                LowEndDeviceBanner()
            }
            // Pre-transcription notice only; during transcription the status card already
            // covers on-device + background, and during editing the space goes to captions.
            if (!hasSegments && !state.isTranscribing) {
                OnDeviceBanner(compact = true)
            }

            TranscriptionStatusCard(
                state = state,
                onRetry = viewModel::retryTranscription,
                onStartManual = viewModel::startTranscriptionManually,
            )

            // Editing controls only matter once captions exist — collapse them while
            // transcribing so the preview stays large.
            if (hasSegments) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showStyleSheet = true }) {
                        Text("Style controls")
                    }
                }

                LanguageBar(
                    displayMode = state.style.displayMode,
                    detectedLanguage = state.transcript?.detectedLanguage,
                    targetLanguage = state.style.targetLanguage,
                    supportedLanguages = state.supportedTargetLanguages,
                    onDisplayModeChange = viewModel::setDisplayMode,
                    onTargetLanguageChange = viewModel::setTargetLanguage,
                )

                AnimatedVisibility(visible = state.showTranslationModeHint) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.editor_translation_hint_original_mode),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                TranscriptList(
                    segments = state.transcript?.segments.orEmpty(),
                    selectedSegmentId = state.selectedSegmentId,
                    onSelectSegment = { segment: Segment ->
                        viewModel.selectSegment(segment.id)
                        player?.seekTo(segment.startMs)
                    },
                    onEditSegmentText = viewModel::editSegmentText,
                    onNudgeSegment = viewModel::nudgeSegment,
                    modifier = Modifier.weight(1f),
                )
            }

            Button(
                onClick = onExport,
                enabled = state.transcript?.segments?.isNotEmpty() == true && !state.isTranscribing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    stringResource(R.string.editor_export),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    if (showStyleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStyleSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Caption style",
                    style = MaterialTheme.typography.titleMedium,
                )
                StylePanel(
                    style = state.style,
                    onFontSizeChanged = viewModel::setFontSize,
                    onPositionChanged = viewModel::setPosition,
                    onHighlightModeChanged = viewModel::setHighlightMode,
                    onOutlineWidthChanged = viewModel::setOutlineWidth,
                    onPrimaryColorChanged = viewModel::setColor,
                    onHighlightColorChanged = viewModel::setHighlightColor,
                )
                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            showStyleSheet = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Done")
                }
            }
        }
        LaunchedEffect(Unit) {
            scope.launch {
                sheetState.show()
            }
        }
    }
}
