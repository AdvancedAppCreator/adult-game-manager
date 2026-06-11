package com.example.f95updater

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

private val Context.joiplayStore by preferencesDataStore("joiplay_settings")
private val JOIPLAY_URI_KEY = stringPreferencesKey("joiplay_games_uri")
private val JOIPLAY_SIZES_KEY = stringPreferencesKey("joiplay_folder_sizes_json")
private val JOIPLAY_SIZE_INFO_KEY = stringPreferencesKey("joiplay_folder_size_info_json")

object JoiPlayScanner {
    private val versionRe = Regex("""[-_ ]v?(\d+(?:\.\d+)+[a-zA-Z0-9]*)""")
    private val json = Json { ignoreUnknownKeys = true }
    private val sizeMapSer = MapSerializer(String.serializer(), Long.serializer())
    private val sizeInfoMapSer = MapSerializer(String.serializer(), SizeInfo.serializer())

    @kotlinx.serialization.Serializable
    data class SizeInfo(
        val totalBytes: Long = 0L,
        val gameBytes: Long = 0L,
        val saveBytes: Long = 0L,
        val backupBytes: Long = 0L,
        val lastScannedAt: Long = 0L,
    ) {
        val otherBytes: Long
            get() = (totalBytes - gameBytes - saveBytes - backupBytes).coerceAtLeast(0L)
    }

    suspend fun getRootUri(context: Context): Uri? =
        context.joiplayStore.data.map { it[JOIPLAY_URI_KEY] }.first()?.let(Uri::parse)

    suspend fun setRootUri(context: Context, uri: Uri?) {
        context.joiplayStore.edit {
            if (uri == null) it.remove(JOIPLAY_URI_KEY)
            else it[JOIPLAY_URI_KEY] = uri.toString()
        }
    }

    suspend fun loadSizeCache(context: Context): Map<String, Long> = withContext(Dispatchers.IO) {
        loadSizeInfo(context).mapValues { it.value.totalBytes }
    }

    suspend fun loadSizeInfo(context: Context): Map<String, SizeInfo> = withContext(Dispatchers.IO) {
        val prefs = context.joiplayStore.data.first()
        prefs[JOIPLAY_SIZE_INFO_KEY]?.let { text ->
            runCatching { json.decodeFromString(sizeInfoMapSer, text) }
                .getOrNull()
                ?.let { return@withContext it }
        }
        val text = prefs[JOIPLAY_SIZES_KEY] ?: return@withContext emptyMap()
        runCatching { json.decodeFromString(sizeMapSer, text) }.getOrDefault(emptyMap())
            .mapValues { (_, bytes) ->
                SizeInfo(
                    totalBytes = bytes,
                    gameBytes = bytes,
                    lastScannedAt = 0L,
                )
            }
    }

    suspend fun saveSizeCache(context: Context, sizes: Map<String, Long>) = withContext(Dispatchers.IO) {
        context.joiplayStore.edit { it[JOIPLAY_SIZES_KEY] = json.encodeToString(sizeMapSer, sizes) }
    }

    suspend fun saveSizeInfo(context: Context, sizes: Map<String, SizeInfo>) = withContext(Dispatchers.IO) {
        context.joiplayStore.edit {
            it[JOIPLAY_SIZE_INFO_KEY] = json.encodeToString(sizeInfoMapSer, sizes)
            it[JOIPLAY_SIZES_KEY] = json.encodeToString(sizeMapSer, sizes.mapValues { entry -> entry.value.totalBytes })
        }
    }

    /** Fast scan: lists subfolders only. Pre-fills apkSize from the cached size map. */
    suspend fun scan(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val uri = getRootUri(context) ?: return@withContext emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return@withContext emptyList()
        if (!root.isDirectory) return@withContext emptyList()
        val sizes = loadSizeInfo(context)
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val name = folder.name ?: return@mapNotNull null
                if (name.startsWith(".")) return@mapNotNull null
                val (label, version) = extractLabelAndVersion(name)
                val lastModified = runCatching { folder.lastModified() }.getOrDefault(0L)
                InstalledApp(
                    packageName = "joiplay:$name",
                    label = label,
                    versionName = version ?: "",
                    versionCode = 0L,
                    firstInstallTime = lastModified,
                    lastUpdateTime = lastModified,
                    lastUsedTime = 0L,
                    apkSize = sizes[name]?.totalBytes ?: 0L,
                    dataSize = 0L,
                    cacheSize = 0L,
                    source = AppSource.JoiPlay,
                    storagePath = name,
                    storageFolderName = name,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Returns just the folder names (no IO beyond the top-level listing). */
    suspend fun listFolderNames(context: Context): List<String> = withContext(Dispatchers.IO) {
        val uri = getRootUri(context) ?: return@withContext emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return@withContext emptyList()
        if (!root.isDirectory) return@withContext emptyList()
        root.listFiles()
            .filter { it.isDirectory && (it.name?.startsWith(".") != true) }
            .mapNotNull { it.name }
            .sorted()
    }

    data class SizeProgress(
        val folderName: String,
        val folderIndex: Int,   // 1-based
        val folderTotal: Int,
        val bytesSoFar: Long,
    )

    data class SizeTarget(
        val key: String,
        val label: String,
        val storagePath: String?,
        val folderName: String?,
    )

    /**
     * Computes sizes recursively for all top-level folders, calling [onProgress] periodically.
     * Cooperative cancellation: bails out as soon as the coroutine context becomes inactive.
     * Persists each completed folder's size to the cache as it goes (so cancel preserves work).
     * Returns the final accumulated size-info cache.
     */
    suspend fun computeAllSizes(
        context: Context,
        recompute: Boolean = false,
        onProgress: suspend (SizeProgress) -> Unit,
    ): Map<String, SizeInfo> = withContext(Dispatchers.IO) {
        val uri = getRootUri(context) ?: return@withContext emptyMap()
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return@withContext emptyMap()
        if (!root.isDirectory) return@withContext emptyMap()
        val folders = root.listFiles()
            .filter { it.isDirectory && (it.name?.startsWith(".") != true) }
            .sortedBy { it.name?.lowercase() ?: "" }
        val cache = loadSizeInfo(context).toMutableMap()
        for ((idx, folder) in folders.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            val name = folder.name ?: continue
            if (!recompute && cache.containsKey(name)) {
                onProgress(SizeProgress(name, idx + 1, folders.size, cache[name]?.totalBytes ?: 0L))
                continue
            }
            val breakdown = computeFolderSizeCooperative(folder) { running ->
                onProgress(SizeProgress(name, idx + 1, folders.size, running))
            }
            if (!currentCoroutineContext().isActive) break  // ditched mid-folder
            cache[name] = breakdown.toSizeInfo(System.currentTimeMillis())
            saveSizeInfo(context, cache)   // persist incrementally
        }
        cache
    }

    /**
     * Computes sizes for concrete JoiPlay game rows. Backup imports can point to nested
     * game folders, so this must not assume every game is a direct child of the granted root.
     */
    suspend fun computeTargetSizes(
        context: Context,
        targets: List<SizeTarget>,
        recompute: Boolean = false,
        onProgress: suspend (SizeProgress) -> Unit,
    ): Map<String, SizeInfo> = withContext(Dispatchers.IO) {
        val uri = getRootUri(context) ?: return@withContext emptyMap()
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return@withContext emptyMap()
        if (!root.isDirectory) return@withContext emptyMap()
        val distinctTargets = targets
            .filter { it.key.isNotBlank() }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
        val cache = loadSizeInfo(context).toMutableMap()
        for ((idx, target) in distinctTargets.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            if (!recompute && (cache[target.key]?.lastScannedAt ?: 0L) > 0L) {
                onProgress(SizeProgress(target.label, idx + 1, distinctTargets.size, cache[target.key]?.totalBytes ?: 0L))
                continue
            }
            val folder = resolveTarget(
                root = root,
                folderName = target.folderName ?: target.key,
                storagePath = target.storagePath,
                rootUri = uri,
            )
            if (folder == null || !folder.isDirectory) {
                AppLog.w(
                    "JoiPlaySize",
                    "Size target not found: key='${target.key}' label='${target.label}' " +
                        "folderName='${target.folderName}' storagePath='${target.storagePath}'"
                )
                continue
            }
            val breakdown = computeFolderSizeCooperative(folder) { running ->
                onProgress(SizeProgress(target.label, idx + 1, distinctTargets.size, running))
            }
            if (!currentCoroutineContext().isActive) break
            cache[target.key] = breakdown.toSizeInfo(System.currentTimeMillis())
            saveSizeInfo(context, cache)
        }
        cache
    }

    private data class SizeBreakdown(
        val gameBytes: Long = 0L,
        val saveBytes: Long = 0L,
        val backupBytes: Long = 0L,
        val otherBytes: Long = 0L,
    ) {
        val totalBytes: Long get() = gameBytes + saveBytes + backupBytes + otherBytes

        fun plus(category: SizeCategory, bytes: Long): SizeBreakdown = when (category) {
            SizeCategory.Game -> copy(gameBytes = gameBytes + bytes)
            SizeCategory.Save -> copy(saveBytes = saveBytes + bytes)
            SizeCategory.Backup -> copy(backupBytes = backupBytes + bytes)
            SizeCategory.Other -> copy(otherBytes = otherBytes + bytes)
        }

        fun toSizeInfo(scannedAt: Long): SizeInfo = SizeInfo(
            totalBytes = totalBytes,
            gameBytes = gameBytes,
            saveBytes = saveBytes,
            backupBytes = backupBytes,
            lastScannedAt = scannedAt,
        )
    }

    private enum class SizeCategory { Game, Save, Backup, Other }

    private data class SizeNode(
        val file: DocumentFile,
        val category: SizeCategory,
    )

    private suspend fun computeFolderSizeCooperative(
        folder: DocumentFile,
        onProgress: suspend (runningTotal: Long) -> Unit,
    ): SizeBreakdown {
        var breakdown = SizeBreakdown()
        var since = 0L
        val stack = ArrayDeque<SizeNode>()
        stack.addLast(SizeNode(folder, SizeCategory.Game))
        var counter = 0
        while (stack.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) return breakdown
            val node = stack.removeFirst()
            val cur = node.file
            if (cur.isFile) {
                val bytes = cur.length()
                breakdown = breakdown.plus(node.category, bytes)
                since += 1
            } else if (cur.isDirectory) {
                val childCategory = sizeCategoryFor(cur.name, node.category)
                cur.listFiles().forEach { stack.addLast(SizeNode(it, childCategory)) }
            }
            counter++
            // Throttle progress callbacks so we don't spam the UI.
            if (counter % 50 == 0) onProgress(breakdown.totalBytes)
        }
        return breakdown
    }

    private fun sizeCategoryFor(name: String?, inherited: SizeCategory): SizeCategory {
        if (inherited == SizeCategory.Backup) return SizeCategory.Backup
        val normalized = name?.lowercase()?.trim('.', '_', '-', ' ') ?: return inherited
        return when {
            normalized.startsWith("bak-") ||
                normalized.startsWith("backup") ||
                normalized.contains("rollback") ||
                normalized.matches(Regex("""bak[-_ ]?\d{8}.*""")) -> SizeCategory.Backup
            inherited != SizeCategory.Save && normalized in setOf(
                "save",
                "saves",
                "savedata",
                "save data",
                "save_data",
                "userdata",
                "user data",
                "user_data",
            ) -> SizeCategory.Save
            inherited == SizeCategory.Save -> SizeCategory.Save
            else -> inherited
        }
    }

    /** Deletes the JoiPlay game folder by name (under the configured root). Returns true on success.
     *  If [storagePath] is provided (absolute filesystem path from the backup), we try to walk
     *  the granted-tree relative-path so games nested under sub-folders work, not just direct
     *  children. */
    suspend fun deleteFolder(
        context: Context,
        folderName: String,
        storagePath: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val uri = getRootUri(context)
        if (uri == null) {
            AppLog.w("JoiPlayDelete", "No JoiPlay root URI configured")
            return@withContext false
        }
        val persisted = runCatching { context.contentResolver.persistedUriPermissions }
            .getOrDefault(emptyList())
        val perm = persisted.firstOrNull { it.uri == uri }
        if (perm == null) {
            AppLog.w("JoiPlayDelete", "URI not in persistedUriPermissions: $uri (granted=${persisted.size} others). Re-grant the JoiPlay games folder.")
            return@withContext false
        }
        if (!perm.isWritePermission) {
            AppLog.w("JoiPlayDelete", "Read-only grant (no write): re-grant with write access. uri=$uri")
            return@withContext false
        }
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (root == null) {
            AppLog.w("JoiPlayDelete", "DocumentFile.fromTreeUri returned null for $uri")
            return@withContext false
        }
        if (!root.isDirectory) {
            AppLog.w("JoiPlayDelete", "Root is not a directory (revoked/moved?): $uri")
            return@withContext false
        }

        // If the target File path is reachable via plain java.io.File and the path
        // simply doesn't exist on disk, treat the delete as a no-op success: the user
        // wants the row gone, and the underlying folder is already gone. This matches
        // the user expectation that "delete on a missing folder should succeed".
        if (!storagePath.isNullOrBlank()) {
            val direct = runCatching { File(storagePath).exists() }.getOrDefault(true)
            if (!direct) {
                AppLog.i("JoiPlayDelete", "Folder already gone on disk: '$storagePath' (treating as success)")
                return@withContext true
            }
        }

        // Strategy A: walk relative path from granted root using storagePath if available.
        val target = resolveTarget(root, folderName, storagePath, uri)
        if (target == null) {
            // Folder isn't reachable under the granted SAF tree. If we *also* couldn't
            // see it via File.exists() above (or no storagePath was provided), check
            // the tree-existence one more time and call it a success if missing.
            val existence = runCatching { folderExists(context, storagePath, folderName) }
                .getOrDefault(null)
            if (existence == false) {
                AppLog.i("JoiPlayDelete", "Folder confirmed missing under granted root: name='$folderName' storagePath='$storagePath' (treating as success)")
                return@withContext true
            }
            val grantedRootHint = grantedRootHint(uri)
            AppLog.w(
                "JoiPlayDelete",
                "Folder not found: name='$folderName' storagePath='$storagePath' grantedRoot=$grantedRootHint. " +
                        "Re-grant a higher-level folder that contains the game's actual location."
            )
            return@withContext false
        }
        if (!target.isDirectory) {
            AppLog.w("JoiPlayDelete", "Target is not a directory: '$folderName' uri=${target.uri}")
            return@withContext false
        }
        if (!target.canWrite()) {
            AppLog.w("JoiPlayDelete", "canWrite()=false on target '$folderName' uri=${target.uri}")
            return@withContext false
        }
        val ok = runCatching { target.delete() }
            .onFailure { AppLog.e("JoiPlayDelete", "target.delete() threw for '$folderName'", it) }
            .getOrDefault(false)
        AppLog.i("JoiPlayDelete", "delete '$folderName' -> $ok (uri=${target.uri})")
        if (ok) {
            val sizes = loadSizeInfo(context).toMutableMap()
            sizes.remove(folderName)
            saveSizeInfo(context, sizes)
        }
        ok
    }

    /** Try to locate the target DocumentFile within [root].
     *  1. If [storagePath] is absolute, compute its relative path under the granted root's
     *     filesystem path (e.g. /storage/emulated/<u>/Games/) and walk segment by segment.
     *  2. Otherwise fall back to a direct child lookup by [folderName]. */
    private fun resolveTarget(
        root: DocumentFile,
        folderName: String,
        storagePath: String?,
        rootUri: Uri,
    ): DocumentFile? {
        if (!storagePath.isNullOrBlank()) {
            val normalized = storagePath.replace('\\', '/').trimEnd('/')
            val rootFsPath = grantedRootHint(rootUri)
            if (rootFsPath != null && normalized.startsWith(rootFsPath)) {
                val rel = normalized.removePrefix(rootFsPath).trim('/')
                if (rel.isNotEmpty()) {
                    var cursor: DocumentFile? = root
                    for (seg in rel.split('/')) {
                        cursor = cursor?.findFile(seg) ?: return null
                    }
                    if (cursor != null && cursor !== root) return cursor
                }
            }
        }
        return root.findFile(folderName)
    }

    /** Translate a tree URI like content://.../tree/primary%3AGames into /storage/emulated/<u>/Games/ */
    private fun grantedRootHint(uri: Uri): String? {
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volume, relative) = parts
        if (volume != "primary") return null
        val envRoot = runCatching {
            android.os.Environment.getExternalStorageDirectory()?.canonicalPath
        }.getOrNull() ?: "/storage/emulated/0"
        val base = if (envRoot.endsWith("/")) envRoot else "$envRoot/"
        return if (relative.isBlank()) base else "$base$relative/"
    }

    /**
     * Best-effort folder existence check.
     *   true  - exists (File API or SAF tree found it)
     *   false - definitely missing (SAF can see the granted root and folder isn't there)
     *   null  - unknown (path is outside any granted root, File.exists() returned false but
     *           that may be a permissions artifact)
     */
    suspend fun folderExists(
        context: Context,
        storagePath: String?,
        folderName: String?,
    ): Boolean? = withContext(Dispatchers.IO) {
        if (!storagePath.isNullOrBlank()) {
            runCatching { if (File(storagePath).exists()) return@withContext true }
        }
        val uri = getRootUri(context) ?: return@withContext null
        val rootFs = grantedRootHint(uri) ?: return@withContext null
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return@withContext null
        if (!root.isDirectory) return@withContext null
        if (!storagePath.isNullOrBlank()) {
            val norm = storagePath.replace('\\', '/').trimEnd('/')
            if (norm.startsWith(rootFs)) {
                val rel = norm.removePrefix(rootFs).trim('/')
                if (rel.isEmpty()) return@withContext true
                var cursor: DocumentFile? = root
                for (seg in rel.split('/')) {
                    cursor = cursor?.findFile(seg) ?: return@withContext false
                }
                return@withContext cursor != null
            }
            return@withContext null
        }
        if (!folderName.isNullOrBlank()) {
            return@withContext root.findFile(folderName) != null
        }
        null
    }

    private fun extractLabelAndVersion(folderName: String): Pair<String, String?> {
        val m = versionRe.find(folderName)
        return if (m != null) {
            val label = folderName.substring(0, m.range.first).trim().trim('-', '_', ' ')
            label.ifBlank { folderName } to m.groupValues[1]
        } else {
            folderName to null
        }
    }
}
