package com.example.f95updater

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

enum class CleanupExistenceStatus {
    Exists,
    Missing,
    Unknown,
}

data class CleanupGameEntry(
    val packageName: String,
    val source: AppSource,
    val title: String,
    val storagePath: String?,
    val effectiveFolderPath: String?,
    val joiPlayGameId: String?,
    val winlatorGameId: String?,
    val winlatorContainerId: Int?,
    val existenceStatus: CleanupExistenceStatus,
)

data class CleanupReviewFolderEntry(
    val name: String,
    val path: String,
    val parentPath: String,
)

internal fun cleanupGameOpenablePath(game: CleanupGameEntry): String? =
    if (game.existenceStatus == CleanupExistenceStatus.Missing) {
        null
    } else {
        (game.effectiveFolderPath ?: game.storagePath)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

data class CleanupReviewReport(
    val rootPath: String,
    val managedGames: List<CleanupGameEntry>,
    val unassociatedFolders: List<CleanupReviewFolderEntry>,
    val orphanGames: List<CleanupGameEntry>,
    val unreadableFolders: List<String>,
    val scannedFolderCount: Int,
    val scanLimitReached: Boolean,
) {
    fun asText(): String = buildString {
        appendLine("Cleanup review")
        appendLine("Root: $rootPath")
        appendLine("Managed games: ${managedGames.size}")
        appendLine("Unassociated folders: ${unassociatedFolders.size}")
        appendLine("Orphan games: ${orphanGames.size}")
        appendLine("Unreadable folders: ${unreadableFolders.size}")
        appendLine("Scanned folders: $scannedFolderCount")
        if (scanLimitReached) appendLine("Warning: scan safety limit reached")
        appendLine()

        appendGames("Managed games", managedGames)
        appendFolders("Unassociated folders", unassociatedFolders)
        appendGames("Orphan games", orphanGames)
        appendLine("Unreadable folders")
        if (unreadableFolders.isEmpty()) {
            appendLine("- (none)")
        } else {
            unreadableFolders.forEach { appendLine("- $it") }
        }
    }.trimEnd()

    private fun StringBuilder.appendGames(title: String, games: List<CleanupGameEntry>) {
        appendLine(title)
        if (games.isEmpty()) {
            appendLine("- (none)")
        } else {
            games.forEach { game ->
                val path = game.effectiveFolderPath ?: game.storagePath.orEmpty().ifBlank { "(unknown path)" }
                appendLine("- ${game.title} [${game.existenceStatus}]: $path")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendFolders(title: String, folders: List<CleanupReviewFolderEntry>) {
        appendLine(title)
        if (folders.isEmpty()) {
            appendLine("- (none)")
        } else {
            folders.forEach { appendLine("- ${it.path}") }
        }
        appendLine()
    }
}

data class FilteredCleanupReview(
    val managedGames: List<CleanupGameEntry>,
    val unassociatedFolders: List<CleanupReviewFolderEntry>,
    val orphanGames: List<CleanupGameEntry>,
    val unreadableFolders: List<String>,
)

internal fun filterCleanupReview(
    report: CleanupReviewReport,
    query: String,
): FilteredCleanupReview {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) {
        return FilteredCleanupReview(
            managedGames = report.managedGames,
            unassociatedFolders = report.unassociatedFolders,
            orphanGames = report.orphanGames,
            unreadableFolders = report.unreadableFolders,
        )
    }
    fun matches(values: List<String?>): Boolean = terms.all { term ->
        values.any { value -> value?.contains(term, ignoreCase = true) == true }
    }
    fun matchesGame(game: CleanupGameEntry): Boolean = matches(
        listOf(
            game.title,
            game.packageName,
            game.source.name,
            game.storagePath,
            game.effectiveFolderPath,
            game.joiPlayGameId,
            game.winlatorGameId,
            game.winlatorContainerId?.toString(),
            game.existenceStatus.name,
        ),
    )
    return FilteredCleanupReview(
        managedGames = report.managedGames.filter(::matchesGame),
        unassociatedFolders = report.unassociatedFolders.filter { folder ->
            matches(listOf(folder.name, folder.path, folder.parentPath))
        },
        orphanGames = report.orphanGames.filter(::matchesGame),
        unreadableFolders = report.unreadableFolders.filter { matches(listOf(it)) },
    )
}

object CleanupReviewReporter {
    private val engineSubdirectories = setOf("www", "game", "app", "src", "resources")

    private const val MAX_DEPTH = 25
    private const val MAX_NODES = 50_000

    data class Progress(
        val stage: String,
        val current: Int = 0,
        val total: Int = 0,
        val detail: String = "",
    )

    fun buildReport(
        rootFolder: File,
        installedApps: List<InstalledApp>,
        onProgress: (Progress) -> Unit = {},
    ): CleanupReviewReport {
        require(rootFolder.isDirectory) { "Root folder is not accessible: ${rootFolder.absolutePath}" }

        val rootPath = normalizedFilePath(rootFolder)
        val rootKey = comparisonKey(rootPath)
        val sourceApps = installedApps.filter {
            it.source == AppSource.Managed ||
                it.source == AppSource.JoiPlay ||
                it.source == AppSource.Winlator ||
                it.source == AppSource.Kirikiroid
        }

        onProgress(Progress("Classifying games", total = sourceApps.size))
        val games = sourceApps.mapIndexed { index, app ->
            onProgress(
                Progress(
                    stage = "Classifying games",
                    current = index + 1,
                    total = sourceApps.size,
                    detail = app.label,
                )
            )
            app.toCleanupEntry(rootFolder)
        }

        val associatedKeys = games.mapNotNull { game ->
            game.effectiveFolderPath
                ?.takeIf { isUnderRoot(it, rootPath) }
                ?.let(::comparisonKey)
                ?.takeUnless { it == rootKey }
        }.toSet()

        val unassociated = mutableListOf<CleanupReviewFolderEntry>()
        val unreadable = mutableListOf<String>()
        var scannedFolderCount = 0
        var remainingNodes = MAX_NODES
        var scanLimitReached = false

        fun subtreeHasAssociation(directoryKey: String): Boolean =
            associatedKeys.any { it == directoryKey || it.startsWith("$directoryKey/") }

        fun descend(directory: File, directoryPath: String, depth: Int) {
            scannedFolderCount++
            onProgress(
                Progress(
                    stage = "Scanning folders",
                    current = scannedFolderCount,
                    total = scannedFolderCount,
                    detail = directoryPath,
                )
            )

            val children = try {
                directory.listFiles()
            } catch (_: SecurityException) {
                null
            }
            if (children == null) {
                unreadable.add(directoryPath)
                return
            }

            for (child in children.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                if (remainingNodes == 0) {
                    scanLimitReached = true
                    return
                }
                if (child.name.startsWith(".")) continue
                when (directoryProbe(child)) {
                    DirectoryProbe.NotDirectory -> continue
                    DirectoryProbe.Unreadable -> {
                        unreadable.add(normalizedFilePath(child))
                        continue
                    }
                    DirectoryProbe.Directory -> Unit
                }

                remainingNodes--
                val childPath = normalizedFilePath(child)
                val childKey = comparisonKey(childPath)
                when {
                    childKey in associatedKeys -> Unit
                    !subtreeHasAssociation(childKey) -> unassociated.add(
                        CleanupReviewFolderEntry(
                            name = child.name,
                            path = childPath,
                            parentPath = directoryPath,
                        )
                    )
                    depth + 1 < MAX_DEPTH -> descend(child, childPath, depth + 1)
                    else -> scanLimitReached = true
                }
            }
        }

        descend(rootFolder, rootPath, depth = 0)
        onProgress(
            Progress(
                stage = "Finalizing report",
                current = scannedFolderCount,
                total = scannedFolderCount,
            )
        )

        val gameSort = compareBy<CleanupGameEntry>(
            { it.title.lowercase(Locale.ROOT) },
            { it.packageName.lowercase(Locale.ROOT) },
        )
        val folderSort = compareBy<CleanupReviewFolderEntry>(
            { it.parentPath.lowercase(Locale.ROOT) },
            { it.name.lowercase(Locale.ROOT) },
        )

        return CleanupReviewReport(
            rootPath = rootPath,
            managedGames = games.sortedWith(gameSort),
            unassociatedFolders = unassociated.sortedWith(folderSort),
            orphanGames = games.filter { it.existenceStatus == CleanupExistenceStatus.Missing }.sortedWith(gameSort),
            unreadableFolders = unreadable.distinct().sortedBy { it.lowercase(Locale.ROOT) },
            scannedFolderCount = scannedFolderCount,
            scanLimitReached = scanLimitReached,
        )
    }

    private fun InstalledApp.toCleanupEntry(rootFolder: File): CleanupGameEntry {
        val originalPath = storagePath
        val effectivePath = originalPath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolveStoragePath(rootFolder, it) }
            ?.let { resolved ->
                if (source == AppSource.JoiPlay &&
                    resolved.name.lowercase(Locale.ROOT) in engineSubdirectories
                ) {
                    resolved.parentFile ?: resolved
                } else {
                    resolved
                }
            }
            ?.let(::normalizedFilePath)

        return CleanupGameEntry(
            packageName = packageName,
            source = source,
            title = label,
            storagePath = originalPath,
            effectiveFolderPath = effectivePath,
            joiPlayGameId = joiPlayGameId,
            winlatorGameId = winlatorGameId,
            winlatorContainerId = winlatorContainerId,
            existenceStatus = effectivePath?.let(::directoryStatus) ?: CleanupExistenceStatus.Unknown,
        )
    }

    private fun resolveStoragePath(rootFolder: File, storagePath: String): File {
        val platformPath = storagePath.replace('\\', File.separatorChar).replace('/', File.separatorChar)
        val file = File(platformPath)
        return if (file.isAbsolute) file else File(rootFolder, platformPath)
    }

    private fun directoryStatus(path: String): CleanupExistenceStatus {
        val filePath = File(path).toPath()
        return try {
            val attributes = Files.readAttributes(filePath, BasicFileAttributes::class.java)
            if (attributes.isDirectory) CleanupExistenceStatus.Exists else CleanupExistenceStatus.Missing
        } catch (_: NoSuchFileException) {
            CleanupExistenceStatus.Missing
        } catch (_: AccessDeniedException) {
            CleanupExistenceStatus.Unknown
        } catch (_: SecurityException) {
            CleanupExistenceStatus.Unknown
        } catch (_: Exception) {
            CleanupExistenceStatus.Unknown
        }
    }

    private enum class DirectoryProbe {
        Directory,
        NotDirectory,
        Unreadable,
    }

    private fun directoryProbe(file: File): DirectoryProbe =
        try {
            if (Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).isDirectory) {
                DirectoryProbe.Directory
            } else {
                DirectoryProbe.NotDirectory
            }
        } catch (_: NoSuchFileException) {
            DirectoryProbe.NotDirectory
        } catch (_: Exception) {
            DirectoryProbe.Unreadable
        }

    private fun normalizedFilePath(file: File): String =
        runCatching { file.canonicalPath }
            .getOrElse { file.absoluteFile.normalize().path }
            .replace('\\', '/')
            .trimEnd('/')

    private fun comparisonKey(path: String): String =
        path.replace('\\', '/').trimEnd('/')

    private fun isUnderRoot(path: String, rootPath: String): Boolean {
        val pathKey = comparisonKey(path)
        val rootKey = comparisonKey(rootPath)
        return pathKey == rootKey || pathKey.startsWith("$rootKey/")
    }
}
