package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ManagedRunnerSelectionDialog(
    title: String,
    inspection: ManagedGameInspection,
    onConfirm: (enabled: Set<ManagedRunnerKind>, default: ManagedRunnerKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val compatible = remember(inspection) {
        inspection.candidates.filter { it.compatible && it.binding != null }.mapTo(linkedSetOf()) { it.kind }
    }
    var enabled by remember(inspection) { mutableStateOf<Set<ManagedRunnerKind>>(compatible) }
    var defaultRunner by remember(inspection) {
        mutableStateOf(
            inspection.recommendedRunner.takeIf { it in compatible }
                ?: compatible.firstOrNull()
                ?: ManagedRunnerKind.JoiPlay,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Execution engines") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    inspection.root.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "AGM will own this exact folder. Deleting the game later permanently deletes this folder. " +
                        "Select every engine that should be offered under Execute with.",
                    style = MaterialTheme.typography.bodySmall,
                )
                inspection.candidates.forEach { candidate ->
                    val selectable = candidate.binding != null
                    val checked = candidate.kind in enabled
                    Surface(
                        color = if (checked) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Checkbox(
                                checked = checked,
                                enabled = selectable,
                                onCheckedChange = { selected ->
                                    enabled = if (selected) enabled + candidate.kind else enabled - candidate.kind
                                    if (defaultRunner !in enabled) {
                                        defaultRunner = enabled.firstOrNull() ?: defaultRunner
                                    }
                                },
                            )
                            Column(modifier = Modifier.weight(1f).padding(top = 5.dp)) {
                                Text(
                                    candidate.kind.displayName(),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    candidate.explanation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (selectable && !candidate.available) {
                                    Text(
                                        "The runtime must be installed before this option can execute.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            RadioButton(
                                selected = checked && defaultRunner == candidate.kind,
                                enabled = checked,
                                onClick = { defaultRunner = candidate.kind },
                            )
                        }
                    }
                }
                Text(
                    "The radio button selects the default Play action.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled.isNotEmpty() && defaultRunner in enabled,
                onClick = { onConfirm(enabled, defaultRunner) },
            ) { Text("Add game") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun ManagedRunnerKind.displayName(): String = when (this) {
    ManagedRunnerKind.JoiPlay -> "JoiPlay"
    ManagedRunnerKind.Winlator -> "Winlator"
    ManagedRunnerKind.Kirikiroid -> "Kirikiroid"
}
