package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Lists duplicate / multi-version game groups and offers a "keep newest, delete the rest" action per
 * group, routed through the normal bulk-delete confirmation. Never auto-deletes.
 */
@Composable
internal fun DuplicatesDialog(
    rows: List<AppRow>,
    joiPlaySizeInfo: Map<String, JoiPlayScanner.SizeInfo>,
    onRequestDelete: (List<InstalledApp>) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(rows) { findDuplicateGroups(rows) }
    fun sizeOf(app: InstalledApp): Long =
        effectiveInstalledSize(app, joiPlaySizeKey(app)?.let { joiPlaySizeInfo[it] })

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Duplicates", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                if (groups.isEmpty()) {
                    Text(
                        "No duplicate or multi-version games found in the current view.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    DialogLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(groups, key = { it.key }) { group ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(group.title, style = MaterialTheme.typography.titleMedium)
                                group.rows.forEachIndexed { index, row ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            buildString {
                                                append(row.installed.versionName.ifBlank { "?" })
                                                append("  ")
                                                append(sourceTag(row.installed.source))
                                            },
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(fmtSize(sizeOf(row.installed)), style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(8.dp))
                                        if (index == 0) {
                                            AssistChip(onClick = {}, enabled = false, label = { Text("Keep") })
                                        } else {
                                            Text("older", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (group.older.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { onRequestDelete(group.older.map { it.installed }) },
                                    ) { Text("Delete ${group.older.size} older") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sourceTag(source: AppSource): String = when (source) {
    AppSource.Android -> "app"
    AppSource.Managed -> "managed"
    AppSource.JoiPlay -> "JoiPlay"
    AppSource.Winlator -> "Winlator"
    AppSource.Kirikiroid -> "Kirikiroid"
}
