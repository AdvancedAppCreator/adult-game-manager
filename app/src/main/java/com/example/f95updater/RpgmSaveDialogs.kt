package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sentinel category filter that surfaces only the most meaningful/named save properties. */
private const val RPGM_HIGHLIGHTS = "\u0000highlights"

/** A value worth surfacing by default: party/actor/inventory data, or a named variable/switch. */
internal fun isRpgmHighlight(value: RpgmEditableValue): Boolean =
    value.category == RpgmCategory.PARTY ||
        value.category == RpgmCategory.ACTORS ||
        value.category == RpgmCategory.INVENTORY ||
        ((value.category == RpgmCategory.VARIABLES || value.category == RpgmCategory.SWITCHES) &&
            !value.name.isNullOrBlank())

private fun matchesCategoryFilter(value: RpgmEditableValue, filter: String?): Boolean = when (filter) {
    null -> true
    RPGM_HIGHLIGHTS -> isRpgmHighlight(value)
    else -> value.category == filter
}

/** Adds [delta] to the current numeric text, preserving int/float formatting. Falls back to delta. */
private fun stepRpgmNumber(current: String, delta: Long, type: String): String {
    val trimmed = current.trim()
    return if (type == "float") {
        val base = trimmed.toDoubleOrNull() ?: 0.0
        val result = base + delta
        if (result == result.toLong().toDouble()) result.toLong().toString() + ".0" else result.toString()
    } else {
        ((trimmed.toLongOrNull() ?: 0L) + delta).toString()
    }
}

@Composable
internal fun RpgmSaveLocationsContent(
    locations: List<RpgmSaveLocation>,
    lastScannedAt: Long,
    associationActionsEnabled: Boolean,
    onAssociate: (RpgmSaveLocation) -> Unit,
    onClearAssociation: (RpgmSaveLocation) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(SaveReportFilter.All) }
    val associatedCount = locations.count { it.associatedPackageName != null }
    val unassociatedCount = locations.count { it.associatedPackageName == null }
    fun matches(location: RpgmSaveLocation): Boolean {
        val q = query.trim()
        val statusOk = when (statusFilter) {
            SaveReportFilter.All -> true
            SaveReportFilter.Associated -> location.associatedPackageName != null
            SaveReportFilter.Unassociated -> location.associatedPackageName == null
        }
        val queryOk = q.isBlank() ||
            location.saveDirPath.contains(q, ignoreCase = true) ||
            location.ownerId.contains(q, ignoreCase = true) ||
            location.associatedLabel?.contains(q, ignoreCase = true) == true ||
            location.associatedPackageName?.contains(q, ignoreCase = true) == true
        return statusOk && queryOk
    }
    val filtered = locations.filter { matches(it) }
    val associated = filtered.filter { it.associatedPackageName != null }
    val unassociated = filtered.filter { it.associatedPackageName == null }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    val controlsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("Found: ${locations.size}") })
                    AssistChip(onClick = {}, label = { Text("Associated: $associatedCount") })
                    AssistChip(onClick = {}, label = { Text("Unmatched: $unassociatedCount") })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search save folders") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SaveReportFilter.values().forEach { filter ->
                        FilterChip(
                            selected = statusFilter == filter,
                            onClick = { statusFilter = filter },
                            label = { Text(filter.label, fontSize = 12.sp) },
                        )
                    }
                }
                Text(
                    "${filtered.size} matching folder${if (filtered.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Last scanned: ${fmtDateTime(lastScannedAt)}. RPGM support discovers MV/MZ and VX Ace saves and edits only values that can be written and verified safely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                DialogLazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (statusFilter != SaveReportFilter.Unassociated) {
                        item { ReportHeader("Associated", associated.size) }
                        if (associated.isEmpty()) item { ReportEmptyRow() }
                        else items(associated) { location ->
                            RpgmSaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
                        }
                    }
                    if (statusFilter != SaveReportFilter.Associated) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            ReportHeader("Unassociated", unassociated.size)
                        }
                        if (unassociated.isEmpty()) item { ReportEmptyRow() }
                        else items(unassociated) { location ->
                            RpgmSaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
                        }
                    }
                }
            }
            if (wideDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.width(340.dp).fillMaxHeight().dialogVerticalScroll()) {
                        controlsPane()
                    }
                    listPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    controlsPane()
                    listPane(Modifier.fillMaxWidth().heightIn(max = 430.dp))
                }
            }
}

@Composable
internal fun RpgmSaveAssociationPickerDialog(
    location: RpgmSaveLocation,
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onAssociate: (InstalledApp) -> Unit,
) {
    var query by remember { mutableStateOf(location.ownerId) }
    val filtered = remember(apps, query) {
        val q = query.trim()
        apps.asSequence()
            .filter { app ->
                q.isBlank() ||
                    app.label.contains(q, ignoreCase = true) ||
                    app.packageName.contains(q, ignoreCase = true) ||
                    app.storageFolderName?.contains(q, ignoreCase = true) == true
            }
            .sortedWith(
                compareBy<InstalledApp> {
                    it.source != AppSource.JoiPlay && it.source != AppSource.Managed
                }.thenBy { it.label.lowercase() },
            )
            .take(80)
            .toList()
    }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("Associate RPGM saves") },
        text = {
            val controlsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    location.saveDirPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find game") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
            }
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                DialogLazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item { ReportEmptyRow() }
                    } else {
                        items(filtered, key = { it.packageName }) { app ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssociate(app) },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(app.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        buildString {
                                            append(app.packageName)
                                            if (app.source == AppSource.Managed) append(" • Managed")
                                            else if (app.source == AppSource.JoiPlay) append(" • JoiPlay")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    app.storagePath?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (wideDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.width(340.dp), content = { controlsPane() })
                    listPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    controlsPane()
                    listPane(Modifier.fillMaxWidth().heightIn(max = 430.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RpgmSaveViewerDialog(
    app: InstalledApp,
    locations: List<RpgmSaveLocation>,
    onDismiss: () -> Unit,
) {
    var slots by remember(locations) { mutableStateOf<Map<String, List<RpgmSaveSlot>>?>(null) }
    var selectedSlot by remember { mutableStateOf<RpgmSaveSlot?>(null) }
    var compareOpen by remember { mutableStateOf(false) }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    val contentHeight = if (wideDialog) (LocalConfiguration.current.screenHeightDp * 0.58f).dp else 600.dp
    LaunchedEffect(locations) {
        slots = withContext(Dispatchers.IO) {
            locations.associate { location -> location.saveDirPath to RpgmSaveScanner.listSaveSlots(location) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("RPGM saves") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wideDialog) Modifier.height(contentHeight) else Modifier.heightIn(max = contentHeight)),
                verticalArrangement = Arrangement.spacedBy(if (wideDialog) 6.dp else 8.dp),
            ) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "RPGM support lists verified MV/MZ and VX Ace saves and safely edits scalar JSON or Ruby Marshal values with automatic backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (wideDialog) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                val loaded = slots
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading RPGM saves…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val allSlots = locations.flatMap { loaded[it.saveDirPath].orEmpty() }
                    val controlsPane: @Composable () -> Unit = {
                        TextButton(
                            enabled = allSlots.size >= 2,
                            onClick = { compareOpen = true },
                        ) {
                            Text("Compare saves")
                        }
                    }
                    val listPane: @Composable (Modifier) -> Unit = { modifier ->
                        DialogLazyColumn(
                            modifier = modifier,
                            verticalArrangement = Arrangement.spacedBy(if (wideDialog) 6.dp else 8.dp),
                        ) {
                            locations.forEach { location ->
                                item { RenPySaveLocationHeader(location.saveDirPath, loaded[location.saveDirPath].orEmpty().size) }
                                val locationSlots = loaded[location.saveDirPath].orEmpty()
                                if (locationSlots.isEmpty()) item { ReportEmptyRow() }
                                else items(locationSlots) { slot ->
                                    RpgmSaveSlotRow(slot = slot, onClick = { selectedSlot = slot })
                                }
                            }
                        }
                    }
                    if (wideDialog) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(320.dp).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                content = { controlsPane() },
                            )
                            listPane(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        controlsPane()
                        listPane(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    selectedSlot?.let { slot ->
        RpgmSaveSlotDetailDialog(
            slot = slot,
            onDismiss = { selectedSlot = null },
        )
    }
    if (compareOpen) {
        val loaded = slots.orEmpty()
        RpgmSaveCompareDialog(
            slots = locations.flatMap { loaded[it.saveDirPath].orEmpty() },
            onDismiss = { compareOpen = false },
        )
    }
}

@Composable
internal fun RpgmSaveCompareDialog(
    slots: List<RpgmSaveSlot>,
    onDismiss: () -> Unit,
) {
    var left by remember(slots) { mutableStateOf(slots.getOrNull(0)) }
    var right by remember(slots) { mutableStateOf(slots.getOrNull(1)) }
    var diffs by remember { mutableStateOf<List<SaveCompareDiff>?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(left?.filePath, right?.filePath) {
        val l = left
        val r = right
        diffs = null
        warning = null
        if (l != null && r != null && l.filePath != r.filePath) {
            val leftInspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(l) }
            val rightInspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(r) }
            warning = listOfNotNull(leftInspection.warning, rightInspection.warning).distinct().joinToString("\n").ifBlank { null }
            diffs = buildSaveDiffs(
                leftInspection.values.map { SaveCompareValue(it.path, it.type, it.displayValue) },
                rightInspection.values.map { SaveCompareValue(it.path, it.type, it.displayValue) },
            )
        } else {
            diffs = emptyList()
        }
    }
    SaveCompareDialogContent(
        title = "Compare RPGM saves",
        slots = slots.map { it.filePath to it.fileName },
        leftPath = left?.filePath,
        rightPath = right?.filePath,
        warning = warning,
        diffs = diffs,
        onLeft = { path -> left = slots.firstOrNull { it.filePath == path } },
        onRight = { path -> right = slots.firstOrNull { it.filePath == path } },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun RpgmSaveSlotDetailDialog(
    slot: RpgmSaveSlot,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wideEditor = isWideEditorLayout(LocalConfiguration.current)
    val valuesScroll = rememberScrollState()
    var inspection by remember(slot.filePath) { mutableStateOf<RpgmEditInspection?>(null) }
    var editTarget by remember { mutableStateOf<RpgmEditableValue?>(null) }
    var massReplaceTargets by remember { mutableStateOf<List<RpgmEditableValue>?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<String?>(null) }
    var sessionBackup by remember(slot.filePath) { mutableStateOf<SaveEditSessionBackup?>(null) }
    var backups by remember(slot.filePath) { mutableStateOf<List<RenPySaveBackup>>(emptyList()) }
    var restorePickerOpen by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<RenPySaveBackup?>(null) }
    var valueFilter by remember { mutableStateOf("") }
    var wholeWordFilter by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var categoryFilter by remember(slot.filePath) { mutableStateOf<String?>(RPGM_HIGHLIGHTS) }
    var syncTarget by remember(slot.filePath) { mutableStateOf<SaveSyncMirrorTarget?>(null) }
    var overwriteSyncToo by remember(slot.filePath) { mutableStateOf(false) }
    val stagedValues = remember(slot.filePath) { mutableStateMapOf<String, String>() }
    LaunchedEffect(slot.filePath) {
        inspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(slot) }
        backups = withContext(Dispatchers.IO) { RpgmSaveEditor.listBackups(slot) }
        syncTarget = SaveSyncMirror.findSyncTarget(slot.filePath)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(slot.fileName) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (wideEditor) 620.dp else 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!wideEditor) {
                    Text(
                        "${slot.codec} • ${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                            if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    editMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    syncTarget?.let { target ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !editing) { overwriteSyncToo = !overwriteSyncToo },
                        ) {
                            Checkbox(
                                checked = overwriteSyncToo,
                                enabled = !editing,
                                onCheckedChange = { overwriteSyncToo = it },
                            )
                            Column {
                                Text("Overwrite sync too", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${target.fileName} • ${fmtDateTime(target.modifiedAt)} • ${fmtSize(target.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (backups.isNotEmpty()) {
                        TextButton(enabled = !editing, onClick = { restorePickerOpen = true }) {
                            Text("Restore backup… (${backups.size})")
                        }
                    }
                    HorizontalDivider()
                    Text("Editable values", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                val loaded = inspection
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Inspecting save…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    loaded.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    val q = valueFilter.trim()
                    val availableCategories = remember(loaded.values) {
                        RpgmCategory.order.filter { cat -> loaded.values.any { it.category == cat } }
                    }
                    val hasHighlights = remember(loaded.values) { loaded.values.any { isRpgmHighlight(it) } }
                    LaunchedEffect(hasHighlights) {
                        if (!hasHighlights && categoryFilter == RPGM_HIGHLIGHTS) categoryFilter = null
                    }
                    val effectiveCategoryFilter = if (categoryFilter == RPGM_HIGHLIGHTS && !hasHighlights) null else categoryFilter
                    val values = loaded.values.filter { value ->
                        val effectiveValue = stagedValues[value.path] ?: value.displayValue
                        (typeFilter == null || value.type == typeFilter) &&
                            matchesCategoryFilter(value, effectiveCategoryFilter) &&
                            SaveSearchMatcher.matchesAny(
                                query = q,
                                wholeWord = wholeWordFilter,
                                fields = listOfNotNull(value.path, effectiveValue, value.type, value.name),
                            )
                    }
                    val filterControls: @Composable ColumnScope.() -> Unit = {
                        if (wideEditor) {
                            Text(
                                "${slot.codec} • ${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                                    if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            editMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                            syncTarget?.let { target ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !editing) { overwriteSyncToo = !overwriteSyncToo },
                                ) {
                                    Checkbox(
                                        checked = overwriteSyncToo,
                                        enabled = !editing,
                                        onCheckedChange = { overwriteSyncToo = it },
                                    )
                                    Column {
                                        Text("Overwrite sync too", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${target.fileName} • ${fmtDateTime(target.modifiedAt)} • ${fmtSize(target.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (backups.isNotEmpty()) {
                                TextButton(enabled = !editing, onClick = { restorePickerOpen = true }) {
                                    Text("Restore backup… (${backups.size})")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = valueFilter,
                            onValueChange = { valueFilter = it },
                            label = { Text("Filter values") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (valueFilter.isNotBlank()) {
                                    IconButton(onClick = { valueFilter = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear filter")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (hasHighlights) {
                                FilterChip(
                                    selected = categoryFilter == RPGM_HIGHLIGHTS,
                                    onClick = { categoryFilter = RPGM_HIGHLIGHTS },
                                    label = { Text("Highlights", fontSize = 12.sp) },
                                )
                            }
                            availableCategories.forEach { cat ->
                                FilterChip(
                                    selected = categoryFilter == cat,
                                    onClick = { categoryFilter = cat },
                                    label = { Text(cat, fontSize = 12.sp) },
                                )
                            }
                            FilterChip(
                                selected = categoryFilter == null,
                                onClick = { categoryFilter = null },
                                label = { Text("All", fontSize = 12.sp) },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = wholeWordFilter,
                                onClick = { wholeWordFilter = !wholeWordFilter },
                                label = { Text("Whole word", fontSize = 12.sp) },
                            )
                            listOf(null, "int", "float", "bool", "string").forEach { type ->
                                FilterChip(
                                    selected = typeFilter == type,
                                    onClick = { typeFilter = if (typeFilter == type) null else type },
                                    label = { Text(type ?: "All", fontSize = 12.sp) },
                                )
                            }
                        }
                        Text("${values.size} of ${loaded.values.size} values", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (values.isNotEmpty()) {
                            OutlinedButton(
                                enabled = !editing,
                                onClick = { massReplaceTargets = values },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Mass replace ${values.size}…", fontSize = 12.sp)
                            }
                        }
                        if (stagedValues.isNotEmpty()) {
                            Text(
                                "${stagedValues.size} staged edit${if (stagedValues.size == 1) "" else "s"} - tap Save to write",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    val valueList: @Composable (Modifier) -> Unit = { modifier ->
                        if (values.isEmpty()) {
                            ReportEmptyRow()
                        } else {
                            ScrollableColumnWithScrollbar(
                                modifier = modifier,
                                scrollState = valuesScroll,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                var lastCategory: String? = null
                                values.forEach { value ->
                                    if (value.category != lastCategory) {
                                        lastCategory = value.category
                                        Text(
                                            value.category,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                    val staged = stagedValues[value.path]
                                    val displayValue = staged ?: value.displayValue
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !editing) { editTarget = value },
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            value.name?.let { name ->
                                                Text(
                                                    name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text(
                                                value.path,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (value.name == null) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (value.name == null) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                "${value.type}: $displayValue" + if (staged != null) " (staged)" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (staged != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (wideEditor) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(270.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                content = filterControls,
                            )
                            valueList(
                                Modifier
                                    .weight(1f)
                                    .heightIn(max = 520.dp)
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            content = filterControls,
                        )
                        valueList(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !editing && stagedValues.isNotEmpty(),
                    onClick = {
                        editing = true
                        scope.launch {
                            var backup = sessionBackup
                            var message = ""
                            var saved = 0
                            val pending = stagedValues.toList()
                            for ((path, newValue) in pending) {
                                val (result, nextBackup) = RpgmSaveEditor.edit(slot, path, newValue, backup)
                                backup = nextBackup
                                message = result.message
                                if (!result.ok) break
                                saved++
                            }
                            sessionBackup = backup
                            val allSaved = saved == pending.size
                            if (allSaved) {
                                if (overwriteSyncToo) {
                                    syncTarget?.let { target ->
                                        val syncResult = SaveSyncMirror.overwriteSyncTarget(slot.filePath, target)
                                        message = "$message ${syncResult.message}"
                                    }
                                }
                                stagedValues.clear()
                                inspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RpgmSaveEditor.listBackups(slot) }
                                syncTarget = SaveSyncMirror.findSyncTarget(slot.filePath)
                            }
                            editMessage = if (saved > 1 && allSaved) {
                                "Saved $saved edits. $message"
                            } else {
                                message.ifBlank { "Saved $saved edits." }
                            }
                            editing = false
                        }
                    },
                ) { Text(if (editing) "Saving…" else "Save") }
                TextButton(onClick = onDismiss, enabled = !editing) { Text("Close") }
            }
        },
    )
    editTarget?.let { value ->
        RpgmValueEditDialog(
            value = value,
            busy = editing,
            onDismiss = { if (!editing) editTarget = null },
            onSave = { newValue ->
                val original = inspection?.values?.firstOrNull { it.path == value.path }?.displayValue ?: value.displayValue
                if (newValue == original) {
                    stagedValues.remove(value.path)
                } else {
                    stagedValues[value.path] = newValue
                }
                editMessage = "Staged ${value.path}. Tap Save to write changes."
                editTarget = null
            },
        )
    }
    if (restorePickerOpen) {
        RenPyBackupPickerDialog(
            backups = backups,
            onDismiss = { if (!editing) restorePickerOpen = false },
            onPick = { backup ->
                restorePickerOpen = false
                restoreTarget = backup
            },
        )
    }
    massReplaceTargets?.let { targets ->
        MassReplaceDialog(
            count = targets.size,
            onDismiss = { massReplaceTargets = null },
            onApply = { newValue ->
                var changed = 0
                targets.forEach { value ->
                    if (newValue == value.displayValue) {
                        stagedValues.remove(value.path)
                    } else {
                        stagedValues[value.path] = newValue
                        changed++
                    }
                }
                editMessage = "Staged mass replace on $changed value${if (changed == 1) "" else "s"}. Tap Save to write."
                massReplaceTargets = null
            },
        )
    }
    restoreTarget?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!editing) restoreTarget = null },
            title = { Text("Restore RPGM backup?") },
            text = {
                Text("Restore ${backup.fileName}? AGM will first back up the current RPGM save, then replace it with this backup.")
            },
            confirmButton = {
                TextButton(
                    enabled = !editing,
                    onClick = {
                        editing = true
                        scope.launch {
                            val result = RpgmSaveEditor.restoreBackup(slot, backup)
                            editMessage = result.message
                            if (result.ok) {
                                inspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RpgmSaveEditor.listBackups(slot) }
                                sessionBackup = null
                                restoreTarget = null
                            }
                            editing = false
                        }
                    },
                ) {
                    Text(if (editing) "Restoring…" else "Restore")
                }
            },
            dismissButton = {
                TextButton(enabled = !editing, onClick = { restoreTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun MassReplaceDialog(
    count: Int,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mass replace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Set all $count value${if (count == 1) "" else "s"} in the current filtered list to the value below. " +
                        "The change is staged - review it and tap Save to write. A backup is created before writing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("New value for all") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Values whose type can't accept this input will fail when saving - filter to one type first to be safe. " +
                        "Mass-editing the wrong values can break game progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = count > 0, onClick = { onApply(text) }) { Text("Stage replace") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RpgmValueEditDialog(
    value: RpgmEditableValue,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(value.path) { mutableStateOf(value.displayValue) }
    val isBool = value.type == "bool"
    val boolOn = text.trim().lowercase() in setOf("true", "1", "yes", "on")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(value.name?.let { "Edit $it" } ?: "Edit ${value.path}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                value.name?.let {
                    Text(value.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Type: ${value.type}", style = MaterialTheme.typography.bodySmall)
                if (isBool) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(
                            checked = boolOn,
                            enabled = !busy,
                            onCheckedChange = { text = if (it) "true" else "false" },
                        )
                        Text(if (boolOn) "ON (true)" else "OFF (false)", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("New value") },
                        enabled = !busy,
                        singleLine = value.type != "string",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (value.type == "int" || value.type == "float") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(-100L, -10L, -1L, 1L, 10L, 100L).forEach { delta ->
                                OutlinedButton(
                                    enabled = !busy,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    onClick = { text = stepRpgmNumber(text, delta, value.type) },
                                ) { Text(if (delta > 0) "+$delta" else "$delta", fontSize = 12.sp) }
                            }
                        }
                    }
                }
                Text(
                    "A backup is created before writing. Editing the wrong value can break game progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onSave(text) }) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
