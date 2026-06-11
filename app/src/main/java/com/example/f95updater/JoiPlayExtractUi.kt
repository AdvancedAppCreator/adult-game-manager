package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Composables that implement the "Install game in JoiPlay (with extract)" flow.
 *
 * The flow:
 *   1. User picks a compressed archive (SAF picker, biased to source folder if set)
 *   2. We copy it to cache, sniff format, ask for password if needed
 *   3. We extract to destination (SAF tree or File depending on storage strategy)
 *   4. We show our own folder browser of the extracted result, highlighting likely
 *      launch files (Game.exe, index.html, script.rpy, *.swf, *.py, *.sh)
 *   5. The user taps one — we hand it to JoiPlayInstaller.buildIntent()
 *
 * Each composable here is self-contained and is invoked from MainActivity.
 */

// ---------------------------- INSTALL WARNING ----------------------------

@Composable
fun JoiPlayInstallWarningDialog(
    onDismiss: () -> Unit,
    onContinue: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heads up") },
        text = {
            Column {
                Text(
                    "Continue only for games/files you trust. Adult Game Manager will hand the selected launch file to JoiPlay so JoiPlay can add it to its library.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Adult Game Manager won't show this game in its own list until you re-export your JoiPlay backup and import the new .joiback here. That's a JoiPlay limitation, not ours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { dontShowAgain = !dontShowAgain }) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Text("Don't show this again")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onContinue(dontShowAgain) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------- SETTINGS DIALOG ----------------------------

@Composable
fun JoiPlaySettingsDialog(
    onDismiss: () -> Unit,
    onSourceChange: (Uri?) -> Unit,
    onDestChange: (Uri?) -> Unit,
) {
    val context = LocalContext.current
    val wideDialog = isWideJoiPlayDialog()
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<String?>(null) }
    var destUri by remember { mutableStateOf<String?>(null) }
    // Reactive permission check — re-evaluates whenever the host activity resumes
    // (i.e. when the user comes back from the system 'All files access' page).
    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                allFilesGranted = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        sourceUri = JoiPlaySettingsStore.sourceFolderUri(context)
        destUri = JoiPlaySettingsStore.extractDestUri(context)
    }

    // Custom File-API folder browser is used for both source and destination. Without
    // MANAGE_EXTERNAL_STORAGE we can't read system folders, so on first attempt we
    // prompt the user to grant it.
    var folderPickerFor by remember { mutableStateOf<String?>(null) }  // "source" or "dest"
    var permissionPromptFor by remember { mutableStateOf<String?>(null) }  // same — buffered until grant
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("JoiPlay settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Used by 'Install game in JoiPlay' to find compressed archives and extract them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val sourceBlock: @Composable () -> Unit = {
                    SettingsBlock(
                        title = "Source folder",
                        body = "Where the file picker opens when you choose an archive. Leave unset to pick on-demand.",
                        current = sourceUri,
                        onPick = {
                            if (hasAllFilesAccess()) folderPickerFor = "source"
                            else permissionPromptFor = "source"
                        },
                        onClear = {
                            sourceUri = null
                            scope.launch { JoiPlaySettingsStore.setSourceFolderUri(context, null); onSourceChange(null) }
                        },
                    )
                }
                val destBlock: @Composable () -> Unit = {
                    SettingsBlock(
                        title = "Destination folder",
                        body = "Where extracted games are saved. Each archive gets its own subfolder. Leave unset to pick on-demand.",
                        current = destUri,
                        onPick = {
                            if (hasAllFilesAccess()) folderPickerFor = "dest"
                            else permissionPromptFor = "dest"
                        },
                        onClear = {
                            destUri = null
                            scope.launch { JoiPlaySettingsStore.setExtractDestUri(context, null); onDestChange(null) }
                        },
                    )
                }
                if (wideDialog) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) { sourceBlock() }
                        Column(Modifier.weight(1f)) { destBlock() }
                    }
                } else {
                    sourceBlock()
                    destBlock()
                }

                // Permission banner — required by the folder pickers below.
                Surface(
                    color = if (allFilesGranted) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (allFilesGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (allFilesGranted) "All files access granted"
                                else "All files access required",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (allFilesGranted)
                                "Adult Game Manager can read and write to any folder on internal storage. " +
                                        "Revoke any time via Settings \u203a Apps \u203a Adult Game Manager \u203a Permissions \u203a All files access."
                            else
                                "Only the file-based JoiPlay/archive tools need this. It lets the app browse your Download/game folders and extract archives there. Leave it off if you don't use those tools.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { requestAllFilesAccess(context) }) {
                            Text(if (allFilesGranted) "Manage in settings" else "Grant permission")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    folderPickerFor?.let { which ->
        FolderPickerDialog(
            // Use the actual external storage dir (handles Secure Folder profile id 150, etc.)
            initialPath = android.os.Environment.getExternalStorageDirectory().absolutePath,
            onCancel = { folderPickerFor = null },
            onPick = { absolutePath ->
                val file = File(absolutePath)
                // Validate access for the role.
                val problem = when (which) {
                    "source" -> if (!file.canRead()) "Can't read $absolutePath" else null
                    "dest" -> when {
                        !file.canWrite() -> "Can't write to $absolutePath"
                        !file.canRead() -> "Can't read $absolutePath"
                        else -> null
                    }
                    else -> null
                }
                if (problem != null) {
                    validationError = "$problem\n\nMake sure 'All files access' is granted to Adult Game Manager."
                    folderPickerFor = null
                    return@FolderPickerDialog
                }
                val fileUri = Uri.fromFile(file).toString()
                scope.launch {
                    when (which) {
                        "source" -> {
                            sourceUri = fileUri
                            JoiPlaySettingsStore.setSourceFolderUri(context, fileUri)
                            onSourceChange(Uri.parse(fileUri))
                        }
                        "dest" -> {
                            destUri = fileUri
                            JoiPlaySettingsStore.setExtractDestUri(context, fileUri)
                            onDestChange(Uri.parse(fileUri))
                        }
                    }
                    folderPickerFor = null
                }
            },
        )
    }
    permissionPromptFor?.let { which ->
        AlertDialog(
            onDismissRequest = { permissionPromptFor = null },
            title = { Text("Permission needed") },
            text = {
                Text(
                    "To browse your Download/game folders directly, this tool needs 'All files access'. " +
                            "Android will open the settings page; toggle it on and come back. " +
                            "You can leave it off if you do not use JoiPlay/archive install tools."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    requestAllFilesAccess(context)
                    permissionPromptFor = null
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { permissionPromptFor = null }) { Text("Cancel") }
            },
        )
    }
    validationError?.let { msg ->
        AlertDialog(
            onDismissRequest = { validationError = null },
            title = { Text("Access error") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    validationError = null
                    requestAllFilesAccess(context)
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { validationError = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun SettingsBlock(
    title: String,
    body: String,
    current: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(body, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        // Selected path stands out in a tinted pill so the user can immediately tell
        // whether a folder is set and what it is.
        Surface(
            color = if (current != null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = current?.let { humanReadableUri(it) }
                    ?: "(not set — picks on-demand)",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (current != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current != null) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPick) { Text(if (current == null) "Pick folder…" else "Change…") }
            if (current != null) TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

private fun humanReadableUri(uri: String): String {
    if (uri.startsWith("file://")) {
        val path = Uri.decode(uri.removePrefix("file://"))
        val ext = runCatching {
            android.os.Environment.getExternalStorageDirectory().absolutePath
        }.getOrDefault("")
        val rel = if (ext.isNotEmpty() && path.startsWith(ext)) path.removePrefix(ext) else path
        return if (rel.startsWith("/")) {
            "Internal storage" + rel.replace("/", " \u203a ")
        } else "Internal storage"
    }
    // Legacy support: tree URIs from older versions.
    val decoded = Uri.decode(uri)
    val docId = decoded.substringAfterLast("/tree/").substringBefore("/document/")
    val (volume, path) = docId.split(":", limit = 2).let { p ->
        if (p.size == 2) p[0] to p[1] else "" to decoded
    }
    val volumeLabel = when (volume) {
        "primary" -> "Internal storage"
        "" -> ""
        else -> volume
    }
    val parts = mutableListOf<String>()
    if (volumeLabel.isNotEmpty()) parts.add(volumeLabel)
    if (path.isNotBlank()) parts.addAll(path.split('/').filter { it.isNotBlank() })
    val joined = parts.joinToString(" \u203a ")
    return joined.ifBlank { decoded }
}

// ---------------------------- PROGRESS DIALOG ----------------------------

@Composable
fun ExtractProgressDialog(
    archiveName: String,
    phase: JoiPlayExtractFlow.Phase,
    progress: ArchiveExtractor.Progress?,
    onCancel: () -> Unit,
) {
    val phaseLabel = when (phase) {
        JoiPlayExtractFlow.Phase.Preparing -> "Preparing"
        JoiPlayExtractFlow.Phase.Extracting -> "Extracting"
        JoiPlayExtractFlow.Phase.Cancelling -> "Cancelling"
    }
    val isCancelling = phase == JoiPlayExtractFlow.Phase.Cancelling
    AlertDialog(
        onDismissRequest = { /* not dismissible by tap-out */ },
        title = { Text("$phaseLabel $archiveName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isCancelling || (progress?.bytesTotal ?: 0L) <= 0L) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress?.percent ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val written = progress?.bytesWritten ?: 0L
                val total = progress?.bytesTotal ?: 0L
                Text(
                    text = buildString {
                        append("%,d".format(written))
                        append(" / ")
                        append(if (total > 0) "%,d".format(total) else "?")
                        append(" bytes")
                        if (total > 0) {
                            append("  •  ")
                            append("%.1f%%".format((written.toDouble() / total) * 100))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${humanSize(written)} of ${humanSize(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress?.currentEntry?.let {
                    Text(
                        if (isCancelling) "Stopping extraction and cleaning up partial files…" else it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, enabled = !isCancelling) {
                Text(if (isCancelling) "Cancelling…" else "Cancel")
            }
        },
    )
}

@Composable
fun PasswordPromptDialog(
    archiveName: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column {
                Text("'$archiveName' is encrypted.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pwd) }, enabled = pwd.isNotBlank()) { Text("Extract") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// ---------------------------- FILE BROWSER ----------------------------

enum class ExtractTargetMode { JoiPlay, ApkInstall }

private val joiPlayLaunchRanks: List<Pair<Regex, String>> = listOf(
    Regex("^game\\.exe$", RegexOption.IGNORE_CASE) to "RPG Maker",
    Regex("^index\\.html$", RegexOption.IGNORE_CASE) to "HTML / Tyrano",
    Regex("^script\\.rpy$", RegexOption.IGNORE_CASE) to "Ren'Py",
    Regex("^.*\\.(exe)$", RegexOption.IGNORE_CASE) to "Windows EXE",
    Regex("^.*\\.(swf)$", RegexOption.IGNORE_CASE) to "Flash (Ruffle)",
    Regex("^.*\\.(py)$", RegexOption.IGNORE_CASE) to "Python",
    Regex("^.*\\.(sh)$", RegexOption.IGNORE_CASE) to "Shell",
    Regex("^.*\\.(html|htm)$", RegexOption.IGNORE_CASE) to "HTML",
)

private val apkInstallRanks: List<Pair<Regex, String>> = listOf(
    Regex("^.*\\.apk$", RegexOption.IGNORE_CASE) to "Android package",
)

private fun ranksFor(mode: ExtractTargetMode): List<Pair<Regex, String>> = when (mode) {
    ExtractTargetMode.JoiPlay -> joiPlayLaunchRanks
    ExtractTargetMode.ApkInstall -> apkInstallRanks
}

// Backwards-compat name used by older callers.
private val launchFileRanks: List<Pair<Regex, String>> get() = joiPlayLaunchRanks

private data class BrowsableNode(
    val name: String,
    val isFile: Boolean,
    val safFile: DocumentFile?,
    val javaFile: File?,
    val children: List<BrowsableNode>,
    val launchHint: String?,
    val rank: Int,
) {
    val uri: Uri? get() = safFile?.uri ?: javaFile?.let { Uri.fromFile(it) }
}

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
    }
    val hint = when (mode) {
        ExtractTargetMode.JoiPlay -> "Pick the game's launch file (highlighted candidates are most likely). If you are unsure, cancel and check the game instructions first."
        ExtractTargetMode.ApkInstall -> "Pick the APK to install (highlighted). Only install APKs from sources you trust."
    }
    val confirmLabel = when (mode) {
        ExtractTargetMode.JoiPlay -> "Send to JoiPlay"
        ExtractTargetMode.ApkInstall -> "Install APK"
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
                    LazyColumn(modifier = modifier) {
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
private fun isWideJoiPlayDialog(): Boolean {
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

private fun buildTree(
    root: ArchiveExtractor.ExtractRoot,
    displayName: String,
    ranks: List<Pair<Regex, String>> = launchFileRanks,
): BrowsableNode {
    return when (root) {
        is ArchiveExtractor.ExtractRoot.Saf -> safToNode(root.doc, displayName, ranks)
        is ArchiveExtractor.ExtractRoot.FileRoot -> fileToNode(root.file, displayName, ranks)
    }
}

private fun safToNode(
    doc: DocumentFile,
    displayName: String,
    ranks: List<Pair<Regex, String>> = launchFileRanks,
): BrowsableNode {
    if (doc.isFile) {
        val rank = ranks.indexOfFirst { (re, _) -> re.matches(doc.name ?: "") }
        return BrowsableNode(
            name = displayName.ifBlank { doc.name ?: "?" },
            isFile = true, safFile = doc, javaFile = null, children = emptyList(),
            launchHint = if (rank >= 0) ranks[rank].second else null,
            rank = if (rank >= 0) rank else Int.MAX_VALUE,
        )
    }
    val children = (doc.listFiles().toList()).map { safToNode(it, it.name ?: "?", ranks) }
        .sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
    return BrowsableNode(
        name = displayName.ifBlank { doc.name ?: "?" },
        isFile = false, safFile = doc, javaFile = null, children = children,
        launchHint = null, rank = Int.MAX_VALUE,
    )
}

private fun fileToNode(
    file: File,
    displayName: String,
    ranks: List<Pair<Regex, String>> = launchFileRanks,
): BrowsableNode {
    if (file.isFile) {
        val rank = ranks.indexOfFirst { (re, _) -> re.matches(file.name) }
        return BrowsableNode(
            name = displayName.ifBlank { file.name },
            isFile = true, safFile = null, javaFile = file, children = emptyList(),
            launchHint = if (rank >= 0) ranks[rank].second else null,
            rank = if (rank >= 0) rank else Int.MAX_VALUE,
        )
    }
    val children = (file.listFiles() ?: emptyArray()).map { fileToNode(it, it.name, ranks) }
        .sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
    return BrowsableNode(
        name = displayName.ifBlank { file.name },
        isFile = false, safFile = null, javaFile = file, children = children,
        launchHint = null, rank = Int.MAX_VALUE,
    )
}

private fun findBestLaunchFile(node: BrowsableNode): BrowsableNode? {
    val all = mutableListOf<BrowsableNode>()
    fun collect(n: BrowsableNode) {
        if (n.isFile && n.launchHint != null) all.add(n)
        for (c in n.children) collect(c)
    }
    collect(node)
    return all.minByOrNull { it.rank }
}

private fun expandPathTo(root: BrowsableNode, target: BrowsableNode): Set<String> {
    val out = mutableSetOf<String>()
    fun walk(n: BrowsableNode): Boolean {
        if (n === target) return true
        for (c in n.children) {
            if (walk(c)) { out.add(n.name); return true }
        }
        return false
    }
    walk(root)
    return out
}

private fun flatten(node: BrowsableNode, expanded: Set<String>, depth: Int): List<Pair<Int, BrowsableNode>> {
    val out = mutableListOf<Pair<Int, BrowsableNode>>()
    out.add(depth to node)
    if (node.name in expanded || depth == 0) {
        for (c in node.children) out.addAll(flatten(c, expanded, depth + 1))
    }
    return out
}

// ---------------------------- HELPERS ----------------------------

internal fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/** Returns true if MANAGE_EXTERNAL_STORAGE has been granted (Android 11+).
 *  Returns true unconditionally on Android 10 and below where legacy storage applies. */
internal fun hasAllFilesAccess(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else true
}

/** Open the system Settings page for granting MANAGE_EXTERNAL_STORAGE to this app. */
internal fun requestAllFilesAccess(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
    val intent = Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(intent) }
        .onFailure {
            // Fallback to the broad list page if the per-app deeplink fails.
            val list = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(list) }
        }
}

// ---------------------------- ORCHESTRATOR ----------------------------

class JoiPlayExtractFlow(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    enum class Phase { Preparing, Extracting, Cancelling }
    var archiveName by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf(Phase.Preparing); private set
    var progress by mutableStateOf<ArchiveExtractor.Progress?>(null); private set
    var passwordPromptFor by mutableStateOf<ArchiveExtractor.Format?>(null); private set
    var extractedRoot by mutableStateOf<ArchiveExtractor.ExtractRoot?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    private var pendingArchive: File? = null
    private var pendingDestRoot: ArchiveExtractor.ExtractRoot? = null
    private var sourceArchiveFile: File? = null
    private var currentJob: Job? = null

    /** True while *anything* is in flight (copying source, extracting). UI uses this to
     *  decide whether to show the progress dialog. */
    val inProgress: Boolean
        get() = progress != null || (phase == Phase.Cancelling && archiveName != null)

    fun start(
        archiveUri: Uri,
        destRoot: ArchiveExtractor.ExtractRoot,
        deleteSourceOnSuccess: File? = null,
    ) {
        cancelInProgress()
        sourceArchiveFile = deleteSourceOnSuccess
        val quickName = archiveUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "archive"
        archiveName = quickName
        phase = Phase.Preparing
        progress = ArchiveExtractor.Progress(
            bytesWritten = 0L,
            bytesTotal = 0L,
            entriesProcessed = 0,
            entriesTotal = 1,
            currentEntry = "Preparing $quickName…",
        )
        currentJob = scope.launch {
            try {
                val name = uriDisplayName(context, archiveUri).ifBlank { "archive" }
                archiveName = name
                // Phase 1: copy SAF source to cache, with progress.
                phase = Phase.Preparing
                val totalBytes = sourceSizeBytes(context, archiveUri)
                progress = ArchiveExtractor.Progress(
                    bytesWritten = 0L,
                    bytesTotal = totalBytes,
                    entriesProcessed = 0,
                    entriesTotal = 1,
                    currentEntry = "Reading $name…",
                )
                val cached = copyToCacheWithProgress(context, archiveUri, name, totalBytes) { written ->
                    progress = ArchiveExtractor.Progress(
                        bytesWritten = written,
                        bytesTotal = if (totalBytes > 0) totalBytes else written,
                        entriesProcessed = 0,
                        entriesTotal = 1,
                        currentEntry = "Reading $name…",
                    )
                }
                val format = detectFormat(cached)
                if (format == null) {
                    progress = null
                    errorMessage = "Unknown archive format. Supported: ZIP, RAR, 7Z."
                    archiveName = null
                    return@launch
                }
                pendingArchive = cached
                pendingDestRoot = destRoot
                // Phase 2: extract.
                phase = Phase.Extracting
                runExtraction(cached, format, destRoot, password = null, suggestedName = name.substringBeforeLast('.'))
            } catch (ce: CancellationException) {
                AppLog.i("Extract", "Cancelled")
                clearInFlightState()
            } catch (t: Throwable) {
                progress = null
                errorMessage = "Could not start extraction: ${t.message}"
                archiveName = null
            }
        }
    }

    fun submitPassword(password: String) {
        val arc = pendingArchive ?: return
        val dest = pendingDestRoot ?: return
        val name = archiveName ?: "archive"
        passwordPromptFor = null
        phase = Phase.Preparing
        progress = ArchiveExtractor.Progress(0L, 0L, 0, 1, "Preparing $name…")
        currentJob = scope.launch {
            try {
                val format = detectFormat(arc) ?: return@launch
                phase = Phase.Extracting
                runExtraction(arc, format, dest, password = password.toCharArray(), suggestedName = name.substringBeforeLast('.'))
            } catch (ce: CancellationException) {
                AppLog.i("Extract", "Cancelled")
                clearInFlightState()
            }
        }
    }

    fun cancelInProgress() {
        val job = currentJob
        if (job?.isActive == true) {
            phase = Phase.Cancelling
            progress = progress ?: ArchiveExtractor.Progress(0L, 0L, 0, 1, "Cancelling…")
            passwordPromptFor = null
            job.cancel()
            return
        }
        clearInFlightState()
    }

    private fun clearInFlightState() {
        currentJob = null
        progress = null
        archiveName = null
        passwordPromptFor = null
        sourceArchiveFile = null
    }

    fun acknowledgeError() {
        errorMessage = null
    }

    fun acknowledgeResult() {
        extractedRoot = null
        archiveName = null
        progress = null
        pendingArchive?.delete()
        pendingArchive = null
        pendingDestRoot = null
    }

    private fun detectFormat(file: File): ArchiveExtractor.Format? {
        val byExt = ArchiveExtractor.detectFormatByExt(file.name)
        if (byExt != null) return byExt
        return file.inputStream().use { ArchiveExtractor.detectFormat(it) }
    }

    private suspend fun runExtraction(
        archive: File,
        format: ArchiveExtractor.Format,
        destRoot: ArchiveExtractor.ExtractRoot,
        password: CharArray?,
        suggestedName: String,
    ) {
        val outcome = try {
            ArchiveExtractor.extract(
                context, archive, format, password, destRoot, suggestedName,
            ) { p -> progress = p }
        } catch (ce: CancellationException) {
            clearInFlightState()
            throw ce
        }
        when (outcome) {
            is ArchiveExtractor.Outcome.Ok -> {
                progress = null
                extractedRoot = outcome.rootFolder
                AppLog.i("Extract", "Extracted ${outcome.bytesWritten} bytes to ${outcome.rootFolder.displayPath}")
                // If the caller asked to delete the source archive on success, do it now.
                sourceArchiveFile?.let { src ->
                    runCatching {
                        if (src.exists() && src.delete()) {
                            AppLog.i("Extract", "Deleted source archive ${src.absolutePath}")
                        } else {
                            AppLog.w("Extract", "Could not delete source archive ${src.absolutePath}")
                        }
                    }
                }
                sourceArchiveFile = null
            }
            is ArchiveExtractor.Outcome.NeedsPassword -> {
                progress = null
                passwordPromptFor = outcome.format
            }
            is ArchiveExtractor.Outcome.Failed -> {
                progress = null
                errorMessage = outcome.message
                archiveName = null
                AppLog.w("Extract", "Failed: ${outcome.message}", outcome.cause)
            }
            is ArchiveExtractor.Outcome.Cancelled -> {
                progress = null
                archiveName = null
                currentJob = null
                AppLog.i("Extract", "Cancelled")
            }
        }
    }

    private suspend fun sourceSizeBytes(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) {
                    val raw = c.getLong(idx)
                    if (raw > 0) return@withContext raw
                }
            }
        }
        0L
    }

    private suspend fun copyToCacheWithProgress(
        context: Context,
        uri: Uri,
        name: String,
        totalBytes: Long,
        onProgress: (bytesCopied: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val cache = File(context.cacheDir, "extract_in_${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            cache.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                var lastReportBytes = 0L
                var lastReportTimeNs = System.nanoTime()
                while (true) {
                    kotlinx.coroutines.yield()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    val now = System.nanoTime()
                    // Report every 256 KiB OR every 200 ms — heartbeat keeps UI moving
                    // even when SAF stalls on a large flush.
                    if (written - lastReportBytes >= 256 * 1024L ||
                        now - lastReportTimeNs >= 200_000_000L
                    ) {
                        lastReportBytes = written
                        lastReportTimeNs = now
                        onProgress(written)
                    }
                }
                onProgress(written)
            }
        } ?: error("Could not open archive uri $uri")
        cache
    }

    private fun uriDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: ""
            }
        }
        return uri.lastPathSegment ?: ""
    }
}
