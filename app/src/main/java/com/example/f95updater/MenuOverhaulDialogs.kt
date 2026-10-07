package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Unified "Show save folders" dialog with Ren'Py and RPGM tabs. Reuses the same content
 * composables that back the standalone location dialogs so there is a single source of truth
 * for the list bodies.
 */
@Composable
internal fun SaveLocationsTabbedDialog(
    renPyLocations: List<RenPySaveLocation>,
    rpgmLocations: List<RpgmSaveLocation>,
    renPyLastScannedAt: Long,
    rpgmLastScannedAt: Long,
    renPyActionsEnabled: Boolean,
    rpgmActionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onAssociateRenPy: (RenPySaveLocation) -> Unit,
    onClearRenPy: (RenPySaveLocation) -> Unit,
    onAssociateRpgm: (RpgmSaveLocation) -> Unit,
    onClearRpgm: (RpgmSaveLocation) -> Unit,
    onCopyRenPy: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("Save locations") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("Ren'Py (${renPyLocations.size})") },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("RPGM (${rpgmLocations.size})") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                when (tab) {
                    0 -> RenPySaveLocationsContent(
                        locations = renPyLocations,
                        lastScannedAt = renPyLastScannedAt,
                        associationActionsEnabled = renPyActionsEnabled,
                        onAssociate = onAssociateRenPy,
                        onClearAssociation = onClearRenPy,
                    )
                    else -> RpgmSaveLocationsContent(
                        locations = rpgmLocations,
                        lastScannedAt = rpgmLastScannedAt,
                        associationActionsEnabled = rpgmActionsEnabled,
                        onAssociate = onAssociateRpgm,
                        onClearAssociation = onClearRpgm,
                    )
                }
            }
        },
        confirmButton = {
            if (tab == 0) {
                TextButton(onClick = onCopyRenPy) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * App-wide Settings dialog: theme mode, storage/usage permissions, and a shortcut to the
 * non-Android game settings.
 */
private enum class AppSettingsTab(val label: String) {
    Appearance("Look"),
    Permissions("Access"),
    Browsing("Browser"),
    Games("Games"),
}

private data class CardColorOption(val name: String, val hex: String)

private val supportedCardColors = listOf(
    CardColorOption("Purple", "#6750A4"),
    CardColorOption("Plum", "#7D5260"),
    CardColorOption("Indigo", "#536DFE"),
    CardColorOption("Blue", "#1976D2"),
    CardColorOption("Cyan", "#0097A7"),
    CardColorOption("Teal", "#4DB6AC"),
    CardColorOption("Green", "#388E3C"),
    CardColorOption("Lime", "#9E9D24"),
    CardColorOption("Amber", "#F9A825"),
    CardColorOption("Orange", "#EF6C00"),
    CardColorOption("Red", "#D32F2F"),
    CardColorOption("Violet", "#9575CD"),
)

@Composable
internal fun AppSettingsDialog(
    themeMode: AppThemeMode,
    onSelectTheme: (AppThemeMode) -> Unit,
    cardColors: CardColorSettings,
    onSetCardColors: (CardColorSettings) -> Unit,
    hasAllFiles: Boolean,
    onToggleAllFiles: () -> Unit,
    hasUsage: Boolean,
    onToggleUsage: () -> Unit,
    openLinksInApp: Boolean,
    onToggleOpenLinksInApp: () -> Unit,
    downloadBackend: BrowserDownloadBackend,
    onSelectDownloadBackend: (BrowserDownloadBackend) -> Unit,
    downloadFolderLabel: String,
    onPickDownloadFolder: () -> Unit,
    popupAllowedHosts: List<String>,
    popupBlockedHosts: List<String>,
    onAllowPopupHost: (String) -> Unit,
    onBlockPopupHost: (String) -> Unit,
    onRemoveAllowedPopupHost: (String) -> Unit,
    onRemoveBlockedPopupHost: (String) -> Unit,
    onOpenNonAndroidSettings: () -> Unit,
    onFindAllEngines: () -> Unit,
    engineDiscoveryRunning: Boolean,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppSettingsTab.Appearance) }
    var androidColor by remember(cardColors) { mutableStateOf(cardColors.android) }
    var joiPlayColor by remember(cardColors) { mutableStateOf(cardColors.joiPlay) }
    var winlatorColor by remember(cardColors) { mutableStateOf(cardColors.winlator) }
    var kirikiroidColor by remember(cardColors) { mutableStateOf(cardColors.kirikiroid) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                ) {
                    AppSettingsTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label, maxLines = 1, fontSize = 10.sp) },
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .dialogVerticalScroll()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when (selectedTab) {
                        AppSettingsTab.Appearance -> {
                            SettingsSectionHeader("Theme")
                            AppThemeMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectTheme(mode) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = themeMode == mode,
                                        onClick = { onSelectTheme(mode) },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(mode.label)
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            SettingsSectionHeader("Card colors")
                            Text(
                                "Managed-game cards use the color of their default execution engine.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CardColorDropdown("Android", androidColor) { androidColor = it }
                            CardColorDropdown("JoiPlay", joiPlayColor) { joiPlayColor = it }
                            CardColorDropdown("Winlator", winlatorColor) { winlatorColor = it }
                            CardColorDropdown("Kirikiroid", kirikiroidColor) { kirikiroidColor = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    onSetCardColors(
                                        CardColorSettings(
                                            android = androidColor,
                                            joiPlay = joiPlayColor,
                                            winlator = winlatorColor,
                                            kirikiroid = kirikiroidColor,
                                        ),
                                    )
                                }) { Text("Apply colors") }
                                TextButton(onClick = {
                                    val defaults = CardColorSettings()
                                    androidColor = defaults.android
                                    joiPlayColor = defaults.joiPlay
                                    winlatorColor = defaults.winlator
                                    kirikiroidColor = defaults.kirikiroid
                                    onSetCardColors(defaults)
                                }) { Text("Reset") }
                            }
                        }
                        AppSettingsTab.Permissions -> {
                            SettingsActionRow(
                                icon = Icons.Default.FolderOpen,
                                label = if (hasAllFiles) "Revoke all files access" else "Grant all files access",
                                onClick = onToggleAllFiles,
                            )
                            SettingsActionRow(
                                icon = Icons.Default.Schedule,
                                label = if (hasUsage) "Revoke usage data access" else "Grant usage data access",
                                onClick = onToggleUsage,
                            )
                        }
                        AppSettingsTab.Browsing -> {
                            CheckboxRow(
                                checked = openLinksInApp,
                                label = "Open links inside AGM",
                                onToggle = onToggleOpenLinksInApp,
                            )
                            SettingsSectionHeader("Download handler")
                            BrowserDownloadBackend.entries.forEach { backend ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectDownloadBackend(backend) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = downloadBackend == backend,
                                        onClick = { onSelectDownloadBackend(backend) },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(backend.label)
                                }
                            }
                            Text(
                                if (downloadBackend == BrowserDownloadBackend.ExternalBrowser) {
                                    "AGM opens the URL without sharing its WebView cookies and " +
                                        "watches the system Downloads and selected folder."
                                } else {
                                    "Android DownloadManager transfers and reports progress."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SettingsActionRow(
                                icon = Icons.Default.Download,
                                label = "Browser downloads folder\u2026",
                                subtitle = downloadFolderLabel,
                                onClick = onPickDownloadFolder,
                            )
                            BrowserPopupHostSettings(
                                allowedHosts = popupAllowedHosts,
                                blockedHosts = popupBlockedHosts,
                                onAllow = onAllowPopupHost,
                                onBlock = onBlockPopupHost,
                                onRemoveAllowed = onRemoveAllowedPopupHost,
                                onRemoveBlocked = onRemoveBlockedPopupHost,
                            )
                        }
                        AppSettingsTab.Games -> {
                            SettingsActionRow(
                                icon = Icons.Default.Settings,
                                label = "Non-Android game settings\u2026",
                                onClick = onOpenNonAndroidSettings,
                            )
                            SettingsActionRow(
                                icon = Icons.Default.Search,
                                label = if (engineDiscoveryRunning) {
                                    "Finding supported engines\u2026"
                                } else {
                                    "Find all engines for all games"
                                },
                                subtitle = "Scans every managed game folder and enables newly detected supported engines.",
                                enabled = !engineDiscoveryRunning,
                                onClick = onFindAllEngines,
                            )
                        }

                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BrowserPopupHostSettings(
    allowedHosts: List<String>,
    blockedHosts: List<String>,
    onAllow: (String) -> Unit,
    onBlock: (String) -> Unit,
    onRemoveAllowed: (String) -> Unit,
    onRemoveBlocked: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val normalized = normalizeBrowserHost(input)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SettingsSectionHeader("Pop-up hosts")
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("Host or URL") },
        singleLine = true,
        isError = input.isNotBlank() && normalized == null,
        supportingText = {
            if (input.isNotBlank() && normalized == null) Text("Enter a valid host or URL.")
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            enabled = normalized != null,
            onClick = {
                onBlock(requireNotNull(normalized))
                input = ""
            },
        ) {
            Icon(Icons.Default.Block, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Block")
        }
        TextButton(
            enabled = normalized != null,
            onClick = {
                onAllow(requireNotNull(normalized))
                input = ""
            },
        ) { Text("Allow") }
    }
    PopupHostList("Allowed", allowedHosts, onRemoveAllowed)
    PopupHostList("Blocked", blockedHosts, onRemoveBlocked)
}

@Composable
private fun PopupHostList(
    label: String,
    hosts: List<String>,
    onRemove: (String) -> Unit,
) {
    Text(
        "$label (${hosts.size})",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (hosts.isEmpty()) {
        Text(
            "None",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        hosts.forEach { host ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    host,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                IconButton(onClick = { onRemove(host) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove $host")
                }
            }
        }
    }
}

@Composable
private fun CardColorDropdown(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = supportedCardColors.firstOrNull { it.hex.equals(value, ignoreCase = true) }
        ?: CardColorOption("Current", value)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            shape = MaterialTheme.shapes.small,
            color = parseCardColor(current.hex),
        ) {}
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("$label: ${current.name}", modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                supportedCardColors.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        leadingIcon = {
                            Surface(
                                modifier = Modifier.size(22.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                                color = parseCardColor(option.hex),
                            ) {}
                        },
                        onClick = {
                            onValueChange(option.hex)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = tint)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * "Refresh from catalog" popup with two persisted options and Refresh/Cancel actions.
 */
@Composable
internal fun CatalogRefreshDialog(
    overwriteManual: Boolean,
    onToggleOverwrite: () -> Unit,
    resetAcks: Boolean,
    onToggleResetAcks: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Refresh from catalog") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Re-match your installed games against the catalog.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                CheckboxRow(
                    checked = overwriteManual,
                    label = "Overwrite manual matches",
                    onToggle = onToggleOverwrite,
                )
                CheckboxRow(
                    checked = resetAcks,
                    label = "Reset all acknowledgements",
                    onToggle = onToggleResetAcks,
                )
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CheckboxRow(checked: Boolean, label: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
