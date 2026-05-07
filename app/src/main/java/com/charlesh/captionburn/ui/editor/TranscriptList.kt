package com.charlesh.captionburn.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.charlesh.captionburn.domain.model.Segment

@Composable
fun TranscriptList(
    segments: List<Segment>,
    selectedSegmentId: String?,
    onSelectSegment: (Segment) -> Unit,
    onEditSegmentText: (segmentId: String, text: String) -> Unit,
    onNudgeSegment: (segmentId: String, deltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draftById = remember { mutableStateMapOf<String, String>() }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(segments, key = { it.id }) { segment ->
            val selected = selectedSegmentId == segment.id
            val value = draftById[segment.id] ?: segment.text
            Surface(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clickable { onSelectSegment(segment) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "${segment.startMs}ms - ${segment.endMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            draftById[segment.id] = it
                            onEditSegmentText(segment.id, it)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = { onNudgeSegment(segment.id, -120) }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Earlier")
                        }
                        IconButton(onClick = { onNudgeSegment(segment.id, 120) }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Later")
                        }
                    }
                }
            }
        }
    }
}
