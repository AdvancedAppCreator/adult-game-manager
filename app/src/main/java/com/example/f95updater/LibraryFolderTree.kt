package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

sealed interface LibraryListEntry {
    val key: String

    data class Folder(
        override val key: String,
        val label: String,
        val depth: Int,
        val gameCount: Int,
        val expanded: Boolean,
    ) : LibraryListEntry

    data class Game(
        val row: AppRow,
        val depth: Int,
    ) : LibraryListEntry {
        override val key: String = "game:${row.installed.packageName}"
    }
}

internal fun buildLibraryFolderTree(
    rows: List<AppRow>,
    collapsedFolders: Set<String> = emptySet(),
    forceExpanded: Boolean = false,
): List<LibraryListEntry> {
    val roots = linkedMapOf<String, MutableLibraryFolder>()
    rows.forEach { row ->
        val location = libraryFolderLocation(row.installed)
        var node = roots.getOrPut(location.rootKey) {
            MutableLibraryFolder(location.rootKey, location.rootLabel)
        }
        location.segments.forEach { segment ->
            val childKey = "${node.key}/$segment"
            node = node.children.getOrPut(childKey) {
                MutableLibraryFolder(childKey, segment)
            }
        }
        node.games += row
    }

    val output = mutableListOf<LibraryListEntry>()
    fun append(node: MutableLibraryFolder, depth: Int) {
        val expanded = forceExpanded || node.key !in collapsedFolders
        output += LibraryListEntry.Folder(
            key = "folder:${node.key}",
            label = node.label,
            depth = depth,
            gameCount = node.gameCount(),
            expanded = expanded,
        )
        if (!expanded) return
        node.children.values
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .forEach { append(it, depth + 1) }
        node.games.forEach { output += LibraryListEntry.Game(it, depth + 1) }
    }
    roots.values
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .forEach { append(it, 0) }
    return output
}

private data class LibraryFolderLocation(
    val rootKey: String,
    val rootLabel: String,
    val segments: List<String>,
)

private class MutableLibraryFolder(
    val key: String,
    val label: String,
) {
    val children = linkedMapOf<String, MutableLibraryFolder>()
    val games = mutableListOf<AppRow>()

    fun gameCount(): Int = games.size + children.values.sumOf(MutableLibraryFolder::gameCount)
}

private fun libraryFolderLocation(app: InstalledApp): LibraryFolderLocation {
    val raw = app.storagePath?.trim()?.replace('\\', '/')?.trimEnd('/').orEmpty()
    if (raw.isBlank()) {
        return if (app.source == AppSource.Android) {
            LibraryFolderLocation("virtual:android", "Android apps", emptyList())
        } else {
            LibraryFolderLocation("virtual:no-path", "No folder path", emptyList())
        }
    }

    Regex("^/storage/emulated/\\d+/?(.*)$").matchEntire(raw)?.let { match ->
        return LibraryFolderLocation(
            rootKey = "primary",
            rootLabel = "Internal storage",
            segments = pathSegments(match.groupValues[1]),
        )
    }
    Regex("^/storage/self/primary/?(.*)$").matchEntire(raw)?.let { match ->
        return LibraryFolderLocation(
            rootKey = "primary",
            rootLabel = "Internal storage",
            segments = pathSegments(match.groupValues[1]),
        )
    }
    Regex("^([A-Za-z]:)/?(.*)$").matchEntire(raw)?.let { match ->
        val drive = match.groupValues[1].uppercase()
        return LibraryFolderLocation(
            rootKey = "drive:$drive",
            rootLabel = drive,
            segments = pathSegments(match.groupValues[2]),
        )
    }
    if (raw.startsWith('/')) {
        return LibraryFolderLocation(
            rootKey = "absolute",
            rootLabel = "Filesystem",
            segments = pathSegments(raw),
        )
    }
    return LibraryFolderLocation(
        rootKey = "relative",
        rootLabel = "Relative paths",
        segments = pathSegments(raw),
    )
}

private fun pathSegments(path: String): List<String> =
    path.split('/').map(String::trim).filter(String::isNotEmpty)

@Composable
internal fun LibraryFolderHeader(
    folder: LibraryListEntry.Folder,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (folder.depth * 14).dp, top = 3.dp, end = 4.dp, bottom = 1.dp)
            .clickable(onClick = onToggle),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                folder.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                folder.gameCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (folder.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (folder.expanded) {
                    "Collapse ${folder.label}"
                } else {
                    "Expand ${folder.label}"
                },
            )
        }
    }
}
