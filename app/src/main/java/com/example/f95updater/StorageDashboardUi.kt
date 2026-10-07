package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private fun sourceLabel(source: AppSource): String = when (source) {
    AppSource.Android -> "Apps"
    AppSource.Managed -> "Managed games"
    AppSource.JoiPlay -> "JoiPlay"
    AppSource.Winlator -> "Winlator"
    AppSource.Kirikiroid -> "Kirikiroid"
}

private fun staleWindowLabel(months: Int): String = "${months}mo"

/**
 * Read-only "where does storage go" dashboard plus AGM-owned reclaim actions (prune old JoiPlay
 * rollback backups, clear AGM cache) and, behind a toggle, a link to the read-only leftover-folder
 * review. Self-contained so InstalledScreen stays within the JVM 64 KB method limit.
 */
@Composable
internal fun StorageDashboardDialog(
    apps: List<InstalledApp>,
    joiPlaySizeInfo: Map<String, JoiPlayScanner.SizeInfo>,
    lastPlayed: Map<String, Long>,
    onRequestDelete: (List<InstalledApp>) -> Unit,
    onOpenLeftoverReview: () -> Unit,
    onRescan: () -> Unit,
    onSnack: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }

    var staleMonths by remember { mutableStateOf(3) }
    var showLeftovers by remember { mutableStateOf(CleanupPrefs.showLeftovers(context)) }

    var backupReclaim by remember { mutableStateOf<HousekeepingManager.BackupReclaim?>(null) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    fun rescanReclaim() {
        scanning = true
        scope.launch {
            backupReclaim = HousekeepingManager.scanPrunableBackups(apps)
            cacheBytes = HousekeepingManager.scanCache(context)
            scanning = false
        }
    }
    LaunchedEffect(apps) { rescanReclaim() }

    val sizeOf: (AppRow) -> Long = { r ->
        effectiveInstalledSize(r.installed, joiPlaySizeKey(r.installed)?.let { joiPlaySizeInfo[it] })
    }
    val rows = remember(apps) { apps.map { AppRow(it, null, UpdateStatus.Unknown) } }
    val insights = remember(rows, joiPlaySizeInfo, lastPlayed, staleMonths) {
        buildStorageInsights(
            rows = rows,
            sizeOf = sizeOf,
            lastUsedOf = { LastPlayedStore.effectiveLastUsed(it.installed, lastPlayed) },
            now = now,
            staleThresholdMs = staleMonths * STALE_MONTH_MS,
            topN = 10,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Storage & cleanup",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                DialogLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "${fmtSize(insights.totalBytes)} across ${insights.totalCount} " +
                                "game${if (insights.totalCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            insights.perSource.forEach { u ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(sourceLabel(u.source), modifier = Modifier.weight(1f))
                                    Text("${u.count} \u00b7 ${fmtSize(u.bytes)}")
                                }
                            }
                        }
                    }

                    item { SectionHeader("Biggest games") }
                    items(insights.biggest, key = { "big:" + it.row.installed.packageName }) { sized ->
                        DashboardGameRow(
                            title = sized.row.installed.label,
                            trailing = fmtSize(sized.bytes),
                            onDelete = { onRequestDelete(listOf(sized.row.installed)) },
                        )
                    }

                    item {
                        SectionHeader("Not played recently")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf(1, 3, 6).forEach { m ->
                                FilterChip(
                                    selected = staleMonths == m,
                                    onClick = { staleMonths = m },
                                    label = { Text(staleWindowLabel(m)) },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (insights.stale.isNotEmpty()) {
                                OutlinedButton(onClick = {
                                    onRequestDelete(insights.stale.map { it.row.installed })
                                }) { Text("Delete ${insights.stale.size}") }
                            }
                        }
                    }
                    if (insights.stale.isEmpty()) {
                        item {
                            Text(
                                "Nothing older than $staleMonths month${if (staleMonths == 1) "" else "s"}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(insights.stale.take(20), key = { "stale:" + it.row.installed.packageName }) { sized ->
                            DashboardGameRow(
                                title = sized.row.installed.label,
                                trailing = if (sized.lastUsed <= 0L) "never" else fmtDate(sized.lastUsed),
                                onDelete = { onRequestDelete(listOf(sized.row.installed)) },
                            )
                        }
                    }

                    item { SectionHeader("Reclaim space") }
                    item {
                        val br = backupReclaim
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Old update backups")
                                Text(
                                    when {
                                        scanning -> "Scanning\u2026"
                                        br == null || br.prunableFolders == 0 -> "Nothing to prune (keeps newest 2)"
                                        else -> "${br.prunableFolders} folder(s) \u00b7 ${fmtSize(br.prunableBytes)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                enabled = !busy && br != null && br.prunableFolders > 0,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val res = HousekeepingManager.pruneBackups(apps)
                                        onSnack("Pruned ${res.deletedFolders} backup folder(s), freed ${fmtSize(res.freedBytes)}")
                                        onRescan()
                                        rescanReclaim()
                                        busy = false
                                    }
                                },
                            ) { Text("Prune") }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("App cache")
                                Text(
                                    when {
                                        scanning -> "Scanning\u2026"
                                        cacheBytes == null || cacheBytes == 0L -> "Empty"
                                        else -> fmtSize(cacheBytes!!)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                enabled = !busy && (cacheBytes ?: 0L) > 0L,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val freed = HousekeepingManager.clearCache(context)
                                        onSnack("Cleared cache, freed ${fmtSize(freed)}")
                                        rescanReclaim()
                                        busy = false
                                    }
                                },
                            ) { Text("Clear") }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Show possible leftover folders")
                                Text(
                                    "Read-only review of folders not linked to any game. AGM never deletes them.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = showLeftovers,
                                onCheckedChange = {
                                    showLeftovers = it
                                    CleanupPrefs.setShowLeftovers(context, it)
                                },
                            )
                        }
                    }
                    if (showLeftovers) {
                        item {
                            AssistChip(
                                onClick = onOpenLeftoverReview,
                                label = { Text("Review leftover folders\u2026") },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DashboardGameRow(title: String, trailing: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(trailing, style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
