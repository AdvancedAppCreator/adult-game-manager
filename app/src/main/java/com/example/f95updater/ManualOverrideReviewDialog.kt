package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Lets the user decide which manually-mapped games (whose automated re-match points at a
 * DIFFERENT catalog entry) should be overwritten by the automated result. Unselected games
 * keep their manual mapping.
 */
@Composable
internal fun ManualOverrideReviewDialog(
    items: List<ManualOverrideMatch>,
    noAutoResultCount: Int,
    onOverwrite: (List<ManualOverrideMatch>) -> Unit,
    onKeepAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    val selected = remember(items) {
        mutableStateMapOf<String, Boolean>().apply {
            items.forEach { put(it.row.installed.packageName, false) }
        }
    }
    val selectedCount = selected.count { it.value }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overwrite manual matches?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${items.size} manually-set game${if (items.size == 1) "" else "s"} now match a " +
                        "different catalog entry automatically. Select the ones to replace with the " +
                        "automated match. Unselected games keep their manual mapping." +
                        if (noAutoResultCount > 0) {
                            "\n\n$noAutoResultCount other manual game" +
                                if (noAutoResultCount == 1) " had no automated result found."
                                else "s had no automated result found."
                        } else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                DialogLazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items, key = { it.row.installed.packageName }) { item ->
                        val pkg = item.row.installed.packageName
                        val checked = selected[pkg] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[pkg] = !checked }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { selected[pkg] = it })
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    item.row.installed.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val currentTitle = item.current.mappedCatalogTitle
                                    .ifBlank { item.current.f95Url ?: "manual mapping" }
                                Text(
                                    "Manual: $currentTitle",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Automated: ${item.auto.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = items.filter { selected[it.row.installed.packageName] == true }
                    onOverwrite(chosen)
                },
                enabled = selectedCount > 0,
            ) { Text(if (selectedCount > 0) "Overwrite selected ($selectedCount)" else "Overwrite selected") }
        },
        dismissButton = {
            TextButton(onClick = onKeepAll) { Text("Keep all manual") }
        },
    )
}
