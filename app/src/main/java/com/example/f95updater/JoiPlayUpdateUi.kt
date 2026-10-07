package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Banner shown on the installed-library screen when the installed JoiPlay is older than the latest
 * on joiplay.net. Tapping opens the JoiPlay downloads dialog. JoiPlay is a third-party app, so this
 * only informs + deep-links; AGM never hosts or installs it.
 */
@Composable
internal fun JoiPlayUpdateBanner(
    status: JoiPlayUpdateChecker.Status,
    onOpen: () -> Unit,
) {
    if (!status.updateAvailable) return
    val latest = status.core?.version ?: return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "JoiPlay update available: v$latest",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "Installed v${status.installedVersion}. Tap to view downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/**
 * Lists JoiPlay's official downloads (core app + engine plugins) with their latest versions and the
 * installed core version, each deep-linking to the official download. AGM cannot host these APKs
 * (third-party, MEGA-hosted), so every action opens the official source in the browser.
 */
@Composable
internal fun JoiPlayDownloadsDialog(
    status: JoiPlayUpdateChecker.Status,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val siteUrl = "https://joiplay.net/#downloads"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("JoiPlay downloads") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().dialogVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    status.downloads.isEmpty() ->
                        Text(
                            "Couldn't reach joiplay.net. Check your connection and try again, or open " +
                                "the site directly.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    status.installedVersion == null ->
                        Text(
                            "JoiPlay isn't installed. Install the core app and the plugins for the " +
                                "engines you play from the official downloads below.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    status.updateAvailable ->
                        Text(
                            "Your JoiPlay (v${status.installedVersion}) is older than the latest " +
                                "(v${status.core?.version}). Update the core app and any plugins you use.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    else ->
                        Text(
                            "JoiPlay v${status.installedVersion} is installed. Latest versions of the " +
                                "core app and plugins are listed below.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                }

                status.downloads.forEach { item ->
                    val isCore = item.id == status.core?.id && status.core != null
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCore && status.updateAvailable) {
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text("Update") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        disabledLabelColor = MaterialTheme.colorScheme.error,
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            TextButton(onClick = { if (item.link.isNotBlank()) onOpenUrl(item.link) }) {
                                Text("Open")
                            }
                        }
                        val installedSuffix =
                            if (isCore && status.installedVersion != null) " · installed v${status.installedVersion}" else ""
                        Text(
                            buildString {
                                append("v${item.version}")
                                if (item.badge.isNotBlank()) append(" · ${item.badge}")
                                if (item.size.isNotBlank()) append(" · ${item.size}")
                                append(installedSuffix)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.description.isNotBlank()) {
                            Text(item.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenUrl(siteUrl) }) { Text("Open joiplay.net") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
