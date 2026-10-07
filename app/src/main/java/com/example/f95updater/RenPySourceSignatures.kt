package com.example.f95updater

import java.io.File

/**
 * Bounded, deterministic identity signatures read out of Ren'Py *source* (.rpy) files.
 *
 * Some patches ship no options.rpy at all — the real-world case is an archive holding only
 * `game/script.rpy`, `game/gallery_scenes.rpy` and a couple of GUI images. Their identity is still
 * provable, because the script declares the exact set of `Character(...)` objects and labels the
 * installed game declares. Those declarations are compared verbatim: nothing here decompiles
 * anything, infers a title, or looks at a file name.
 *
 * Every scan is capped (file count, per-file bytes, total bytes, signatures per kind) so a huge
 * game can never turn identification into an unbounded read.
 */
data class RenPySourceSignatures(
    val characters: Set<String> = emptySet(),
    val labels: Set<String> = emptySet(),
    val declarations: Set<String> = emptySet(),
    val filesScanned: Int = 0,
    val bytesScanned: Long = 0L,
    val truncated: Boolean = false,
) {
    val total: Int get() = characters.size + labels.size + declarations.size
    val isEmpty: Boolean get() = total == 0

    companion object {
        val EMPTY = RenPySourceSignatures()
    }
}

/** How many of one side's signatures the other side also declares. */
data class RenPySignatureOverlap(
    val characterMatches: Int,
    val characterTotal: Int,
    val labelMatches: Int,
    val labelTotal: Int,
    val declarationMatches: Int,
    val declarationTotal: Int,
) {
    val matches: Int get() = characterMatches + labelMatches + declarationMatches
    val total: Int get() = characterTotal + labelTotal + declarationTotal
    val characterCoverage: Float
        get() = if (characterTotal == 0) 0f else characterMatches.toFloat() / characterTotal
    val coverage: Float get() = if (total == 0) 0f else matches.toFloat() / total
}

object RenPySourceScanner {

    const val MAX_FILES = 64
    const val MAX_FILE_BYTES = 1L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L
    const val MAX_SIGNATURES_PER_KIND = 600
    private const val MAX_DIRECTORIES = 512

    private val CHARACTER = Regex(
        """(?m)^[ \t]*(?:define|default)[ \t]+(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)[ \t]*=[ \t]*Character[ \t]*\((.*)$""",
    )
    private val LABEL = Regex("""(?m)^[ \t]*label[ \t]+([A-Za-z_]\w*)[ \t]*(?:\([^)]*\))?[ \t]*:""")
    private val DECLARATION = Regex(
        """(?m)^[ \t]*(?:define|default)[ \t]+([A-Za-z_][\w.]*)[ \t]*=[ \t]*(?:_\([ \t]*)?["']([^"']{2,80})["']""",
    )
    private val QUOTED = Regex("""["']([^"']*)["']""")

    /** Ren'Py's own boilerplate speakers. Every game declares them, so they prove nothing. */
    private val boilerplateCharacters = setOf(
        "narrator", "name_only", "centered", "vcentered", "extend", "adv", "nvl", "nvl_narrator",
    )

    /** Labels the engine itself requires. Shared by every game, so they are not identity. */
    private val boilerplateLabels = setOf(
        "start", "quit", "end", "return", "splashscreen", "main_menu", "after_load", "after_warp",
        "before_main_menu", "hide_windows", "replay_start",
    )

    /** Theme/GUI variables are template values shipped with Ren'Py, not game identity. */
    private val ignoredDeclarationPrefixes = listOf("gui.", "style.", "preferences.", "layout.")

    fun scanDirectory(root: File): RenPySourceSignatures {
        if (!root.isDirectory) return RenPySourceSignatures.EMPTY
        val files = collectSourceFiles(root)
        return scanFiles(files)
    }

    fun scanFiles(files: List<File>): RenPySourceSignatures {
        val characters = LinkedHashSet<String>()
        val labels = LinkedHashSet<String>()
        val declarations = LinkedHashSet<String>()
        var bytes = 0L
        var scanned = 0
        var truncated = files.size > MAX_FILES
        for (file in files.take(MAX_FILES)) {
            if (bytes >= MAX_TOTAL_BYTES) {
                truncated = true
                break
            }
            val budget = minOf(MAX_FILE_BYTES, MAX_TOTAL_BYTES - bytes)
            val (text, cut) = readBounded(file, budget) ?: continue
            if (cut) truncated = true
            bytes += text.length.toLong()
            scanned++
            characters += characterSignatures(text)
            labels += labelSignatures(text)
            declarations += declarationSignatures(text)
            if (characters.size > MAX_SIGNATURES_PER_KIND ||
                labels.size > MAX_SIGNATURES_PER_KIND ||
                declarations.size > MAX_SIGNATURES_PER_KIND
            ) {
                truncated = true
                break
            }
        }
        return RenPySourceSignatures(
            characters = characters.take(MAX_SIGNATURES_PER_KIND).toSet(),
            labels = labels.take(MAX_SIGNATURES_PER_KIND).toSet(),
            declarations = declarations.take(MAX_SIGNATURES_PER_KIND).toSet(),
            filesScanned = scanned,
            bytesScanned = bytes,
            truncated = truncated,
        )
    }

    /** Deterministic, breadth-first, path-sorted so two scans of the same tree always agree. */
    fun collectSourceFiles(root: File): List<File> {
        val found = mutableListOf<File>()
        val queue = ArrayDeque<File>()
        queue += root
        var directories = 0
        while (queue.isNotEmpty() && found.size <= MAX_FILES && directories < MAX_DIRECTORIES) {
            val current = queue.removeFirst()
            directories++
            val children = current.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
            for (child in children) {
                when {
                    child.isDirectory -> queue += child
                    child.isFile && child.extension.equals("rpy", ignoreCase = true) &&
                        child.canRead() && child.length() > 0L -> found += child
                }
            }
        }
        return found.sortedBy { it.path.replace('\\', '/').lowercase() }
    }

    private fun readBounded(file: File, budget: Long): Pair<String, Boolean>? {
        if (budget <= 0L) return null
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        val limit = minOf(length, budget).toInt()
        val bytes = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(limit)
                var read = 0
                while (read < limit) {
                    val n = input.read(buffer, read, limit - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        val cut = length > limit
        var text = String(bytes, Charsets.UTF_8)
        // A capped read can stop mid-line; that partial line is dropped so a truncated file can
        // never invent a signature the file does not actually declare.
        if (cut) text = text.substringBeforeLast('\n', "")
        return text to cut
    }

    fun characterSignatures(text: String): List<String> = CHARACTER.findAll(text)
        .mapNotNull { match ->
            val variable = match.groupValues[1].lowercase()
            val display = QUOTED.find(match.groupValues[2])?.groupValues?.get(1).orEmpty()
            val normalizedDisplay = display.lowercase().filter { it.isLetterOrDigit() }
            if (variable in boilerplateCharacters && normalizedDisplay.isEmpty()) return@mapNotNull null
            if (normalizedDisplay.isEmpty() && variable.length < 4) return@mapNotNull null
            "char:$variable=$normalizedDisplay"
        }
        .toList()

    fun labelSignatures(text: String): List<String> = LABEL.findAll(text)
        .map { it.groupValues[1].lowercase() }
        .filter { it !in boilerplateLabels }
        .map { "label:$it" }
        .toList()

    fun declarationSignatures(text: String): List<String> = DECLARATION.findAll(text)
        .mapNotNull { match ->
            val name = match.groupValues[1].lowercase()
            if (ignoredDeclarationPrefixes.any { name.startsWith(it) }) return@mapNotNull null
            val value = match.groupValues[2].trim().lowercase()
            if (value.isEmpty()) return@mapNotNull null
            "define:$name=$value"
        }
        .toList()

    fun overlap(patch: RenPySourceSignatures, game: RenPySourceSignatures): RenPySignatureOverlap =
        RenPySignatureOverlap(
            characterMatches = patch.characters.count { it in game.characters },
            characterTotal = patch.characters.size,
            labelMatches = patch.labels.count { it in game.labels },
            labelTotal = patch.labels.size,
            declarationMatches = patch.declarations.count { it in game.declarations },
            declarationTotal = patch.declarations.size,
        )
}

/**
 * Structured version markers a patch's own source carries (`label v16:`), and the single rule AGM
 * uses to turn one into a comparable version. A marker with one number is inherently ambiguous —
 * `v16` could be 16, 1.6 or 0.16 — so it is never treated as proof of what the patch targets.
 */
object PatchSourceVersionEvidence {

    private val MARKER = Regex("""^v(\d+(?:[._]\d+)*)([a-z]?)$""")

    /**
     * Version-shaped labels, ordered from lowest to highest by numeric value.
     *
     * The numeric groups decide the order; how *many* groups a marker has is only a tie-breaker.
     * Ordering by group count first would let `v0_9` outrank `v10` purely because it is written with
     * two groups, which would then hand [highestMarker] a marker that maps cleanly to "0.9" and hide
     * the fact that the patch's real highest marker (`v10`) has no defined version mapping at all.
     */
    fun markers(labels: Set<String>): List<String> = labels
        .map { it.removePrefix("label:") }
        .filter { MARKER.matches(it) }
        .distinct()
        .sortedWith(compareBy<String> { GroupOrder(numericGroups(it)) }.thenBy { numericGroups(it).size }.thenBy { it })

    fun highestMarker(labels: Set<String>): String? = markers(labels).lastOrNull()

    /**
     * The only defined normalisation: a marker that already carries at least two numeric groups maps
     * to those groups joined by dots. Anything else stays unproven.
     */
    fun normalizeMarker(marker: String): String? {
        val groups = numericGroups(marker)
        if (groups.size < 2) return null
        return groups.joinToString(".")
    }

    private fun numericGroups(marker: String): List<String> {
        val match = MARKER.matchEntire(marker) ?: return emptyList()
        return match.groupValues[1].split('.', '_').filter { it.isNotEmpty() }
    }

    private class GroupOrder(val groups: List<String>) : Comparable<GroupOrder> {
        override fun compareTo(other: GroupOrder): Int {
            val size = maxOf(groups.size, other.groups.size)
            for (i in 0 until size) {
                // A missing group sorts below any declared one, so v1 < v1_0 < v1_2 and v0_9 < v10.
                val a = groups.getOrNull(i)?.toLongOrNull() ?: -1L
                val b = other.groups.getOrNull(i)?.toLongOrNull() ?: -1L
                if (a != b) return a.compareTo(b)
            }
            return 0
        }
    }
}
