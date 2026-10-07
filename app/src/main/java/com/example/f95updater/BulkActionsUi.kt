package com.example.f95updater

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Confirmation for deleting several games at once. Explains that JoiPlay games are removed
 * immediately, while Winlator and Android games each need their own confirmation because there is
 * no silent native uninstall path for them.
 */
@Composable
internal fun BulkDeleteConfirmDialog(
    breakdown: BulkDeleteBreakdown,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${breakdown.total} game${if (breakdown.total == 1) "" else "s"}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About ${fmtSize(breakdown.totalBytes)} of storage in the selection.")
                if (breakdown.silentCount > 0) {
                    Text(
                        "\u2022 ${breakdown.silentCount} JoiPlay game${if (breakdown.silentCount == 1) "" else "s"} " +
                            "will be deleted now.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (breakdown.managed.isNotEmpty()) {
                    Text(
                        "\u2022 ${breakdown.managed.size} AGM-managed game" +
                            "${if (breakdown.managed.size == 1) "" else "s"} will have all runner " +
                            "bindings removed before their shared files are deleted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (breakdown.winlator.isNotEmpty()) {
                    Text(
                        "\u2022 ${breakdown.winlator.size} Winlator game${if (breakdown.winlator.size == 1) "" else "s"} " +
                            "\u2014 you'll confirm each one in Winlator.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (breakdown.android.isNotEmpty()) {
                    Text(
                        "\u2022 ${breakdown.android.size} app game${if (breakdown.android.size == 1) "" else "s"} " +
                            "\u2014 Android will prompt for each uninstall.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Files stored inside managed game folders, including local saves, are deleted. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Adds and/or removes user tags across several selected games at once. The user stages tags to add
 * (free text, normalized) and tags to remove (chosen from tags already present on the selection),
 * then applies both in one pass.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun BulkTagDialog(
    count: Int,
    tagsOnSelection: List<String>,
    allExistingTags: List<String>,
    onDismiss: () -> Unit,
    onApply: (add: Set<String>, remove: Set<String>) -> Unit,
) {
    var toAdd by remember { mutableStateOf<List<String>>(emptyList()) }
    var toRemove by remember { mutableStateOf<Set<String>>(emptySet()) }
    var input by remember { mutableStateOf("") }

    fun addCurrentInput() {
        val t = UserTagsStore.normalize(input) ?: return
        if (t !in toAdd) toAdd = toAdd + t
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag $count game${if (count == 1) "" else "s"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add tags", style = MaterialTheme.typography.labelLarge)
                if (toAdd.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        toAdd.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { toAdd = toAdd - tag },
                                label = { Text(tag) },
                                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Add a tag") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addCurrentInput() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { addCurrentInput() },
                        enabled = UserTagsStore.normalize(input) != null,
                    ) { Text("Add") }
                }
                val suggestions = remember(input, toAdd, allExistingTags) {
                    val q = input.trim().lowercase()
                    allExistingTags.filter { it !in toAdd && (q.isEmpty() || it.contains(q)) }.take(8)
                }
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { s ->
                            AssistChip(
                                onClick = { if (s !in toAdd) toAdd = toAdd + s },
                                label = { Text(s, fontSize = 12.sp) },
                            )
                        }
                    }
                }
                if (tagsOnSelection.isNotEmpty()) {
                    Text("Remove tags", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tagsOnSelection.forEach { tag ->
                            FilterChip(
                                selected = tag in toRemove,
                                onClick = {
                                    toRemove = if (tag in toRemove) toRemove - tag else toRemove + tag
                                },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(toAdd.toSet(), toRemove) },
                enabled = toAdd.isNotEmpty() || toRemove.isNotEmpty(),
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Hosts the selection-mode bulk dialogs (tag / delete-confirm / delete-progress). Extracted from
 * the InstalledScreen composable so that composable stays under the JVM 64 KB method limit. The
 * heavy delete/tag execution stays with the caller via the callbacks.
 */
@Composable
internal fun InstalledBulkActionDialogs(
    bulkTagTarget: List<AppRow>?,
    bulkDeleteConfirm: List<AppRow>?,
    bulkTally: BulkTally?,
    bulkSilentActive: Boolean,
    interactivePending: Boolean,
    userTags: Map<String, Set<String>>,
    sizeOf: (AppRow) -> Long,
    onSetBulkTagTarget: (List<AppRow>?) -> Unit,
    onApplyTags: (targets: List<AppRow>, add: Set<String>, remove: Set<String>) -> Unit,
    onSetBulkDeleteConfirm: (List<AppRow>?) -> Unit,
    onStartBulkDelete: (BulkDeleteBreakdown) -> Unit,
) {
    bulkTagTarget?.let { targets ->
        val sel = remember(targets) { targets.map { it.installed.packageName }.toSet() }
        val tagsOnSelection = remember(targets, userTags) {
            sel.flatMap { userTags[it].orEmpty() }.toSortedSet().toList()
        }
        BulkTagDialog(
            count = targets.size,
            tagsOnSelection = tagsOnSelection,
            allExistingTags = UserTagsStore.allTags(userTags),
            onDismiss = { onSetBulkTagTarget(null) },
            onApply = { add, remove -> onApplyTags(targets, add, remove) },
        )
    }
    bulkDeleteConfirm?.let { rows ->
        val breakdown = remember(rows) { buildBulkDeleteBreakdown(rows, sizeOf) }
        BulkDeleteConfirmDialog(
            breakdown = breakdown,
            onDismiss = { onSetBulkDeleteConfirm(null) },
            onConfirm = {
                onSetBulkDeleteConfirm(null)
                onStartBulkDelete(breakdown)
            },
        )
    }
    bulkTally?.let { tally ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Deleting\u2026") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { if (tally.total == 0) 0f else tally.processed.toFloat() / tally.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${tally.processed} of ${tally.total} processed")
                    if (!bulkSilentActive && interactivePending) {
                        Text(
                            "Confirm each Winlator / app removal as prompted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/**
 * Self-contained host for selection-mode bulk delete/tag. Owns the delete run's execution state and
 * its per-item interactive uninstall launchers (Winlator round-trip / Android system uninstall),
 * which have no silent native path. JoiPlay games are folder-deleted silently up front. Kept out of
 * the InstalledScreen composable so that method stays within the JVM 64 KB limit.
 */
@Composable
internal fun BulkActionsHost(
    bulkTagTarget: List<AppRow>?,
    bulkDeleteConfirm: List<AppRow>?,
    userTags: Map<String, Set<String>>,
    joiPlaySizeInfo: Map<String, JoiPlayScanner.SizeInfo>,
    onClearTagTarget: () -> Unit,
    onClearDeleteConfirm: () -> Unit,
    onUserTagsChanged: (Map<String, Set<String>>) -> Unit,
    onRescan: () -> Unit,
    onSnack: (String) -> Unit,
    onSelectionClear: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tally by remember { mutableStateOf<BulkTally?>(null) }
    var interactiveQueue by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var silentActive by remember { mutableStateOf(false) }

    val finalize: () -> Unit = {
        onRescan()
        GameStorageSizeWork.enqueueImmediate(context.applicationContext)
        tally?.let { onSnack(bulkDeleteSummary(it)) }
        tally = null
        interactiveQueue = emptyList()
        onSelectionClear()
    }
    val advance: (Boolean) -> Unit = { failed ->
        tally = tally?.advanced(failed)
        val rest = interactiveQueue.drop(1)
        interactiveQueue = rest
        if (rest.isEmpty()) finalize()
    }

    val winlatorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val parsed = WinlatorApi.parseResult(result.resultCode, result.data)
        val head = interactiveQueue.firstOrNull()
        if (head?.installed?.source == AppSource.Managed &&
            parsed is WinlatorApi.OperationResult.Success
        ) {
            scope.launch {
                val deleted = runCatching {
                    deleteManagedGameFilesAndRecord(context.applicationContext, head.installed)
                }.getOrDefault(false)
                advance(!deleted)
            }
        } else {
            advance(parsed is WinlatorApi.OperationResult.Failure)
        }
    }
    val androidLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        advance(result.resultCode != Activity.RESULT_OK)
    }

    // Drains the interactive queue one item at a time, launching each per-item uninstall.
    LaunchedEffect(interactiveQueue.firstOrNull()?.installed?.packageName) {
        val head = interactiveQueue.firstOrNull() ?: return@LaunchedEffect
        when (head.installed.source) {
            AppSource.Managed -> {
                val gid = head.installed.winlatorGameId
                if (gid != null && WinlatorClient.isInstalled(context.applicationContext)) {
                    runCatching { winlatorLauncher.launch(WinlatorApi.delete(gid)) }
                        .onFailure { advance(true) }
                } else {
                    val deleted = runCatching {
                        deleteManagedGameFilesAndRecord(context.applicationContext, head.installed)
                    }.getOrDefault(false)
                    advance(!deleted)
                }
            }
            AppSource.Winlator -> {
                val gid = head.installed.winlatorGameId
                if (gid == null) {
                    advance(true)
                    return@LaunchedEffect
                }
                runCatching { winlatorLauncher.launch(WinlatorApi.delete(gid)) }
                    .onFailure { advance(true) }
            }
            AppSource.Android -> {
                val intent = Intent(
                    Intent.ACTION_UNINSTALL_PACKAGE,
                    Uri.parse("package:${head.installed.packageName}"),
                ).putExtra(Intent.EXTRA_RETURN_RESULT, true)
                runCatching { androidLauncher.launch(intent) }.onFailure { advance(true) }
            }
            AppSource.JoiPlay -> advance(true)
            AppSource.Kirikiroid -> advance(true)
        }
    }

    InstalledBulkActionDialogs(
        bulkTagTarget = bulkTagTarget,
        bulkDeleteConfirm = bulkDeleteConfirm,
        bulkTally = tally,
        bulkSilentActive = silentActive,
        interactivePending = interactiveQueue.isNotEmpty(),
        userTags = userTags,
        sizeOf = { r ->
            effectiveInstalledSize(r.installed, joiPlaySizeKey(r.installed)?.let { joiPlaySizeInfo[it] })
        },
        onSetBulkTagTarget = { if (it == null) onClearTagTarget() },
        onApplyTags = { targets, add, remove ->
            val sel = targets.map { it.installed.packageName }.toSet()
            val updated = userTags.toMutableMap()
            for (pkg in sel) {
                val cur = updated[pkg].orEmpty().toMutableSet()
                cur.addAll(add)
                cur.removeAll(remove)
                if (cur.isEmpty()) updated.remove(pkg) else updated[pkg] = cur
            }
            onUserTagsChanged(updated)
            onClearTagTarget()
            scope.launch(Dispatchers.IO) { UserTagsStore.save(context.applicationContext, updated) }
            onSnack("Updated tags on ${sel.size} game${if (sel.size == 1) "" else "s"}")
            onSelectionClear()
        },
        onSetBulkDeleteConfirm = { if (it == null) onClearDeleteConfirm() },
        onStartBulkDelete = { breakdown ->
            tally = BulkTally(total = breakdown.total)
            silentActive = breakdown.silent.isNotEmpty()
            scope.launch {
                val appCtx = context.applicationContext
                for (r in breakdown.joiPlay) {
                    val name = r.installed.storageFolderName
                    val ok = if (name.isNullOrBlank()) false
                    else JoiPlayScanner.deleteFolder(appCtx, name, r.installed.storagePath)
                    if (ok) r.installed.joiPlayGameId?.let { JoiPlayBackupReader.markDeleted(appCtx, it) }
                    tally = tally?.advanced(!ok)
                }
                for (r in breakdown.kirikiroid) {
                    val path = r.installed.storagePath
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = path?.let { java.io.File(it) }
                            dir != null && dir.isDirectory && dir.deleteRecursively()
                        }.getOrDefault(false)
                    }
                    tally = tally?.advanced(!ok)
                }
                silentActive = false
                if (breakdown.interactive.isEmpty()) finalize()
                else interactiveQueue = breakdown.interactive
            }
        },
    )
}
