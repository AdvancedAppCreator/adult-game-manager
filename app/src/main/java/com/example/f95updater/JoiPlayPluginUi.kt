package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private fun fmtBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return if (i == 0) "$bytes B" else "%.1f %s".format(value, units[i])
}

/**
 * Prompt to install (or update) a single JoiPlay engine plugin from AGM. Runs the native in-app
 * MEGA download + system installer ([onInstall]); the "Open joiplay.net" action is the manual
 * fallback (A) if the user prefers, and the UI also auto-falls-back to it on download failure.
 */
@Composable
internal fun PluginInstallDialog(
    state: PluginInstallState,
    progress: Pair<Long, Long>?,
    onInstall: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val d = state.download
    val busy = progress != null
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Install ${d.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append("Latest v${d.version}")
                        if (d.size.isNotBlank()) append(" · ${d.size}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "AGM downloads the plugin straight from the official MEGA source and hands it to " +
                        "Android's installer. Nothing is hosted or modified by AGM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress?.let { (done, total) ->
                    Spacer(Modifier.height(4.dp))
                    val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Text("${fmtBytes(done)} / ${fmtBytes(total)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onInstall, enabled = !busy) {
                Text(if (busy) "Downloading…" else "Install")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpenLink(d.link) }, enabled = !busy && d.link.isNotBlank()) {
                    Text("Open site")
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            }
        },
    )
}
