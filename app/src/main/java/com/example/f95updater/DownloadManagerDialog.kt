package com.example.f95updater

import android.app.DownloadManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent Download Manager: lists downloads started from the in-app browser. Running items show
 * live progress (polled from [DownloadManager]); completed items expose Install / Delete / Clear.
 */
@Composable
fun DownloadManagerDialog(
    records: List<DownloadRecord>,
    onCancel: (DownloadRecord) -> Unit,
    onRetry: (DownloadRecord) -> Unit,
    onInstall: (DownloadRecord) -> Unit,
    onDeleteFile: (DownloadRecord) -> Unit,
    onClearEntry: (DownloadRecord) -> Unit,
    onOpenInCatalog: (CatalogGame) -> Unit,
    onRemoveMissing: () -> Unit,
    onClearFinished: () -> Unit,
    onReturnToBrowser: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dm = remember { context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager }
    var progress by remember { mutableStateOf<Map<Long, DownloadManagerSnapshot>>(emptyMap()) }
    var queriedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingConstraints by remember { mutableStateOf(PendingDownloadConstraints()) }
    var confirmDelete by remember { mutableStateOf<DownloadRecord?>(null) }

    val runningIds = records.filter {
        it.backend == DownloadBackend.AndroidDownloadManager &&
            it.state == DownloadState.Running
    }.map { it.id }
    LaunchedEffect(runningIds) {
        if (runningIds.isEmpty()) {
            progress = emptyMap()
            queriedIds = emptySet()
            return@LaunchedEffect
        }
        while (runningIds.isNotEmpty()) {
            val (snapshots, constraints) = withContext(Dispatchers.IO) {
                queryDownloadManagerSnapshots(dm, runningIds) to
                    queryPendingDownloadConstraints(context.applicationContext)
            }
            progress = snapshots
            pendingConstraints = constraints
            queriedIds = runningIds.toSet()
            delay(800)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize().padding(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Downloads", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    onReturnToBrowser?.let { returnToBrowser ->
                        TextButton(onClick = returnToBrowser) { Text("Browser") }
                    }
                    val hasFinished = records.any { !it.state.isActive }
                    val hasCompleted = records.any { it.state == DownloadState.Completed }
                    TextButton(onClick = onRemoveMissing, enabled = hasCompleted) { Text("Remove missing") }
                    TextButton(onClick = onClearFinished, enabled = hasFinished) { Text("Clear list") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                if (records.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No downloads yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    DialogLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        items(records, key = { it.id }) { rec ->
                            DownloadRow(
                                rec = rec,
                                progress = progress[rec.id],
                                queryCompleted = rec.id in queriedIds,
                                pendingConstraints = pendingConstraints,
                                onCancel = { onCancel(rec) },
                                onRetry = { onRetry(rec) },
                                onInstall = { onInstall(rec) },
                                onDelete = { confirmDelete = rec },
                                onClearEntry = { onClearEntry(rec) },
                                onOpenInCatalog = rec.catalogGame
                                    ?.takeIf { catalogNavigationIdentity(it) != null }
                                    ?.let { game ->
                                    { onOpenInCatalog(game) }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = { Text("Permanently delete \u201C${rec.fileName}\u201D from storage? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFile(rec)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DownloadRow(
    rec: DownloadRecord,
    progress: DownloadManagerSnapshot?,
    queryCompleted: Boolean,
    pendingConstraints: PendingDownloadConstraints,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onClearEntry: () -> Unit,
    onOpenInCatalog: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(rec.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        rec.catalogGame?.let { game ->
            Text(
                "Linked to ${game.title.ifBlank { game.agmGroupId ?: "${game.source}:${game.sourceId}" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val subtitle = when (rec.state) {
            DownloadState.Running -> if (rec.backend == DownloadBackend.ExternalBrowser) {
                "Opened in external browser — waiting to detect file"
            } else {
                downloadRuntimeStatusText(
                    rec,
                    progress,
                    queryCompleted,
                    System.currentTimeMillis(),
                    pendingConstraints,
                )
            }
            DownloadState.Finalizing -> if (rec.totalBytes > 0L) {
                "Finalizing — ${humanDownloadBytes(rec.finalizedBytes)} / " +
                    humanDownloadBytes(rec.totalBytes)
            } else {
                "Finalizing file"
            }
            DownloadState.Completed -> rec.destPath ?: "Completed"
            DownloadState.Failed -> "Failed" + (rec.error?.let { " ($it)" } ?: "")
            DownloadState.FinalizationFailed ->
                "Finalization failed" + (rec.error?.let { " ($it)" } ?: "")
            DownloadState.Cancelled -> "Cancelled"
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (rec.state) {
            DownloadState.Running -> {
                if (rec.backend == DownloadBackend.ExternalBrowser) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onRetry) { Text("Retry in browser") }
                        TextButton(onClick = onCancel) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Text("Stop tracking", modifier = Modifier.padding(start = 4.dp))
                        }
                        onOpenInCatalog?.let { open ->
                            TextButton(onClick = open) { Text("Catalog") }
                        }
                    }
                    return@Column
                }
                val frac = progress
                    ?.takeIf {
                        (it.status == DownloadManager.STATUS_RUNNING ||
                            it.status == DownloadManager.STATUS_PAUSED) &&
                            it.totalBytes > 0
                    }
                    ?.let { it.downloadedBytes.toFloat() / it.totalBytes }
                if (frac != null) {
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (progress?.status != DownloadManager.STATUS_SUCCESSFUL) {
                        TextButton(onClick = onRetry) {
                            Text(
                                if (queryCompleted &&
                                    (progress == null ||
                                        progress.status == DownloadManager.STATUS_FAILED)
                                ) "Retry" else "Restart",
                            )
                        }
                        TextButton(onClick = onCancel) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Text("Cancel", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    onOpenInCatalog?.let { open ->
                        TextButton(onClick = open) { Text("Catalog") }
                    }
                }
            }
            DownloadState.Finalizing -> {
                val fraction = rec.totalBytes
                    .takeIf { it > 0L }
                    ?.let { (rec.finalizedBytes.toFloat() / it).coerceIn(0f, 1f) }
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                onOpenInCatalog?.let { open ->
                    TextButton(onClick = open) { Text("Catalog") }
                }
            }
            DownloadState.Completed -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onInstall) { Text("Install") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                    onOpenInCatalog?.let { open ->
                        TextButton(onClick = open) { Text("Catalog") }
                    }
                    TextButton(onClick = onClearEntry) { Text("Clear from list") }
                }
            }
            DownloadState.Failed, DownloadState.Cancelled -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    onOpenInCatalog?.let { open ->
                        TextButton(onClick = open) { Text("Catalog") }
                    }
                    TextButton(onClick = onClearEntry) { Text("Clear from list") }
                }
            }
            DownloadState.FinalizationFailed -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) { Text("Retry finalization") }
                    onOpenInCatalog?.let { open ->
                        TextButton(onClick = open) { Text("Catalog") }
                    }
                    TextButton(onClick = onClearEntry) { Text("Clear from list") }
                }
            }
        }
    }
}
