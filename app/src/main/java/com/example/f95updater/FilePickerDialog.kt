package com.example.f95updater

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File-system *file* picker. Like [FolderPickerDialog] but you select a leaf file.
 * Folders expand/collapse; files are tappable. An optional extension allow-list
 * (lowercase, without dot) hides everything else.
 *
 * Requires MANAGE_EXTERNAL_STORAGE granted; caller is responsible for prompting.
 */
@Composable
fun FilePickerDialog(
    initialPath: String = android.os.Environment.getExternalStorageDirectory().absolutePath,
    title: String = "Pick a file",
    allowedExtensions: Set<String>? = null,
    onCancel: () -> Unit,
    onPick: (File) -> Unit,
) {
    val wideDialog = isWidePickerDialog()
    // Always root at external storage so the user can navigate anywhere.
    val storagePath = android.os.Environment.getExternalStorageDirectory().absolutePath
    val rootFile = remember { File(storagePath) }
    var selected by remember { mutableStateOf<File?>(null) }
    val expanded = remember { mutableStateMapOf<String, List<File>>() }
    val openSet = remember { mutableStateListOf<String>() }
    var rootChildren by remember { mutableStateOf<List<File>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialPath) {
        // Load root children first.
        val children = withContext(Dispatchers.IO) { listDirEntries(rootFile, allowedExtensions) }
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
                        val dirChildren = withContext(Dispatchers.IO) { listDirEntries(dir, allowedExtensions) }
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
        title = { Text(title) },
        text = {
            val selectionPane: @Composable () -> Unit = {
                FilePickerSelectionPane(
                    selectedText = selected?.absolutePath ?: "(no file selected)",
                    allowedExtensions = allowedExtensions,
                    listError = listError,
                )
            }
            val browserPane: @Composable (Modifier) -> Unit = { modifier ->
                if (rootChildren == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading folders…")
                    }
                } else {
                    val flat = remember(openSet.toList(), expanded.toMap()) {
                        flattenEntries(rootFile, expanded, openSet.toSet(), depth = 0)
                    }
                    LazyColumn(modifier = modifier) {
                        items(flat) { (depth, file) ->
                            FileEntryRow(
                                file = file,
                                depth = depth,
                                isExpanded = file.absolutePath in openSet,
                                isSelected = selected?.absolutePath == file.absolutePath,
                                onSelect = { if (file.isFile) selected = file },
                                onToggle = {
                                    val path = file.absolutePath
                                    if (file.isFile) return@FileEntryRow
                                    if (path in openSet) {
                                        openSet.remove(path)
                                    } else {
                                        openSet.add(path)
                                        if (path !in expanded) {
                                            expanded[path] = listDirEntries(file, allowedExtensions)
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
                    browserPane(Modifier.weight(1f).fillMaxHeight())
                    Column(modifier = Modifier.width(300.dp), content = { selectionPane() })
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    selectionPane()
                    browserPane(Modifier.fillMaxWidth().heightIn(max = 450.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onPick(it) } },
                enabled = selected != null && selected!!.isFile,
            ) { Text("Use this file") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Scoped-storage file picker backed by a user-granted SAF folder tree.
 * Keeps the app's own picker UI while avoiding broad All files access.
 */
@Composable
fun ScopedFilePickerDialog(
    rootUri: Uri,
    title: String = "Pick a file",
    allowedExtensions: Set<String>? = null,
    onCancel: () -> Unit,
    onPick: (Uri, String) -> Unit,
) {
    val wideDialog = isWidePickerDialog()
    val context = LocalContext.current
    val root = remember(rootUri) { DocumentFile.fromTreeUri(context, rootUri) }
    var selected by remember { mutableStateOf<DocumentFile?>(null) }
    val expanded = remember { mutableStateMapOf<String, List<DocumentFile>>() }
    val openSet = remember { mutableStateListOf<String>() }
    var rootChildren by remember { mutableStateOf<List<DocumentFile>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rootUri) {
        selected = null
        expanded.clear()
        openSet.clear()
        listError = null
        val rootDoc = root
        if (rootDoc == null || !rootDoc.isDirectory) {
            listError = "Couldn't open the selected folder. Pick the folder that contains your .joiback file."
            rootChildren = emptyList()
            return@LaunchedEffect
        }
        val key = rootDoc.uri.toString()
        val children = withContext(Dispatchers.IO) { listDocumentEntries(rootDoc, allowedExtensions) }
        rootChildren = children
        openSet.add(key)
        expanded[key] = children
        if (children.isEmpty()) {
            listError = "No matching files found in this folder. Pick the folder containing your .joiback backup."
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text(title) },
        text = {
            val selectionPane: @Composable () -> Unit = {
                FilePickerSelectionPane(
                    selectedText = selected?.name ?: "(no file selected)",
                    allowedExtensions = allowedExtensions,
                    listError = listError,
                )
            }
            val browserPane: @Composable (Modifier) -> Unit = { modifier ->
                if (rootChildren == null || root == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading folder…")
                    }
                } else {
                    val flat = remember(openSet.toList(), expanded.toMap(), rootUri) {
                        flattenDocumentEntries(root, expanded, openSet.toSet(), depth = 0)
                    }
                    LazyColumn(modifier = modifier) {
                        items(flat) { (depth, file) ->
                            DocumentEntryRow(
                                file = file,
                                depth = depth,
                                isExpanded = file.uri.toString() in openSet,
                                isSelected = selected?.uri == file.uri,
                                onSelect = { if (file.isFile) selected = file },
                                onToggle = {
                                    val key = file.uri.toString()
                                    if (file.isFile) return@DocumentEntryRow
                                    if (key in openSet) {
                                        openSet.remove(key)
                                    } else {
                                        openSet.add(key)
                                        if (key !in expanded) {
                                            expanded[key] = listDocumentEntries(file, allowedExtensions)
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
                    browserPane(Modifier.weight(1f).fillMaxHeight())
                    Column(modifier = Modifier.width(300.dp), content = { selectionPane() })
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    selectionPane()
                    browserPane(Modifier.fillMaxWidth().heightIn(max = 450.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let { onPick(it.uri, it.name ?: it.uri.toString()) }
                },
                enabled = selected?.isFile == true,
            ) { Text("Use this file") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun isWidePickerDialog(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= 700 || configuration.screenWidthDp > configuration.screenHeightDp
}

@Composable
private fun FilePickerSelectionPane(
    selectedText: String,
    allowedExtensions: Set<String>?,
    listError: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selectedText,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        allowedExtensions?.takeIf { it.isNotEmpty() }?.let {
            Text(
                "Showing only: " + it.joinToString(" / ") { e -> ".$e" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        listError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FileEntryRow(
    file: File,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
             else androidx.compose.ui.graphics.Color.Transparent
    val ext = file.extension.lowercase()
    val fileColor = when {
        file.isDirectory -> MaterialTheme.colorScheme.primary
        ext in archiveExtensions -> MaterialTheme.colorScheme.tertiary
        ext in launchExtensionHints -> MaterialTheme.colorScheme.error
        ext in mediaExtensions -> androidx.compose.ui.graphics.Color(0xFF66BB6A) // green
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(start = (depth * 14).dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (file.isDirectory) {
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
                contentDescription = null, modifier = Modifier.size(18.dp),
                tint = fileColor,
            )
        } else {
            Spacer(Modifier.width(30.dp))
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null, modifier = Modifier.size(18.dp),
                tint = fileColor,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = run {
                val isRoot = file.absolutePath == android.os.Environment.getExternalStorageDirectory().absolutePath
                if (isRoot) "Internal storage" else file.name.ifBlank { file.absolutePath }
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (file.isDirectory || isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = fileColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { if (file.isDirectory) onToggle() else onSelect() },
        )
    }
}

private val launchExtensionHints = setOf("exe", "html", "rpy", "swf", "py", "sh", "jgp")
private val archiveExtensions = setOf("apk", "zip", "rar", "7z", "tar", "gz", "xapk")
private val mediaExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "mp4", "webm", "ogg", "mp3")

private fun listDirEntries(parent: File, allowedExtensions: Set<String>?): List<File> {
    return runCatching {
        (parent.listFiles() ?: emptyArray())
            .filter { f ->
                if (f.name.startsWith(".")) return@filter false
                if (f.isDirectory) return@filter true
                if (allowedExtensions == null) return@filter true
                f.extension.lowercase() in allowedExtensions
            }
            .sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
    }.getOrDefault(emptyList())
}

private fun flattenEntries(
    root: File,
    expanded: Map<String, List<File>>,
    openSet: Set<String>,
    depth: Int,
): List<Pair<Int, File>> {
    val out = mutableListOf<Pair<Int, File>>()
    out.add(depth to root)
    if (root.absolutePath in openSet) {
        val children = expanded[root.absolutePath] ?: return out
        for (c in children) {
            if (c.isDirectory) out.addAll(flattenEntries(c, expanded, openSet, depth + 1))
            else out.add((depth + 1) to c)
        }
    }
    return out
}

@Composable
private fun DocumentEntryRow(
    file: DocumentFile,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
             else androidx.compose.ui.graphics.Color.Transparent
    val ext = file.name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
    val fileColor = when {
        file.isDirectory -> MaterialTheme.colorScheme.primary
        ext in archiveExtensions -> MaterialTheme.colorScheme.tertiary
        ext in launchExtensionHints -> MaterialTheme.colorScheme.error
        ext in mediaExtensions -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(start = (depth * 14).dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (file.isDirectory) {
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
                tint = fileColor,
            )
        } else {
            Spacer(Modifier.width(30.dp))
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = fileColor,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = file.name?.ifBlank { file.uri.toString() } ?: file.uri.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (file.isDirectory || isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = fileColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable { if (file.isDirectory) onToggle() else onSelect() },
        )
    }
}

private fun listDocumentEntries(parent: DocumentFile, allowedExtensions: Set<String>?): List<DocumentFile> {
    return runCatching {
        parent.listFiles()
            .filter { f ->
                val name = f.name.orEmpty()
                if (name.startsWith(".")) return@filter false
                if (f.isDirectory) return@filter true
                if (allowedExtensions == null) return@filter true
                name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in allowedExtensions
            }
            .sortedWith(compareBy({ it.isFile }, { it.name.orEmpty().lowercase() }))
    }.getOrDefault(emptyList())
}

private fun flattenDocumentEntries(
    root: DocumentFile,
    expanded: Map<String, List<DocumentFile>>,
    openSet: Set<String>,
    depth: Int,
): List<Pair<Int, DocumentFile>> {
    val out = mutableListOf<Pair<Int, DocumentFile>>()
    out.add(depth to root)
    val key = root.uri.toString()
    if (key in openSet) {
        val children = expanded[key] ?: return out
        for (c in children) {
            if (c.isDirectory) out.addAll(flattenDocumentEntries(c, expanded, openSet, depth + 1))
            else out.add((depth + 1) to c)
        }
    }
    return out
}
