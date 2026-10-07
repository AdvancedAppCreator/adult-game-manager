package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
                    "Continue only with games and installers you trust. AGM detects Android APKs, Windows games for Winlator Secure, and JoiPlay-compatible games from the selected file or archive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Archives are inspected before extraction. Split APK bundles are rejected instead of installing only base.apk. JoiPlay imports may still require a refreshed JoiPlay backup for complete engine metadata.",
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
