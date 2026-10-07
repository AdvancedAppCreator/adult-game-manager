package com.example.f95updater

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ExtractProgressDialog(
    archiveName: String,
    phase: JoiPlayExtractFlow.Phase,
    progress: ArchiveExtractor.Progress?,
    onCancel: () -> Unit,
    subStatus: String? = null,
    cancelEnabled: Boolean = true,
    onMinimize: (() -> Unit)? = null,
) {
    val phaseLabel = when (phase) {
        JoiPlayExtractFlow.Phase.Preparing -> "Preparing"
        JoiPlayExtractFlow.Phase.Extracting -> "Extracting"
        JoiPlayExtractFlow.Phase.Cancelling -> "Cancelling"
    }
    val isCancelling = phase == JoiPlayExtractFlow.Phase.Cancelling
    AlertDialog(
        onDismissRequest = { /* not dismissible by tap-out */ },
        title = { Text("$phaseLabel $archiveName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (subStatus != null) {
                    Text(
                        subStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isCancelling || (progress?.bytesTotal ?: 0L) <= 0L) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress?.percent ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val written = progress?.bytesWritten ?: 0L
                val total = progress?.bytesTotal ?: 0L
                Text(
                    text = buildString {
                        append("%,d".format(written))
                        append(" / ")
                        append(if (total > 0) "%,d".format(total) else "?")
                        append(" bytes")
                        if (total > 0) {
                            append("  •  ")
                            append("%.1f%%".format((written.toDouble() / total) * 100))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${fmtSize(written)} of ${fmtSize(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress?.currentEntry?.let {
                    Text(
                        if (isCancelling) "Stopping extraction and cleaning up partial files…" else it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, enabled = !isCancelling && cancelEnabled) {
                Text(if (isCancelling) "Cancelling…" else "Cancel")
            }
        },
        dismissButton = onMinimize?.let {
            {
                TextButton(onClick = it, enabled = !isCancelling) { Text("Minimize") }
            }
        },
    )
}

@Composable
fun PasswordPromptDialog(
    archiveName: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
    message: String? = null,
) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (message != null) "Password needed" else "Password required") },
        text = {
            Column {
                Text(message ?: "'$archiveName' is encrypted.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "A password that works is saved for next time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pwd) }, enabled = pwd.isNotBlank()) { Text("Extract") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// ---------------------------- SAVED PASSWORDS ----------------------------

@Composable
fun SavedPasswordsDialog(onClose: () -> Unit) {
    var newPwd by remember { mutableStateOf("") }
    val entries = PasswordVault.entries
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Saved archive passwords") },
        text = {
            Column {
                Text(
                    "Tried automatically (top first) when an archive needs a password. A password that works is saved here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Text("No saved passwords yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 240.dp)
                            .dialogVerticalScroll()
                    ) {
                        entries.toList().forEach { pwd ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    pwd,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                IconButton(onClick = { PasswordVault.delete(pwd) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete password")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPwd,
                        onValueChange = { newPwd = it },
                        label = { Text("Add password") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (PasswordVault.add(newPwd.trim())) newPwd = "" },
                        enabled = newPwd.isNotBlank(),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}
