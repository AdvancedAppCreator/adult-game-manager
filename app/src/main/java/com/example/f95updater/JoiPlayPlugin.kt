package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * How a JoiPlay engine is executed on-device.
 *
 *  - [Plugin]: the engine runs in a **separate, self-contained plugin APK** (Ren'Py, RPG Maker
 *    XP/VX/VXAce, Godot, Ruffle). These plugins bundle their own interpreter + SDL + controls and
 *    run games with NO JoiPlay core app installed (empirically verified: launching
 *    `cyou.joiplay.runtime.<engine>.run` straight at the plugin boots the game standalone).
 *  - [Core]: the engine's runtime activity lives **inside the JoiPlay core APK**
 *    (`cyou.joiplay.joiplay`) — RPG Maker MV/MZ, Tyrano, Construct, Twine, HTML, Electron. These
 *    have no standalone plugin, so JoiPlay core remains required for them.
 */
enum class JoiPlayTier { Plugin, Core }

/**
 * Static registry describing every JoiPlay engine type AGM knows about, which execution [JoiPlayTier]
 * it belongs to, and (for plugin-tier engines) how it maps to a JoiPlay plugin family on
 * joiplay.net. This is the single source of truth shared by the launcher, the plugin version-control
 * checker, and the standalone-migration classifier.
 */
object JoiPlayPluginRegistry {

    /** Immutable description of one engine type (a JoiPlay `joiPlayType` value). */
    data class Engine(
        val type: String,
        val tier: JoiPlayTier,
        val displayName: String,
        /**
         * The leading text of the matching joiplay.net download title family, used to match an
         * installed plugin (or recommend one) — e.g. "Ren'Py", "RPG Maker Plugin", "Godot 3",
         * "Godot 4", "Ruffle". Null for core-tier engines (no standalone plugin).
         */
        val pluginTitleFamily: String?,
    ) {
        val isPlugin: Boolean get() = tier == JoiPlayTier.Plugin

        /** The exported runtime-activity intent action a plugin (or core) registers for this engine. */
        val runtimeAction: String get() = runtimeAction(type)
    }

    private val engines: List<Engine> = listOf(
        // ---- Plugin tier: standalone-capable (separate plugin APKs) ----
        Engine("renpy", JoiPlayTier.Plugin, "Ren'Py", "Ren'Py"),
        Engine("legacyrenpy", JoiPlayTier.Plugin, "Ren'Py (legacy)", "Ren'Py"),
        Engine("rpgmxp", JoiPlayTier.Plugin, "RPG Maker XP", "RPG Maker Plugin"),
        Engine("rpgmvx", JoiPlayTier.Plugin, "RPG Maker VX", "RPG Maker Plugin"),
        Engine("rpgmvxa", JoiPlayTier.Plugin, "RPG Maker VX Ace", "RPG Maker Plugin"),
        Engine("godot3", JoiPlayTier.Plugin, "Godot 3", "Godot 3"),
        Engine("godot4", JoiPlayTier.Plugin, "Godot 4", "Godot 4"),
        Engine("ruffle", JoiPlayTier.Plugin, "Ruffle (Flash)", "Ruffle"),
        // ---- Core tier: runtime lives inside the JoiPlay core APK ----
        Engine("rpgmmv", JoiPlayTier.Core, "RPG Maker MV", null),
        Engine("rpgmmz", JoiPlayTier.Core, "RPG Maker MZ", null),
        Engine("tyrano", JoiPlayTier.Core, "TyranoScript", null),
        Engine("construct", JoiPlayTier.Core, "Construct", null),
        Engine("twine", JoiPlayTier.Core, "Twine", null),
        Engine("html", JoiPlayTier.Core, "HTML", null),
        Engine("electron", JoiPlayTier.Core, "Electron", null),
    )

    private val byType: Map<String, Engine> = engines.associateBy { it.type }

    /** All plugin-tier engine types (used to enumerate installed plugins by their runtime actions). */
    val pluginEngineTypes: List<String> = engines.filter { it.isPlugin }.map { it.type }

    /** The runtime-activity intent action for an engine type. */
    fun runtimeAction(engineType: String): String =
        "cyou.joiplay.runtime.${engineType.lowercase().trim()}.run"

    /** Engine type expected by the standalone runtime plugin's intent action and game payload. */
    fun runtimeEngineType(engineType: String): String =
        when (val normalized = engineType.lowercase().trim()) {
            // AGM/JoiPlay backup metadata abbreviates VX Ace; the plugin does not.
            "rpgmvxa" -> "rpgmvxace"
            else -> normalized
        }

    /** Runtime actions that may serve [engineType], including known aliases for the same engine. */
    fun runtimeActionCandidates(engineType: String): List<String> {
        val normalized = engineType.lowercase().trim()
        return listOf(runtimeAction(runtimeEngineType(normalized)), runtimeAction(normalized)).distinct()
    }

    /** Looks up an engine by its `joiPlayType`; null when unknown. */
    fun engine(engineType: String?): Engine? =
        engineType?.lowercase()?.trim()?.ifBlank { null }?.let { byType[it] }

    /** The execution tier for an engine type, or null when the type is unknown. */
    fun tier(engineType: String?): JoiPlayTier? = engine(engineType)?.tier

    fun isPluginTier(engineType: String?): Boolean = tier(engineType) == JoiPlayTier.Plugin

    fun isCoreTier(engineType: String?): Boolean = tier(engineType) == JoiPlayTier.Core

    /** Human-readable engine name, falling back to the raw type for unknown engines. */
    fun displayName(engineType: String?): String =
        engine(engineType)?.displayName ?: engineType?.trim().orEmpty()
}

/**
 * A JoiPlay engine-runtime plugin currently installed on the device, discovered via its exported
 * runtime action (package visibility is declared in the manifest `<queries>` block).
 */
data class InstalledJoiPlayPlugin(
    val packageName: String,
    /** The plugin's application label, e.g. "Ren'Py 8.3.7 Plugin for JoiPlay". */
    val label: String,
    /** [label] with a trailing " for JoiPlay" removed, matching the joiplay.net download title. */
    val titleStem: String,
    /** The plugin APK's versionName, e.g. "1.00.60-patreon". */
    val versionName: String?,
    /** `cyou.joiplay.runtime.version` metadata: a zero-padded MMmmpp int (e.g. 80307 for 8.3.7). */
    val runtimeVersion: Int?,
    /** `cyou.joiplay.runtime.types` metadata, ";"-separated engine types the plugin serves. */
    val engineTypes: List<String>,
) {
    /** True when this plugin advertises support for [engineType]. */
    fun serves(engineType: String): Boolean =
        engineTypes.any { it.equals(engineType, ignoreCase = true) }
}

/**
 * Enumerates installed JoiPlay engine-runtime plugins by resolving the plugin-tier runtime actions
 * declared in the manifest `<queries>` block, then reading each plugin package's label, versionName
 * and JoiPlay runtime metadata.
 */
object JoiPlayPluginDiscovery {

    private const val META_VERSION = "cyou.joiplay.runtime.version"
    private const val META_TYPES = "cyou.joiplay.runtime.types"
    private const val LABEL_SUFFIX = " for JoiPlay"

    /** Whether any engine-runtime plugin for [engineType] is installed and resolvable. */
    fun isPluginInstalledFor(context: Context, engineType: String): Boolean {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return JoiPlayPluginRegistry.runtimeActionCandidates(engineType).any { action ->
            pm.queryIntentActivities(Intent(action), 0).isNotEmpty()
        }
    }

    /** The package names of every plugin that resolves [engineType]'s runtime action. */
    fun pluginPackagesFor(context: Context, engineType: String): List<String> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return JoiPlayPluginRegistry.runtimeActionCandidates(engineType)
            .flatMap { action -> pm.queryIntentActivities(Intent(action), 0) }
            .map { it.activityInfo.packageName }
            .distinct()
    }

    /** All installed JoiPlay engine-runtime plugins, de-duplicated by package. */
    fun installedPlugins(context: Context): List<InstalledJoiPlayPlugin> {
        val pm = context.packageManager
        val packages = JoiPlayPluginRegistry.pluginEngineTypes
            .flatMap { pluginPackagesFor(context, it) }
            .distinct()
        return packages.mapNotNull { pkg -> readPlugin(pm, pkg) }
            .sortedBy { it.titleStem.lowercase() }
    }

    private fun readPlugin(pm: PackageManager, pkg: String): InstalledJoiPlayPlugin? {
        return runCatching {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val label = pm.getApplicationLabel(appInfo).toString().trim()
            val versionName = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0).versionName?.trim()?.ifBlank { null }
            }.getOrNull()
            val md = appInfo.metaData
            val runtimeVersion = md?.get(META_VERSION)?.toString()?.trim()?.toIntOrNull()
            val types = md?.get(META_TYPES)?.toString()
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            InstalledJoiPlayPlugin(
                packageName = pkg,
                label = label,
                titleStem = titleStemOf(label),
                versionName = versionName,
                runtimeVersion = runtimeVersion,
                engineTypes = types,
            )
        }.onFailure {
            AppLog.w("JoiPlayPlugin", "Failed to read plugin package $pkg", it)
        }.getOrNull()
    }

    /** Strips a trailing " for JoiPlay" so an app label matches its joiplay.net download title. */
    internal fun titleStemOf(label: String): String {
        val trimmed = label.trim()
        return if (trimmed.endsWith(LABEL_SUFFIX, ignoreCase = true)) {
            trimmed.dropLast(LABEL_SUFFIX.length).trim()
        } else {
            trimmed
        }
    }
}
