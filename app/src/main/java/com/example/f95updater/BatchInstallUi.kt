package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Floating top banner that tracks a running bulk-install queue. Drawn over the app content (below
 * the per-item install dialogs, which live in their own windows). Passes touches through except on
 * the card itself.
 */
@Composable
fun BatchInstallBanner(
    session: BatchInstallSession,
    onCancelRemaining: () -> Unit,
    onClose: () -> Unit,
) {
    val complete = session.isComplete
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        ElevatedCard(
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.elevatedCardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DownloadForOffline, contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (complete) "Bulk install finished"
                        else "Bulk install \u2014 ${session.activePosition} of ${session.total}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!complete) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close",
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }

                LinearProgressIndicator(
                    progress = { if (session.total == 0) 0f else session.settledCount.toFloat() / session.total },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    buildString {
                        append("${session.doneCount} done")
                        if (session.failedCount > 0) append(", ${session.failedCount} failed")
                        if (session.skippedCount > 0) append(", ${session.skippedCount} skipped")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .dialogVerticalScroll(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    session.items.forEach { item -> BatchItemRow(item) }
                }

                if (!complete) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onCancelRemaining) { Text("Cancel remaining") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchItemRow(item: BatchItem) {
    val (icon, tint) = when (item.status) {
        BatchItemStatus.Done -> Icons.Default.CheckCircle to Color(0xFF43A047)
        BatchItemStatus.Failed -> Icons.Default.Error to MaterialTheme.colorScheme.error
        BatchItemStatus.Skipped -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        BatchItemStatus.Active -> Icons.Default.DownloadForOffline to MaterialTheme.colorScheme.primary
        BatchItemStatus.Queued -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (item.status == BatchItemStatus.Active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val detail = batchItemDetail(item)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            BatchInstall.kindLabel(item.kind),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun batchItemDetail(item: BatchItem): String? = when (val decision = item.decision) {
    is BatchInstallDecision.Upgrade -> "Updates ${decision.targetLabel}"
    BatchInstallDecision.InstallAsNew -> "Installing as new"
    BatchInstallDecision.Skip -> item.note ?: "Skipped by you"
    null -> item.note
}

/** Scan + mode choice: shown while every picked file is classified, before anything is installed. */
@Composable
fun BatchPreflightDialog(
    preflight: BatchInstallPreflight,
    onChooseAutomatic: () -> Unit,
    onChooseReview: () -> Unit,
    onCancel: () -> Unit,
) {
    val scanning = preflight.scanning
    AlertDialog(
        onDismissRequest = { if (scanning) onCancel() },
        title = {
            Text(if (scanning) "Checking ${preflight.total} files\u2026" else "Install ${preflight.installableCount} games?")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (scanning) {
                    LinearProgressIndicator(
                        progress = {
                            if (preflight.total == 0) 0f
                            else preflight.scanned.toFloat() / preflight.total
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${preflight.scanned} of ${preflight.total} inspected. AGM reads each archive " +
                            "before installing anything so it can ask about updates up front.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val decisions = preflight.decisionItems.size
                    val blocked = preflight.items.count { !it.installable }
                    Text(
                        "Automatic mode uses configured destinations, detected compatible runners, " +
                            "saved passwords, and your delete-source preference. It pauses instead of guessing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (decisions > 0) {
                        Text(
                            "$decisions file(s) match an installed game. AGM asks about each one before " +
                                "the queue starts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (blocked > 0) {
                        Text(
                            "$blocked file(s) cannot be installed and are already marked as skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .dialogVerticalScroll(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    preflight.items.forEach { item -> BatchPreflightRow(item) }
                }
            }
        },
        confirmButton = {
            if (!scanning) {
                TextButton(onClick = onChooseAutomatic) { Text("Continue automatically") }
            }
        },
        dismissButton = {
            Row {
                if (!scanning) TextButton(onClick = onChooseReview) { Text("Review individually") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun BatchPreflightRow(item: BatchPreflightItem) {
    val (icon, tint) = when {
        !item.installable -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        item.needsDecision -> Icons.Default.Error to MaterialTheme.colorScheme.primary
        else -> Icons.Default.CheckCircle to Color(0xFF43A047)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val detail = when {
                item.note != null -> item.note
                item.needsDecision -> "Matches ${item.targets.first().label}"
                else -> null
            }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            BatchInstall.kindLabel(item.kind),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One up-front decision for an archive that proved it updates an installed game. Every exit path
 * settles the item, so the preflight can never stall waiting for an answer that never comes.
 */
@Composable
fun BatchUpgradeDecisionDialog(
    item: BatchPreflightItem,
    position: Int,
    total: Int,
    onUpgrade: (BatchUpgradeTarget) -> Unit,
    onInstallAsNew: () -> Unit,
    onSkip: () -> Unit,
    onSkipAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Update an installed game? ($position of $total)") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).dialogVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose the installed game this archive replaces, install it as a new game, or " +
                        "skip it. Nothing is installed until every file has an answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.targets.forEach { target ->
                    ElevatedCard(
                        onClick = { onUpgrade(target) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("Update ${target.label}", fontWeight = FontWeight.SemiBold)
                            if (target.storagePath.isNotBlank()) {
                                Text(
                                    target.storagePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                target.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onInstallAsNew) { Text("Install as new") } },
        dismissButton = {
            Row {
                if (total > 1) TextButton(onClick = onSkipAll) { Text("Skip all") }
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        },
    )
}
