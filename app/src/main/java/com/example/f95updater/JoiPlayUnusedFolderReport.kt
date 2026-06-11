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
        appendLine("Scanned parent folders: ${scannedParentPaths.size}")
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
            appendLine("Inaccessible parent folders")
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

        val parentChildCounts = parsedGames
            .flatMap { candidateFolders(it.gamePath, rootPath) }
            .groupBy { it.parentPath }
            .mapValues { (_, candidates) -> candidates.map { key(it.path) }.distinct().size }

        val backupFolders = parsedGames.mapNotNull { game ->
            val candidates = candidateFolders(game.gamePath, rootPath)
            val chosen = candidates.maxWithOrNull(
                compareBy<BackupFolderCandidate> { parentChildCounts[it.parentPath] ?: 0 }
                    .thenByDescending { -pathDepth(it.parentPath, rootPath) }
            ) ?: return@mapNotNull null
            BackupFolder(
                title = game.title,
                path = chosen.path,
                parentPath = chosen.parentPath,
                gamePath = game.gamePath,
            )
        }

        val reportFolders = backupFolders
            .groupBy { key(it.path) }
            .values
            .map { folders ->
                val first = folders.first()
                BackupFolder(
                    title = folders.mapNotNull { it.title }.distinct().joinToString(", ").ifBlank { null },
                    path = first.path,
                    parentPath = first.parentPath,
                    gamePath = folders.joinToString(" | ") { it.gamePath },
                )
            }

        val parents = reportFolders.map { it.parentPath }.distinct().sortedBy { it.lowercase() }
        val reportByPathKey = reportFolders.associateBy { key(it.path) }
        val inUse = mutableListOf<JoiPlayUnusedFolderEntry>()
        val unused = mutableListOf<JoiPlayUnusedFolderEntry>()
        val seenChildKeys = mutableSetOf<String>()
        val inaccessible = mutableListOf<String>()

        AppLog.i(
            "JoiPlayUnused",
            "Scan start root='$rootPath' backupGames=${backupGames.size} underRoot=${parsedGames.size} " +
                "reportFolders=${reportFolders.size} parents=${parents.size}"
        )
        parents.take(50).forEach { AppLog.i("JoiPlayUnused", "parent='$it'") }

        for ((idx, parentPath) in parents.withIndex()) {
            onProgress(
                Progress(
                    stage = "Scanning adjacent folders",
                    current = idx + 1,
                    total = parents.size,
                    detail = parentPath,
                )
            )
            val parent = File(parentPath)
            val children = runCatching { parent.listFiles() }.getOrNull()
            if (children == null) {
                inaccessible.add(parentPath)
                AppLog.w("JoiPlayUnused", "parent inaccessible: '$parentPath'")
                continue
            }
            children
                .asSequence()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .forEach { child ->
                    val childPath = normalizeExisting(child).trimEnd('/')
                    val childKey = key(childPath)
                    seenChildKeys.add(childKey)
                    val matchingBackupFolder = reportByPathKey[childKey]
                    val entry = JoiPlayUnusedFolderEntry(
                        name = child.name,
                        path = childPath,
                        parentPath = parentPath,
                        title = matchingBackupFolder?.title,
                    )
                    if (matchingBackupFolder != null) {
                        inUse.add(entry)
                        AppLog.i(
                            "JoiPlayUnused",
                            "IN path='${entry.path}' title='${entry.title ?: ""}' " +
                                "matchedBackup=${matchingBackupFolder.gamePath}"
                        )
                    } else {
                        unused.add(entry)
                        AppLog.i("JoiPlayUnused", "OUT path='${entry.path}' parent='${entry.parentPath}'")
                    }
                }
        }

        onProgress(Progress(stage = "Finalizing report", current = parents.size, total = parents.size))
        val missing = reportFolders
            .filter { backup ->
                backup.parentPath !in inaccessible &&
                    key(backup.path) !in seenChildKeys
            }
            .map {
                JoiPlayUnusedFolderEntry(
                    name = it.path.substringAfterLast('/'),
                    path = it.path,
                    parentPath = it.parentPath,
                    title = it.title,
                )
            }
            .sortedWith(compareBy<JoiPlayUnusedFolderEntry> { it.parentPath.lowercase() }.thenBy { it.name.lowercase() })

        JoiPlayUnusedFolderReport(
            rootPath = rootPath,
            backupGameCount = backupGames.size,
            backupGamesUnderRoot = parsedGames.size,
            scannedParentPaths = parents,
            inUseFolders = inUse,
            probablyUnusedFolders = unused,
            missingReferencedFolders = missing,
            inaccessibleParentPaths = inaccessible,
        ).also { report ->
            AppLog.i(
                "JoiPlayUnused",
                "Scan done in=${report.inUseFolders.size} out=${report.probablyUnusedFolders.size} " +
                    "missing=${report.missingReferencedFolders.size} inaccessible=${report.inaccessibleParentPaths.size}"
            )
        }
    }

    private data class ParsedBackupGame(
        val title: String?,
        val gamePath: String,
    )

    private data class BackupFolder(
        val title: String?,
        val path: String,
        val parentPath: String,
        val gamePath: String,
    )

    private data class BackupFolderCandidate(
        val path: String,
        val parentPath: String,
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

    private fun candidateFolders(path: String, rootPath: String): List<BackupFolderCandidate> {
        val cleanRoot = rootPath.trimEnd('/')
        val rel = path.removePrefix(cleanRoot).trim('/')
        if (rel.isBlank()) return emptyList()
        val segments = rel.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptyList()
        if (segments.size == 1) {
            return listOf(BackupFolderCandidate(path = "$cleanRoot/${segments[0]}", parentPath = cleanRoot))
        }
        return (1 until segments.size).map { parentSegmentCount ->
            val parentRel = segments.take(parentSegmentCount).joinToString("/")
            val childRel = segments.take(parentSegmentCount + 1).joinToString("/")
            BackupFolderCandidate(
                path = "$cleanRoot/$childRel",
                parentPath = "$cleanRoot/$parentRel",
            )
        }
    }

    private fun pathDepth(path: String, rootPath: String): Int {
        val rel = path.removePrefix(rootPath.trimEnd('/')).trim('/')
        if (rel.isBlank()) return 0
        return rel.count { it == '/' } + 1
    }

    private fun normalizeExisting(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).replace('\\', '/')

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimEnd('/')

    private fun key(path: String): String =
        normalizePath(path).lowercase()
}
