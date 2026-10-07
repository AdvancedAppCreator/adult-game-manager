package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun FullSizeImageDialog(url: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}

@Composable
internal fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenHelp: () -> Unit,
    onShareApp: () -> Unit,
    onReportIssue: () -> Unit,
    onOpenSupport: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("About Adult Game Manager") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Android companion for tracking adult game updates across installed APKs " +
                            "and JoiPlay games " +
                            "(Ren'Py, RPG Maker, HTML, etc.).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Local-first and privacy-minded: no site login, no hosted account, " +
                            "no analytics SDK, and no automatic game downloader. Releases, " +
                            "changelogs, and docs are hosted publicly on GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text("Quick tips:", style = MaterialTheme.typography.labelLarge)
                Text(
                    "• Tap a row to expand details / copy text\n" +
                            "• Tap the cover thumbnail to view it full-size\n" +
                            "• Tap the refresh icon on a row to check that game\n" +
                            "• Long-press a row for multi-select (bulk hide / unhide)\n" +
                            "• In the search box, type tag:harem tag:incest to filter by tags\n" +
                            "• For JoiPlay games, import your .joiback from Menu\n" +
                            "• Full help with search at the link below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = {
                    onOpenHelp()
                    onDismiss()
                }) { Text("Open help") }
                TextButton(onClick = {
                    onShareApp()
                    onDismiss()
                }) { Text("Share app") }
                TextButton(onClick = {
                    onReportIssue()
                    onDismiss()
                }) { Text("Report issue") }
                TextButton(onClick = { onOpenSupport(); onDismiss() }) { Text("Support thread") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
