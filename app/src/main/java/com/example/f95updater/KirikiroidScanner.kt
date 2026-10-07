package com.example.f95updater

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers installed Kirikiroid games by listing the immediate subfolders of the configured
 * Kirikiroid root (JoiPlaySettingsStore.InstallRoot.Kirikiroid). Each subfolder is one extracted
 * KiriKiri/XP3 game, launched at runtime through the Kirikiroid2 emulator ([KirikiroidLauncher]).
 *
 * Unlike JoiPlay, this root is deliberately NOT allowed to fall back to the shared games root: the
 * JoiPlay folder scanner claims every subfolder of that root as a JoiPlay game, so a Kirikiroid root
 * must be a distinct folder to avoid the same folder being scanned as both sources.
 */
object KirikiroidScanner {

    suspend fun getRootUri(context: Context): Uri? = withContext(Dispatchers.IO) {
        JoiPlaySettingsStore.installRootUri(context, JoiPlaySettingsStore.InstallRoot.Kirikiroid)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    /** Fast scan: lists subfolders only, pre-filling size from the cached size map. */
    suspend fun scan(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val uri = getRootUri(context) ?: return@withContext emptyList()
        val root = JoiPlayScanner.documentRoot(context, uri) ?: return@withContext emptyList()
        if (!root.isDirectory) return@withContext emptyList()
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val name = folder.name ?: return@mapNotNull null
                if (name.startsWith(".")) return@mapNotNull null
                val (label, version) = JoiPlayScanner.extractLabelAndVersion(name)
                val lastModified = runCatching { folder.lastModified() }.getOrDefault(0L)
                InstalledApp(
                    packageName = "kirikiroid:$name",
                    label = label,
                    versionName = version ?: "",
                    versionCode = 0L,
                    firstInstallTime = lastModified,
                    lastUpdateTime = lastModified,
                    lastUsedTime = 0L,
                    apkSize = 0L,
                    dataSize = 0L,
                    cacheSize = 0L,
                    source = AppSource.Kirikiroid,
                    storagePath = JoiPlayScanner.absoluteFolderPath(folder) ?: name,
                    storageFolderName = name,
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
