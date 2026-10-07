package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AmbiguousCatalogDialog(
    item: AmbiguousCatalogMatch,
    onPick: (CatalogGame) -> Unit,
    onNone: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose catalog match") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .dialogVerticalScroll()
            ) {
                Text(item.row.installed.label, style = MaterialTheme.typography.titleSmall)
                Text(item.row.installed.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Multiple catalog titles matched (${item.via}). Pick the correct thread, or choose None.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (game in item.candidates) {
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(game) },
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(game.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium)
                                val sub = buildString {
                                    game.version?.takeIf { it.isNotBlank() }?.let { append(it) }
                                    game.creator?.takeIf { it.isNotBlank() }?.let {
                                        if (isNotEmpty()) append(" \u2022 ")
                                        append(it)
                                    }
                                    if (isNotEmpty()) append(" \u2022 ")
                                    append(game.source.sourceDisplayName).append(" id ").append(game.sourceId ?: game.thread_id.toString())
                                }
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNone) { Text("None / not in catalog") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("Skip") }
                TextButton(onClick = onDismiss) { Text("Close all") }
            }
        },
    )
}

@Composable
internal fun UnmappedReviewDialog(
    items: List<AmbiguousCatalogMatch>,
    alreadyMatchedItems: List<AlreadyMatchedCatalogMatch>,
    onDismiss: () -> Unit,
    onDismissAlreadyMatched: () -> Unit,
    onKeepAlreadyMatched: () -> Unit,
    onPick: (AmbiguousCatalogMatch, CatalogGame) -> Unit,
    onNone: (AmbiguousCatalogMatch) -> Unit,
    onSearch: (AmbiguousCatalogMatch) -> Unit,
    onAddManualMatches: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review unmapped games") },
        text = {
            if (items.isEmpty() && alreadyMatchedItems.isEmpty()) {
                Text(
                    "No unmapped games are currently listed. Use Add manually mapped matches to review manual mappings here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                DialogLazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (alreadyMatchedItems.isNotEmpty()) {
                        item("already-matched-header") {
                            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Already matched (${alreadyMatchedItems.size})", fontWeight = FontWeight.Bold)
                                    Text(
                                        "These look like games you already mapped before. Keep all to apply the previous catalog choices and remove them from this review.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(onClick = onKeepAlreadyMatched) { Text("Keep all already matched") }
                                        TextButton(onClick = onDismissAlreadyMatched) { Text("Dismiss all") }
                                    }
                                }
                            }
                        }
                        items(alreadyMatchedItems, key = { "already-${it.item.row.installed.packageName}" }) { already ->
                            val item = already.item
                            val game = already.keptGame
                            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text(item.row.installed.label, fontWeight = FontWeight.Bold)
                                    Text(item.row.installed.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "Previously mapped to: ${game.title.ifBlank { "(untitled)" }} • ${game.source.sourceDisplayName} id ${game.sourceId ?: game.thread_id.toString()}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = { onPick(item, game) }) { Text("Keep") }
                                        TextButton(onClick = { onSearch(item) }) { Text("Search") }
                                    }
                                }
                            }
                        }
                        if (items.isNotEmpty()) {
                            item("unmatched-header") {
                                Text(
                                    "New / still unmatched (${items.size})",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    items(items, key = { it.row.installed.packageName }) { item ->
                        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(item.row.installed.label, fontWeight = FontWeight.Bold)
                                Text(item.row.installed.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.candidates.isEmpty()) {
                                    Text("No safe suggestion. Search manually or mark as not in catalog.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Suggestions (${item.via})", style = MaterialTheme.typography.bodySmall)
                                    item.candidates.take(4).forEach { game ->
                                        TextButton(onClick = { onPick(item, game) }) {
                                            Text("${game.title.ifBlank { "(untitled)" }} • ${game.source.sourceDisplayName} id ${game.sourceId ?: game.thread_id.toString()}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { onSearch(item) }) { Text("Search") }
                                    TextButton(onClick = { onNone(item) }) { Text("Not in catalog") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onAddManualMatches) { Text("Add manually mapped matches", softWrap = false) }
                TextButton(onClick = onDismiss) { Text("Close", softWrap = false) }
            }
        },
    )
}
