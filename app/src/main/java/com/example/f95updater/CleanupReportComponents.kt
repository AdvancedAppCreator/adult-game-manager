package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ReportHeader(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun CleanupReportSectionHeader(
    title: String,
    visibleCount: Int,
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (visibleCount == totalCount) "($totalCount)" else "($visibleCount of $totalCount)",
            style = MaterialTheme.typography.labelMedium,
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
        )
    }
}

@Composable
internal fun ReportEmptyRow(text: String = "- (none)") {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun CleanupGameReportRow(
    game: CleanupGameEntry,
    onOpenFolder: ((String) -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val openablePath = cleanupGameOpenablePath(game)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (game.existenceStatus) {
                        CleanupExistenceStatus.Exists -> "Folder exists"
                        CleanupExistenceStatus.Missing -> "Folder missing"
                        CleanupExistenceStatus.Unknown -> "Folder status unknown"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (game.existenceStatus == CleanupExistenceStatus.Missing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (onOpenFolder != null) {
                IconButton(
                    onClick = { openablePath?.let(onOpenFolder) },
                    enabled = openablePath != null,
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = if (openablePath == null) {
                            "Folder unavailable"
                        } else {
                            "Open ${game.title} folder"
                        },
                    )
                }
            }
            action?.invoke()
        }
        Text(
            game.effectiveFolderPath ?: game.storagePath.orEmpty().ifBlank { "(no folder path)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun CleanupFolderReportRow(
    entry: CleanupReviewFolderEntry,
    onOpenFolder: (String) -> Unit,
) {
    CleanupPathReportRow(
        title = entry.name,
        path = entry.path,
        onOpenFolder = onOpenFolder,
    )
}

@Composable
internal fun CleanupPathReportRow(
    title: String,
    path: String,
    error: Boolean = false,
    onOpenFolder: ((String) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (onOpenFolder != null) {
                IconButton(onClick = { onOpenFolder(path) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Open $title folder")
                }
            }
        }
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
