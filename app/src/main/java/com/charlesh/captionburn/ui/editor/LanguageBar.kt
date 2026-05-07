package com.charlesh.captionburn.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.charlesh.captionburn.data.translation.TranslationLanguage
import com.charlesh.captionburn.domain.model.DisplayMode

@Composable
fun LanguageBar(
    displayMode: DisplayMode,
    detectedLanguage: String?,
    targetLanguage: String?,
    supportedLanguages: List<TranslationLanguage>,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onTargetLanguageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(DisplayMode.Original, DisplayMode.Translated, DisplayMode.Both)
    val selectedLanguage = supportedLanguages.firstOrNull { it.code == targetLanguage }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Language",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { mode ->
                FilterChip(
                    selected = displayMode == mode,
                    onClick = { onDisplayModeChange(mode) },
                    label = { Text(mode.name) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Text(
            text = "Detected source: ${detectedLanguage?.uppercase() ?: "pending"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Box(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = supportedLanguages.isNotEmpty(),
            ) {
                Text(
                    selectedLanguage?.let { "${it.displayName} (${it.code.uppercase()})" }
                        ?: "No translation",
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("No translation") },
                    onClick = {
                        expanded = false
                        onTargetLanguageChange(null)
                    },
                )
                supportedLanguages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text("${language.displayName} (${language.code.uppercase()})") },
                        onClick = {
                            expanded = false
                            onTargetLanguageChange(language.code)
                        },
                    )
                }
            }
        }
    }
}
