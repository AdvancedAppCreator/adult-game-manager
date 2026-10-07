package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RenPySlotSort(val label: String) {
    Modified("Modified"),
    FileName("File"),
    SaveName("Save name"),
    Size("Size"),
}

@Composable
internal fun RenPySaveLocationsContent(
    locations: List<RenPySaveLocation>,
    lastScannedAt: Long,
    associationActionsEnabled: Boolean,
    onAssociate: (RenPySaveLocation) -> Unit,
    onClearAssociation: (RenPySaveLocation) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(SaveReportFilter.All) }
    val associatedCount = locations.count { it.associatedPackageName != null }
    val unassociatedCount = locations.count { it.associatedPackageName == null }
    fun matches(location: RenPySaveLocation): Boolean {
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
            location.associatedPackageName?.contains(q, ignoreCase = true) == true ||
            location.sampleSaveNames.any { it.contains(q, ignoreCase = true) }
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
                    buildString {
                        append("Private app saves under /data/data are not readable without root. ")
                        append("This report lists verified Ren'Py save folders found in accessible storage.")
                        if (lastScannedAt > 0L) append(" Last scanned ${fmtDateTime(lastScannedAt)}.")
                    },
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
                            RenPySaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
                        }
                    }
                    if (statusFilter != SaveReportFilter.Associated) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            ReportHeader("Unassociated", unassociated.size)
                        }
                        if (unassociated.isEmpty()) item { ReportEmptyRow() }
                        else items(unassociated) { location ->
                            RenPySaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
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
internal fun RenPySaveAssociationPickerDialog(
    location: RenPySaveLocation,
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
        title = { Text("Associate Ren'Py saves") },
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
internal fun RenPySaveEditorDialog(
    app: InstalledApp,
    locations: List<RenPySaveLocation>,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val wideEditor = isWideEditorLayout(configuration)
    val contentHeight = if (wideEditor) (configuration.screenHeightDp * 0.58f).dp else 600.dp
    var slots by remember(locations) { mutableStateOf<Map<String, List<RenPySaveSlot>>?>(null) }
    var selectedSlot by remember { mutableStateOf<RenPySaveSlot?>(null) }
    var compareOpen by remember { mutableStateOf(false) }
    var slotSort by remember { mutableStateOf(RenPySlotSort.Modified) }
    var slotSortDesc by remember { mutableStateOf(true) }
    LaunchedEffect(locations) {
        slots = withContext(Dispatchers.IO) {
            locations.associate { location -> location.saveDirPath to RenPySaveScanner.listSaveSlots(location) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Ren'Py save editor") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wideEditor) Modifier.height(contentHeight) else Modifier.heightIn(max = contentHeight)),
                verticalArrangement = Arrangement.spacedBy(if (wideEditor) 6.dp else 8.dp),
            ) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap a save to inspect/edit direct store.* scalar values. A backup is created before writes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (wideEditor) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                val loaded = slots
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading save slots…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val allSlots = locations.flatMap { loaded[it.saveDirPath].orEmpty() }
                    val sortControls: @Composable () -> Unit = {
                        TextButton(
                            enabled = allSlots.size >= 2,
                            onClick = { compareOpen = true },
                        ) {
                            Text("Compare saves")
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RenPySlotSort.values().forEach { sort ->
                                FilterChip(
                                    selected = slotSort == sort,
                                    onClick = {
                                        if (slotSort == sort) slotSortDesc = !slotSortDesc
                                        else {
                                            slotSort = sort
                                            slotSortDesc = sort == RenPySlotSort.Modified || sort == RenPySlotSort.Size
                                        }
                                    },
                                    label = {
                                        Text(
                                            sort.label + if (slotSort == sort) {
                                                if (slotSortDesc) " ↓" else " ↑"
                                            } else "",
                                            fontSize = 12.sp,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    val saveList: @Composable (Modifier) -> Unit = { modifier ->
                        DialogLazyColumn(
                            modifier = modifier,
                            verticalArrangement = Arrangement.spacedBy(if (wideEditor) 6.dp else 8.dp),
                        ) {
                            locations.forEach { location ->
                                item {
                                    RenPySaveLocationHeader(location.saveDirPath, loaded[location.saveDirPath].orEmpty().size)
                                }
                                val locationSlots = loaded[location.saveDirPath].orEmpty()
                                if (locationSlots.isEmpty()) {
                                    item { ReportEmptyRow() }
                                } else {
                                    val sortedSlots = sortRenPySlots(locationSlots, slotSort, slotSortDesc)
                                    items(sortedSlots) { slot ->
                                        RenPySaveSlotRow(
                                            slot = slot,
                                            onClick = { selectedSlot = slot },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (wideEditor) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(320.dp)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                sortControls()
                            }
                            saveList(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    } else {
                        sortControls()
                        saveList(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    selectedSlot?.let { slot ->
        RenPySaveSlotDetailDialog(
            slot = slot,
            onDismiss = { selectedSlot = null },
        )
    }
    if (compareOpen) {
        val loaded = slots.orEmpty()
        RenPySaveCompareDialog(
            slots = locations.flatMap { loaded[it.saveDirPath].orEmpty() },
            onDismiss = { compareOpen = false },
        )
    }
}

private fun sortRenPySlots(
    slots: List<RenPySaveSlot>,
    sort: RenPySlotSort,
    desc: Boolean,
): List<RenPySaveSlot> {
    val sorted = when (sort) {
        RenPySlotSort.Modified -> slots.sortedBy { it.modifiedAt }
        RenPySlotSort.FileName -> slots.sortedBy { it.fileName.lowercase() }
        RenPySlotSort.SaveName -> slots.sortedBy { (it.saveName ?: it.fileName).lowercase() }
        RenPySlotSort.Size -> slots.sortedBy { it.sizeBytes }
    }
    return if (desc) sorted.reversed() else sorted
}

@Composable
internal fun RenPySaveCompareDialog(
    slots: List<RenPySaveSlot>,
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
            val leftInspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(l) }
            val rightInspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(r) }
            warning = listOfNotNull(leftInspection.warning, rightInspection.warning).distinct().joinToString("\n").ifBlank { null }
            diffs = buildSaveDiffs(
                leftInspection.variables.map { SaveCompareValue(it.key, it.type, it.displayValue) },
                rightInspection.variables.map { SaveCompareValue(it.key, it.type, it.displayValue) },
            )
        } else {
            diffs = emptyList()
        }
    }
    SaveCompareDialogContent(
        title = "Compare Ren'Py saves",
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
internal fun RenPySaveSlotRow(slot: RenPySaveSlot, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember(slot.filePath, slot.modifiedAt) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(slot.filePath, slot.modifiedAt) {
        thumbnail = withContext(Dispatchers.IO) { RenPySaveEditor.extractThumbnail(context.applicationContext, slot) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumbnail != null) {
            coil.compose.AsyncImage(
                model = thumbnail,
                contentDescription = "Save thumbnail",
                modifier = Modifier
                    .size(width = 72.dp, height = 44.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else if (slot.hasScreenshot) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 44.dp)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(slot.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            slot.saveName?.takeIf { it.isNotBlank() }?.let {
                Text("Name: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Modified: ${fmtDateTime(slot.modifiedAt)} • Size: ${fmtSize(slot.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slot.renpyVersion?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Screenshot: ${if (slot.hasScreenshot) "yes" else "no"} • Entries: ${slot.entries.joinToString(", ").ifBlank { "legacy/non-zip" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun RenPySaveSlotDetailDialog(
    slot: RenPySaveSlot,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val wideEditor = isWideEditorLayout(LocalConfiguration.current)
    val variablesScroll = rememberScrollState()
    var inspection by remember(slot.filePath) { mutableStateOf<RenPyEditInspection?>(null) }
    var editTarget by remember { mutableStateOf<RenPyEditableVariable?>(null) }
    var massReplaceTargets by remember { mutableStateOf<List<RenPyEditableVariable>?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<String?>(null) }
    var sessionBackup by remember(slot.filePath) { mutableStateOf<SaveEditSessionBackup?>(null) }
    var variableFilter by remember { mutableStateOf("") }
    var wholeWordFilter by remember { mutableStateOf(false) }
    var variableTypeFilter by remember { mutableStateOf<String?>(null) }
    var backups by remember(slot.filePath) { mutableStateOf<List<RenPySaveBackup>>(emptyList()) }
    var restorePickerOpen by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<RenPySaveBackup?>(null) }
    var syncTarget by remember(slot.filePath) { mutableStateOf<SaveSyncMirrorTarget?>(null) }
    var overwriteSyncToo by remember(slot.filePath) { mutableStateOf(false) }
    val stagedVariables = remember(slot.filePath) { mutableStateMapOf<String, String>() }
    LaunchedEffect(slot.filePath) {
        inspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(slot) }
        backups = withContext(Dispatchers.IO) { RenPySaveEditor.listBackups(slot) }
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
                    .heightIn(max = if (wideEditor) 620.dp else 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!wideEditor) {
                    Text(
                        "${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                            if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    editMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
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
                    Text("Editable variables", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                val loaded = inspection
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Inspecting save…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    loaded.warning?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    val query = variableFilter.trim()
                    val filteredVariables = loaded.variables.filter { variable ->
                        val effectiveValue = stagedVariables[variable.key] ?: variable.displayValue
                        val typeMatches = variableTypeFilter == null || variable.type == variableTypeFilter
                        val queryMatches = SaveSearchMatcher.matchesAny(
                            query = query,
                            wholeWord = wholeWordFilter,
                            fields = listOf(variable.key, effectiveValue, variable.type),
                        )
                        typeMatches && queryMatches
                    }
                    val filterControls: @Composable ColumnScope.() -> Unit = {
                        if (wideEditor) {
                            Text(
                                "${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                                    if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            editMessage?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
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
                            value = variableFilter,
                            onValueChange = { variableFilter = it },
                            label = { Text("Filter variables") },
                            placeholder = { Text("name, value, or type") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (variableFilter.isNotBlank()) {
                                    IconButton(onClick = { variableFilter = "" }) {
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
                            FilterChip(
                                selected = wholeWordFilter,
                                onClick = { wholeWordFilter = !wholeWordFilter },
                                label = { Text("Whole word", fontSize = 12.sp) },
                            )
                            listOf(null, "int", "float", "bool", "string").forEach { type ->
                                FilterChip(
                                    selected = variableTypeFilter == type,
                                    onClick = { variableTypeFilter = if (variableTypeFilter == type) null else type },
                                    label = { Text(type ?: "All", fontSize = 12.sp) },
                                )
                            }
                        }
                        Text(
                            "${filteredVariables.size} of ${loaded.variables.size} variables",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (filteredVariables.isNotEmpty()) {
                            OutlinedButton(
                                enabled = !editing,
                                onClick = { massReplaceTargets = filteredVariables },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Mass replace ${filteredVariables.size}…", fontSize = 12.sp)
                            }
                        }
                        if (stagedVariables.isNotEmpty()) {
                            Text(
                                "${stagedVariables.size} staged edit${if (stagedVariables.size == 1) "" else "s"} - tap Save to write",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    val valueList: @Composable (Modifier) -> Unit = { modifier ->
                        if (filteredVariables.isEmpty()) {
                            ReportEmptyRow()
                        } else {
                            ScrollableColumnWithScrollbar(
                                modifier = modifier,
                                scrollState = variablesScroll,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                filteredVariables.forEach { variable ->
                                    val staged = stagedVariables[variable.key]
                                    val displayValue = staged ?: variable.displayValue
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !editing) { editTarget = variable },
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(variable.key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${variable.type}: $displayValue" + if (staged != null) " (staged)" else "",
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
                    enabled = !editing && stagedVariables.isNotEmpty(),
                    onClick = {
                        editing = true
                        scope.launch {
                            var backup = sessionBackup
                            var message = ""
                            var saved = 0
                            val pending = stagedVariables.toList()
                            for ((key, newValue) in pending) {
                                val (result, nextBackup) = RenPySaveEditor.edit(slot, key, newValue, backup)
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
                                stagedVariables.clear()
                                inspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RenPySaveEditor.listBackups(slot) }
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
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(slot.filePath))
                    },
                    enabled = !editing,
                ) {
                    Text("Copy path")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    editTarget?.let { variable ->
        RenPyVariableEditDialog(
            variable = variable,
            busy = editing,
            onDismiss = { if (!editing) editTarget = null },
            onSave = { newValue ->
                val original = inspection?.variables?.firstOrNull { it.key == variable.key }?.displayValue ?: variable.displayValue
                if (newValue == original) {
                    stagedVariables.remove(variable.key)
                } else {
                    stagedVariables[variable.key] = newValue
                }
                editMessage = "Staged ${variable.key}. Tap Save to write changes."
                editTarget = null
            },
        )
    }
    massReplaceTargets?.let { targets ->
        MassReplaceDialog(
            count = targets.size,
            onDismiss = { massReplaceTargets = null },
            onApply = { newValue ->
                var changed = 0
                targets.forEach { variable ->
                    if (newValue == variable.displayValue) {
                        stagedVariables.remove(variable.key)
                    } else {
                        stagedVariables[variable.key] = newValue
                        changed++
                    }
                }
                editMessage = "Staged mass replace on $changed variable${if (changed == 1) "" else "s"}. Tap Save to write."
                massReplaceTargets = null
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
    restoreTarget?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!editing) restoreTarget = null },
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "Restore ${backup.fileName}? AGM will first back up the current save, then replace it with this backup.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !editing,
                    onClick = {
                        editing = true
                        scope.launch {
                            val result = RenPySaveEditor.restoreBackup(slot, backup)
                            editMessage = result.message
                            if (result.ok) {
                                inspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RenPySaveEditor.listBackups(slot) }
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
internal fun RenPyVariableEditDialog(
    variable: RenPyEditableVariable,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(variable.key) { mutableStateOf(variable.displayValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${variable.key}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type: ${variable.type}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("New value") },
                    enabled = !busy,
                    singleLine = variable.type != "string",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A .bak copy is created before writing. The game may show an unsigned-save warning because the original Ren'Py signature is invalidated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onSave(value) }) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RenPyBackupPickerDialog(
    backups: List<RenPySaveBackup>,
    onDismiss: () -> Unit,
    onPick: (RenPySaveBackup) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Pick a backup to restore. AGM will back up the current save before replacing it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogLazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (backups.isEmpty()) {
                        item { ReportEmptyRow() }
                    } else {
                        items(backups) { backup ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(backup) },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f).widthIn(max = 220.dp)) {
                                        Text(
                                            backup.fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${fmtDateTime(backup.createdAt)} • ${fmtSize(backup.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text("Select", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
