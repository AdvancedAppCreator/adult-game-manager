package com.example.f95updater

/**
 * Pure header-level analysis of a candidate game-patch archive.
 *
 * Nothing here touches the filesystem or an archive library: [PatchArchiveScanner] takes the entry
 * list produced by [PatchArchiveReader] and either rejects the archive outright or returns the exact
 * set of relative paths the archive would produce. Every downstream step (staging verification,
 * planning, commit) is checked against that set, so an archive can never write a path AGM did not
 * approve.
 */

/** Entry-count and size ceilings. Deliberately smaller than the whole-game extractor's caps. */
const val PATCH_MAX_ENTRIES = 100_000
const val PATCH_MAX_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L
const val PATCH_MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L

/**
 * Prefix AGM reserves for its own control files at every depth: the managed-game ownership marker,
 * the JoiPlay install marker, download parts, save backups and the patch installer's own work
 * folder and `.part` files. An archive that carries such a name could forge ownership or collide
 * with an in-flight transaction, so it is refused before anything is staged.
 */
const val AGM_RESERVED_NAME_PREFIX = ".agm-"

enum class PatchEntryKind { File, Directory, Link }

/** One archive header, normalised only by the reader that produced it. */
data class PatchArchiveEntry(
    val rawName: String,
    val size: Long,
    val kind: PatchEntryKind,
)

data class PatchStagedFile(
    val relativePath: String,
    val size: Long,
)

/** Where an accepted patch merges into the installed game. */
enum class PatchDestination {
    /** Archive root holds Ren'Py game content; merges into `<install>/game`. */
    GameFolder,

    /** Archive root holds a `game/` tree; merges into `<install>`. */
    InstallRoot,
}

sealed interface PatchArchiveScan {
    data class Rejected(val reason: String) : PatchArchiveScan

    data class Accepted(
        val wrapperFolder: String?,
        val destination: PatchDestination,
        val files: List<PatchStagedFile>,
        val totalBytes: Long,
    ) : PatchArchiveScan {
        val relativePaths: List<String> get() = files.map { it.relativePath }
    }
}

object PatchArchiveScanner {

    private val RENPY_CONTENT_EXTENSIONS = setOf("rpy", "rpyc", "rpa", "rpyb", "rpym", "rpymc")

    /**
     * Characters [ArchiveExtractor] rewrites when it materialises an entry. An entry containing one
     * would land under a different name than the one validated here, so it is rejected instead.
     */
    private val REWRITTEN_CHARACTERS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    fun scan(entries: List<PatchArchiveEntry>): PatchArchiveScan {
        if (entries.isEmpty()) return PatchArchiveScan.Rejected("The archive is empty.")
        if (entries.size > PATCH_MAX_ENTRIES) {
            return PatchArchiveScan.Rejected(
                "The archive holds ${entries.size} entries, over the $PATCH_MAX_ENTRIES entry limit.",
            )
        }
        entries.firstOrNull { it.kind == PatchEntryKind.Link }?.let {
            return PatchArchiveScan.Rejected(
                "The archive contains a link entry (${it.rawName.trim()}). AGM does not install " +
                    "archives that carry links because they can redirect writes outside the game.",
            )
        }

        val normalised = ArrayList<Pair<PatchArchiveEntry, String>>(entries.size)
        for (entry in entries) {
            val path = entry.rawName.replace('\\', '/')
            validatePath(path, entry.kind)?.let { return PatchArchiveScan.Rejected(it) }
            if (entry.kind == PatchEntryKind.File) {
                if (entry.size < 0L) {
                    return PatchArchiveScan.Rejected("Entry '$path' declares a negative size.")
                }
                if (entry.size > PATCH_MAX_ENTRY_BYTES) {
                    return PatchArchiveScan.Rejected(
                        "Entry '$path' declares ${entry.size} bytes, over the per-file patch limit.",
                    )
                }
            }
            normalised += entry to path.trim('/')
        }

        val wrapper = commonRootFolder(normalised.map { it.second })
        val files = LinkedHashMap<String, Long>()
        val directories = LinkedHashSet<String>()
        for ((entry, path) in normalised) {
            val relative = stripWrapper(path, wrapper)
            if (relative.isEmpty()) {
                if (entry.kind == PatchEntryKind.File) {
                    return PatchArchiveScan.Rejected("Entry '$path' has no path inside the archive.")
                }
                continue
            }
            // The approved relative path is what actually lands in the game, so it is re-checked
            // after the wrapper strip rather than trusting the raw-name pass alone.
            relative.split('/').firstOrNull { isReservedAgmName(it) }?.let { segment ->
                return PatchArchiveScan.Rejected(reservedNameReason(relative, segment))
            }
            when (entry.kind) {
                PatchEntryKind.Directory -> directories += relative
                PatchEntryKind.File -> {
                    if (files.put(relative, entry.size) != null) {
                        return PatchArchiveScan.Rejected(
                            "The archive stores '$relative' more than once, so its content is ambiguous.",
                        )
                    }
                }
                PatchEntryKind.Link -> return PatchArchiveScan.Rejected("Unreachable link entry '$path'.")
            }
        }
        if (files.isEmpty()) return PatchArchiveScan.Rejected("The archive contains no files.")

        val conflicting = files.keys.firstOrNull { file ->
            directories.contains(file) || files.keys.any { it != file && it.startsWith("$file/") }
        }
        if (conflicting != null) {
            return PatchArchiveScan.Rejected(
                "The archive stores '$conflicting' as both a file and a folder.",
            )
        }

        val totalBytes = files.values.sum()
        if (totalBytes > PATCH_MAX_TOTAL_BYTES) {
            return PatchArchiveScan.Rejected(
                "The archive expands to $totalBytes bytes, over the patch size limit.",
            )
        }

        val destination = detectDestination(files.keys, directories)
            ?: return PatchArchiveScan.Rejected(
                "AGM does not recognise this archive's layout. A patch must either hold Ren'Py game " +
                    "content (for example script.rpy, script.rpyc, scripts.rpa or options.rpy) at its " +
                    "root, or a 'game' folder to merge into the installed game.",
            )

        return PatchArchiveScan.Accepted(
            wrapperFolder = wrapper,
            destination = destination,
            files = files.map { PatchStagedFile(it.key, it.value) }.sortedBy { it.relativePath },
            totalBytes = totalBytes,
        )
    }

    /** Mirrors [ArchiveExtractor]'s single-top-level-folder strip so plan and staging agree. */
    fun commonRootFolder(names: List<String>): String? {
        if (names.isEmpty()) return null
        val firstSegments = names.map { it.replace('\\', '/').trimStart('/').substringBefore('/') }
        val unique = firstSegments.toSet()
        if (unique.size != 1) return null
        val candidate = unique.single()
        if (candidate.isBlank()) return null
        return if (names.any { it.contains('/') }) candidate else null
    }

    private fun stripWrapper(path: String, wrapper: String?): String {
        val trimmed = path.trim('/')
        if (wrapper == null) return trimmed
        return when {
            trimmed == wrapper -> ""
            trimmed.startsWith("$wrapper/") -> trimmed.substring(wrapper.length + 1).trim('/')
            else -> trimmed
        }
    }

    private fun detectDestination(files: Set<String>, directories: Set<String>): PatchDestination? {
        val hasGameTree = files.any { it.startsWith("game/") }
        val rootRenPyContent = files.any { file ->
            !file.contains('/') && file.substringAfterLast('.', "").lowercase() in RENPY_CONTENT_EXTENSIONS
        }
        return when {
            hasGameTree && !rootRenPyContent -> PatchDestination.InstallRoot
            rootRenPyContent && !hasGameTree && !directories.contains("game") -> PatchDestination.GameFolder
            else -> null
        }
    }

    private fun isReservedAgmName(segment: String): Boolean =
        segment.startsWith(AGM_RESERVED_NAME_PREFIX, ignoreCase = true)

    private fun reservedNameReason(path: String, segment: String): String =
        "Entry '$path' uses the reserved name '$segment'. AGM never installs a path whose folder or " +
            "file name starts with '$AGM_RESERVED_NAME_PREFIX' because those names belong to AGM's own " +
            "ownership, install and patch control files."

    private fun validatePath(path: String, kind: PatchEntryKind): String? {
        if (path.isBlank()) return "The archive contains an entry with no name."
        if (path.startsWith("/")) return "Entry '$path' uses an absolute path."
        if (path.startsWith("//")) return "Entry '$path' uses a network path."
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return "Entry '$path' carries a drive prefix."
        val body = if (kind == PatchEntryKind.Directory) path.trimEnd('/') else path
        if (body.endsWith("/")) return "Entry '$path' names a folder but is stored as a file."
        val segments = body.split('/')
        if (segments.isEmpty()) return "Entry '$path' has no path segments."
        segments.forEachIndexed { index, segment ->
            val isLeafFile = kind == PatchEntryKind.File && index == segments.lastIndex
            when {
                segment.isEmpty() -> return "Entry '$path' contains an empty path segment."
                segment == "." || segment == ".." ->
                    return "Entry '$path' tries to traverse outside the archive."
                segment.any { it.code < 0x20 || it.code == 0x7F } ->
                    return "Entry '$path' contains a control character."
                segment.any { it in REWRITTEN_CHARACTERS } ->
                    return "Entry '$path' contains a character AGM would have to rewrite."
                isReservedAgmName(segment) -> return reservedNameReason(path, segment)
                !isLeafFile && segment.trim('.') != segment ->
                    return "Entry '$path' contains a folder name AGM would have to rewrite."
                segment.isBlank() -> return "Entry '$path' contains a blank path segment."
            }
        }
        return null
    }
}
