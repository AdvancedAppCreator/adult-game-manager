package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Picks a [GameState] to apply to a set of selected games (or clears it). */
@Composable
internal fun GameStatePickerDialog(
    count: Int,
    onDismiss: () -> Unit,
    onApply: (GameState) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set status \u00b7 $count game${if (count == 1) "" else "s"}") },
        text = {
            Column {
                GameState.assignable.forEach { st ->
                    Text(
                        st.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onApply(st) }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                HorizontalDivider()
                Text(
                    "Clear status",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onApply(GameState.None) }
                        .padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Browse the library by collection: user-assigned status buckets and user tags, each with a count.
 * Tapping a row applies the corresponding filter to the library and closes.
 */
@Composable
internal fun CollectionsDialog(
    stateCounts: Map<GameState, Int>,
    tagCounts: List<Pair<String, Int>>,
    onPickState: (GameState) -> Unit,
    onPickTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Collections", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                DialogLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item { Text("By status", style = MaterialTheme.typography.titleMedium) }
                    val states = GameState.assignable.filter { (stateCounts[it] ?: 0) > 0 }
                    if (states.isEmpty()) {
                        item {
                            Text(
                                "No statuses assigned yet. Select games and use Status.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(states, key = { "state:" + it.name }) { st ->
                            CollectionRow(st.label, stateCounts[st] ?: 0) { onPickState(st) }
                        }
                    }
                    item {
                        Text(
                            "By tag",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (tagCounts.isEmpty()) {
                        item {
                            Text(
                                "No tags yet. Select games and use Tag.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(tagCounts, key = { "tag:" + it.first }) { (tag, count) ->
                            CollectionRow(tag, count) { onPickTag(tag) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(label: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
