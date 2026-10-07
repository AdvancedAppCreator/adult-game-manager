package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.json.JSONObject
import java.io.File

/**
 * Outcome of a JoiPlay game launch.
 *
 *  - [Success]: the runtime activity was started.
 *  - [Failed]: a hard error (bad metadata, core app missing for a core-tier engine, or the runtime
 *    refused to start). [message] is user-facing.
 *  - [PluginRequired]: a plugin-tier game (Ren'Py, RPG Maker XP/VX/VXAce, Godot, Ruffle) has no
 *    installed runtime plugin. The UI can offer to install the recommended plugin. This is NOT a
 *    failure of the launch itself — the game is standalone-capable once the plugin is present.
 */
sealed interface JoiPlayLaunchResult {
    data object Success : JoiPlayLaunchResult
    data class Failed(val message: String) : JoiPlayLaunchResult
    data class PluginRequired(
        val engineType: String,
        val displayName: String,
        val detectedVersion: String?,
    ) : JoiPlayLaunchResult
}

/**
 * Launches a JoiPlay game directly into its runtime activity (HTMLActivity / TyranoActivity
 * inside JoiPlay, or a separate plugin APK for renpy/rpgmaker/godot/ruffle).
 *
 * Contract reverse-engineered from JoiPlay 1.21.000:
 *   action: "cyou.joiplay.runtime.<engineType>.run"
 *   extras: "preloadScripts"  -> string array list, can be empty
 *           "postloadScripts" -> string array list, can be empty
 *           "game"            -> JSON string {"title","id","folder","execFile","type"}
 *           "settings"        -> configuration.json from the game folder, or "{}" when missing
 *
 * HTMLActivity / TyranoActivity in cyou.joiplay.joiplay are android:exported="true" with
 * intent-filters for: rpgmmv, rpgmmz, construct, twine, html, electron, tyrano.
 *
 * Plugin engines (renpy, rpgmxp/rpgmvxa, godot3/godot4, ruffle) live in separate plugin APKs
 * that declare their own exported intent-filters with the same naming convention.
 */
object JoiPlayLauncher {

    /** Engine types that are handled directly by activities inside the JoiPlay APK itself. */
    private val joiPlayInternalEngines = setOf(
        "rpgmmv", "rpgmmz", "construct", "twine", "html", "electron", "tyrano",
    )

    /** Maps a JoiPlay engine type to the intent action it expects. */
    private fun actionFor(engineType: String): String =
        "cyou.joiplay.runtime.${engineType.lowercase()}.run"

    /**
     * Attempts to launch the given JoiPlay game. See [JoiPlayLaunchResult] for the outcomes.
     */
    fun launch(context: Context, app: InstalledApp): JoiPlayLaunchResult {
        if (app.source != AppSource.JoiPlay && app.source != AppSource.Managed) {
            return JoiPlayLaunchResult.Failed("JoiPlay is not enabled for this game")
        }
        val engine = app.joiPlayType?.lowercase()?.ifBlank { null }
            ?: return JoiPlayLaunchResult.Failed("Game engine type is unknown (re-import the JoiPlay backup)")
        val gameId = app.joiPlayGameId ?: app.managedGameId
            ?: return JoiPlayLaunchResult.Failed("Game id missing")
        val folder = app.storagePath ?: return JoiPlayLaunchResult.Failed("Game folder missing")
        val runtimeEngine = JoiPlayPluginRegistry.runtimeEngineType(engine)
        LastPlayedStore.recordLaunch(context, app.packageName)

        val gameJson = JSONObject().apply {
            put("title", app.label)
            put("id", gameId)
            put("folder", folder)
            put("execFile", app.joiPlayExecFile ?: "")
            put("type", runtimeEngine)
        }.toString()

        val settingsJson = readGameSettings(folder)
            ?: app.joiPlaySettingsJson?.trim()?.ifBlank { null }
            ?: "{}"
        val settingsSource = when {
            java.io.File(folder, "configuration.json").isFile -> "game-folder"
            !app.joiPlaySettingsJson.isNullOrBlank() -> "joiback-settings"
            else -> "empty"
        }
        val requestedAction = actionFor(engine)
        AppLog.i(
            "JoiPlayLauncher",
            "Launching $engine game id=$gameId folder=$folder exec=${app.joiPlayExecFile ?: ""} " +
                "settingsSource=$settingsSource settingsLength=${settingsJson.length} via action=$requestedAction"
        )
        AppLog.i("JoiPlayLauncher", "Game payload: $gameJson")
        AppLog.i("JoiPlayLauncher", "Settings payload summary: ${settingsSummary(settingsJson)}")

        val pm = context.packageManager

        // Core-tier engines (RPG Maker MV/MZ, Tyrano, Construct, Twine, HTML, Electron) run inside
        // the JoiPlay core APK — there is no standalone plugin for them.
        if (engine in joiPlayInternalEngines) {
            val intent = baseIntent(requestedAction, gameJson, settingsJson)
                .setPackage(JoiPlayUpdateChecker.PACKAGE)
            if (pm.resolveActivity(intent, 0) != null) {
                return runCatching { context.startActivity(intent); JoiPlayLaunchResult.Success }
                    .getOrElse { JoiPlayLaunchResult.Failed("Launch failed: ${it.message}") }
            }
            return JoiPlayLaunchResult.Failed(
                "${JoiPlayPluginRegistry.displayName(engine)} games run inside the JoiPlay core app, " +
                    "which isn't installed. Install JoiPlay from joiplay.net.",
            )
        }

        // Plugin-tier engine — runs in its own standalone plugin APK (no JoiPlay core needed).
        val resolvedRuntime = JoiPlayPluginRegistry.runtimeActionCandidates(engine)
            .firstNotNullOfOrNull { action ->
                val matches = pm.queryIntentActivities(baseIntent(action, null, null), 0)
                matches.takeIf { it.isNotEmpty() }?.let { action to it }
            }
        if (resolvedRuntime == null) {
            AppLog.i("JoiPlayLauncher", "No runtime plugin installed for engine '$engine'")
            return JoiPlayLaunchResult.PluginRequired(
                engineType = engine,
                displayName = JoiPlayPluginRegistry.displayName(engine),
                detectedVersion = detectVersionFor(engine, folder),
            )
        }
        val (runtimeAction, matches) = resolvedRuntime
        if (runtimeAction != requestedAction) {
            AppLog.i(
                "JoiPlayLauncher",
                "Using compatible runtime action $runtimeAction for engine '$engine'",
            )
        }
        val intent = baseIntent(runtimeAction, gameJson, settingsJson).apply {
            val selected = selectPlugin(pm, engine, folder, matches)
            if (selected != null) {
                setPackage(selected.activityInfo.packageName)
                AppLog.i(
                    "JoiPlayLauncher",
                    "Selected plugin: ${selected.activityInfo.packageName}/${selected.activityInfo.name}",
                )
            } else {
                AppLog.i(
                    "JoiPlayLauncher",
                    "Multiple plugins (${matches.size}) handle $runtimeAction; showing chooser: ${matches.describe()}",
                )
            }
        }
        return runCatching { context.startActivity(intent); JoiPlayLaunchResult.Success }
            .getOrElse { JoiPlayLaunchResult.Failed("Launch failed: ${it.message}") }
    }

    /**
     * Detects a game's engine version string for plugin recommendation (currently Ren'Py only,
     * where multiple version-specific plugins exist). Returns null for single-plugin engines or
     * when detection fails.
     */
    fun detectGameVersion(app: InstalledApp): String? {
        val engine = app.joiPlayType?.lowercase()?.ifBlank { null } ?: return null
        val folder = app.storagePath ?: return null
        return detectVersionFor(engine, folder)
    }

    private fun detectVersionFor(engine: String, folder: String): String? =
        if (engine == "renpy" || engine == "legacyrenpy") detectRenPyVersion(folder) else null

    private fun baseIntent(action: String, gameJson: String?, settingsJson: String?): Intent =
        Intent(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra("preloadScripts", arrayListOf())
            putStringArrayListExtra("postloadScripts", arrayListOf())
            if (gameJson != null) putExtra("game", gameJson)
            if (settingsJson != null) putExtra("settings", settingsJson)
        }

    private fun readGameSettings(folder: String): String? {
        return runCatching {
            val configFile = File(folder, "configuration.json")
            if (configFile.isFile) {
                val text = configFile.readText()
                JSONObject(text)
                AppLog.i("JoiPlayLauncher", "Loaded configuration.json from $folder (${text.length} chars)")
                text
            } else {
                AppLog.i("JoiPlayLauncher", "No configuration.json in $folder; using empty settings")
                null
            }
        }.getOrElse {
            AppLog.w("JoiPlayLauncher", "Unable to read configuration.json from $folder; using empty settings", it)
            null
        }
    }

    private fun settingsSummary(settingsJson: String): String {
        return runCatching {
            val root = JSONObject(settingsJson)
            val topKeys = root.keys().asSequence().toList().sorted()
            val renpy = root.optJSONObject("renpy")
            val renpySummary = renpy?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",") { key ->
                "$key=${renpy.opt(key)}"
            } ?: "missing"
            "topKeys=$topKeys renpy={$renpySummary}"
        }.getOrElse { "invalid: ${it.message}" }
    }

    /**
     * Chooses the JoiPlay runtime plugin for a game, mirroring JoiPlay 1.21.000's own
     * runtime-selection logic (decompiled from cyou.joiplay.joiplay.utilities.r0.d()):
     *
     *  1. Read the game's *build* version from game/script_version.txt (then .rpy, then .rpyc)
     *     - NOT renpy/__init__.py, which can disagree with the compiled scripts.
     *  2. Read each candidate plugin's cyou.joiplay.runtime.version (a zero-padded MMmmpp int,
     *     e.g. 8.2.1 -> 80201) and cyou.joiplay.runtime.types (";"-separated) manifest metadata.
     *  3. Filter to plugins supporting the game type, sort ascending by version, and pick the
     *     SMALLEST runtime whose version >= the game's build version.
     *  4. When no script_version.* file exists, fall back to a Python 2/3 decision (max runtime
     *     with version < 80000 for legacy/py2, else max runtime with version >= 80000).
     *
     * If the plugins expose no runtime metadata (unexpected), falls back to the legacy
     * package-name token heuristic in [selectPluginByToken].
     */
    private fun selectPlugin(
        pm: PackageManager,
        engine: String,
        folder: String,
        matches: List<ResolveInfo>,
    ): ResolveInfo? {
        if (matches.size == 1) return matches[0]
        AppLog.i("JoiPlayLauncher", "Plugin candidates: ${matches.describe()}")

        val candidates = matches.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            val meta = runCatching {
                pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData
            }.getOrNull() ?: return@mapNotNull null
            val typesRaw = meta.get("cyou.joiplay.runtime.types")?.toString() ?: return@mapNotNull null
            val version = meta.get("cyou.joiplay.runtime.version")?.toString()?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            val types = typesRaw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            RuntimeCandidate(pkg, version, types)
        }
        if (candidates.isEmpty()) {
            AppLog.w("JoiPlayLauncher", "No runtime metadata on candidates; using token heuristic")
            return selectPluginByToken(engine, folder, matches)
        }

        val gameVersion = readGameScriptVersion(folder)
        val hasPython2Lib = File(folder, "lib/pythonlib2.7").exists()
        val chosenPkg = chooseRuntime(engine, candidates, gameVersion, hasPython2Lib)
        val selected = matches.firstOrNull { it.activityInfo.packageName == chosenPkg }
        AppLog.i(
            "JoiPlayLauncher",
            "RenPy plugin selection: engine=$engine gameVersion=${gameVersion ?: "none"} " +
                "hasPy2Lib=$hasPython2Lib candidates=" +
                candidates.joinToString { "${it.packageName}=${it.version}${it.types}" } +
                " selected=${selected?.activityInfo?.packageName ?: "chooser"}"
        )
        return selected ?: selectPluginByToken(engine, folder, matches)
    }

    /** A JoiPlay runtime plugin's selection-relevant manifest metadata. */
    data class RuntimeCandidate(val packageName: String, val version: Int, val types: List<String>)

    /**
     * Pure runtime-selection core mirroring JoiPlay r0.d(). Returns the chosen plugin package
     * name, or null when there are no candidates.
     *
     * @param gameVersion the game's build version as an MMmmpp int (see [encodeScriptVersion]),
     *   or null when no script_version.* file exists (triggers the Python 2/3 fallback).
     */
    fun chooseRuntime(
        engine: String,
        candidates: List<RuntimeCandidate>,
        gameVersion: Int?,
        hasPython2Lib: Boolean,
    ): String? {
        if (candidates.isEmpty()) return null
        val ofType = candidates.filter { it.types.contains(engine) }.ifEmpty { candidates }
        return if (gameVersion != null) {
            (ofType.sortedBy { it.version }.firstOrNull { it.version >= gameVersion }
                ?: ofType.maxByOrNull { it.version })?.packageName
        } else {
            val py2 = engine.contains("7") || engine.contains("legacy") || hasPython2Lib
            val pool = if (py2) ofType.filter { it.version < 80000 } else ofType.filter { it.version >= 80000 }
            pool.ifEmpty { ofType }.maxByOrNull { it.version }?.packageName
        }
    }

    /**
     * Encodes a Ren'Py version like "(8, 0, 3)" into JoiPlay's comparable MMmmpp int (80003),
     * mirroring r0.d()/r0.h(): each of the first two components is zero-padded to two digits, the
     * third (patch) too when present, otherwise "00". Returns null when unparseable.
     */
    fun encodeScriptVersion(raw: String): Int? {
        val cleaned = Regex("[^0-9,]").replace(raw, "")
        val parts = cleaned.split(",").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        fun pad(p: String): String? {
            val n = p.toIntOrNull() ?: return null
            return if (n > 9) p else "0$p"
        }
        val major = pad(parts[0]) ?: return null
        val minor = pad(parts[1]) ?: return null
        val patch = if (parts.size >= 3) (pad(parts[2]) ?: return null) else "00"
        return (major + minor + patch).toIntOrNull()
    }

    /**
     * Reads the game's build version (MMmmpp int) from game/script_version.txt, then .rpy, then
     * .rpyc, mirroring JoiPlay r0.d(). Returns null only when none of those files exist (the
     * caller then applies the Python 2/3 fallback).
     */
    private fun readGameScriptVersion(folder: String): Int? {
        val txt = File(folder, "game/script_version.txt")
        val rpy = File(folder, "game/script_version.rpy")
        val rpyc = File(folder, "game/script_version.rpyc")
        if (txt.isFile) {
            return runCatching { encodeScriptVersion(txt.readText()) }.getOrNull() ?: 999999
        }
        if (rpy.isFile) {
            return runCatching {
                val s = rpy.readText()
                val idx = s.indexOf("script_version")
                encodeScriptVersion(if (idx >= 0) s.substring(idx) else s)
            }.getOrNull() ?: 0
        }
        if (rpyc.isFile) return 0
        return null
    }

    /**
     * Legacy fallback: pick a plugin by matching a package-name token derived from the version in
     * renpy/__init__.py. Only used when a plugin exposes no runtime metadata.
     */
    private fun selectPluginByToken(engine: String, folder: String, matches: List<ResolveInfo>): ResolveInfo? {
        if (matches.size == 1) return matches[0]
        if (engine != "renpy" && engine != "legacyrenpy") return null

        val detected = detectRenPyVersion(folder)
        val preferredToken = when {
            detected?.startsWith("7.4.") == true || detected?.startsWith("7.3.") == true -> "v7d4"
            detected?.startsWith("7.5.") == true ||
                detected?.startsWith("7.6.") == true ||
                detected?.startsWith("7.7.") == true -> "v7d7"
            detected?.startsWith("8.0.") == true ||
                detected?.startsWith("8.1.") == true ||
                detected?.startsWith("8.2.") == true -> "v8d2"
            detected?.startsWith("8.3.") == true ||
                detected?.startsWith("8.4.") == true ||
                detected?.startsWith("8.5.") == true -> "v8d4"
            engine == "legacyrenpy" -> "v7d4"
            else -> null
        }
        val selected = preferredToken?.let { token ->
            matches.firstOrNull { it.activityInfo.packageName.contains(token, ignoreCase = true) }
        }
        AppLog.i(
            "JoiPlayLauncher",
            "RenPy plugin selection: detected=${detected ?: "unknown"} " +
                "preferred=${preferredToken ?: "chooser"} selected=${selected?.activityInfo?.packageName}"
        )
        return selected
    }

    private fun detectRenPyVersion(folder: String): String? {
        val files = listOf(
            File(folder, "renpy/__init__.py"),
        )
        val tupleRe = Regex("""version_tuple\s*=\s*\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)""")
        val textRe = Regex("""Ren'?Py\s+(\d+\.\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        for (file in files) {
            val text = runCatching {
                if (file.isFile) file.readText().take(128 * 1024) else null
            }.getOrNull() ?: continue
            tupleRe.find(text)?.let {
                val version = "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}"
                AppLog.i("JoiPlayLauncher", "Detected RenPy $version from ${file.path}")
                return version
            }
            textRe.find(text)?.let {
                AppLog.i("JoiPlayLauncher", "Detected RenPy ${it.groupValues[1]} from ${file.path}")
                return it.groupValues[1]
            }
        }
        AppLog.i("JoiPlayLauncher", "Unable to detect RenPy version from $folder")
        return null
    }

    private fun List<ResolveInfo>.describe(): String =
        joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
}
