package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val PATCH_FILE_PREVIEW_LIMIT = 12

@Composable
private fun PatchDetailLines(lines: List<String>) {
    lines.forEach {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PatchFileList(title: String, files: List<String>) {
    if (files.isEmpty()) return
    Text("$title (${files.size})", style = MaterialTheme.typography.labelLarge)
    files.take(PATCH_FILE_PREVIEW_LIMIT).forEach {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (files.size > PATCH_FILE_PREVIEW_LIMIT) {
        Text(
            "…and ${files.size - PATCH_FILE_PREVIEW_LIMIT} more",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PatchInstallPreviewDialog(
    preview: PatchInstallPreview,
    acknowledged: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val override = preview.requiresOverrideAcknowledgement
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(PatchOverrideGate.title(preview)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .dialogVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (override) {
                    Text(
                        PatchOverrideGate.headline(preview),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    preview.compatibilityReason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Installed version: ${preview.installedVersion ?: "not declared by the game"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    preview.patchVersionMarker?.let {
                        Text(
                            "Patch version marker: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    preview.patchVersionHint?.let {
                        Text(
                            "File-name version hint (never proof): $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }

                Text("Patch archive", style = MaterialTheme.typography.labelLarge)
                Text(preview.archiveName, fontWeight = FontWeight.SemiBold)
                PatchDetailLines(preview.patchIdentityLines)

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Matched game", style = MaterialTheme.typography.labelLarge)
                Text(preview.targetLabel, fontWeight = FontWeight.SemiBold)
                Text(
                    preview.targetStoragePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PatchDetailLines(preview.targetIdentityLines)
                Text("Proved by", style = MaterialTheme.typography.labelLarge)
                PatchDetailLines(preview.proofs)

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Destination", style = MaterialTheme.typography.labelLarge)
                Text(
                    preview.destinationRoot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${preview.additions.size} file(s) added, ${preview.replacements.size} replaced, " +
                        "${fmtSize(preview.totalBytes)} written.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (override) {
                        PatchOverrideGate.rollbackStatement(preview)
                    } else {
                        "Every replaced file is backed up first and can be restored with Roll back " +
                            "installed patch. Your archive is never deleted."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PatchFileList("Replaces", preview.replacements)
                PatchFileList("Adds", preview.additions)

                if (override) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = acknowledged,
                                role = Role.Checkbox,
                                onValueChange = onAcknowledgedChange,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Text(
                            PatchOverrideGate.acknowledgementLabel(preview),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onInstall,
                enabled = PatchOverrideGate.mayInstall(preview, acknowledged),
            ) { Text(PatchOverrideGate.confirmLabel(preview)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun PatchRefusalDialog(report: PatchRefusalReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Patch not installed") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .dialogVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(report.archiveName, fontWeight = FontWeight.SemiBold)
                Text(report.reason, style = MaterialTheme.typography.bodyMedium)
                if (report.details.isNotEmpty()) {
                    HorizontalDivider()
                    Text("What AGM could read", style = MaterialTheme.typography.labelLarge)
                    PatchDetailLines(report.details)
                }
                Text(
                    "Nothing on disk was changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun PatchInstallResultDialog(
    result: PatchInstallResult,
    onRollback: () -> Unit,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Patch installed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${result.label} was patched with ${result.archiveName}.")
                Text(
                    result.storagePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Destination: ${result.destinationRoot}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${result.addedCount} file(s) added, ${result.replacedCount} replaced and verified.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (result.compatibilityOverridden) {
                    Text(
                        PatchOverrideGate.installedNote(result.compatibilityOverrideReason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "AGM kept a verified backup so this patch can be rolled back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = { TextButton(onClick = onRollback) { Text("Roll back now") } },
    )
}

@Composable
fun PatchRollbackOutcomeDialog(outcome: PatchRollbackOutcome, onDismiss: () -> Unit) {
    val title = when (outcome) {
        is PatchRollbackOutcome.RolledBack -> "Patch rolled back"
        is PatchRollbackOutcome.Unresolved -> "Rollback unfinished"
        is PatchRollbackOutcome.NoLongerAvailable -> "Nothing to roll back"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (outcome) {
                is PatchRollbackOutcome.RolledBack -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${outcome.label} was restored to its pre-patch state.")
                    Text(
                        outcome.storagePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${outcome.restoredCount} file(s) restored, ${outcome.removedCount} removed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is PatchRollbackOutcome.Unresolved -> Text(outcome.message)
                is PatchRollbackOutcome.NoLongerAvailable -> Text(outcome.message)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun PatchRollbackPointsDialog(
    records: List<PatchTransactionRecord>,
    onRollback: (PatchTransactionRecord) -> Unit,
    onDiscard: (PatchTransactionRecord) -> Unit,
    onRetryRollback: (PatchTransactionRecord) -> Unit,
    onForget: (PatchTransactionRecord) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Installed patches") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .dialogVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (records.isEmpty()) {
                    Text("No patch is currently installed through AGM.")
                    return@Column
                }
                records.forEach { record ->
                    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(record.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                record.archiveName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${record.added.size} added, ${record.replaced.size} replaced " +
                                    "in ${record.destinationRoot}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (record.compatibilityOverridden) {
                                Text(
                                    PatchOverrideGate.installedNote(record.compatibilityOverrideReason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            val actions = patchRecordActions(record)
                            if (record.isUsableRollbackPoint) {
                                record.unresolvedReason?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                // Never offer a destructive rollback for a record that no longer
                                // matches the library: it could only write into the wrong folder.
                                // Remove is offered only when the backup is provably expendable.
                                Text(
                                    patchRecordUnresolvedSummary(record),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    if (record.isSafeToForget) {
                                        "Its backup was already abandoned, so only the record " +
                                            "itself is left to clean up."
                                    } else {
                                        "AGM keeps the backup in ${record.backupDir} until this is " +
                                            "resolved."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                actions.forEach { action ->
                                    when (action) {
                                        PatchRecordAction.RollBack -> TextButton(
                                            onClick = { onRollback(record) },
                                        ) { Text("Roll back") }
                                        PatchRecordAction.DiscardBackup -> TextButton(
                                            onClick = { onDiscard(record) },
                                        ) { Text("Keep patch, delete backup") }
                                        PatchRecordAction.RetryRollback -> TextButton(
                                            onClick = { onRetryRollback(record) },
                                        ) { Text("Retry rollback") }
                                        PatchRecordAction.RemoveRecord -> TextButton(
                                            onClick = { onForget(record) },
                                        ) { Text("Remove this record") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
