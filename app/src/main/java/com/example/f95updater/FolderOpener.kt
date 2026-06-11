package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Opens a filesystem folder in the user's file manager.
 *
 * Multi-stage strategy (each stage is attempted until one succeeds):
 *  1. ACTION_VIEW on a DocumentsUI document URI with mime "vnd.android.document/directory".
 *  2. ACTION_VIEW on the document URI without an explicit mime (lets file managers that
 *     don't register for the directory mime pick it up via DEFAULT category).
 *  3. ACTION_VIEW on a file:// folder URI with common folder mime variants.
 *
 * All attempts are implicit, not package-targeted and not wrapped in createChooser(), so
 * Android can use the user's default app or show the native resolver that supports setting
 * a default for this action/type. We intentionally do not fall back to
 * ACTION_OPEN_DOCUMENT_TREE here: that is a permission picker with a "Use this folder"
 * button, not an "open this folder in my preferred app" flow.
 *
 * Returns a Result containing a user-visible message describing the outcome — the caller
 * is expected to show it via snackbar so failures aren't silent.
 */
object FolderOpener {

    data class Result(val ok: Boolean, val message: String)

    fun open(context: Context, absolutePath: String): Result {
        val relative = relativeOnPrimary(absolutePath)
            ?: return Result(false, "Folder is not on primary storage: $absolutePath")

        val encodedDocId = if (relative.isEmpty()) "primary:" else "primary:$relative"
        val docUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/" + Uri.encode(encodedDocId)
        )
        val fileUri = Uri.fromFile(File(absolutePath))

        // Stage 1: ACTION_VIEW + directory mime. Start the implicit intent directly so
        // Android can honor or set the user's default app for this action/type.
        val viewCandidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW, docUri).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW, fileUri).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )

        for (intent in viewCandidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) == null) continue
            runCatching {
                context.startActivity(intent)
                return Result(true, "Opening folder…")
            }
        }
        return Result(false, "No file manager app is registered to open folders.")
    }

    private fun relativeOnPrimary(absolutePath: String): String? {
        val f = File(absolutePath)
        val canonical = runCatching { f.canonicalPath }.getOrElse { absolutePath }

        // 1. Runtime-resolved primary storage root (works in Samsung Secure Folder where
        //    the user ID is e.g. /storage/emulated/150/ rather than /0/).
        val envRoot = runCatching {
            android.os.Environment.getExternalStorageDirectory()?.canonicalPath
        }.getOrNull()
        if (!envRoot.isNullOrBlank()) {
            val rootWithSlash = if (envRoot.endsWith("/")) envRoot else "$envRoot/"
            if (canonical == envRoot) return ""
            if (canonical.startsWith(rootWithSlash)) {
                return canonical.removePrefix(rootWithSlash).trimStart('/')
            }
        }

        // 2. Generic /storage/emulated/<userId>/ match for any user profile (Secure Folder,
        //    Work profile, multi-user). Fallback if Environment lookup failed.
        val emulatedAny = Regex("^/storage/emulated/(\\d+)/?(.*)$").matchEntire(canonical)
        if (emulatedAny != null) {
            return emulatedAny.groupValues[2].trimStart('/')
        }

        // 3. Legacy primary-storage alias.
        val roots = listOf("/storage/self/primary/")
        for (root in roots) {
            if (canonical.startsWith(root)) {
                return canonical.removePrefix(root).trimStart('/')
            }
        }
        return null
    }
}
