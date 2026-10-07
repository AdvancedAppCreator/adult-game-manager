package com.example.f95updater

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.File

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
    var winlatorRoot by remember { mutableStateOf<String?>(null) }
    var joiplayRoot by remember { mutableStateOf<String?>(null) }
    var kirikiroidRoot by remember { mutableStateOf<String?>(null) }
    var videosRoot by remember { mutableStateOf<String?>(null) }
    var otherRoot by remember { mutableStateOf<String?>(null) }
    var autoWinlator by remember { mutableStateOf(false) }
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
        destUri = JoiPlayScanner.getRootUri(context)?.toString()
        winlatorRoot = JoiPlaySettingsStore.installRootUri(context, JoiPlaySettingsStore.InstallRoot.Winlator)
        joiplayRoot = JoiPlaySettingsStore.installRootUri(context, JoiPlaySettingsStore.InstallRoot.JoiPlay)
        kirikiroidRoot = JoiPlaySettingsStore.installRootUri(context, JoiPlaySettingsStore.InstallRoot.Kirikiroid)
        videosRoot = JoiPlaySettingsStore.installRootUri(context, JoiPlaySettingsStore.InstallRoot.Videos)
        otherRoot = JoiPlaySettingsStore.installRootUri(context, JoiPlaySettingsStore.InstallRoot.Other)
        autoWinlator = JoiPlaySettingsStore.autoUseWinlator(context)
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
        title = { Text("Non-Android game settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .dialogVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Controls where AGM starts file browsing and where extracted JoiPlay and Winlator games are stored by default.",
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
                        title = "Default destination folder",
                        body = "Where extracted JoiPlay and Winlator games are saved. Each archive gets its own subfolder. You can override this during each install.",
                        current = destUri,
                        onPick = {
                            if (hasAllFilesAccess()) folderPickerFor = "dest"
                            else permissionPromptFor = "dest"
                        },
                        onClear = {
                            destUri = null
                            scope.launch {
                                JoiPlayScanner.setRootUri(context, null)
                                onDestChange(null)
                            }
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

                HorizontalDivider()
                Text("Per-type install folders", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Where each kind of content is saved. Leave a game folder unset to use the default " +
                        "destination above. Videos and Other must be set to route those; they are never added to the library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rootBlock: @Composable (String, String, String?, JoiPlaySettingsStore.InstallRoot, (String?) -> Unit) -> Unit =
                    { title, body, current, rootKind, setLocal ->
                        SettingsBlock(
                            title = title,
                            body = body,
                            current = current,
                            onPick = {
                                if (hasAllFilesAccess()) folderPickerFor = "root:${rootKind.name}"
                                else permissionPromptFor = "root:${rootKind.name}"
                            },
                            onClear = {
                                setLocal(null)
                                scope.launch { JoiPlaySettingsStore.setInstallRootUri(context, rootKind, null) }
                            },
                        )
                    }
                rootBlock(
                    "Winlator games folder",
                    "Windows (.exe) games sent to Winlator extract here. Must be a normal internal-storage path.",
                    winlatorRoot, JoiPlaySettingsStore.InstallRoot.Winlator, { winlatorRoot = it },
                )
                rootBlock(
                    "JoiPlay games folder",
                    "Ren'Py/RPGM/HTML games sent to JoiPlay extract here.",
                    joiplayRoot, JoiPlaySettingsStore.InstallRoot.JoiPlay, { joiplayRoot = it },
                )
                rootBlock(
                    "Kirikiroid games folder",
                    "KiriKiri/KAG (.xp3) games sent to Kirikiroid2 extract here. Must be a distinct folder " +
                        "from the JoiPlay/default games folder, and a normal internal-storage path.",
                    kirikiroidRoot, JoiPlaySettingsStore.InstallRoot.Kirikiroid, { kirikiroidRoot = it },
                )
                rootBlock(
                    "Videos folder",
                    "Video collections extract here and are not added to the library.",
                    videosRoot, JoiPlaySettingsStore.InstallRoot.Videos, { videosRoot = it },
                )
                rootBlock(
                    "Other folder",
                    "Unrecognized (non-game) content extracts here and is not added to the library.",
                    otherRoot, JoiPlaySettingsStore.InstallRoot.Other, { otherRoot = it },
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            autoWinlator = !autoWinlator
                            scope.launch { JoiPlaySettingsStore.setAutoUseWinlator(context, autoWinlator) }
                        },
                ) {
                    Switch(
                        checked = autoWinlator,
                        onCheckedChange = {
                            autoWinlator = it
                            scope.launch { JoiPlaySettingsStore.setAutoUseWinlator(context, it) }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Automatically use Winlator when recommended", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Skip the Winlator-vs-JoiPlay prompt for .exe games when AGM recommends Winlator.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                                "Only file-based non-Android game tools need this. It lets AGM browse Download/game folders and extract archives there. Leave it off if you don't use those tools.",
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
                val problem = when {
                    which == "source" -> if (!file.canRead()) "Can't read $absolutePath" else null
                    which == "dest" || which.startsWith("root:") -> when {
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
                    when {
                        which == "source" -> {
                            sourceUri = fileUri
                            JoiPlaySettingsStore.setSourceFolderUri(context, fileUri)
                            onSourceChange(Uri.parse(fileUri))
                        }
                        which == "dest" -> {
                            destUri = fileUri
                            JoiPlayScanner.setRootUri(context, Uri.parse(fileUri))
                            onDestChange(Uri.parse(fileUri))
                        }
                        which.startsWith("root:") -> {
                            val rootKind = JoiPlaySettingsStore.InstallRoot.valueOf(which.removePrefix("root:"))
                            when (rootKind) {
                                JoiPlaySettingsStore.InstallRoot.Winlator -> winlatorRoot = fileUri
                                JoiPlaySettingsStore.InstallRoot.JoiPlay -> joiplayRoot = fileUri
                                JoiPlaySettingsStore.InstallRoot.Kirikiroid -> kirikiroidRoot = fileUri
                                JoiPlaySettingsStore.InstallRoot.Videos -> videosRoot = fileUri
                                JoiPlaySettingsStore.InstallRoot.Other -> otherRoot = fileUri
                            }
                            JoiPlaySettingsStore.setInstallRootUri(context, rootKind, fileUri)
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
                            "You can leave it off if you do not use non-Android archive install tools."
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
