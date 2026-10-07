package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SaveBackupBrowserEntry(
    val engine: String,
    val gameLabel: String,
    val slotName: String,
    val backup: RenPySaveBackup,
)

@Composable
internal fun SaveBackupBrowserDialog(
    renPyLocations: List<RenPySaveLocation>,
    rpgmLocations: List<RpgmSaveLocation>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<SaveBackupBrowserEntry>>(emptyList()) }
    LaunchedEffect(renPyLocations, rpgmLocations) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            buildList {
                for (location in renPyLocations) {
                    val label = location.associatedLabel ?: location.ownerId
                    for (slot in RenPySaveScanner.listSaveSlots(location)) {
                        for (backup in RenPySaveEditor.listBackups(slot)) {
                            add(SaveBackupBrowserEntry("Ren'Py", label, slot.fileName, backup))
                        }
                    }
                }
                for (location in rpgmLocations) {
                    val label = location.associatedLabel ?: location.ownerId
                    for (slot in RpgmSaveScanner.listSaveSlots(location)) {
                        for (backup in RpgmSaveEditor.listBackups(slot)) {
                            add(SaveBackupBrowserEntry("RPGM", label, slot.fileName, backup))
                        }
                    }
                }
            }.sortedByDescending { it.backup.createdAt }
        }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.88f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Save backup browser") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Backups are grouped from currently scanned Ren'Py/RPGM save folders. Open a slot editor to restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading backups…", style = MaterialTheme.typography.bodySmall)
                    }
                    entries.isEmpty() -> Text("No AGM-managed save backups found.", style = MaterialTheme.typography.bodySmall)
                    else -> DialogLazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(entries) { entry ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${entry.engine} • ${entry.gameLabel}", fontWeight = FontWeight.SemiBold)
                                    Text("Slot: ${entry.slotName}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${entry.backup.fileName} • ${fmtDateTime(entry.backup.createdAt)} • ${fmtSize(entry.backup.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(entry.backup.filePath)) }) {
                                        Text("Copy backup path")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
