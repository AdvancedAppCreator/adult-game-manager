package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simple file-system folder browser — used for both source and destination so the user
 * can pick folders the SAF tree picker won't show (like /storage/emulated/0/Download/).
 *
 * Requires MANAGE_EXTERNAL_STORAGE granted (we prompt for it elsewhere). Without it,
 * File.listFiles() returns null for system folders.
 *
 * Each row is a folder. Tap the chevron (or the row's left half) to expand/collapse;
 * tap the row title to select. The selected folder is highlighted and shown at the top.
 * "Use this folder" returns the absolute path; "Cancel" closes without picking.
 *
 * Children are loaded lazily per expand to avoid walking thousands of folders.
 */
@Composable
fun FolderPickerDialog(
    initialPath: String = android.os.Environment.getExternalStorageDirectory().absolutePath,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    val wideDialog = isWideFolderPickerDialog()
    // Always root at external storage so the user can navigate anywhere.
    val storagePath = android.os.Environment.getExternalStorageDirectory().absolutePath
    val rootFile = remember { File(storagePath) }
    var selected by remember { mutableStateOf<File?>(File(initialPath)) }
    val expanded = remember { mutableStateMapOf<String, List<File>>() }
    val openSet = remember { mutableStateListOf<String>() }
    var rootChildren by remember { mutableStateOf<List<File>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var createFolderOpen by remember { mutableStateOf(false) }
    // Bumping this forces the LazyColumn recomposition after we mutate `expanded`
    // in-place (which mutableStateMapOf doesn't always pick up automatically for
    // value-replacement vs. key-addition).
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(initialPath) {
        val children = withContext(Dispatchers.IO) { listDirs(rootFile) }
        if (children.isEmpty() && rootFile.listFiles() == null) {
            listError = "Couldn't read $storagePath. Make sure 'All files access' is granted."
        }
        rootChildren = children
        openSet.add(storagePath)
        expanded[storagePath] = children

        // Auto-expand the path chain from root to initialPath so it's visible.
        if (initialPath != storagePath) {
            val target = File(initialPath)
            if (target.isDirectory) {
                val chain = mutableListOf<File>()
                var cursor: File? = target
                while (cursor != null && cursor.absolutePath != storagePath) {
                    chain.add(0, cursor)
                    cursor = cursor.parentFile
                }
                for (dir in chain) {
                    val dirPath = dir.absolutePath
                    if (dirPath !in openSet) openSet.add(dirPath)
                    if (dirPath !in expanded) {
                        val dirChildren = withContext(Dispatchers.IO) { listDirs(dir) }
                        expanded[dirPath] = dirChildren
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("Pick a folder") },
        text = {
            val detailsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = selected?.absolutePath ?: "(none)",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { createFolderOpen = true },
                        enabled = selected != null && selected!!.isDirectory && selected!!.canWrite(),
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null,
                             modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New folder here")
                    }
                    listError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            val treePane: @Composable (Modifier) -> Unit = { modifier ->
                if (rootChildren == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading folders…")
                    }
                } else {
                    val flat = remember(openSet.toList(), expanded.toMap(), refreshTick) {
                        flatten(rootFile, expanded, openSet.toSet(), depth = 0)
                    }
                    LazyColumn(modifier = modifier) {
                        items(flat) { (depth, dir) ->
                            FolderRow(
                                dir = dir,
                                depth = depth,
                                isExpanded = dir.absolutePath in openSet,
                                isSelected = selected?.absolutePath == dir.absolutePath,
                                onSelect = { selected = dir },
                                onToggle = {
                                    val path = dir.absolutePath
                                    if (path in openSet) {
                                        openSet.remove(path)
                                    } else {
                                        openSet.add(path)
                                        if (path !in expanded) {
                                            expanded[path] = listDirs(dir)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (wideDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    treePane(Modifier.weight(1f).fillMaxHeight())
                    Column(modifier = Modifier.width(320.dp), content = { detailsPane() })
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    detailsPane()
                    Spacer(Modifier.height(6.dp))
                    treePane(Modifier.fillMaxWidth().heightIn(max = 420.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onPick(it.absolutePath) } },
                enabled = selected != null && selected!!.isDirectory,
            ) { Text("Use this folder") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )

    if (createFolderOpen) {
        var newName by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { createFolderOpen = false },
            title = { Text("New folder") },
            text = {
                Column {
                    Text("Inside ${selected?.absolutePath ?: "?"}", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; errorMsg = null },
                        label = { Text("Folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    errorMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parent = selected ?: return@TextButton
                        val name = newName.trim()
                        if (name.isBlank() || name.contains('/') || name.contains('\\')) {
                            errorMsg = "Invalid folder name."
                            return@TextButton
                        }
                        val newDir = File(parent, name)
                        if (newDir.exists()) {
                            errorMsg = "A folder with that name already exists."
                            return@TextButton
                        }
                        if (!newDir.mkdir()) {
                            errorMsg = "Couldn't create folder (permission?)."
                            return@TextButton
                        }
                        // Refresh the parent's children, expand it, and select the new folder.
                        expanded[parent.absolutePath] = listDirs(parent)
                        if (parent.absolutePath !in openSet) openSet.add(parent.absolutePath)
                        selected = newDir
                        refreshTick++
                        createFolderOpen = false
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { createFolderOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun isWideFolderPickerDialog(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= 700 || configuration.screenWidthDp > configuration.screenHeightDp
}

@Composable
private fun FolderRow(
    dir: File,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val bg = if (isSelected)
        MaterialTheme.colorScheme.secondaryContainer
    else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(start = (depth * 14).dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = run {
                val isRoot = dir.absolutePath == android.os.Environment.getExternalStorageDirectory().absolutePath
                if (isRoot) "Internal storage" else dir.name.ifBlank { dir.absolutePath }
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { onSelect() },
        )
    }
}

private fun listDirs(parent: File): List<File> {
    return runCatching {
        (parent.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())
}

private fun flatten(
    root: File,
    expanded: Map<String, List<File>>,
    openSet: Set<String>,
    depth: Int,
): List<Pair<Int, File>> {
    val out = mutableListOf<Pair<Int, File>>()
    out.add(depth to root)
    if (root.absolutePath in openSet) {
        val children = expanded[root.absolutePath] ?: return out
        for (c in children) out.addAll(flatten(c, expanded, openSet, depth + 1))
    }
    return out
}
