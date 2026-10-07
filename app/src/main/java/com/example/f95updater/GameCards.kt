package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withContext

private fun statusColor(s: UpdateStatus): Color = when (s) {
    UpdateStatus.UpdateAvailable -> Color(0xFFE57373)
    UpdateStatus.UpToDate -> Color(0xFF81C784)
    UpdateStatus.NotMapped -> Color.Gray
    UpdateStatus.Unknown -> Color(0xFFFFB74D)
    UpdateStatus.CheckFailed -> Color(0xFFBA68C8)
}

@Composable
private fun gameSourceContainerColor(app: InstalledApp): Color =
    LocalCardColorSettings.current.colorFor(app)

@Composable
private fun ManagedRunnerLabels(app: InstalledApp) {
    if (app.source != AppSource.Managed) return
    val labels = app.managedRunnerBindings
        .filter { it.enabled }
        .map { binding ->
            binding.kind.displayName() +
                if (binding.kind == app.managedDefaultRunner) " ★" else ""
        }
    if (labels.isNotEmpty()) {
        Text(
            text = labels.joinToString("  •  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun versionEvidenceSummary(app: InstalledApp, mapping: AppMapping?): String {
    val installed = effectiveInstalledVersion(app, mapping).ifBlank { "unknown" }
    val installedSource = when (app.source) {
        AppSource.Android -> "Android PackageManager versionName"
        AppSource.Managed -> "AGM managed-game metadata; use Detect version for file/marker candidates"
        AppSource.JoiPlay -> "JoiPlay row/folder metadata; use Detect version for file/marker candidates"
        AppSource.Winlator -> "Winlator managed-game metadata; use Set installed version when metadata is unavailable"
        AppSource.Kirikiroid -> "Kirikiroid game folder metadata; use Set installed version when metadata is unavailable"
    }
    if (hasActiveManualInstalledVersion(app, mapping)) {
        val latest = mapping?.lastSeenVersion?.let { "latest catalog $it" } ?: "no catalog version"
        return "$installed from manual installed-version override; $latest"
    }
    val latest = mapping?.lastSeenVersion?.let { "latest catalog $it" } ?: "no catalog version"
    return "$installed from $installedSource; $latest"
}

private fun updateDecisionSummary(app: InstalledApp, mapping: AppMapping?, status: UpdateStatus): String =
    when {
        mapping == null || mapping.f95Url.isNullOrBlank() -> "Unmapped: no source URL/catalog entry."
        mapping.lastSeenVersion == null -> "Unknown: mapped, but latest catalog version is unknown."
        mapping.acknowledgedVersion != null && mapping.acknowledgedVersion == mapping.lastSeenVersion ->
            "Current: user acknowledged ${mapping.lastSeenVersion} as installed."
        VersionCompare.matchesInstalled(mapping.lastSeenVersion, effectiveInstalledVersion(app, mapping)) ->
            "Current: installed '${effectiveInstalledVersion(app, mapping)}' structurally matches catalog '${mapping.lastSeenVersion}'."
        status == UpdateStatus.UpdateAvailable ->
            "Update: installed '${effectiveInstalledVersion(app, mapping).ifBlank { "unknown" }}' differs from catalog '${mapping.lastSeenVersion}'."
        else -> "${statusLabel(status)}: installed '${effectiveInstalledVersion(app, mapping).ifBlank { "unknown" }}', catalog '${mapping.lastSeenVersion}'."
    }

@Composable
internal fun GameActionDropdown(
    row: AppRow,
    expanded: Boolean,
    actionMenuOpen: Boolean,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onLaunchRunner: (ManagedRunnerKind) -> Unit,
    onRunWinlatorInstaller: () -> Unit,
    onRefreshOne: () -> Unit,
    onOpenSource: () -> Unit,
    onGoToCatalog: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    var launchWithOpen by remember(row.installed.packageName) { mutableStateOf(false) }
    LaunchedEffect(actionMenuOpen) {
        if (!actionMenuOpen) launchWithOpen = false
    }
    DropdownMenu(
        expanded = actionMenuOpen,
        onDismissRequest = onDismiss,
    ) {
        if (launchWithOpen) {
            DropdownMenuItem(
                text = { Text("Launch with\u2026") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") },
                onClick = { launchWithOpen = false },
            )
            HorizontalDivider()
            row.installed.managedRunnerBindings
                .filter { it.enabled && it.compatible }
                .forEach { binding ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                binding.kind.displayName() +
                                    if (binding.kind == row.installed.managedDefaultRunner) " (default)" else "",
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                        onClick = {
                            launchWithOpen = false
                            onDismiss()
                            onLaunchRunner(binding.kind)
                        },
                    )
                }
            return@DropdownMenu
        }
        Text(
            "Game actions",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (row.installed.source == AppSource.Managed) {
            DropdownMenuItem(
                text = {
                    Text(
                        "Launch" +
                            row.installed.managedDefaultRunner?.let { " with ${it.displayName()}" }.orEmpty(),
                    )
                },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                onClick = { onDismiss(); onLaunch() },
            )
            DropdownMenuItem(
                text = { Text("Launch with\u2026") },
                leadingIcon = { Icon(Icons.Default.MoreHoriz, null) },
                trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                onClick = { launchWithOpen = true },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Launch game/app") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                onClick = { onDismiss(); onLaunch() },
            )
        }
        if (
            row.installed.source == AppSource.Winlator ||
            row.installed.managedRunnerBindings.any { it.kind == ManagedRunnerKind.Winlator && it.enabled }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (row.installed.winlatorState == "installing") {
                            "Retry / run installer\u2026"
                        } else {
                            "Run installer / update\u2026"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Default.GetApp, null) },
                onClick = { onDismiss(); onRunWinlatorInstaller() },
            )
        }
        DropdownMenuItem(
            text = { Text("Refresh / check update") },
            leadingIcon = { Icon(Icons.Default.Refresh, null) },
            onClick = { onDismiss(); onRefreshOne() },
        )
        DropdownMenuItem(
            text = { Text(if (row.mapping?.f95Url != null) "Open thread / source" else "Search thread / source") },
            leadingIcon = {
                Icon(if (row.mapping?.f95Url != null) Icons.Default.OpenInBrowser else Icons.Default.Search, null)
            },
            onClick = { onDismiss(); onOpenSource() },
        )
        onGoToCatalog?.let { navigate ->
            DropdownMenuItem(
                text = { Text("Go to game in catalog") },
                leadingIcon = { Icon(Icons.Default.MenuBook, null) },
                onClick = { onDismiss(); navigate() },
            )
        }
        DropdownMenuItem(
            text = { Text("Game settings") },
            leadingIcon = { Icon(Icons.Default.Settings, null) },
            onClick = { onDismiss(); onOpenSettings() },
        )
        DropdownMenuItem(
            text = { Text(if (expanded) "Hide details" else "Show details") },
            leadingIcon = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
            onClick = { onDismiss(); onToggleExpand() },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NiceGameCard(
    row: AppRow,
    hasBackup: Boolean,
    joiPlaySizeInfo: JoiPlayScanner.SizeInfo?,
    isJoiPlaySizeScanning: Boolean,
    renPySaves: List<RenPySaveAssociation>,
    rpgmSaves: List<RpgmSaveLocation>,
    expanded: Boolean,
    catalogGame: CatalogGame?,
    catalogDisplayTitle: String?,
    catalogThumbnail: String?,
    catalogCover: String?,
    catalogLabels: CatalogLabelsV2?,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggleExpand: () -> Unit,
    onOpenRenPySaves: () -> Unit,
    onAddRenPySaveFolder: () -> Unit,
    onOpenRpgmSaves: () -> Unit,
    onAddRpgmSaveFolder: () -> Unit,
    onRunWinlatorInstaller: () -> Unit,
    onLaunch: () -> Unit,
    onLaunchRunner: (ManagedRunnerKind) -> Unit,
    onRefreshOne: () -> Unit,
    onShowCover: (String) -> Unit,
    onSnack: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSource: () -> Unit,
    onGoToCatalog: (() -> Unit)?,
) {
    var actionMenuOpen by remember { mutableStateOf(false) }
    val displayTotalSize = effectiveDisplaySize(row.installed, joiPlaySizeInfo)
    val displayName = effectiveGameName(row.installed, row.mapping, catalogDisplayTitle)
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else actionMenuOpen = true },
                    onLongClick = onLongPress,
                ),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    else -> gameSourceContainerColor(row.installed)
                },
            ),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!catalogThumbnail.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = catalogThumbnail,
                        contentDescription = "Cover",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(118.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onShowCover(catalogCover ?: catalogThumbnail) },
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                Text(
                    displayName + (row.installed.launcherLabel?.takeIf { it != displayName }?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ManagedRunnerLabels(row.installed)
                catalogGame?.title?.takeIf { ct ->
                    ct.isNotBlank() &&
                        CatalogRepository.normalizeTitle(ct) != CatalogRepository.normalizeTitle(displayName)
                }?.let {
                    Text(
                        "Catalog: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                catalogGame?.let { cg ->
                    val prefixNames = catalogLabels?.let { l -> cg.prefixes.mapNotNull { l.prefixName(cg.source, it.toString()) } }
                    if (!prefixNames.isNullOrEmpty()) {
                        Text(
                            prefixNames.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    listOfNotNull(
                        effectiveInstalledVersion(row.installed, row.mapping).ifBlank { "?" },
                        row.mapping?.lastSeenVersion?.let { "→ $it" },
                    ).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(statusColor(row.status), MaterialTheme.shapes.small))
                    Text(statusLabel(row.status), style = MaterialTheme.typography.bodySmall)
                    if (hasBackup) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = "Has AGM backup",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (isJoiPlaySizeScanning) "Scanning…" else fmtSize(displayTotalSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                val saveParts = buildList {
                    if (renPySaves.isNotEmpty()) add("Ren'Py saves ${renPySaves.sumOf { it.saveCount }}")
                    if (rpgmSaves.isNotEmpty()) add("RPGM saves ${rpgmSaves.sumOf { it.saveCount }}")
                }
                if (saveParts.isNotEmpty()) {
                    Text(
                        saveParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        GameActionDropdown(
            row = row,
            expanded = expanded,
            actionMenuOpen = actionMenuOpen,
            onDismiss = { actionMenuOpen = false },
            onLaunch = onLaunch,
            onLaunchRunner = onLaunchRunner,
            onRunWinlatorInstaller = onRunWinlatorInstaller,
            onRefreshOne = onRefreshOne,
            onOpenSource = onOpenSource,
            onGoToCatalog = onGoToCatalog,
            onOpenSettings = onOpenSettings,
            onToggleExpand = onToggleExpand,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppRowCard(
    row: AppRow,
    hasBackup: Boolean,
    joiPlaySizeInfo: JoiPlayScanner.SizeInfo?,
    isJoiPlaySizeScanning: Boolean,
    renPySaves: List<RenPySaveAssociation>,
    rpgmSaves: List<RpgmSaveLocation>,
    expanded: Boolean,
    catalogGame: CatalogGame?,
    catalogDisplayTitle: String?,
    catalogThumbnail: String?,
    catalogCover: String?,
    catalogLabels: CatalogLabelsV2?,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggleExpand: () -> Unit,
    onOpenRenPySaves: () -> Unit,
    onAddRenPySaveFolder: () -> Unit,
    onOpenRpgmSaves: () -> Unit,
    onAddRpgmSaveFolder: () -> Unit,
    onRunWinlatorInstaller: () -> Unit,
    onLaunch: () -> Unit,
    onLaunchRunner: (ManagedRunnerKind) -> Unit,
    onRefreshOne: () -> Unit,
    onShowCover: (String) -> Unit,
    onSnack: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSource: () -> Unit,
    onGoToCatalog: (() -> Unit)?,
) {
    val color = statusColor(row.status)
    val context = LocalContext.current
    val compactWidth = LocalConfiguration.current.screenWidthDp < 420
    val displayTotalSize = effectiveDisplaySize(row.installed, joiPlaySizeInfo)
    val displayName = effectiveGameName(row.installed, row.mapping, catalogDisplayTitle)
    var actionMenuOpen by remember { mutableStateOf(false) }
    var details by remember(row.installed.packageName) { mutableStateOf<AppDetails?>(null) }
    LaunchedEffect(expanded, row.installed.packageName) {
        if (expanded && details == null) {
            details = withContext(kotlinx.coroutines.Dispatchers.IO) {
                AppDetailsProvider.get(context, row.installed.packageName)
            }
        }
    }
    // Resolve native app icon for Android rows (cheap, cached by Compose remember).
    val appIcon: android.graphics.drawable.Drawable? = null  // disabled per user request — only thumbnail
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else actionMenuOpen = true },
                onLongClick = onLongPress,
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> gameSourceContainerColor(row.installed)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp)) {
                Surface(color = color, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxSize()) {}
            }
            Spacer(Modifier.width(6.dp))
            // Catalog listing thumbnail (clickable to open the full cover). Only if mapped + a
            // thumbnail/cover URL is known.
            if (!catalogThumbnail.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = catalogThumbnail,
                    contentDescription = "Cover",
                    modifier = Modifier
                        .size(width = 40.dp, height = 56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onShowCover(catalogCover ?: catalogThumbnail) },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName + (row.installed.launcherLabel?.takeIf { it != displayName }?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ManagedRunnerLabels(row.installed)
                if (hasBackup) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = "Has AGM backup",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            "Backup",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                }
                // Keep the association visible when the user overrides the displayed name.
                catalogGame?.title?.takeIf { ct ->
                    ct.isNotBlank() &&
                        ct.lowercase().filter { it.isLetterOrDigit() } !=
                        displayName.lowercase().filter { it.isLetterOrDigit() }
                }?.let { catalogTitle ->
                    Text(
                        text = "Catalog: $catalogTitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                catalogGame?.let { cg ->
                    val prefixNames = catalogLabels?.let { l -> cg.prefixes.mapNotNull { l.prefixName(cg.source, it.toString()) } }
                    if (!prefixNames.isNullOrEmpty()) {
                        Text(
                            prefixNames.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val installedVer = effectiveInstalledVersion(row.installed, row.mapping).ifBlank { "?" }
                val latest = row.mapping?.lastSeenVersion
                Text(
                    text = if (latest != null) "$installedVer → $latest" else installedVer,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                row.mapping?.let { mapping ->
                    val personalParts = buildList {
                        if (mapping.userStatus != UserGameStatus.None) add(mapping.userStatus.label)
                        mapping.personalRating?.let { add("Rating $it/5") }
                        if (mapping.personalNotes.isNotBlank()) add("Notes")
                    }
                    if (personalParts.isNotEmpty()) {
                        Text(
                            text = personalParts.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (renPySaves.isNotEmpty()) {
                    val folderCount = renPySaves.size
                    val saveCount = renPySaves.sumOf { it.saveCount }
                    Text(
                        text = "Ren'Py saves: $saveCount in $folderCount folder${if (folderCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (rpgmSaves.isNotEmpty()) {
                    val folderCount = rpgmSaves.size
                    val saveCount = rpgmSaves.sumOf { it.saveCount }
                    Text(
                        text = "RPGM saves: $saveCount in $folderCount folder${if (folderCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "App updated ${fmtDate(row.installed.lastUpdateTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    if (row.installed.lastUsedTime > 0L) {
                        Text(
                            text = "Last used ${fmtDate(row.installed.lastUsedTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    catalogGame?.ts?.takeIf { it > 0L }?.let { ts ->
                        Text(
                            text = "Thread updated ${fmtDate(ts * 1000L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    if (
                        (row.installed.source == AppSource.JoiPlay ||
                            row.installed.source == AppSource.Managed) &&
                        (joiPlaySizeInfo?.lastScannedAt ?: 0L) > 0L
                    ) {
                       Text(
                           text = "Size scan ${fmtDate(joiPlaySizeInfo?.lastScannedAt ?: 0L)}",
                           style = MaterialTheme.typography.bodySmall,
                           color = MaterialTheme.colorScheme.onSurfaceVariant,
                           fontSize = 10.sp,
                       )
                    }
                    Text(
                       text = if (isJoiPlaySizeScanning) "Scanning…" else fmtSize(displayTotalSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box {
                    IconButton(onClick = { actionMenuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Game actions", modifier = Modifier.size(20.dp))
                    }
                    GameActionDropdown(
                        row = row,
                        expanded = expanded,
                        actionMenuOpen = actionMenuOpen,
                        onDismiss = { actionMenuOpen = false },
                        onLaunch = onLaunch,
                        onLaunchRunner = onLaunchRunner,
                        onRunWinlatorInstaller = onRunWinlatorInstaller,
                        onRefreshOne = onRefreshOne,
                        onOpenSource = onOpenSource,
                        onGoToCatalog = onGoToCatalog,
                        onOpenSettings = onOpenSettings,
                        onToggleExpand = onToggleExpand,
                    )
                }
            }
        }
        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DetailRow("Package", row.installed.packageName)
                row.installed.launcherLabel?.let { DetailRow("Home-screen name", it) }
                row.installed.storagePath?.let { path ->
                    DetailRowWithAction(
                        key = "Storage path",
                        value = path,
                        actionIcon = Icons.Default.FolderOpen,
                        actionDesc = "Open folder",
                        onAction = {
                            val res = FolderOpener.open(context, path)
                            AppLog.i("OpenFolder", "${if (res.ok) "ok" else "fail"}: ${res.message} (path=$path)")
                            onSnack(res.message)
                        }
                    )
                }
                DetailRow("Game name", displayName)
                if (displayName != row.installed.label) {
                    DetailRow("Detected name", row.installed.label)
                }
                DetailRow("Version code", row.installed.versionCode.toString())
                DetailRow("Installed date", fmtDateTime(effectiveInstalledDate(row.installed, row.mapping)))
                val installedDateSource = if (hasActiveManualInstalledDate(row.installed, row.mapping)) {
                    row.mapping?.manualInstalledDateSource?.ifBlank { "manual override" }
                } else {
                    row.installed.installedDateSource.takeIf { it.isNotBlank() }
                }
                installedDateSource?.let { DetailRow("Installed-date source", it) }
                DetailRow("Last update", fmtDate(row.installed.lastUpdateTime))
                mappedCatalogGame(row.mapping, catalogGame?.let { mapOf(it.thread_id to it) })?.ts?.takeIf { it > 0L }?.let { ts ->
                    DetailRow("Thread updated", fmtDateTime(ts * 1000L))
                }
                if (row.installed.source == AppSource.JoiPlay || row.installed.source == AppSource.Managed) {
                    DetailRow("Total size", if (isJoiPlaySizeScanning) "Scanning…" else fmtSize(displayTotalSize))
                    DetailRow("Size last scanned", fmtDateTime(joiPlaySizeInfo?.lastScannedAt ?: 0L))
                    joiPlaySizeInfo?.let { info ->
                        if (info.gameBytes > 0L) DetailRow("Game files", fmtSize(info.gameBytes))
                        if (info.saveBytes > 0L) DetailRow("Saves", fmtSize(info.saveBytes))
                        if (info.backupBytes > 0L) {
                            DetailRow("Backups / rollback folders", fmtSize(info.backupBytes))
                        }
                        if (info.otherBytes > 0L) DetailRow("Other files", fmtSize(info.otherBytes))
                    }
                    if (row.installed.source == AppSource.Managed) {
                        DetailRow(
                            "Execution engines",
                            row.installed.managedRunnerBindings
                                .filter { it.enabled }
                                .joinToString { binding ->
                                    binding.kind.displayName() +
                                        if (binding.kind == row.installed.managedDefaultRunner) " (default)" else ""
                                },
                        )
                        row.installed.storagePath?.let { DetailRow("Game folder", it) }
                        row.installed.winlatorState?.let {
                            DetailRow("Winlator state", it.replace('_', ' '))
                        }
                    }
                } else if (row.installed.source == AppSource.Winlator) {
                    DetailRow(
                        "Winlator container policy",
                        when (row.installed.winlatorContainerPolicy) {
                            WinlatorApi.ContainerPolicy.SharedDefault.wireValue -> "Shared default"
                            WinlatorApi.ContainerPolicy.Isolated.wireValue -> "Isolated"
                            else -> "Legacy / unknown"
                        },
                    )
                    row.installed.winlatorContainerId?.let { DetailRow("Winlator container ID", it.toString()) }
                    row.installed.winlatorContainerKey?.let { DetailRow("Winlator shared key", it) }
                    row.installed.winlatorContainerReferenceCount?.let {
                        DetailRow("Container game references", it.toString())
                    }
                    row.installed.winlatorContainerAllocatedSizeBytes?.let {
                        DetailRow("Container allocated size", fmtSize(it))
                    }
                    row.installed.winlatorState?.let { DetailRow("Winlator state", it.replace('_', ' ')) }
                } else {
                    DetailRow("APK size", fmtSize(row.installed.apkSize))
                    DetailRow("Data size", fmtSize(row.installed.dataSize))
                    DetailRow("Cache size", fmtSize(row.installed.cacheSize))
                    DetailRow("Total size", fmtSize(row.installed.totalSize))
                }
                DetailRow("Installed version", effectiveInstalledVersion(row.installed, row.mapping).ifBlank { "—" })
                if (hasActiveManualInstalledVersion(row.installed, row.mapping)) {
                    DetailRow("Installed-version override", row.mapping?.manualInstalledVersion.orEmpty())
                }
                if (hasActiveManualInstalledDate(row.installed, row.mapping)) {
                    DetailRow("Installed-date override", fmtDateTime(row.mapping?.manualInstalledDate ?: 0L))
                }
                row.mapping?.let { mapping ->
                    if (mapping.userStatus != UserGameStatus.None) {
                        DetailRow("User status", mapping.userStatus.label)
                    }
                    mapping.personalRating?.let { DetailRow("Your rating", "$it / 5") }
                    if (mapping.personalNotes.isNotBlank()) {
                        DetailRow("Your notes", mapping.personalNotes)
                    }
                    if (mapping.manualCorrectionNote.isNotBlank()) {
                        DetailRow("Manual correction", mapping.manualCorrectionNote)
                    }
                }
                row.mapping?.f95Url?.let { DetailRow("Source URL", it) }
                row.mapping?.acknowledgedVersion?.let { DetailRow("Acknowledged", it) }
                if (row.mapping?.lastChecked != null && row.mapping.lastChecked > 0L) {
                    DetailRow("Last checked", fmtDateTime(row.mapping.lastChecked))
                }
                DetailRow("Installed-version source", versionEvidenceSummary(row.installed, row.mapping))
                DetailRow("Update decision", updateDecisionSummary(row.installed, row.mapping, row.status))
                row.mapping?.matchSource?.let { DetailRow("Match source", it) }
                if (renPySaves.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onOpenRenPySaves) { Text("Open Ren'Py save editor") }
                    TextButton(onClick = onAddRenPySaveFolder) { Text("Add another Ren'Py save folder") }
                    DetailRow("Ren'Py save folders", renPySaves.size.toString())
                } else {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onAddRenPySaveFolder) { Text("Add Ren'Py save folder") }
                }
                if (renPySaves.isNotEmpty()) {
                    renPySaves.forEachIndexed { index, save ->
                        DetailRow(
                            if (renPySaves.size == 1) "Save path" else "Save path ${index + 1}",
                            save.saveDirPath,
                        )
                        DetailRow("Save files", save.saveCount.toString())
                        DetailRow("Save owner", save.ownerId)
                        save.renpyVersion?.takeIf { it.isNotBlank() }?.let { DetailRow("Ren'Py version", it) }
                        save.sampleSaveNames.takeIf { it.isNotEmpty() }?.let { names ->
                            DetailRow("Save names", names.joinToString(" • "))
                        }
                        DetailRow("Last save", fmtDateTime(save.latestModified))
                        DetailRow("Association", "${save.confidence}% • ${save.reason}")
                    }
                }
                if (rpgmSaves.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onOpenRpgmSaves) { Text("Open RPGM save viewer") }
                    TextButton(onClick = onAddRpgmSaveFolder) { Text("Add another RPGM save folder") }
                    DetailRow("RPGM save folders", rpgmSaves.size.toString())
                    rpgmSaves.forEachIndexed { index, save ->
                        DetailRow(
                            if (rpgmSaves.size == 1) "RPGM save path" else "RPGM save path ${index + 1}",
                            save.saveDirPath,
                        )
                        DetailRow("RPGM save files", save.saveCount.toString())
                        DetailRow("RPGM save owner", save.ownerId)
                        DetailRow("RPGM last save", fmtDateTime(save.latestModified))
                        DetailRow("RPGM association", "${save.confidence}% • ${save.reason}")
                    }
                } else {
                    TextButton(onClick = onAddRpgmSaveFolder) { Text("Add RPGM save folder") }
                }
                Spacer(Modifier.height(4.dp))
                if (details == null) {
                    Text("Loading details…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else details?.let { d ->
                    DetailRow("Installed by", d.installerPackage ?: "(sideload)")
                    DetailRow("UID", d.uid.toString())
                    DetailRow("Min SDK", d.minSdk?.toString() ?: "?")
                    DetailRow("Target SDK", d.targetSdk.toString())
                    DetailRow("Process", d.processName)
                    DetailRow("Splits", d.splitCount.toString())
                    d.sourceDir?.let { DetailRow("APK path", it) }
                    d.nativeLibDir?.let { DetailRow("Native libs", it) }
                    d.dataDir?.let { DetailRow("Data dir", it) }
                    if (d.externalDataBytes > 0) DetailRow("External data", fmtSize(d.externalDataBytes))
                    if (d.externalCacheBytes > 0) DetailRow("External cache", fmtSize(d.externalCacheBytes))
                }
                catalogGame?.let { g ->
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    DetailRow("Catalog title", g.title)
                    g.creator?.let { DetailRow("Developer", it) }
                    g.version?.let { DetailRow("Catalog version", it) }
                    g.rating?.let { DetailRow("Rating", "%.2f / 5".format(it)) }
                    val labels = catalogLabels
                    if (labels != null) {
                        val prefixNames = g.prefixes.mapNotNull { labels.prefixName(g.source, it.toString()) }
                        if (prefixNames.isNotEmpty()) DetailRow("Type", prefixNames.joinToString(" • "))
                        val tagNames = g.tags.mapNotNull { labels.tagName(g.source, it.toString()) }
                        if (tagNames.isNotEmpty()) DetailRow("Tags", tagNames.joinToString(", "))
                    }
                }
            }
        }
    }
}
