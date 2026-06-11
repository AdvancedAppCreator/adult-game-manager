package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands a file off to JoiPlay's import flow.
 *
 * What JoiPlay actually accepts (from the manifest dump of v1.21.000):
 *
 *  • `SplashActivity` (the main launcher) responds to ACTION_VIEW on file:// or
 *    content:// URIs ending in: .exe .sh .py .html .swf — JoiPlay auto-detects the
 *    engine from the file/folder layout and imports the containing game folder.
 *  • `InstallerActivity` responds to ACTION_VIEW on file:// or content:// URIs
 *    ending in .jgp (JoiPlay's own package format) or joiplay:// URIs.
 *
 * There's no documented JoiPlay action that accepts a raw zip/rar/7z and extracts
 * it. The user must unzip with their file manager first, then point at a
 * recognizable launch file inside the game folder.
 */
object JoiPlayInstaller {

    /** Archives JoiPlay won't ingest directly — user must extract first. */
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    /**
     * Build an Intent to hand a file off to JoiPlay's import flow.
     * Returns null if JoiPlay can't accept the file (caller should show a snackbar).
     */
    suspend fun buildIntent(context: Context, uri: Uri): Intent? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri).lowercase()
        val ext = name.substringAfterLast('.', "")
        AppLog.i("JoiPlayInstall", "buildIntent uri=$uri displayName=$name ext=$ext")

        val joiplayInstalled = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("cyou.joiplay.joiplay", 0); true
        }.getOrDefault(false)
        if (!joiplayInstalled) {
            AppLog.w("JoiPlayInstall", "JoiPlay isn't installed")
            return@withContext null
        }
        if (ext in archiveExtensions) {
            AppLog.w("JoiPlayInstall", "Archive extension .$ext — JoiPlay doesn't extract archives")
            return@withContext null
        }
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, mimeType)
            setPackage("cyou.joiplay.joiplay")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // NOTE: do NOT add FLAG_ACTIVITY_NEW_TASK here — that would break
            // startActivityForResult (Android refuses to deliver results across tasks).
        }
        val resolved = context.packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            AppLog.w("JoiPlayInstall", "No JoiPlay activity claims ACTION_VIEW on $name (mime=$mimeType ext=$ext)")
            return@withContext null
        }
        AppLog.i("JoiPlayInstall", "Dispatching to JoiPlay (${resolved.activityInfo.name}) for $name (mime=$mimeType)")
        intent
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment ?: "file"
    }
}
