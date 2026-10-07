package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dialog to add/remove the user's own tags on a single installed game. Tags are normalized on add
 * (lowercased, whitespace-collapsed). Existing tags from other games are offered as suggestions.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun EditUserTagsDialog(
    gameLabel: String,
    currentTags: Set<String>,
    allExistingTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var tags by remember { mutableStateOf(currentTags.toList()) }
    var input by remember { mutableStateOf("") }

    fun addCurrentInput() {
        val t = UserTagsStore.normalize(input) ?: return
        if (t !in tags) tags = tags + t
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    gameLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { tags = tags - tag },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Add a tag") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addCurrentInput() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { addCurrentInput() },
                        enabled = UserTagsStore.normalize(input) != null,
                    ) { Text("Add") }
                }
                val suggestions = remember(input, tags, allExistingTags) {
                    val q = input.trim().lowercase()
                    allExistingTags.filter { it !in tags && (q.isEmpty() || it.contains(q)) }.take(8)
                }
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { s ->
                            AssistChip(
                                onClick = { if (s !in tags) tags = tags + s },
                                label = { Text(s, fontSize = 12.sp) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(tags.toSet()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
