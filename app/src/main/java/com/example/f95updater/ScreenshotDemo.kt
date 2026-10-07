package com.example.f95updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ScreenshotDemoOverlay(panel: ScreenshotPanel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(panel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    screenshotPanelSubtitle(panel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                screenshotPanelRows(panel).forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.first, modifier = Modifier.width(34.dp), fontSize = 20.sp)
                        Text(row.second, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (panel in setOf(ScreenshotPanel.ApkConfirm, ScreenshotPanel.ExtractConfirm, ScreenshotPanel.JoiPlayDelete)) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {}) { Text("Cancel") }
                        TextButton(onClick = {}) {
                            Text(
                                when (panel) {
                                    ScreenshotPanel.ApkConfirm -> "Install"
                                    ScreenshotPanel.ExtractConfirm -> "Extract"
                                    ScreenshotPanel.JoiPlayDelete -> "Delete"
                                    else -> "OK"
                                },
                                color = if (panel == ScreenshotPanel.JoiPlayDelete) MaterialTheme.colorScheme.error else LocalContentColor.current,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun screenshotPanelSubtitle(panel: ScreenshotPanel): String = when (panel) {
    ScreenshotPanel.LaunchLibrary -> "Track Android APKs and JoiPlay games in one Android-focused library."
    ScreenshotPanel.LaunchCatalogFilters -> "Browse the multi-source catalog and narrow results by source and platform."
    ScreenshotPanel.LaunchAdvancedFilters -> "Keep the main catalog clean while advanced filters stay one tap away."
    ScreenshotPanel.LaunchGameDetails -> "Review source details, version, platforms, tags, and open the original source page."
    ScreenshotPanel.LaunchReviewUnmapped -> "Quickly map installed Android/JoiPlay games to catalog entries."
    ScreenshotPanel.LaunchF95Import -> "First-run migration can import an existing F95 Updater library."
    ScreenshotPanel.SortMenu -> "Sort installed games by name, install date, app update, thread update, size, and update status."
    ScreenshotPanel.MainMenu -> "Primary actions for catalog refresh, updates, installs, save tools, backup, and help."
    ScreenshotPanel.CatalogMenu -> "Catalog maintenance actions."
    ScreenshotPanel.JoiPlayMenu -> "Auto-detect Android, Winlator, or JoiPlay installs; refresh JoiPlay storage sizes; and open non-Android game settings."
    ScreenshotPanel.BackupMenu -> "AGM backup/restore, JoiPlay backup import, and unused-folder reports."
    ScreenshotPanel.DiagnosticsMenu -> "Diagnostics are only visible when diagnosticsEnabled is true."
    ScreenshotPanel.About -> "Version, project description, sharing, issue reporting, and support links."
    ScreenshotPanel.Support -> "Optional donation links."
    ScreenshotPanel.JoiPlaySettings -> "Configure source and default destination folders used by JoiPlay and Winlator extraction."
    ScreenshotPanel.JoiPlayWarning -> "Safety warning before handing files to JoiPlay."
    ScreenshotPanel.JoiPlayPicker -> "Custom file picker for launch files and archives."
    ScreenshotPanel.ApkPicker -> "Custom file picker for APK and archive install flows."
    ScreenshotPanel.ApkConfirm -> "Confirmation screen before Android's package installer opens."
    ScreenshotPanel.ExtractConfirm -> "Confirmation screen before extracting an archive."
    ScreenshotPanel.JoiPlayDelete -> "Confirmation screen before deleting a JoiPlay game folder."
    ScreenshotPanel.CatalogMain -> "Browse the full multi-source catalog with ratings, update dates, tags, and engine prefixes."
    ScreenshotPanel.CatalogTagFilter -> "Search supports tag: filters across both tags and thread prefixes."
}

internal fun screenshotPanelRows(panel: ScreenshotPanel): List<Pair<String, String>> = when (panel) {
    ScreenshotPanel.LaunchLibrary -> listOf("Library" to "Installed APKs + JoiPlay games", "Update status" to "Mapped, unknown, ignored, and hidden", "Actions" to "Open, map, backup, refresh, and install")
    ScreenshotPanel.LaunchCatalogFilters -> listOf("Source" to "All sources, F95Zone, AdultGameWorld", "Platform" to "Android, Windows, Mac, Linux", "Search" to "Title, developer, source, and tag: filters", "Result" to "F95Zone and AdultGameWorld entries side by side")
    ScreenshotPanel.LaunchAdvancedFilters -> listOf("Status" to "Completed, on-hold, abandoned", "Engine/type" to "Ren'Py, RPGM, Unity, HTML, VN", "Rating" to "Minimum rating slider", "Install state" to "Installed-only or not-installed-only")
    ScreenshotPanel.LaunchGameDetails -> listOf("Source" to "AdultGameWorld or F95Zone", "Version" to "Latest catalog version", "Platforms" to "Android / Windows / Mac where available", "Tags" to "Prefixes and source tags", "Open" to "Jump to the original source page")
    ScreenshotPanel.LaunchReviewUnmapped -> listOf("Suggestions" to "Multi-source name matching", "Review" to "Accept, skip, or manually choose a match", "Coverage" to "Uses F95Zone and AdultGameWorld catalog entries", "Layout" to "Portrait and unfolded screens supported")
    ScreenshotPanel.LaunchF95Import -> listOf("Detect" to "Finds installed F95 Updater data", "Permission" to "Requests access before reading legacy files", "Import" to "Copies mappings into AGM", "Continue" to "AGM remains the new multi-source Android manager")
    ScreenshotPanel.SortMenu -> listOf("↕" to "App update", "↕" to "Thread updated", "↕" to "Update status", "↕" to "Last used")
    ScreenshotPanel.MainMenu -> listOf("↻" to "Refresh from catalog", "⬇" to "Check for AGM and Winlator updates", "?" to "Help ...")
    ScreenshotPanel.CatalogMenu -> listOf("⇄" to "Sync catalog now", "🏷" to "Refresh labels", "🧹" to "Clear catalog cache")
    ScreenshotPanel.JoiPlayMenu -> listOf("➕" to "Add / Install game", "📊" to "Refresh JoiPlay storage sizes", "⚙" to "Non-Android game settings")
    ScreenshotPanel.BackupMenu -> listOf("💾" to "Export AGM backup", "📂" to "Import AGM backup", "📥" to "Import JoiPlay backup", "↩" to "Restore automatic backup")
    ScreenshotPanel.DiagnosticsMenu -> listOf("📸" to "Capture walkthrough screenshots", "🐞" to "Save logs to Documents", "☁" to "Upload app logs + screenshots")
    ScreenshotPanel.About -> listOf("ℹ" to "Adult Game Manager", "📱" to "Android + JoiPlay update tracking", "↗" to "Share app, report issues, and open support")
    ScreenshotPanel.Support -> listOf("💳" to "Card / wallet support", "☕" to "Support link")
    ScreenshotPanel.JoiPlaySettings -> listOf("📁" to "Source folder: Documents/AdultGameManager", "📁" to "Default destination: Games", "✓" to "Per-install destination override")
    ScreenshotPanel.JoiPlayWarning -> listOf("!" to "Only install files from sources you trust", "✓" to "Continue to custom file picker", "☐" to "Don't show again")
    ScreenshotPanel.JoiPlayPicker -> listOf("📁" to "Games", "📁" to "Downloads", "🔴" to "Game.exe", "🟣" to "GameArchive.zip")
    ScreenshotPanel.ApkPicker -> listOf("📁" to "Download", "🟣" to "GameName.apk", "🟣" to "GameBundle.xapk", "🟣" to "ArchivedGame.zip")
    ScreenshotPanel.ApkConfirm -> listOf("📦" to "From: /storage/emulated/0/Download/GameName.apk", "☑" to "Delete the APK after a successful install")
    ScreenshotPanel.ExtractConfirm -> listOf("🟣" to "From: /storage/emulated/0/Download/GameArchive.zip", "📁" to "To: /storage/emulated/0/Games", "↪" to "Choose a different folder for this install")
    ScreenshotPanel.JoiPlayDelete -> listOf("🗑" to "This will permanently delete the folder for: Example JoiPlay Game", "!" to "This cannot be undone.")
    ScreenshotPanel.CatalogMain -> listOf("🔎" to "Search title/dev + tag:harem tag:incest", "🏷" to "Ren'Py • VN • Completed", "⭐" to "Rating 4.6 • Updated today", "📖" to "Open source details")
    ScreenshotPanel.CatalogTagFilter -> listOf("🔎" to "tag:renpy", "🏷" to "Matches prefix: Ren'Py", "🎮" to "Filter chips: Completed, VN, RPGM, Unity", "✅" to "Installed-only / not-installed filters")
}
