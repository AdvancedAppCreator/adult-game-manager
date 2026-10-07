package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.withContext

private enum class GameSettingsTab(val title: String) {
    Details("Details"),
    Saves("Saves"),
    Backup("Backup"),
    Engines("Engines"),
    Winlator("Winlator"),
    Advanced("Advanced"),
}

internal fun managedEngineChoicesChanged(
    originalEnabled: Set<ManagedRunnerKind>,
    originalDefault: ManagedRunnerKind,
    enabled: Set<ManagedRunnerKind>,
    defaultRunner: ManagedRunnerKind,
): Boolean = enabled != originalEnabled || defaultRunner != originalDefault

/**
 * Per-game settings surface opened from the "Game settings" card action. Consolidates every
 * per-game action that used to live directly on the card's overflow menu into tabs, showing only
 * the tabs relevant to the game's source (e.g. Android has no Backup/Winlator tabs). The Winlator
 * tab embeds the full [WinlatorSettingsPane] (General/Translator/Runtime/Diagnostics) unchanged.
 */
@Composable
fun GameSettingsDialog(
    row: AppRow,
    renPySaves: List<RenPySaveAssociation>,
    rpgmSaves: List<RpgmSaveLocation>,
    onDismiss: () -> Unit,
    // Details
    onEditMapping: () -> Unit,
    onSetInstalledVersion: () -> Unit,
    onSetInstalledDate: () -> Unit,
    // Tags
    onEditTags: () -> Unit,
    // Saves
    onOpenRenPySaves: () -> Unit,
    onAddRenPySaveFolder: () -> Unit,
    onOpenRpgmSaves: () -> Unit,
    onAddRpgmSaveFolder: () -> Unit,
    // Backup (JoiPlay)
    onRevertBackup: () -> Unit,
    onDeleteBackup: () -> Unit,
    // Winlator
    onWinlatorSubmit: (List<WinlatorConfigSubmission>) -> Unit,
    onMoveWinlatorToIsolated: () -> Unit,
    onUpdateRunners: (
        bindings: List<ManagedRunnerBinding>,
        enabled: Set<ManagedRunnerKind>,
        defaultRunner: ManagedRunnerKind,
    ) -> Unit,
    engineOperationBusy: Boolean,
    // Advanced
    onRemoveFromList: () -> Unit,
    onDelete: () -> Unit,
) {
    val source = row.installed.source

    var hasBackup by remember(row.installed.packageName) { mutableStateOf(false) }
    LaunchedEffect(row.installed.packageName) {
        hasBackup = (source == AppSource.JoiPlay || source == AppSource.Managed) &&
            runCatching { JoiPlayBackupManager.findBackup(row.installed) }.getOrNull() != null
    }

    val tabs = buildList {
        add(GameSettingsTab.Details)
        add(GameSettingsTab.Saves)
        if ((source == AppSource.JoiPlay || source == AppSource.Managed) && hasBackup) {
            add(GameSettingsTab.Backup)
        }
        if (source == AppSource.Managed) add(GameSettingsTab.Engines)
        if (
            source == AppSource.Winlator ||
            row.installed.managedRunnerBindings.any {
                it.kind == ManagedRunnerKind.Winlator && it.enabled
            }
        ) {
            add(GameSettingsTab.Winlator)
        }
        add(GameSettingsTab.Advanced)
    }
    var tab by remember(row.installed.packageName) { mutableStateOf(GameSettingsTab.Details) }
    var unityTextureMemoryExpanded by remember(row.installed.packageName) { mutableStateOf(false) }
    val selectedIndex = tabs.indexOf(tab).coerceAtLeast(0)

    Dialog(onDismissRequest = { if (!engineOperationBusy) onDismiss() }) {
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Game settings", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            row.installed.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !engineOperationBusy,
                    ) { Icon(Icons.Default.Close, "Close") }
                }
                ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 12.dp) {
                    tabs.forEachIndexed { i, t ->
                        Tab(
                            selected = i == selectedIndex,
                            onClick = { if (!engineOperationBusy) tab = t },
                            enabled = !engineOperationBusy,
                            text = { Text(t.title) },
                        )
                    }
                }
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        GameSettingsTab.Details -> SettingsActionList {
                            ActionRow(
                                Icons.Default.Edit,
                                "Name, match, notes & status",
                                onClick = onEditMapping,
                            )
                            ActionRow(Icons.Default.Event, "Set installed version", onClick = onSetInstalledVersion)
                            ActionRow(Icons.Default.Event, "Set installed date", onClick = onSetInstalledDate)
                            ActionRow(Icons.Default.Label, "Edit my tags\u2026", onClick = onEditTags)
                        }
                        GameSettingsTab.Saves -> SettingsActionList {
                            ActionRow(
                                Icons.Default.Save,
                                "Open Ren\u2019Py save editor",
                                subtitle = if (renPySaves.isEmpty()) "No Ren\u2019Py save folders yet" else null,
                                enabled = renPySaves.isNotEmpty(),
                                onClick = onOpenRenPySaves,
                            )
                            ActionRow(Icons.Default.CreateNewFolder, "Add Ren\u2019Py save folder", onClick = onAddRenPySaveFolder)
                            ActionRow(
                                Icons.Default.Save,
                                "Open RPGM save editor",
                                subtitle = if (rpgmSaves.isEmpty()) "No RPGM save folders yet" else null,
                                enabled = rpgmSaves.isNotEmpty(),
                                onClick = onOpenRpgmSaves,
                            )
                            ActionRow(Icons.Default.CreateNewFolder, "Add RPGM save folder", onClick = onAddRpgmSaveFolder)
                        }
                        GameSettingsTab.Backup -> SettingsActionList {
                            ActionRow(Icons.Default.Restore, "Revert to backup", onClick = onRevertBackup)
                            ActionRow(Icons.Default.DeleteSweep, "Delete backup", onClick = onDeleteBackup)
                        }
                        GameSettingsTab.Engines -> ManagedEngineSettings(
                            app = row.installed,
                            onSave = onUpdateRunners,
                            operationBusy = engineOperationBusy,
                        )
                        GameSettingsTab.Winlator -> WinlatorSettingsPane(
                            app = row.installed,
                            onClose = onDismiss,
                            embedded = true,
                            onSubmit = {
                                onWinlatorSubmit(it)
                                onDismiss()
                            },
                        )
                        GameSettingsTab.Advanced -> SettingsActionList {
                            if (
                                !row.installed.storagePath.isNullOrBlank() ||
                                    !row.installed.winlatorExecutablePath.isNullOrBlank()
                            ) {
                                ActionRow(
                                    Icons.Default.Memory,
                                    "Analyze compatibility · Unity texture memory",
                                    subtitle = "Read-only Unity metadata analysis",
                                    onClick = { unityTextureMemoryExpanded = !unityTextureMemoryExpanded },
                                )
                                if (unityTextureMemoryExpanded) {
                                    UnityTextureMemoryPane(
                                        app = row.installed,
                                        onSubmit = {
                                            onWinlatorSubmit(it)
                                            onDismiss()
                                        },
                                    )
                                }
                            }
                            if (source != AppSource.Android) {
                                ActionRow(
                                    Icons.Default.VisibilityOff,
                                    "Remove from list",
                                    subtitle = "Hides it here without deleting the game",
                                    onClick = onRemoveFromList,
                                )
                            }

                            if (row.installed.winlatorContainerShared && row.installed.winlatorGameId != null) {
                                ActionRow(
                                    Icons.Default.Lock,
                                    "Move to isolated container\u2026",
                                    onClick = onMoveWinlatorToIsolated,
                                )
                            }
                            ActionRow(
                                Icons.Default.Delete,
                                when (source) {
                                    AppSource.Android -> "Uninstall Android app"
                                    AppSource.Managed -> "Delete managed game"
                                    AppSource.JoiPlay -> "Delete JoiPlay game"
                                    AppSource.Winlator -> "Delete Winlator game"
                                    AppSource.Kirikiroid -> "Delete Kirikiroid game"
                                },
                                destructive = true,
                                onClick = onDelete,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagedEngineSettings(
    app: InstalledApp,
    onSave: (List<ManagedRunnerBinding>, Set<ManagedRunnerKind>, ManagedRunnerKind) -> Unit,
    operationBusy: Boolean,
) {
    val context = LocalContext.current
    var inspection by remember(app.packageName) { mutableStateOf<ManagedGameInspection?>(null) }
    var inspectionError by remember(app.packageName) { mutableStateOf<String?>(null) }
    LaunchedEffect(app.packageName, app.storagePath) {
        val path = app.storagePath
        if (path.isNullOrBlank()) {
            inspectionError = "This game has no folder path to inspect."
            return@LaunchedEffect
        }
        runCatching {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                ManagedGameDiscovery.inspect(
                    root = java.io.File(path),
                    selectedLaunchFile = null,
                    joiPlayAvailable = runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(JoiPlayUpdateChecker.PACKAGE, 0)
                        true
                    }.getOrDefault(false),
                    winlatorAvailable = WinlatorClient.isInstalled(context),
                    kirikiroidAvailable = KirikiroidLauncher.isInstalled(context),
                )
            }
        }.onSuccess {
            inspection = it
            inspectionError = null
        }.onFailure {
            inspectionError = it.message ?: "Could not inspect this game folder."
        }
    }
    val availableBindings = remember(app.managedRunnerBindings, inspection) {
        val byKind = app.managedRunnerBindings.associateBy { it.kind }.toMutableMap()
        inspection?.candidates?.forEach { candidate ->
            if (candidate.kind !in byKind && candidate.binding != null) {
                byKind[candidate.kind] = candidate.binding
            }
        }
        ManagedRunnerKind.entries.mapNotNull(byKind::get)
    }
    val available = remember(availableBindings) {
        availableBindings.mapTo(linkedSetOf()) { it.kind }
    }
    var enabled by remember(app.packageName, app.managedRunnerBindings) {
        mutableStateOf<Set<ManagedRunnerKind>>(
            app.managedRunnerBindings.filter { it.enabled }.mapTo(linkedSetOf()) { it.kind },
        )
    }
    var defaultRunner by remember(app.packageName, app.managedDefaultRunner) {
        mutableStateOf(app.managedDefaultRunner ?: enabled.first())
    }
    val originalEnabled = remember(app.packageName, app.managedRunnerBindings) {
        app.managedRunnerBindings.filter { it.enabled }.mapTo(linkedSetOf()) { it.kind }
    }
    val originalDefault = remember(app.packageName, app.managedDefaultRunner) {
        app.managedDefaultRunner ?: originalEnabled.first()
    }
    val inspectionBusy = inspection == null && inspectionError == null
    val controlsEnabled = !inspectionBusy && !operationBusy
    val changed = managedEngineChoicesChanged(
        originalEnabled = originalEnabled,
        originalDefault = originalDefault,
        enabled = enabled,
        defaultRunner = defaultRunner,
    )
    val requiresWinlatorSetup =
        ManagedRunnerKind.Winlator in enabled &&
            app.managedRunnerBindings
                .filterIsInstance<ManagedRunnerBinding.Winlator>()
                .singleOrNull()
                ?.managedId == null
    SettingsActionList {
        Text(
            "Enabled engines appear as separate Execute with actions.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (inspection == null && inspectionError == null) {
            Text(
                "Inspecting the game folder for additional engines…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        inspectionError?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ManagedRunnerKind.entries.forEach { runner ->
            val availableForRunner = runner in available
            val canEnable = controlsEnabled && availableForRunner
            val discovered = runner !in app.managedRunnerBindings.map { it.kind }
            val explanation = inspection?.candidates?.firstOrNull { it.kind == runner }?.explanation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canEnable) {
                        enabled = if (runner in enabled) enabled - runner else enabled + runner
                        if (defaultRunner !in enabled) defaultRunner = enabled.firstOrNull() ?: defaultRunner
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = runner in enabled,
                    enabled = canEnable,
                    onCheckedChange = null,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(runner.displayName())
                    if (!availableForRunner) {
                        Text(
                            explanation ?: "No validated launch configuration exists for this engine.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (discovered) {
                        Text(
                            explanation ?: "A compatible launch file was discovered in the game folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                androidx.compose.material3.RadioButton(
                    selected = runner == defaultRunner && runner in enabled,
                    enabled = controlsEnabled && runner in enabled,
                    onClick = { defaultRunner = runner },
                )
            }
        }
        TextButton(
            onClick = { onSave(availableBindings, enabled, defaultRunner) },
            enabled = controlsEnabled && changed && enabled.isNotEmpty() && defaultRunner in enabled,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                when {
                    operationBusy -> "Applying\u2026"
                    requiresWinlatorSetup && changed -> "Apply and set up Winlator"
                    else -> "Apply engine choices"
                },
            )
        }
    }
}

@Composable
private fun SettingsActionList(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .dialogVerticalScroll()
            .padding(vertical = 8.dp),
    ) { content() }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
