package com.example.f95updater

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File

/**
 * Launches a Kirikiroid (KiriKiri/KAG/XP3) game into the Kirikiroid2 emulator.
 *
 * Contract v1 agreed with the kimochi/Kirikiroid2 session:
 *  - Explicit component: (<package>, "org.github.krkr2.LaunchGameActivity"). The activity class
 *    name is constant across build flavors (namespace stays org.github.krkr2); only the package
 *    differs (org.github.krkr2 for prod, com.agm.krkr2 for the AGM integration build).
 *  - action = "com.agm.kirikiri.action.LAUNCH_GAME", category DEFAULT (the explicit component is
 *    what's required; the action is a secondary marker).
 *  - String extras: game_id (required, echoed back), game_path (required, absolute game folder),
 *    startup_file (optional configured entry point), title (optional display title).
 *  - The runner requires MANAGE_EXTERNAL_STORAGE to read arbitrary game folders and verifies the
 *    caller (package + signing cert), so the launch MUST go through StartActivityForResult:
 *      RESULT_OK + success=true  -> accepted
 *      RESULT_OK + success=false -> error_code + error_message
 *      RESULT_CANCELED           -> untrusted/rejected caller (no detail leaked)
 *  - Presence + capabilities: query PackageManager for the package, and read the read-only
 *    capabilities provider (authority "<package>.capabilities", single row, column "json") whose
 *    apiVersion is the version key.
 */
object KirikiroidLauncher {

    const val LAUNCH_ACTIVITY = "org.github.krkr2.LaunchGameActivity"
    const val ACTION_LAUNCH_GAME = "com.agm.kirikiri.action.LAUNCH_GAME"

    /** The AGM-integration build AGM distributes + version-controls (carries our caller-cert allowlist). */
    const val MANAGED_PACKAGE = "com.agm.krkr2"

    /**
     * Candidate package names, in preference order. The AGM-managed build (com.agm.krkr2) is
     * preferred when present — it is the one AGM version-controls — falling back to the upstream
     * prod build (org.github.krkr2). Both share the same LaunchGameActivity + launch contract.
     */
    val CANDIDATE_PACKAGES = listOf(MANAGED_PACKAGE, "org.github.krkr2")

    const val EXTRA_GAME_ID = "game_id"
    const val EXTRA_GAME_PATH = "game_path"
    const val EXTRA_STARTUP_FILE = "startup_file"
    const val EXTRA_TITLE = "title"

    const val RESULT_EXTRA_SUCCESS = "success"
    const val RESULT_EXTRA_ERROR_CODE = "error_code"
    const val RESULT_EXTRA_ERROR_MESSAGE = "error_message"

    /** Returns the installed Kirikiroid2 package name (prod preferred, then AGM test build), or null. */
    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        return CANDIDATE_PACKAGES.firstOrNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * Builds the launch intent for [app], targeting the installed Kirikiroid2 explicitly.
     * @return the ready-to-launch Intent, or null when the runner isn't installed or the game has
     *   no absolute folder path (Kirikiroid needs a plain File path; there is no content:// path in v1).
     */
    fun buildLaunchIntent(context: Context, app: InstalledApp): Intent? {
        if (app.source != AppSource.Kirikiroid && app.source != AppSource.Managed) return null
        val pkg = installedPackage(context) ?: return null
        val folder = app.storagePath?.trim()?.ifBlank { null } ?: return null
        return Intent(ACTION_LAUNCH_GAME).apply {
            component = ComponentName(pkg, LAUNCH_ACTIVITY)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(EXTRA_GAME_ID, app.packageName)
            putExtra(EXTRA_GAME_PATH, folder)
            resolveStartupFile(folder, app.kirikiroidStartupPath)?.let {
                putExtra(EXTRA_STARTUP_FILE, it)
            }
            app.label.trim().takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_TITLE, it) }
        }
    }

    internal fun resolveStartupFile(folder: String, configuredPath: String?): String? {
        val path = configuredPath?.trim()?.ifBlank { null } ?: return null
        val file = File(path).let { if (it.isAbsolute) it else File(folder, path) }
        if (!file.isFile || !file.canRead()) return null
        val dataXp3 = File(file.parentFile, "data.xp3")
        return if (
            file.extension.equals("xp3", ignoreCase = true) &&
            !file.name.equals("data.xp3", ignoreCase = true) &&
            dataXp3.isFile &&
            dataXp3.canRead()
        ) {
            dataXp3.absolutePath
        } else {
            file.absolutePath
        }
    }

    /**
     * Interprets a StartActivityForResult callback. Returns null on success (the game was accepted
     * and launched), or a human-readable error string on failure.
     */
    fun interpretResult(resultCode: Int, data: Intent?): String? {
        if (resultCode == android.app.Activity.RESULT_CANCELED) {
            return "Kirikiroid2 rejected the launch (the emulator didn't recognise this app as a trusted caller)."
        }
        val success = data?.getBooleanExtra(RESULT_EXTRA_SUCCESS, false) ?: false
        if (success) return null
        val code = data?.getStringExtra(RESULT_EXTRA_ERROR_CODE)
        val message = data?.getStringExtra(RESULT_EXTRA_ERROR_MESSAGE)
        return when {
            code == "PERMISSION_REQUIRED" ->
                "Kirikiroid2 needs all-files access to read the game folder. Grant it in Kirikiroid2's settings."
            !message.isNullOrBlank() -> "Kirikiroid2 couldn't start the game: $message"
            !code.isNullOrBlank() -> "Kirikiroid2 couldn't start the game ($code)."
            else -> "Kirikiroid2 couldn't start the game."
        }
    }

    /**
     * Reads the runner's advertised capability apiVersion via its read-only capabilities provider,
     * or null when the provider is absent (assume launch-only, apiVersion 1).
     */
    fun capabilityApiVersion(context: Context): Int? {
        val pkg = installedPackage(context) ?: return null
        val authority = "$pkg.capabilities"
        val uri = android.net.Uri.parse("content://$authority")
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val col = cursor.getColumnIndex("json")
                if (col < 0) return@use null
                val json = cursor.getString(col) ?: return@use null
                JSONObject(json).optInt("apiVersion", 1)
            }
        }.getOrNull()
    }
}
