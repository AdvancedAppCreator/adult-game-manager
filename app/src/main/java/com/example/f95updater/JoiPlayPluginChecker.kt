package com.example.f95updater

import android.content.Context

/**
 * Per-plugin version control for JoiPlay engine-runtime plugins, using the SAME official manifest
 * (joiplay.net `downloads.json`) that [JoiPlayUpdateChecker] uses for the core app. Detects each
 * installed plugin's version, matches it to its manifest entry, and reports installed-vs-latest.
 * Also recommends the correct plugin family entry to install for a given game engine + version.
 */
object JoiPlayPluginChecker {

    /** One installed plugin paired with its matched manifest entry and update state. */
    data class PluginStatus(
        val installed: InstalledJoiPlayPlugin,
        val latest: JoiPlayDownload?,
        val updateAvailable: Boolean,
    )

    /** Installed-plugin statuses plus the full manifest (so callers can install missing plugins). */
    data class Report(
        val plugins: List<PluginStatus>,
        val downloads: List<JoiPlayDownload>,
    ) {
        val anyUpdate: Boolean get() = plugins.any { it.updateAvailable }
    }

    /** Fetches the manifest and builds a [Report]. Network/parse failure yields an empty manifest. */
    suspend fun check(context: Context, downloadsUrl: String): Report {
        val downloads = runCatching { JoiPlayUpdateChecker.fetch(downloadsUrl) }
            .onFailure { AppLog.w("JoiPlayPlugin", "Could not fetch manifest from $downloadsUrl", it) }
            .getOrDefault(emptyList())
        return report(context, downloads)
    }

    /** Builds a [Report] from an already-fetched manifest (avoids a redundant network round-trip). */
    fun report(context: Context, downloads: List<JoiPlayDownload>): Report {
        val plugins = JoiPlayPluginDiscovery.installedPlugins(context).map { plugin ->
            val latest = matchLatest(plugin, downloads)
            val updateAvailable = latest != null &&
                plugin.versionName != null &&
                VersionCompare.compare(latest.version, plugin.versionName) > 0
            PluginStatus(plugin, latest, updateAvailable).also {
                AppLog.i(
                    "JoiPlayPlugin",
                    "Plugin ${plugin.packageName} installed=${plugin.versionName ?: "?"} " +
                        "latest=${latest?.version ?: "?"} updateAvailable=$updateAvailable",
                )
            }
        }
        return Report(plugins, downloads)
    }

    /** The manifest entry whose title matches this installed plugin's [InstalledJoiPlayPlugin.titleStem]. */
    fun matchLatest(plugin: InstalledJoiPlayPlugin, downloads: List<JoiPlayDownload>): JoiPlayDownload? =
        downloads.firstOrNull { it.title.trim().equals(plugin.titleStem, ignoreCase = true) }

    /**
     * Recommends the manifest plugin entry to install for [engineType]. For single-family engines
     * (RPG Maker XP/VX/VXAce, Ruffle) returns that family's entry. For versioned families
     * (Ren'Py, Godot 3/4) with a known [gameVersion], picks the smallest family version that is
     * >= the game's version (mirroring JoiPlay's own runtime selection), else the newest available.
     * Returns null when the engine is core-tier or no matching entry exists.
     */
    fun recommend(
        engineType: String,
        gameVersion: String?,
        downloads: List<JoiPlayDownload>,
    ): JoiPlayDownload? {
        val family = JoiPlayPluginRegistry.engine(engineType)?.pluginTitleFamily ?: return null
        val candidates = downloads.filter { d ->
            d.type.equals("APK", ignoreCase = true) &&
                d.title.trim().startsWith(family, ignoreCase = true) &&
                d.link.isNotBlank()
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        // Multiple family entries (e.g. several Ren'Py versions). Order by the version embedded in
        // the title and pick the smallest that covers the game, else the newest.
        val withVersion = candidates.mapNotNull { d ->
            parseFamilyVersion(d.title, family)?.let { v -> d to v }
        }.sortedWith(compareBy(VersionComparator) { it.second })
        if (withVersion.isEmpty()) return candidates.maxWithOrNull(compareBy(VersionComparator) { it.version })
        val newest = withVersion.last().first
        if (gameVersion.isNullOrBlank()) return newest
        return withVersion.firstOrNull { VersionCompare.compare(it.second, gameVersion) >= 0 }?.first
            ?: newest
    }

    /** Comparator ordering version strings via [VersionCompare.compare]. */
    private val VersionComparator = Comparator<String> { a, b -> VersionCompare.compare(a, b) }

    /**
     * Extracts the family version embedded in a download title, e.g.
     * ("Ren'Py 8.3.7 Plugin", "Ren'Py") -> "8.3.7"; ("Godot 4.3 Plugin", "Godot 4") -> "4.3".
     * Returns null when no numeric version follows the family text.
     */
    internal fun parseFamilyVersion(title: String, family: String): String? {
        val trimmed = title.trim()
        if (!trimmed.startsWith(family, ignoreCase = true)) return null
        val rest = trimmed.substring(family.length)
        return Regex("""\d+(?:\.\d+)*""").find(rest)?.value
    }
}
