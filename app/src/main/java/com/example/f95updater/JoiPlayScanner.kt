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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val sizeScanMutex = Mutex()
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
        /**
         * The scanned folder's [File.lastModified] at scan time. Used as a cheap change
         * signature: an incremental scan re-sizes a folder only when its current mtime differs
         * (0 for legacy cache entries forces a one-time re-size after upgrade).
         */
        val dirMtime: Long = 0L,
    ) {
        val otherBytes: Long
            get() = (totalBytes - gameBytes - saveBytes - backupBytes).coerceAtLeast(0L)
    }

    suspend fun getRootUri(context: Context): Uri? =
        JoiPlaySettingsStore.extractDestUri(context)?.let(Uri::parse)
            ?: context.joiplayStore.data.map { it[JOIPLAY_URI_KEY] }.first()?.let(Uri::parse)

    suspend fun setRootUri(context: Context, uri: Uri?) {
        context.joiplayStore.edit {
            if (uri == null) it.remove(JOIPLAY_URI_KEY)
            else it[JOIPLAY_URI_KEY] = uri.toString()
        }
        JoiPlaySettingsStore.setExtractDestUri(context, uri?.toString())
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
        val root = documentRoot(context, uri) ?: return@withContext emptyList()
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
                    storagePath = absoluteFolderPath(folder) ?: name,
                    storageFolderName = name,
                    readmeTitle = readGameReadmeTitle(context, folder),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Returns just the folder names (no IO beyond the top-level listing). */
    suspend fun listFolderNames(context: Context): List<String> = withContext(Dispatchers.IO) {
        val uri = getRootUri(context) ?: return@withContext emptyList()
        val root = documentRoot(context, uri) ?: return@withContext emptyList()
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
    ): Map<String, SizeInfo> = sizeScanMutex.withLock {
        withContext(Dispatchers.IO) {
        val uri = getRootUri(context) ?: return@withContext emptyMap()
        val root = documentRoot(context, uri) ?: return@withContext emptyMap()
        if (!root.isDirectory) return@withContext emptyMap()
        val folders = root.listFiles()
            .filter { it.isDirectory && (it.name?.startsWith(".") != true) }
            .sortedBy { it.name?.lowercase() ?: "" }
        val cache = loadSizeInfo(context).toMutableMap()
        for ((idx, folder) in folders.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            val name = folder.name ?: continue
            val mtime = folderMtimeOf(folder)
            val cached = cache[name]
            if (!recompute && cached != null && cached.lastScannedAt > 0L && cached.dirMtime == mtime) {
                onProgress(SizeProgress(name, idx + 1, folders.size, cached.totalBytes))
                continue
            }
            val breakdown = computeFolderSize(folder) { running ->
                onProgress(SizeProgress(name, idx + 1, folders.size, running))
            }
            if (!currentCoroutineContext().isActive) break  // ditched mid-folder
            cache[name] = breakdown.toSizeInfo(System.currentTimeMillis(), mtime)
            saveSizeInfo(context, cache)   // persist incrementally
        }
            cache
        }
    }

    /**
     * Computes sizes for concrete JoiPlay game rows. Backup imports can point to game folders
     * nested anywhere (outside the single configured root), so resolution prefers direct [File]
     * access — AGM holds MANAGE_EXTERNAL_STORAGE in its own profile, so a game's absolute
     * storagePath is readable even when the SAF navigation from the configured root can't reach it.
     * Only when the absolute path isn't directly accessible do we fall back to the SAF resolver.
     */
    suspend fun computeTargetSizes(
        context: Context,
        targets: List<SizeTarget>,
        recompute: Boolean = false,
        onProgress: suspend (SizeProgress) -> Unit,
    ): Map<String, SizeInfo> = sizeScanMutex.withLock {
        withContext(Dispatchers.IO) {
        val uri = getRootUri(context)
        val root = uri?.let { documentRoot(context, it) }?.takeIf { it.isDirectory }
        val distinctTargets = targets
            .filter { it.key.isNotBlank() }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
        val cache = loadSizeInfo(context).toMutableMap()
        for ((idx, target) in distinctTargets.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            // Preferred: the game's absolute path is directly File-readable.
            val directFile = target.storagePath
                ?.let { runCatching { File(it) }.getOrNull() }
                ?.takeIf { runCatching { it.isDirectory && it.canRead() }.getOrDefault(false) }

            // Fallback: resolve a SAF DocumentFile from the configured root (single granted tree).
            val folder: DocumentFile? = if (directFile != null || root == null || uri == null) {
                null
            } else {
                resolveTarget(
                    root = root,
                    folderName = target.folderName ?: target.key,
                    storagePath = target.storagePath,
                    rootUri = uri,
                )?.takeIf { it.isDirectory }
            }

            if (directFile == null && folder == null) {
                AppLog.w(
                    "JoiPlaySize",
                    "Size target not found: key='${target.key}' label='${target.label}' " +
                        "folderName='${target.folderName}' storagePath='${target.storagePath}'"
                )
                continue
            }

            val mtime = if (directFile != null) {
                runCatching { directFile.lastModified() }.getOrDefault(0L)
            } else {
                folderMtimeOf(folder!!)
            }
            val cached = cache[target.key]
            if (!recompute && cached != null && cached.lastScannedAt > 0L && cached.dirMtime == mtime) {
                onProgress(SizeProgress(target.label, idx + 1, distinctTargets.size, cached.totalBytes))
                continue
            }
            val progress: suspend (Long) -> Unit = { running ->
                onProgress(SizeProgress(target.label, idx + 1, distinctTargets.size, running))
            }
            val breakdown = if (directFile != null) {
                computeFolderSizeFast(directFile, progress)
            } else {
                computeFolderSize(folder!!, progress)
            }
            if (!currentCoroutineContext().isActive) break
            cache[target.key] = breakdown.toSizeInfo(System.currentTimeMillis(), mtime)
            saveSizeInfo(context, cache)
        }
            cache
        }
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

        fun toSizeInfo(scannedAt: Long, dirMtime: Long): SizeInfo = SizeInfo(
            totalBytes = totalBytes,
            gameBytes = gameBytes,
            saveBytes = saveBytes,
            backupBytes = backupBytes,
            lastScannedAt = scannedAt,
            dirMtime = dirMtime,
        )
    }

    private enum class SizeCategory { Game, Save, Backup, Other }

    private data class SizeNode(
        val file: DocumentFile,
        val category: SizeCategory,
    )

    /**
     * Sizes [folder], choosing the fastest correct backend:
     *  - when the folder resolves to a readable absolute [File] (AGM holds MANAGE_EXTERNAL_STORAGE),
     *    a multithreaded [File]-based walk (orders of magnitude faster than SAF);
     *  - otherwise the SAF [DocumentFile] walk, which works for any granted tree.
     * Both produce identical [SizeBreakdown] category semantics.
     */
    private suspend fun computeFolderSize(
        folder: DocumentFile,
        onProgress: suspend (runningTotal: Long) -> Unit,
    ): SizeBreakdown {
        val file = absoluteFileFor(folder)
        if (file != null && runCatching { file.isDirectory && file.canRead() }.getOrDefault(false)) {
            return computeFolderSizeFast(file, onProgress)
        }
        return computeFolderSizeCooperative(folder, onProgress)
    }

    /** Current change signature (folder mtime) via the fast [File] path when available. */
    private fun folderMtimeOf(folder: DocumentFile): Long =
        runCatching {
            absoluteFileFor(folder)?.takeIf { it.isDirectory }?.lastModified()
                ?: folder.lastModified()
        }.getOrDefault(0L)

    private fun absoluteFileFor(folder: DocumentFile): File? =
        absoluteFolderPath(folder)?.let { runCatching { File(it) }.getOrNull() }

    /**
     * Multithreaded [File]-based sizer. Delegates the parallel walk to [FastFolderSizer], mapping
     * each folder to a [SizeCategory] so a file's category is its immediate parent folder's category
     * — identical to [computeFolderSizeCooperative].
     */
    private suspend fun computeFolderSizeFast(
        root: File,
        onProgress: suspend (runningTotal: Long) -> Unit,
    ): SizeBreakdown {
        val totals = FastFolderSizer.sizeByCategory(
            root = root,
            rootCategory = SizeCategory.Game,
            childCategoryOf = { name, parent -> sizeCategoryFor(name, parent) },
            onProgress = onProgress,
        )
        return SizeBreakdown(
            gameBytes = totals[SizeCategory.Game] ?: 0L,
            saveBytes = totals[SizeCategory.Save] ?: 0L,
            backupBytes = totals[SizeCategory.Backup] ?: 0L,
            otherBytes = totals[SizeCategory.Other] ?: 0L,
        )
    }

    /**
     * Test-only entry point exercising the fast [File]-based sizer + category folding over a
     * concrete on-disk tree, returning the same [SizeInfo] the scan cache stores.
     */
    internal suspend fun computeFolderSizeForTest(root: File): SizeInfo =
        computeFolderSizeFast(root) {}.toSizeInfo(scannedAt = 1L, dirMtime = root.lastModified())

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
        // If the target File path simply doesn't exist on disk, treat the delete as a
        // no-op success: the user wants the row gone, and the underlying folder is already
        // gone. This matches the expectation that "delete on a missing folder should succeed".
        if (!storagePath.isNullOrBlank()) {
            val direct = runCatching { File(storagePath).exists() }.getOrDefault(true)
            if (!direct) {
                AppLog.i("JoiPlayDelete", "Folder already gone on disk: '$storagePath' (treating as success)")
                return@withContext true
            }
        }

        val persisted = runCatching { context.contentResolver.persistedUriPermissions }
            .getOrDefault(emptyList())

        // Path-based deletion across ANY granted tree. Backup-sourced JoiPlay games can live
        // outside the single configured JoiPlay root (e.g. the game is under Games/Joiplay/…
        // while the configured JoiPlay root points at Games/PC/). Rather than demanding a
        // pointless "re-grant", try every folder we already hold write access to whose
        // filesystem path contains the target, and delete via the first one that resolves.
        if (!storagePath.isNullOrBlank()) {
            val norm = storagePath.replace('\\', '/').trimEnd('/')
            var sawContainingTree = false
            var sawReadOnlyTree = false
            for (uri in candidateDeleteRoots(context, persisted)) {
                if (uri.scheme != "file") {
                    val perm = persisted.firstOrNull { it.uri == uri } ?: continue
                    if (!perm.isWritePermission) {
                        if (grantedRootHint(uri)?.let(norm::startsWith) == true) sawReadOnlyTree = true
                        continue
                    }
                }
                val rootFs = grantedRootHint(uri) ?: continue
                if (!norm.startsWith(rootFs)) continue
                val root = documentRoot(context, uri) ?: continue
                if (!root.isDirectory) continue
                sawContainingTree = true
                val target = walkTree(root, norm.removePrefix(rootFs).trim('/')) ?: continue
                if (!target.isDirectory || !target.canWrite()) continue
                val ok = runCatching { target.delete() }
                    .onFailure { AppLog.e("JoiPlayDelete", "target.delete() threw for '$folderName'", it) }
                    .getOrDefault(false)
                AppLog.i("JoiPlayDelete", "delete '$folderName' -> $ok via $rootFs (uri=${target.uri})")
                if (ok) {
                    removeSizeEntry(context, folderName)
                    return@withContext true
                }
            }
            // Re-check disk: an external removal (or a partial delete) means we're effectively done.
            if (!runCatching { File(norm).exists() }.getOrDefault(true)) {
                AppLog.i("JoiPlayDelete", "Folder confirmed missing after delete pass: '$norm' (treating as success)")
                return@withContext true
            }
            when {
                sawContainingTree -> AppLog.w(
                    "JoiPlayDelete",
                    "Target found under a granted tree but delete failed (not writable/dir): " +
                            "name='$folderName' storagePath='$storagePath'"
                )
                sawReadOnlyTree -> AppLog.w(
                    "JoiPlayDelete",
                    "Only a read-only grant covers '$storagePath'. Re-grant that folder with write access."
                )
                else -> AppLog.w(
                    "JoiPlayDelete",
                    "No granted folder contains '$storagePath'. Grant the folder that actually holds " +
                            "the game (e.g. its Games/Joiplay parent)."
                )
            }
            return@withContext false
        }

        // Legacy path: no storagePath available — fall back to the single configured root
        // with a direct child lookup by folder name.
        val uri = getRootUri(context)
        if (uri == null) {
            AppLog.w("JoiPlayDelete", "No JoiPlay root URI configured")
            return@withContext false
        }
        if (uri.scheme != "file") {
            val perm = persisted.firstOrNull { it.uri == uri }
            if (perm == null) {
                AppLog.w("JoiPlayDelete", "URI not in persistedUriPermissions: $uri (granted=${persisted.size} others). Re-grant the non-Android games folder.")
                return@withContext false
            }
            if (!perm.isWritePermission) {
                AppLog.w("JoiPlayDelete", "Read-only grant (no write): re-grant with write access. uri=$uri")
                return@withContext false
            }
        }
        val root = documentRoot(context, uri)
        if (root == null) {
            AppLog.w("JoiPlayDelete", "Could not resolve configured games folder $uri")
            return@withContext false
        }
        if (!root.isDirectory) {
            AppLog.w("JoiPlayDelete", "Root is not a directory (revoked/moved?): $uri")
            return@withContext false
        }
        val target = root.findFile(folderName)
        if (target == null) {
            AppLog.w(
                "JoiPlayDelete",
                "Folder not found: name='$folderName' grantedRoot=${grantedRootHint(uri)}. " +
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
        if (ok) removeSizeEntry(context, folderName)
        ok
    }

    /** Ordered, de-duplicated set of roots to attempt a delete under: the configured JoiPlay
     *  root first (fast path), then every other persisted grant we already hold. */
    private suspend fun candidateDeleteRoots(
        context: Context,
        persisted: List<android.content.UriPermission>,
    ): List<Uri> {
        val out = LinkedHashSet<Uri>()
        runCatching { getRootUri(context) }.getOrNull()?.let { out.add(it) }
        persisted.forEach { out.add(it.uri) }
        return out.toList()
    }

    /** Walk a relative "a/b/c" path under [root], returning the DocumentFile or null. */
    private fun walkTree(root: DocumentFile, rel: String): DocumentFile? {
        if (rel.isEmpty()) return null
        var cursor: DocumentFile? = root
        for (seg in rel.split('/')) {
            if (seg.isEmpty()) continue
            cursor = cursor?.findFile(seg) ?: return null
        }
        return cursor?.takeIf { it !== root }
    }

    private suspend fun removeSizeEntry(context: Context, folderName: String) {
        val sizes = loadSizeInfo(context).toMutableMap()
        sizes.remove(folderName)
        saveSizeInfo(context, sizes)
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
        if (uri.scheme == "file") {
            val path = uri.path?.let(::File)?.canonicalPath ?: return null
            return if (path.endsWith("/")) path else "$path/"
        }
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
        val root = documentRoot(context, uri) ?: return@withContext null
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

    internal fun documentRoot(context: Context, uri: Uri): DocumentFile? =
        runCatching {
            if (uri.scheme == "file") {
                uri.path?.let(::File)?.takeIf { it.isDirectory }?.let(DocumentFile::fromFile)
            } else {
                DocumentFile.fromTreeUri(context, uri)
            }
        }.getOrNull()

    internal fun extractLabelAndVersion(folderName: String): Pair<String, String?> {
        val m = versionRe.find(folderName)
        return if (m != null) {
            val label = folderName.substring(0, m.range.first).trim().trim('-', '_', ' ')
            label.ifBlank { folderName } to m.groupValues[1]
        } else {
            folderName to null
        }
    }

    /**
     * Resolves the real filesystem path of a scanned game folder, working for both root types:
     *  - a `file://` root (MANAGE_EXTERNAL_STORAGE / direct-path access) — the child DocumentFile's
     *    URI already carries the absolute path;
     *  - a SAF tree/document root — derive from the document id and the *current user's* external
     *    storage root (so it is correct inside Secure Folder / work profiles, e.g. /storage/emulated/150).
     * Returns null only when neither applies (e.g. an unusual provider), so callers fall back to the name.
     */
    internal fun absoluteFolderPath(folder: DocumentFile): String? {
        val uri = folder.uri
        if (uri.scheme == "file") {
            return uri.path?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) }
        }
        return runCatching {
            val docId = android.provider.DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2 || parts[1].isBlank()) return@runCatching null
            val base = if (parts[0].equals("primary", ignoreCase = true)) {
                android.os.Environment.getExternalStorageDirectory()?.canonicalPath ?: "/storage/emulated/0"
            } else {
                // Non-primary volume (SD card / USB): volume id maps to /storage/<id>.
                "/storage/${parts[0]}"
            }
            val root = if (base.endsWith("/")) base else "$base/"
            "$root${parts[1]}"
        }.getOrNull()
    }
}
