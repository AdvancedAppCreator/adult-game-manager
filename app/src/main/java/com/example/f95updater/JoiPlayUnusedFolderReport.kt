package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class JoiPlayUnusedFolderEntry(
    val name: String,
    val path: String,
    val parentPath: String,
    val title: String? = null,
)

data class JoiPlayUnusedFolderReport(
    val rootPath: String,
    val backupGameCount: Int,
    val backupGamesUnderRoot: Int,
    val scannedParentPaths: List<String>,
    val inUseFolders: List<JoiPlayUnusedFolderEntry>,
    val probablyUnusedFolders: List<JoiPlayUnusedFolderEntry>,
    val missingReferencedFolders: List<JoiPlayUnusedFolderEntry>,
    val inaccessibleParentPaths: List<String>,
) {
    fun asText(): String = buildString {
        appendLine("Probably unused JoiPlay folders")
        appendLine("Root: $rootPath")
        appendLine("Backup games: $backupGameCount")
        appendLine("Backup games under root: $backupGamesUnderRoot")
        appendLine("Scanned folders: ${scannedParentPaths.size}")
        appendLine("In JoiPlay: ${inUseFolders.size}")
        appendLine("Probably unused: ${probablyUnusedFolders.size}")
        if (missingReferencedFolders.isNotEmpty()) {
            appendLine("Referenced by backup but missing on disk: ${missingReferencedFolders.size}")
        }
        if (inaccessibleParentPaths.isNotEmpty()) {
            appendLine("Inaccessible parent folders: ${inaccessibleParentPaths.size}")
        }
        appendLine()

        appendSection("Probably unused folders", probablyUnusedFolders) { it.path }
        appendSection("Folders in JoiPlay backup", inUseFolders) { entry ->
            if (entry.title.isNullOrBlank()) entry.path else "${entry.path}  --  ${entry.title}"
        }
        appendSection("Referenced by backup but missing on disk", missingReferencedFolders) { entry ->
            if (entry.title.isNullOrBlank()) entry.path else "${entry.path}  --  ${entry.title}"
        }
        if (inaccessibleParentPaths.isNotEmpty()) {
            appendLine("Inaccessible folders")
            inaccessibleParentPaths.forEach { appendLine("- $it") }
            appendLine()
        }
    }.trimEnd()

    private fun StringBuilder.appendSection(
        title: String,
        entries: List<JoiPlayUnusedFolderEntry>,
        line: (JoiPlayUnusedFolderEntry) -> String,
    ) {
        appendLine(title)
        if (entries.isEmpty()) {
            appendLine("- (none)")
        } else {
            entries.forEach { appendLine("- ${line(it)}") }
        }
        appendLine()
    }
}

object JoiPlayUnusedFolderReporter {
    private val engineSubdirs = setOf("www", "game", "app", "src", "resources")

    /** Guards against pathological trees. */
    private const val MAX_DEPTH = 25
    private const val MAX_NODES = 50_000

    data class Progress(
        val stage: String,
        val current: Int = 0,
        val total: Int = 0,
        val detail: String = "",
    )

    suspend fun buildReport(
        rootFolder: File,
        backupGames: List<JoiPlayBackupGame>,
        onProgress: suspend (Progress) -> Unit = {},
    ): JoiPlayUnusedFolderReport = withContext(Dispatchers.IO) {
        require(rootFolder.isDirectory) { "Root folder is not accessible: ${rootFolder.absolutePath}" }

        val rootPath = normalizeExisting(rootFolder).trimEnd('/')
        onProgress(Progress(stage = "Reading backup paths", total = backupGames.size))
        val parsedGames = backupGames.mapNotNull { game ->
            val raw = game.folder.trim()
            if (raw.isBlank()) return@mapNotNull null
            val gamePath = effectiveGameFolder(normalizePath(raw))
            if (!isUnderRoot(gamePath, rootPath)) return@mapNotNull null
            ParsedBackupGame(
                title = game.title.trim().ifBlank { null },
                gamePath = gamePath,
            )
        }

        // Titles keyed by the (lowercased) game folder path.
        val titlesByGameKey: Map<String, String?> = parsedGames
            .groupBy { key(it.gamePath) }
            .mapValues { (_, games) -> games.mapNotNull { it.title }.distinct().joinToString(", ").ifBlank { null } }
        val gameKeys: Set<String> = titlesByGameKey.keys

        val inUse = mutableListOf<JoiPlayUnusedFolderEntry>()
        val unused = mutableListOf<JoiPlayUnusedFolderEntry>()
        val scannedContainers = mutableListOf<String>()
        val inaccessible = mutableListOf<String>()
        val seenGameKeys = mutableSetOf<String>()
        var nodeBudget = MAX_NODES

        // True when a game folder is at, or anywhere below, dirKey.
        fun subtreeHasGame(dirKey: String): Boolean =
            gameKeys.any { it == dirKey || it.startsWith("$dirKey/") }

        AppLog.i(
            "JoiPlayUnused",
            "Scan start root='$rootPath' backupGames=${backupGames.size} underRoot=${parsedGames.size} " +
                "gameFolders=${gameKeys.size}"
        )

        suspend fun descend(dir: File, dirPath: String, depth: Int) {
            scannedContainers.add(dirPath)
            onProgress(
                Progress(
                    stage = "Scanning folders",
                    current = scannedContainers.size,
                    total = scannedContainers.size,
                    detail = dirPath,
                )
            )
            val children = runCatching { dir.listFiles() }.getOrNull()
            if (children == null) {
                inaccessible.add(dirPath)
                AppLog.w("JoiPlayUnused", "inaccessible: '$dirPath'")
                return
            }
            children
                .asSequence()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .forEach forEachChild@{ child ->
                    if (nodeBudget <= 0) return@forEachChild
                    nodeBudget--
                    val childPath = normalizeExisting(child).trimEnd('/')
                    val childKey = key(childPath)
                    val isGame = childKey in gameKeys
                    val hasGame = isGame || subtreeHasGame(childKey)
                    when {
                        !hasGame -> {
                            unused.add(
                                JoiPlayUnusedFolderEntry(
                                    name = child.name,
                                    path = childPath,
                                    parentPath = dirPath,
                                    title = null,
                                )
                            )
                            AppLog.i("JoiPlayUnused", "OUT path='$childPath' parent='$dirPath'")
                        }
                        isGame -> {
                            seenGameKeys.add(childKey)
                            val title = titlesByGameKey[childKey]
                            inUse.add(
                                JoiPlayUnusedFolderEntry(
                                    name = child.name,
                                    path = childPath,
                                    parentPath = dirPath,
                                    title = title,
                                )
                            )
                            AppLog.i("JoiPlayUnused", "IN path='$childPath' title='${title ?: ""}'")
                        }
                        depth + 1 < MAX_DEPTH -> descend(child, childPath, depth + 1)
                        else -> AppLog.w("JoiPlayUnused", "depth cap reached at '$childPath'")
                    }
                }
        }

        descend(rootFolder, rootPath, depth = 0)

        onProgress(Progress(stage = "Finalizing report", current = scannedContainers.size, total = scannedContainers.size))
        val missing = parsedGames
            .filter { key(it.gamePath) !in seenGameKeys }
            .groupBy { key(it.gamePath) }
            .map { (_, games) ->
                val first = games.first()
                JoiPlayUnusedFolderEntry(
                    name = first.gamePath.substringAfterLast('/'),
                    path = first.gamePath,
                    parentPath = first.gamePath.substringBeforeLast('/', missingDelimiterValue = rootPath),
                    title = games.mapNotNull { it.title }.distinct().joinToString(", ").ifBlank { null },
                )
            }
            .sortedWith(compareBy<JoiPlayUnusedFolderEntry> { it.parentPath.lowercase() }.thenBy { it.name.lowercase() })

        JoiPlayUnusedFolderReport(
            rootPath = rootPath,
            backupGameCount = backupGames.size,
            backupGamesUnderRoot = parsedGames.size,
            scannedParentPaths = scannedContainers,
            inUseFolders = inUse.sortedWith(sortEntries),
            probablyUnusedFolders = unused.sortedWith(sortEntries),
            missingReferencedFolders = missing,
            inaccessibleParentPaths = inaccessible,
        ).also { report ->
            AppLog.i(
                "JoiPlayUnused",
                "Scan done in=${report.inUseFolders.size} out=${report.probablyUnusedFolders.size} " +
                    "missing=${report.missingReferencedFolders.size} inaccessible=${report.inaccessibleParentPaths.size} " +
                    "scanned=${report.scannedParentPaths.size}"
            )
        }
    }

    private val sortEntries =
        compareBy<JoiPlayUnusedFolderEntry> { it.parentPath.lowercase() }.thenBy { it.name.lowercase() }

    private data class ParsedBackupGame(
        val title: String?,
        val gamePath: String,
    )

    private fun effectiveGameFolder(path: String): String {
        val base = path.substringAfterLast('/').lowercase()
        return if (base in engineSubdirs) {
            path.substringBeforeLast('/', missingDelimiterValue = path).ifBlank { path }
        } else {
            path
        }
    }

    private fun isUnderRoot(path: String, rootPath: String): Boolean {
        val cleanRoot = rootPath.trimEnd('/')
        return path == cleanRoot || path.startsWith("$cleanRoot/")
    }

    private fun normalizeExisting(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).replace('\\', '/')

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimEnd('/')

    private fun key(path: String): String =
        normalizePath(path).lowercase()
}
