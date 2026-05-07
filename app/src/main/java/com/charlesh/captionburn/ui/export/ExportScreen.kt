package com.charlesh.captionburn.ui.export

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charlesh.captionburn.R
import com.charlesh.captionburn.ui.ads.NativeAdvancedAd

@Composable
fun ExportScreen(
    projectId: String,
    viewModel: ExportViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val needsNotificationPermissionRationale =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    LaunchedEffect(projectId) { viewModel.bindProject(projectId) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(if (state.isDone) R.string.export_done_title else R.string.export_progress),
                style = MaterialTheme.typography.headlineMedium,
            )
            AnimatedVisibility(visible = needsNotificationPermissionRationale && state.isExporting) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.export_notifications_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(visible = state.isExporting) {
                Text(
                    text = exportStageText(state.stage),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            AnimatedVisibility(visible = state.isExporting && state.stage == "burn-publish") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(12.dp))
                    NativeAdvancedAd(modifier = Modifier.fillMaxWidth())
                }
            }
            if (state.errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
                if (state.canRetry) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::retry) {
                        Text(stringResource(R.string.error_retry))
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onDone,
                enabled = state.isDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(stringResource(R.string.export_save))
            }
            AnimatedVisibility(visible = state.isDone && !state.outputUri.isNullOrBlank()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val outputUri = state.outputUri?.let(Uri::parse) ?: return@Button
                            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(outputUri, "video/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                context.startActivity(openIntent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.error_generic),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.export_play))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val outputUri = state.outputUri?.let(Uri::parse) ?: return@Button
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/*"
                                putExtra(Intent.EXTRA_STREAM, outputUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                context.startActivity(
                                    Intent.createChooser(
                                        shareIntent,
                                        context.getString(R.string.export_share),
                                    ),
                                )
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.error_generic),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(stringResource(R.string.export_share))
                    }
                }
            }
        }
    }
}

@Composable
private fun exportStageText(stage: String?): String = stringResource(
    when (stage) {
        "download-translation-model" -> R.string.export_stage_download_translation_model
        "translate" -> R.string.export_stage_translate
        "build-subtitles" -> R.string.export_stage_build_subtitles
        "burn-publish" -> R.string.export_stage_burn_publish
        else -> R.string.export_stage_processing
    }
)
