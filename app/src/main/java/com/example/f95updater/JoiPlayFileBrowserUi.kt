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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ExtractedFileBrowser(
    rootName: String,
    root: ArchiveExtractor.ExtractRoot,
    onCancel: () -> Unit,
    onPick: (Uri) -> Unit,
    mode: ExtractTargetMode = ExtractTargetMode.JoiPlay,
) {
    val context = LocalContext.current
    val wideDialog = isWideJoiPlayDialog()
    var tree by remember { mutableStateOf<BrowsableNode?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<BrowsableNode?>(null) }

    LaunchedEffect(root, mode) {
        val node = withContext(Dispatchers.IO) { buildTree(root, rootName, ranksFor(mode)) }
        tree = node
        // Auto-select the highest-ranked launch file
        val auto = node?.let { findBestLaunchFile(it) }
        selected = auto
        // Expand the path leading to the auto-pick
        if (auto != null) {
            expanded = expandPathTo(node, auto)
        }
    }

    val title = when (mode) {
        ExtractTargetMode.JoiPlay -> "Send a file to JoiPlay"
        ExtractTargetMode.ApkInstall -> "Install an APK"
        ExtractTargetMode.Winlator -> "Add a Windows game"
        ExtractTargetMode.Managed -> "Choose the primary launch file"
    }
    val hint = when (mode) {
        ExtractTargetMode.JoiPlay -> "Pick the game's launch file (highlighted candidates are most likely). If you are unsure, cancel and check the game instructions first."
        ExtractTargetMode.ApkInstall -> "Pick the APK to install (highlighted). Only install APKs from sources you trust."
        ExtractTargetMode.Winlator -> "Pick the Windows executable. AGM will then ask whether it is a portable game or an installer."
        ExtractTargetMode.Managed ->
            "Pick the game's primary launch file. AGM will validate the folder and offer every compatible execution engine."
    }
    val confirmLabel = when (mode) {
        ExtractTargetMode.JoiPlay -> "Send to JoiPlay"
        ExtractTargetMode.ApkInstall -> "Install APK"
        ExtractTargetMode.Winlator -> "Continue"
        ExtractTargetMode.Managed -> "Choose engines"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text(title) },
        text = {
            val detailsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selected?.let {
                    Text("Selected: ${it.name}", fontWeight = FontWeight.SemiBold)
                }
                }
            }
            val treePane: @Composable (Modifier) -> Unit = { modifier ->
                val node = tree
                if (node == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading folder…")
                    }
                } else {
                    val flat = remember(node, expanded) { flatten(node, expanded, 0) }
                    DialogLazyColumn(modifier = modifier) {
                        items(flat) { (depth, n) ->
                            FileBrowserRow(
                                node = n,
                                depth = depth,
                                isExpanded = n.name in expanded,
                                isSelected = selected === n,
                                onClick = {
                                    if (n.isFile) {
                                        selected = n
                                    } else {
                                        expanded = if (n.name in expanded) expanded - n.name else expanded + n.name
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
                    Column(Modifier.width(320.dp), content = { detailsPane() })
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    detailsPane()
                    Divider()
                    treePane(Modifier.fillMaxWidth().heightIn(max = 400.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val uri = selected?.uri
                    if (uri != null) onPick(uri) else onCancel()
                },
                enabled = selected?.isFile == true && selected?.uri != null,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Skip") } },
    )
}

@Composable
internal fun isWideJoiPlayDialog(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= 700 || configuration.screenWidthDp > configuration.screenHeightDp
}

@Composable
private fun FileBrowserRow(
    node: BrowsableNode,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val rowBg = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        node.launchHint != null -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onClick() }
            .padding(start = (depth * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!node.isFile) {
            Icon(
                if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (node.launchHint != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            node.launchHint?.let {
                Text(
                    "→ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
