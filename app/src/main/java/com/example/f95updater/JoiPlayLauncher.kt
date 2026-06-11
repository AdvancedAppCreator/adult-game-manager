package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import org.json.JSONObject
import java.io.File

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
     * Attempts to launch the given JoiPlay game.
     * @return null on success, or a human-readable error string on failure.
     */
    fun launch(context: Context, app: InstalledApp): String? {
        if (app.source != AppSource.JoiPlay) return "Not a JoiPlay game"
        val engine = app.joiPlayType?.lowercase()?.ifBlank { null }
            ?: return "Game engine type is unknown (re-import the JoiPlay backup)"
        val gameId = app.joiPlayGameId ?: return "Game id missing"
        val folder = app.storagePath ?: return "Game folder missing"

        val gameJson = JSONObject().apply {
            put("title", app.label)
            put("id", gameId)
            put("folder", folder)
            put("execFile", app.joiPlayExecFile ?: "")
            put("type", engine)
        }.toString()

        val settingsJson = readGameSettings(folder)
            ?: app.joiPlaySettingsJson?.trim()?.ifBlank { null }
            ?: "{}"
        val settingsSource = when {
            java.io.File(folder, "configuration.json").isFile -> "game-folder"
            !app.joiPlaySettingsJson.isNullOrBlank() -> "joiback-settings"
            else -> "empty"
        }
        val action = actionFor(engine)
        AppLog.i(
            "JoiPlayLauncher",
            "Launching $engine game id=$gameId folder=$folder exec=${app.joiPlayExecFile ?: ""} " +
                "settingsSource=$settingsSource settingsLength=${settingsJson.length} via action=$action"
        )
        AppLog.i("JoiPlayLauncher", "Game payload: $gameJson")
        AppLog.i("JoiPlayLauncher", "Settings payload summary: ${settingsSummary(settingsJson)}")

        val pm = context.packageManager

        // Prefer JoiPlay's own package for built-in engines.
        if (engine in joiPlayInternalEngines) {
            val intent = baseIntent(action, gameJson, settingsJson).setPackage("cyou.joiplay.joiplay")
            if (pm.resolveActivity(intent, 0) != null) {
                return runCatching { context.startActivity(intent); null }
                    .getOrElse { "Launch failed: ${it.message}" }
            }
            AppLog.w("JoiPlayLauncher", "Internal engine $engine but no resolver in cyou.joiplay.joiplay; falling back")
        }

        // Plugin engine. Enumerate matching plugins; if exactly one, target it explicitly to
        // skip the Android chooser dialog.
        val probe = baseIntent(action, null, null)
        val matches = pm.queryIntentActivities(probe, 0)
        if (matches.isEmpty()) {
            return "No JoiPlay runtime found for engine '$engine'. Install the matching JoiPlay plugin."
        }
        val intent = baseIntent(action, gameJson, settingsJson).apply {
            val selected = selectPlugin(engine, folder, matches)
            if (selected != null) {
                setPackage(selected.activityInfo.packageName)
                AppLog.i(
                    "JoiPlayLauncher",
                    "Selected plugin: ${selected.activityInfo.packageName}/${selected.activityInfo.name}"
                )
            } else {
                AppLog.i(
                    "JoiPlayLauncher",
                    "Multiple plugins (${matches.size}) handle $action; showing chooser: ${matches.describe()}"
                )
            }
        }
        return runCatching { context.startActivity(intent); null }
            .getOrElse { "Launch failed: ${it.message}" }
    }

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

    private fun selectPlugin(engine: String, folder: String, matches: List<ResolveInfo>): ResolveInfo? {
        if (matches.size == 1) return matches[0]
        AppLog.i("JoiPlayLauncher", "Plugin candidates: ${matches.describe()}")
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
