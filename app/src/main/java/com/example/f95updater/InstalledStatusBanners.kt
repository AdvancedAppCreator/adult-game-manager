package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun InstalledScanStatusBanners(
    apkInstalling: Boolean,
    gameSizeScanning: Boolean,
    gameSizeScanProgress: JoiPlayScanner.SizeProgress?,
) {
    if (apkInstalling) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    "Installing\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
    if (gameSizeScanning) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                val progress = gameSizeScanProgress
                Text(
                    if (progress != null) {
                        "Scanning game storage ${progress.folderIndex} / ${progress.folderTotal}: ${progress.folderName}"
                    } else {
                        "Scanning game storage sizes…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun InstalledSelectionAndUsageBar(
    selectionMode: Boolean,
    selectionCount: Int,
    rowCount: Int,
    showHidden: Boolean,
    hasUsage: Boolean,
    onCancelSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onHideSelected: () -> Unit,
    onUnhideSelected: () -> Unit,
    onTagSelected: () -> Unit,
    onStatusSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    if (selectionMode) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancelSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                }
                Text(
                    "$selectionCount selected",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggleSelectAll) {
                    Text(if (selectionCount == rowCount && rowCount > 0) "Clear" else "All")
                }
                TextButton(onClick = onTagSelected) { Text("Tag") }
                TextButton(onClick = onStatusSelected) { Text("Status") }
                if (showHidden) {
                    TextButton(onClick = onUnhideSelected) { Text("Unhide") }
                } else {
                    TextButton(onClick = onHideSelected) { Text("Hide") }
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        HorizontalDivider()
    }
    if (!hasUsage) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Optional: grant Usage access (menu) so the app can show \"Last used\" dates.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun InstalledSearchHeader(
    nameFilter: String,
    onNameFilterChange: (String) -> Unit,
    onImeDone: () -> Unit,
    onSearchBoundsChange: (androidx.compose.ui.geometry.Rect) -> Unit,
    tagSuggestions: List<String>,
    onTagSuggestionClick: (String) -> Unit,
) {
    OutlinedTextField(
        value = nameFilter,
        onValueChange = onNameFilterChange,
        placeholder = { Text("Filter: text + tag:harem tag:incest") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (nameFilter.isNotEmpty()) {
                IconButton(onClick = { onNameFilterChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { onImeDone() },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onGloballyPositioned { onSearchBoundsChange(it.boundsInParent()) },
    )
    if (tagSuggestions.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (suggestion in tagSuggestions) {
                AssistChip(
                    onClick = { onTagSuggestionClick(suggestion) },
                    label = { Text(suggestion, fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
internal fun InstalledTopBarTitle(
    showHidden: Boolean,
    rowCount: Int,
    compactWidth: Boolean,
    compactHeight: Boolean,
    onShowStatus: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                showHidden -> "Hidden ($rowCount)"
                compactWidth || compactHeight -> "AGM v$versionName"
                else -> "Adult Game Manager v$versionName"
            },
            fontSize = if (compactHeight) 20.sp else TextUnit.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onShowStatus, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Info, contentDescription = "List status")
        }
    }
}

@Composable
internal fun InstalledSortMenu(
    sortMenuOpen: Boolean,
    onSortMenuOpenChange: (Boolean) -> Unit,
    availableSortKeys: List<SortKey>,
    sortKey: SortKey,
    sortDesc: Boolean,
    onSelectSort: (SortKey) -> Unit,
) {
    Box {
        IconButton(onClick = { onSortMenuOpenChange(true) }) {
            Icon(Icons.Default.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { onSortMenuOpenChange(false) }) {
            availableSortKeys.forEach { k ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (k == sortKey) {
                                Icon(
                                    if (sortDesc) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            } else {
                                Spacer(Modifier.width(22.dp))
                            }
                            Text(k.label)
                        }
                    },
                    onClick = { onSelectSort(k) }
                )
            }
        }
    }
}

@Composable
private fun SubmenuHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    open: Boolean,
    onToggle: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = {
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        },
        onClick = onToggle,
    )
}

@Composable
private fun SubmenuBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun InstalledViewSubmenu(
    subViewOpen: Boolean,
    onToggle: () -> Unit,
    layoutMode: LibraryLayoutMode,
    showHidden: Boolean,
    hiddenCount: Int,
    onCollections: () -> Unit,
    onToggleViewType: () -> Unit,
    onToggleShowHidden: () -> Unit,
) {
    SubmenuHeader("View \u2026", Icons.Default.Visibility, subViewOpen, onToggle)
    if (subViewOpen) {
        SubmenuBody {
            DropdownMenuItem(
                text = { Text("Collections\u2026") },
                leadingIcon = { Icon(Icons.Default.Storage, null) },
                onClick = onCollections,
            )
            DropdownMenuItem(
                text = { Text("View type: ${layoutMode.label}") },
                leadingIcon = {
                    Icon(
                        when (layoutMode) {
                            LibraryLayoutMode.List -> Icons.Default.ViewList
                            LibraryLayoutMode.Cards -> Icons.Default.GridView
                            LibraryLayoutMode.FolderTree -> Icons.Default.AccountTree
                        },
                        null,
                    )
                },
                onClick = onToggleViewType,
            )
            DropdownMenuItem(
                text = { Text(if (showHidden) "Show visible apps" else "Show hidden apps ($hiddenCount)") },
                leadingIcon = {
                    Icon(if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)
                },
                onClick = onToggleShowHidden,
            )
        }
    }
}

@Composable
internal fun InstalledSaveToolsSubmenu(
    subSaveToolsOpen: Boolean,
    onToggle: () -> Unit,
    saveScanning: Boolean,
    hasAnyLocations: Boolean,
    onScanSaves: () -> Unit,
    onShowLocations: () -> Unit,
    onBrowseBackups: () -> Unit,
) {
    SubmenuHeader("Save tools \u2026", Icons.Default.Save, subSaveToolsOpen, onToggle)
    if (subSaveToolsOpen) {
        SubmenuBody {
            DropdownMenuItem(
                text = { Text(if (saveScanning) "Scanning saves\u2026" else "Scan save folders") },
                leadingIcon = {
                    if (saveScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Save, null)
                    }
                },
                enabled = !saveScanning,
                onClick = onScanSaves,
            )
            DropdownMenuItem(
                text = { Text("Show save folders") },
                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                enabled = hasAnyLocations && !saveScanning,
                onClick = onShowLocations,
            )
            DropdownMenuItem(
                text = { Text("Browse save backups") },
                leadingIcon = { Icon(Icons.Default.History, null) },
                enabled = hasAnyLocations,
                onClick = onBrowseBackups,
            )
        }
    }
}

@Composable
internal fun InstalledCatalogSubmenu(
    subCatalogOpen: Boolean,
    onToggle: () -> Unit,
    onRefreshFromCatalog: () -> Unit,
    onReviewUnmapped: () -> Unit,
    onAutoHideNonGames: () -> Unit,
) {
    SubmenuHeader("Catalog \u2026", Icons.Default.CloudDownload, subCatalogOpen, onToggle)
    if (subCatalogOpen) {
        SubmenuBody {
            DropdownMenuItem(
                text = { Text("Refresh from catalog") },
                leadingIcon = { Icon(Icons.Default.Sync, null) },
                onClick = onRefreshFromCatalog,
            )
            DropdownMenuItem(
                text = { Text("Review unmapped games") },
                onClick = onReviewUnmapped,
            )
            DropdownMenuItem(
                text = { Text("Auto-hide non-games") },
                onClick = onAutoHideNonGames,
            )
        }
    }
}

@Composable
internal fun InstalledInstallGamesSubmenu(
    subInstallGamesOpen: Boolean,
    onToggle: () -> Unit,
    onAddInstallGame: () -> Unit,
    onBulkInstallGames: () -> Unit,
    onInstallPatch: () -> Unit,
    onManageInstalledPatches: () -> Unit,
    installedPatchCount: Int,
) {
    SubmenuHeader("Install / Games \u2026", Icons.Default.GetApp, subInstallGamesOpen, onToggle)
    if (subInstallGamesOpen) {
        SubmenuBody {
            DropdownMenuItem(
                text = { Text("Add / Install game\u2026") },
                leadingIcon = { Icon(Icons.Default.GetApp, null) },
                onClick = onAddInstallGame,
            )
            DropdownMenuItem(
                text = { Text("Bulk install games\u2026") },
                leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                onClick = onBulkInstallGames,
            )
            DropdownMenuItem(
                text = { Text("Install game patch\u2026") },
                leadingIcon = { Icon(Icons.Default.Build, null) },
                onClick = onInstallPatch,
            )
            DropdownMenuItem(
                text = { Text("Installed patches ($installedPatchCount)\u2026") },
                leadingIcon = { Icon(Icons.Default.Undo, null) },
                enabled = installedPatchCount > 0,
                onClick = onManageInstalledPatches,
            )
        }
    }
}

@Composable
internal fun InstalledMaintenanceSubmenu(
    subMaintenanceOpen: Boolean,
    onToggle: () -> Unit,
    gameSizeScanning: Boolean,
    cleanupScanning: Boolean,
    onCleanupReview: () -> Unit,
    onFindDuplicates: () -> Unit,
    onStorageDashboard: () -> Unit,
    onRefreshStorageSizes: () -> Unit,
) {
    SubmenuHeader("Maintenance \u2026", Icons.Default.Build, subMaintenanceOpen, onToggle)
    if (subMaintenanceOpen) {
        SubmenuBody {
            DropdownMenuItem(
                text = { Text("Cleanup Review\u2026") },
                leadingIcon = {
                    if (cleanupScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Folder, null)
                    }
                },
                enabled = !cleanupScanning,
                onClick = onCleanupReview,
            )
            DropdownMenuItem(
                text = { Text("Find duplicates\u2026") },
                leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                onClick = onFindDuplicates,
            )
            DropdownMenuItem(
                text = { Text("Storage & cleanup\u2026") },
                leadingIcon = { Icon(Icons.Default.Storage, null) },
                onClick = onStorageDashboard,
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (gameSizeScanning) "Scanning game storage\u2026"
                        else "Refresh game storage sizes"
                    )
                },
                leadingIcon = {
                    if (gameSizeScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Storage, null)
                    }
                },
                enabled = !gameSizeScanning,
                onClick = onRefreshStorageSizes,
            )
        }
    }
}

@Composable
internal fun InstalledBackupSubmenu(
    subBackupOpen: Boolean,
    onToggle: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onImportJoiPlayBackup: () -> Unit,
    onRestoreAutoBackup: () -> Unit,
) {
    SubmenuHeader("Backup & config \u2026", Icons.Default.SaveAlt, subBackupOpen, onToggle)
    if (subBackupOpen) {
        SubmenuBody {
            DropdownMenuItem(
                text = { Text("Export backup") },
                onClick = onExportBackup,
            )
            DropdownMenuItem(
                text = { Text("Import backup") },
                onClick = onImportBackup,
            )
            DropdownMenuItem(
                text = { Text("Import JoiPlay backup (.joiback)\u2026") },
                onClick = onImportJoiPlayBackup,
            )
            DropdownMenuItem(
                text = { Text("Restore auto-backup\u2026") },
                onClick = onRestoreAutoBackup,
            )
        }
    }
}

@Composable
internal fun InstalledHelpSubmenu(
    subLogsOpen: Boolean,
    onToggle: () -> Unit,
    hasBmc: Boolean,
    hasStripe: Boolean,
    hasCrashUpload: Boolean,
    diagnosticsEnabled: Boolean,
    matchResearchInProgress: Boolean,
    launchScreenshotRunning: Boolean,
    screenshotWalkthroughRunning: Boolean,
    onDocumentation: () -> Unit,
    onAbout: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onSaveLocalDiagnostics: () -> Unit,
    onSavedPasswords: () -> Unit,
    onUploadCrashLogs: () -> Unit,
    onUploadAppLogs: () -> Unit,
    onSupport: () -> Unit,
    onUploadMatchResearch: () -> Unit,
    onCaptureLaunchScreenshots: () -> Unit,
    onCaptureWalkthroughScreenshots: () -> Unit,
    onSaveLogsToDocuments: () -> Unit,
    onToggleVerbose: () -> Unit,
) {
    val context = LocalContext.current
    DropdownMenuItem(
        text = { Text("Help \u2026") },
        leadingIcon = { Icon(Icons.Default.HelpOutline, null) },
        trailingIcon = {
            Icon(
                if (subLogsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
            )
        },
        onClick = onToggle
    )
    if (subLogsOpen) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
                Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
                    DropdownMenuItem(
                        text = { Text("Documentation (browser)") },
                        leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) },
                        onClick = onDocumentation
                    )
                    DropdownMenuItem(
                        text = { Text("About") },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = onAbout
                    )
                    DropdownMenuItem(
                        text = { Text("Copy diagnostics summary") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = onCopyDiagnostics
                    )
                    DropdownMenuItem(
                        text = { Text("Save local diagnostics") },
                        leadingIcon = { Icon(Icons.Default.BugReport, null) },
                        onClick = onSaveLocalDiagnostics
                    )
                    DropdownMenuItem(
                        text = { Text("Saved archive passwords (${PasswordVault.all().size})") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        onClick = onSavedPasswords
                    )
                    if (hasCrashUpload) {
                        DropdownMenuItem(
                            text = { Text("Upload crash logs (${CrashReporter.pendingCount(context.applicationContext)})") },
                            onClick = onUploadCrashLogs
                        )
                        DropdownMenuItem(
                            text = { Text("Upload app logs + screenshots (${(AppLog.diskBytes() / 1024)} KB)") },
                            onClick = onUploadAppLogs
                        )
                    }
                    if (hasBmc || hasStripe) {
                        DropdownMenuItem(
                            text = { Text(if (hasBmc && hasStripe) "Support the project" else "Support link") },
                            leadingIcon = { Text("\u2615", fontSize = 18.sp) },
                            onClick = onSupport
                        )
                    }
                    if (diagnosticsEnabled) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (matchResearchInProgress) "Uploading match research..." else "Upload match research snapshot") },
                            leadingIcon = {
                                if (matchResearchInProgress) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.CloudUpload, null)
                                }
                            },
                            enabled = !matchResearchInProgress,
                            onClick = onUploadMatchResearch
                        )
                        DropdownMenuItem(
                            text = { Text(if (launchScreenshotRunning) "Capturing launch screenshots..." else "Capture launch screenshots") },
                            leadingIcon = {
                                if (launchScreenshotRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.PhotoCamera, null)
                                }
                            },
                            enabled = !launchScreenshotRunning && !screenshotWalkthroughRunning,
                            onClick = onCaptureLaunchScreenshots
                        )
                        DropdownMenuItem(
                            text = { Text(if (screenshotWalkthroughRunning) "Capturing screenshots..." else "Capture walkthrough screenshots") },
                            leadingIcon = {
                                if (screenshotWalkthroughRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.BugReport, null)
                                }
                            },
                            enabled = !screenshotWalkthroughRunning && !launchScreenshotRunning,
                            onClick = onCaptureWalkthroughScreenshots
                        )
                        DropdownMenuItem(
                            text = { Text("Save logs to Documents") },
                            leadingIcon = { Icon(Icons.Default.BugReport, null) },
                            onClick = onSaveLogsToDocuments
                        )
                        DropdownMenuItem(
                            text = { Text(if (AppLog.verbose) "Verbose logs: ON" else "Verbose logs: off") },
                            onClick = onToggleVerbose
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun InstalledLoadingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            "Loading installed games…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
