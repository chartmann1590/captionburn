package com.charlesh.captionburn.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val feedbackPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> viewModel.setFeedbackScreenshotUri(uri) }
    )

    val commentPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> viewModel.setCommentScreenshotUri(uri) }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(text = "Model management", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.installedModel?.let { "Installed: ${it.displayName} (${it.sizeMb} MB)" }
                    ?: "No model installed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = state.isDownloading) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { state.downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Downloading ${state.activeModelChoice?.displayName ?: "model"} ${(state.downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            WhisperModelChoice.entries.forEach { choice ->
                ModelChoiceCard(
                    choice = choice,
                    selected = state.installedModel == choice,
                    enabled = !state.isDownloading,
                    onInstall = { viewModel.installOrSwitchModel(choice) },
                )
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = viewModel::deleteInstalledModel,
                enabled = !state.isDownloading && state.installedModel != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete installed model")
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Wi-Fi only downloads",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.wifiOnly,
                    onCheckedChange = { viewModel.toggleWifiOnly() },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Share usage & errors",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Help improve the app by sharing anonymous crash logs and telemetry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.telemetryEnabled,
                    onCheckedChange = { viewModel.toggleTelemetry() },
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(text = "Support & Feedback", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Card(
                onClick = { viewModel.showFeedbackDialog(true) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BugReport,
                        contentDescription = "Report a Problem",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Report a Problem", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Submit a bug report or feedback directly to GitHub",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state.savedIssues.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(text = "Your Bug Reports", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                state.savedIssues.forEach { issue ->
                    Card(
                        onClick = { viewModel.selectIssue(issue) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "#${issue.number} ${issue.title}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(2.dp))
                                val formattedDate = remember(issue.dateCreated) {
                                    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                                    sdf.format(java.util.Date(issue.dateCreated))
                                }
                                Text(
                                    text = "Submitted: $formattedDate",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            val isOpen = issue.status.equals("open", ignoreCase = true)
                            val badgeColor = if (isOpen) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val badgeTextColor = if (isOpen) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = badgeColor)
                            ) {
                                Text(
                                    text = if (isOpen) "Open" else "Closed",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = badgeTextColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = state.errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            AnimatedVisibility(visible = state.statusMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text(
                        text = state.statusMessage.orEmpty(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (state.showFeedbackDialog) {
        var titleText by remember { mutableStateOf("") }
        var descriptionText by remember { mutableStateOf("") }
        var nameText by remember { mutableStateOf("") }
        var emailText by remember { mutableStateOf("") }
        var includeDeviceInfo by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { 
                if (!state.isSendingFeedback) {
                    viewModel.showFeedbackDialog(false) 
                }
            },
            title = { Text("Report a Problem") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Submit an issue to the developer. Please be descriptive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ Notice: Any text, name, email, or device details you submit here will be publicly visible on the repository GitHub issues page.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it },
                        label = { Text("Subject / Title") },
                        placeholder = { Text("e.g., App crashes during transcribe") },
                        singleLine = true,
                        enabled = !state.isSendingFeedback,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = descriptionText,
                        onValueChange = { descriptionText = it },
                        label = { Text("Description") },
                        placeholder = { Text("What happened? Steps to reproduce?") },
                        minLines = 4,
                        maxLines = 6,
                        enabled = !state.isSendingFeedback,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                feedbackPhotoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !state.isSendingFeedback,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddAPhoto,
                                contentDescription = "Attach Screenshot",
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(if (state.feedbackScreenshotUri != null) "Change Screenshot" else "Attach Screenshot")
                        }
                        if (state.feedbackScreenshotUri != null) {
                            Spacer(Modifier.width(12.dp))
                            Box(
                                modifier = Modifier.size(64.dp)
                            ) {
                                AsyncImage(
                                    model = state.feedbackScreenshotUri,
                                    contentDescription = "Selected screenshot preview",
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = { viewModel.setFeedbackScreenshotUri(null) },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .align(Alignment.TopEnd)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove screenshot",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = nameText,
                            onValueChange = { nameText = it },
                            label = { Text("Your Name (Optional)") },
                            singleLine = true,
                            enabled = !state.isSendingFeedback,
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        )
                        OutlinedTextField(
                            value = emailText,
                            onValueChange = { emailText = it },
                            label = { Text("Email (Optional)") },
                            singleLine = true,
                            enabled = !state.isSendingFeedback,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = includeDeviceInfo,
                            onCheckedChange = { includeDeviceInfo = it },
                            enabled = !state.isSendingFeedback
                        )
                        Text(
                            text = "Include system diagnostics (device name, OS version, app version, memory, storage)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.submitFeedback(
                            title = titleText,
                            description = descriptionText,
                            name = nameText,
                            email = emailText,
                            includeDeviceInfo = includeDeviceInfo
                        )
                    },
                    enabled = titleText.isNotBlank() && descriptionText.isNotBlank() && !state.isSendingFeedback
                ) {
                    if (state.isSendingFeedback) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Submit")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.showFeedbackDialog(false) },
                    enabled = !state.isSendingFeedback
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val issue = state.selectedIssue
    if (issue != null) {
        val context = LocalContext.current
        var commentText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { viewModel.selectIssue(null) },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${issue.number} ${issue.title}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        val isOpen = issue.status.equals("open", ignoreCase = true)
                        Text(
                            text = if (isOpen) "Status: Open" else "Status: Closed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.fetchCommentsAndStatus(issue.number) }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                ) {
                    Text(
                        text = "Follow and participate in this report from inside the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    
                    if (state.isFetchingComments) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            if (state.issueComments.isEmpty()) {
                                item {
                                    Text(
                                        text = "No comments on this report yet.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                    )
                                }
                            } else {
                                items(state.issueComments) { comment ->
                                    val isUserReply = comment.body.startsWith("**[User Reply from App]**")
                                    val bubbleColor = if (isUserReply) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(bubbleColor, shape = RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = comment.user.login,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            val formattedTime = remember(comment.created_at) {
                                                try {
                                                    val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                                                    parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                                    val date = parser.parse(comment.created_at) ?: java.util.Date()
                                                    val formatter = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                                                    formatter.format(date)
                                                } catch (e: Exception) {
                                                    comment.created_at
                                                }
                                            }
                                            Text(
                                                text = formattedTime,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = comment.body,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))

                    if (state.commentScreenshotUri != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp)
                            ) {
                                AsyncImage(
                                    model = state.commentScreenshotUri,
                                    contentDescription = "Reply screenshot preview",
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = { viewModel.setCommentScreenshotUri(null) },
                                    modifier = Modifier
                                        .size(18.dp)
                                        .align(Alignment.TopEnd)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove screenshot",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Screenshot attached",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                commentPhotoLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !state.isPostingComment
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddAPhoto,
                                contentDescription = "Add screenshot",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            placeholder = { Text("Add comment...") },
                            singleLine = false,
                            maxLines = 3,
                            enabled = !state.isPostingComment,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.postComment(issue.number, commentText)
                                commentText = ""
                            },
                            enabled = commentText.isNotBlank() && !state.isPostingComment,
                            modifier = Modifier.wrapContentHeight()
                        ) {
                            if (state.isPostingComment) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Post")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issue.htmlUrl))
                        context.startActivity(intent)
                    }
                ) {
                    Text("View on GitHub")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.selectIssue(null) }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ModelChoiceCard(
    choice: WhisperModelChoice,
    selected: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(choice.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${choice.sizeMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onInstall, enabled = enabled) {
                Text(if (selected) "Re-download" else "Switch")
            }
        }
    }
}
