package com.example.f95updater

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CleanupReviewDialog(
    report: CleanupReviewReport,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onRemoveOrphan: (CleanupGameEntry) -> Unit,
) {
    var searchQuery by rememberSaveable(report.rootPath) { mutableStateOf("") }
    var managedExpanded by rememberSaveable(report.rootPath) { mutableStateOf(true) }
    var unassociatedExpanded by rememberSaveable(report.rootPath) { mutableStateOf(true) }
    var orphanExpanded by rememberSaveable(report.rootPath) { mutableStateOf(true) }
    var unreadableExpanded by rememberSaveable(report.rootPath) { mutableStateOf(true) }
    val filtered = remember(report, searchQuery) { filterCleanupReview(report, searchQuery) }
    val hasSearch = searchQuery.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cleanup Review") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Root: ${report.rootPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "JoiPlay and Winlator entries come from AGM's current library. Folders are never deleted here; only confirmed orphan records can be removed from the list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${report.managedGames.size} managed games, " +
                        "${report.unassociatedFolders.size} unassociated folders, and ${report.orphanGames.size} orphan games.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (report.unreadableFolders.isNotEmpty() || report.scanLimitReached) {
                    Text(
                        buildString {
                            if (report.unreadableFolders.isNotEmpty()) {
                                append("${report.unreadableFolders.size} folders could not be read.")
                            }
                            if (report.scanLimitReached) {
                                if (isNotEmpty()) append(' ')
                                append("The scan safety limit was reached.")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search report") },
                    placeholder = { Text("Game, folder, path, engine, or status") },
                    singleLine = true,
                )
                DialogLazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        CleanupReportSectionHeader(
                            title = "1. Managed games",
                            visibleCount = filtered.managedGames.size,
                            totalCount = report.managedGames.size,
                            expanded = managedExpanded,
                            onToggle = { managedExpanded = !managedExpanded },
                        )
                    }
                    if (managedExpanded && filtered.managedGames.isEmpty()) {
                        item { ReportEmptyRow(if (hasSearch) "- No matches" else "- (none)") }
                    } else if (managedExpanded) {
                        items(filtered.managedGames, key = { "managed:${it.packageName}" }) { game ->
                            CleanupGameReportRow(game, onOpenFolder = onOpenFolder)
                        }
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                        CleanupReportSectionHeader(
                            title = "2. Unassociated folders",
                            visibleCount = filtered.unassociatedFolders.size,
                            totalCount = report.unassociatedFolders.size,
                            expanded = unassociatedExpanded,
                            onToggle = { unassociatedExpanded = !unassociatedExpanded },
                        )
                    }
                    if (unassociatedExpanded && filtered.unassociatedFolders.isEmpty()) {
                        item { ReportEmptyRow(if (hasSearch) "- No matches" else "- (none)") }
                    } else if (unassociatedExpanded) {
                        items(filtered.unassociatedFolders, key = { it.path }) { folder ->
                            CleanupFolderReportRow(folder, onOpenFolder)
                        }
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                        CleanupReportSectionHeader(
                            title = "3. Orphan games",
                            visibleCount = filtered.orphanGames.size,
                            totalCount = report.orphanGames.size,
                            expanded = orphanExpanded,
                            onToggle = { orphanExpanded = !orphanExpanded },
                        )
                    }
                    if (orphanExpanded && filtered.orphanGames.isEmpty()) {
                        item { ReportEmptyRow(if (hasSearch) "- No matches" else "- (none)") }
                    } else if (orphanExpanded) {
                        items(filtered.orphanGames, key = { "orphan:${it.packageName}" }) { game ->
                            CleanupGameReportRow(
                                game = game,
                                onOpenFolder = onOpenFolder,
                                action = {
                                    TextButton(onClick = { onRemoveOrphan(game) }) {
                                        Text("Remove from list")
                                    }
                                },
                            )
                        }
                    }
                    if (report.unreadableFolders.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            CleanupReportSectionHeader(
                                title = "Unreadable folders",
                                visibleCount = filtered.unreadableFolders.size,
                                totalCount = report.unreadableFolders.size,
                                expanded = unreadableExpanded,
                                onToggle = { unreadableExpanded = !unreadableExpanded },
                            )
                        }
                        if (unreadableExpanded && filtered.unreadableFolders.isEmpty()) {
                            item { ReportEmptyRow("- No matches") }
                        }
                        if (unreadableExpanded) items(filtered.unreadableFolders, key = { it }) { path ->
                            CleanupPathReportRow(
                                title = java.io.File(path).name.ifBlank { path },
                                path = path,
                                error = true,
                                onOpenFolder = onOpenFolder,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onSave) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun AppUpdatesDialog(
    results: List<UpdateCheckResult>,
    downloadProgress: UpdateDownloadProgress?,
    onDismiss: () -> Unit,
    onDownloadAndInstall: (UpdateCheckResult, AppUpdateInfo) -> Unit,
    joiPlayStatus: JoiPlayUpdateChecker.Status? = null,
    onOpenJoiPlayUrl: (String) -> Unit = {},
    pluginReport: JoiPlayPluginChecker.Report? = null,
    onInstallPlugin: (JoiPlayDownload) -> Unit = {},
) {
    val hasUpdate = results.any { it is UpdateCheckResult.Available } ||
        joiPlayStatus?.updateAvailable == true ||
        pluginReport?.anyUpdate == true
    val hasOptionalInstall = results.any { it is UpdateCheckResult.NotInstalled }
    val optionalOnly = results.all { it is UpdateCheckResult.NotInstalled }
    AlertDialog(
        onDismissRequest = { if (downloadProgress == null) onDismiss() },
        title = {
            Text(
                when {
                    hasUpdate -> "App updates available"
                    hasOptionalInstall -> "Optional companion apps"
                    else -> "App update status"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dialogVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                results.forEachIndexed { index, result ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(result.target.displayName, fontWeight = FontWeight.Bold)
                        val info = when (result) {
                            is UpdateCheckResult.Available -> result.info
                            is UpdateCheckResult.NotInstalled -> result.info
                            is UpdateCheckResult.UpToDate,
                            is UpdateCheckResult.Error -> null
                        }
                        when (result) {
                            is UpdateCheckResult.UpToDate -> {
                                Text("Installed v${result.currentVersionName} is up to date.")
                            }
                            is UpdateCheckResult.Error -> {
                                Text("Update check failed: ${result.message}")
                            }
                            is UpdateCheckResult.NotInstalled -> {
                                Text(
                                    result.target.optionalInstallDescription
                                        ?: "Optional companion app.",
                                )
                                Text("Available: v${result.info.versionName}")
                            }
                            is UpdateCheckResult.Available -> {
                                Text("Installed: v${result.currentVersionName}")
                                Text("Available: v${result.info.versionName}")
                            }
                        }
                        info?.let {
                            if (it.size > 0) {
                                Text("Size: ${fmtSize(it.size)}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (it.released.isNotBlank()) {
                                Text("Released: ${it.released}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (it.effectiveNotes.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(it.effectiveNotes, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val progress = downloadProgress?.takeIf { it.target == result.target }
                        progress?.let {
                            Spacer(Modifier.height(8.dp))
                            val frac = if (it.total > 0) {
                                (it.downloaded.toFloat() / it.total).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${fmtSize(it.downloaded)} / ${fmtSize(it.total)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (info != null) {
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = { onDownloadAndInstall(result, info) },
                                enabled = downloadProgress == null,
                            ) {
                                Text(if (progress == null) "Download & install" else "Downloading…")
                            }
                        }
                    }
                }
                joiPlayStatus?.let { jp ->
                    if (results.isNotEmpty()) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("JoiPlay", fontWeight = FontWeight.Bold)
                        when {
                            jp.downloads.isEmpty() ->
                                Text("Couldn't reach joiplay.net to check the JoiPlay version.")
                            !jp.installed -> {
                                Text("Not installed.")
                                jp.core?.let { Text("Latest: v${it.version}") }
                            }
                            jp.updateAvailable -> {
                                Text("Installed: v${jp.installedVersion}")
                                Text("Available: v${jp.core?.version}")
                            }
                            else -> Text("Installed v${jp.installedVersion} is up to date.")
                        }
                        if (jp.downloads.isNotEmpty()) {
                            Text(
                                "JoiPlay is a third-party app, so AGM can't install it directly — open the official site.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { onOpenJoiPlayUrl("https://joiplay.net/#downloads") }) {
                                Text("Open joiplay.net")
                            }
                        }
                    }
                }
                pluginReport?.takeIf { it.plugins.isNotEmpty() }?.let { report ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("JoiPlay engine plugins", fontWeight = FontWeight.Bold)
                        report.plugins.forEach { ps ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(ps.installed.titleStem, fontWeight = FontWeight.SemiBold)
                                when {
                                    ps.updateAvailable -> {
                                        Text("Installed: v${ps.installed.versionName}")
                                        Text("Available: v${ps.latest?.version}")
                                        Button(
                                            onClick = { ps.latest?.let(onInstallPlugin) },
                                            enabled = ps.latest != null,
                                        ) { Text("Update plugin") }
                                    }
                                    ps.latest != null ->
                                        Text(
                                            "Installed v${ps.installed.versionName ?: "?"} is up to date.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    else ->
                                        Text(
                                            "Installed v${ps.installed.versionName ?: "?"} (not on joiplay.net).",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = downloadProgress == null) {
                Text(if (optionalOnly) "Not now" else "Close")
            }
        },
    )
}
