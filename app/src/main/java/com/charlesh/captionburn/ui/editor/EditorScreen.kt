package com.charlesh.captionburn.ui.editor

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Text(
                "Editor",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp)) {
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Project: ${state.projectName.ifBlank { projectId }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(
                visible = state.isLoading || state.isTranscribing || state.transcript == null || state.errorMessage != null,
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
                        }
                        if (state.canRetryTranscription) {
                            TextButton(onClick = viewModel::retryTranscription) {
                                Text(stringResource(R.string.error_retry))
                            }
                        }
                        if (state.canStartTranscription) {
                            TextButton(onClick = viewModel::startTranscriptionManually) {
                                Text(stringResource(R.string.editor_start_transcription))
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { showStyleSheet = true },
                ) {
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
                modifier = Modifier.height(260.dp),
            )
            Button(
                onClick = onExport,
                enabled = state.transcript?.segments?.isNotEmpty() == true && !state.isTranscribing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
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
