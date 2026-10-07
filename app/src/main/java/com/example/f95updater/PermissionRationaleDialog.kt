package com.example.f95updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun PermissionRationaleDialog(
    rationale: PermissionRationale,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val (title, body, confirm) = when (rationale) {
        PermissionRationale.AllFilesConfig -> Triple(
            "Manage all files access",
            "All files access lets Adult Game Manager auto-read app_config.json from Documents/AdultGameManager and use file-based tools like APK/JoiPlay installs and folder reports. You can grant or revoke it in Android settings.",
            "Open settings",
        )
        PermissionRationale.AllFilesInstallGame -> Triple(
            "All files access for game installs",
            "Add / Install game uses direct filesystem paths to inspect archives, install APKs, hand files to JoiPlay, and let Winlator Secure access Windows games in shared storage. Leave this off if you only track installed games.",
            "Open settings",
        )
        PermissionRationale.AllFilesCleanupReview -> Triple(
            "All files access for Cleanup Review",
            "Cleanup Review compares folders under a selected root with the current JoiPlay and Winlator games shown in AGM. It can identify unassociated folders and stale game records. Folder scanning is read-only; removing an orphan only removes its stale game record.",
            "Open settings",
        )
        PermissionRationale.AllFilesRenPySaves -> Triple(
            "All files access for Ren'Py saves",
            "Ren'Py save discovery scans shared storage for verified .save files, including Downloads, Documents, Android/data public save folders, and custom game folders. It is read-only and cannot access private /data/data saves without root.",
            "Open settings",
        )
        PermissionRationale.UsageAccess -> Triple(
            "Manage usage data access",
            "Usage data access lets Adult Game Manager show Last used dates for installed apps so sorting is more useful. You can grant or revoke it in Android settings.",
            "Open settings",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                Text(
                    "If you do not use this feature, leave the permission off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
