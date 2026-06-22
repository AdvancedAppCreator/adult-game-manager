package com.example.f95updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.content.pm.ResolveInfo

/**
 * Opens a filesystem folder in the user's file manager.
 *
 * Native strategy:
 *  1. ACTION_VIEW on a DocumentsUI document URI with mime "vnd.android.document/directory".
 *  2. ACTION_VIEW on the document URI without an explicit mime (lets file managers that
 *     don't register for the directory mime pick it up via DEFAULT category).
 *  3. ACTION_OPEN_DOCUMENT_TREE with the target folder as initial location if no folder
 *     viewer is registered. This opens Android's native folder picker at/near the folder.
 *
 * Folder-view attempts are launched as implicit intents. Android will either use the
 * saved default app or show its native "Just once / Always" resolver when possible.
 *
 * Returns a Result containing a user-visible message describing the outcome — the caller
 * is expected to show it via snackbar so failures aren't silent.
 */
object FolderOpener {

    data class Result(val ok: Boolean, val message: String)

    fun open(context: Context, absolutePath: String): Result {
        val relative = relativeOnPrimary(absolutePath)
            ?: return Result(false, "Folder is not on primary storage: $absolutePath")

        val ungrantedDocId = if (relative.isEmpty()) "primary:" else "primary:$relative"
        val ungrantedDocUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/" + Uri.encode(ungrantedDocId)
        )
        val grantedDocUri = grantedDocumentUriFor(context, relative)
        val docUri = grantedDocUri ?: ungrantedDocUri
        AppLog.i(
            "OpenFolder",
            "Target relative='$relative' grantedUri=${grantedDocUri != null} uri=$docUri",
        )

        val viewCandidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW, docUri).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )

        for ((index, intent) in viewCandidates.withIndex()) {
            val handlers = context.packageManager.queryIntentActivities(intent, 0)
            AppLog.i(
                "OpenFolder",
                "ACTION_VIEW candidate[$index] data=${intent.data} type=${intent.type} handlers=${handlers.describe()}",
            )
            val nonSystemHandlers = handlers.filterNot { it.isSystemFolderHandler() }
            if (nonSystemHandlers.isEmpty()) {
                AppLog.i("OpenFolder", "No non-system folder handlers for candidate[$index]; skipping direct ACTION_VIEW")
                continue
            }
            val resolved = context.packageManager.resolveActivity(intent, 0)
            AppLog.i(
                "OpenFolder",
                "Launching raw ACTION_VIEW candidate[$index]; resolved=${resolved?.describeOne()} nonSystem=${nonSystemHandlers.describe()}",
            )
            runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return Result(true, "Opening folder…")
            }.onFailure {
                AppLog.w("OpenFolder", "ACTION_VIEW candidate[$index] failed", it)
            }
        }

        val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (context.packageManager.resolveActivity(treeIntent, 0) != null) {
            runCatching {
                AppLog.i("OpenFolder", "Falling back to ACTION_OPEN_DOCUMENT_TREE initialUri=$docUri")
                context.startActivity(treeIntent)
                return Result(true, "Opening Android folder picker…")
            }
        }
        return Result(false, "No Android folder picker is available.")
    }

    private fun relativeOnPrimary(absolutePath: String): String? {
        val f = java.io.File(absolutePath)
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

    private fun grantedDocumentUriFor(context: Context, relativePath: String): Uri? {
        val target = relativePath.trim('/').replace('\\', '/')
        for (grant in context.contentResolver.persistedUriPermissions) {
            if (!grant.isReadPermission) continue
            val uri = grant.uri
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: continue
            if (!treeId.startsWith("primary:")) continue
            val treeRel = treeId.removePrefix("primary:").trim('/')
            val covers = when {
                treeRel.isBlank() -> true
                target == treeRel -> true
                target.startsWith("$treeRel/") -> true
                else -> false
            }
            if (!covers) continue
            val docId = if (target.isBlank()) "primary:" else "primary:$target"
            return DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        }
        return null
    }

    private fun ResolveInfo.isSystemFolderHandler(): Boolean {
        val pkg = activityInfo?.packageName.orEmpty()
        return pkg == "com.google.android.documentsui" ||
            pkg == "com.android.documentsui" ||
            pkg == "android"
    }

    private fun ResolveInfo.describeOne(): String =
        "${activityInfo?.packageName}/${activityInfo?.name}"

    private fun List<ResolveInfo>.describe(): String =
        if (isEmpty()) "[]" else joinToString(prefix = "[", postfix = "]") { it.describeOne() }
}
