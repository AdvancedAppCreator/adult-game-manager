package com.example.f95updater

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

enum class ExtractTargetMode { JoiPlay, ApkInstall, Winlator, Managed }

private val joiPlayLaunchRanks: List<Pair<Regex, String>> = listOf(
    Regex("^game\\.exe$", RegexOption.IGNORE_CASE) to "RPG Maker",
    Regex("^index\\.html$", RegexOption.IGNORE_CASE) to "HTML / Tyrano",
    Regex("^script\\.rpy$", RegexOption.IGNORE_CASE) to "Ren'Py",
    Regex("^.*\\.(exe)$", RegexOption.IGNORE_CASE) to "Windows EXE",
    Regex("^.*\\.(swf)$", RegexOption.IGNORE_CASE) to "Flash (Ruffle)",
    Regex("^.*\\.(py)$", RegexOption.IGNORE_CASE) to "Python",
    Regex("^.*\\.(sh)$", RegexOption.IGNORE_CASE) to "Shell",
    Regex("^.*\\.(html|htm)$", RegexOption.IGNORE_CASE) to "HTML",
)

private val apkInstallRanks: List<Pair<Regex, String>> = listOf(
    Regex("^.*\\.apk$", RegexOption.IGNORE_CASE) to "Android package",
)

internal fun ranksFor(mode: ExtractTargetMode): List<Pair<Regex, String>> = when (mode) {
    ExtractTargetMode.JoiPlay -> joiPlayLaunchRanks
    ExtractTargetMode.ApkInstall -> apkInstallRanks
    ExtractTargetMode.Winlator -> joiPlayLaunchRanks.filter { (_, hint) ->
        hint == "RPG Maker" || hint == "Windows EXE"
    }
    ExtractTargetMode.Managed -> listOf(
        Regex("^game\\.exe$", RegexOption.IGNORE_CASE) to "Game executable",
        Regex("^startup\\.tjs$", RegexOption.IGNORE_CASE) to "KiriKiri startup",
        Regex("^.*\\.(exe)$", RegexOption.IGNORE_CASE) to "Windows executable",
        Regex("^.*\\.(xp3)$", RegexOption.IGNORE_CASE) to "KiriKiri archive",
        Regex("^index\\.(html|htm)$", RegexOption.IGNORE_CASE) to "HTML game",
        Regex("^.*\\.(swf|py|sh|html|htm)$", RegexOption.IGNORE_CASE) to "Game launch file",
    )
}

// Backwards-compat name used by older callers.
private val launchFileRanks: List<Pair<Regex, String>> get() = joiPlayLaunchRanks

internal data class BrowsableNode(
    val name: String,
    val isFile: Boolean,
    val safFile: DocumentFile?,
    val javaFile: File?,
    val children: List<BrowsableNode>,
    val launchHint: String?,
    val rank: Int,
) {
    val uri: Uri? get() = safFile?.uri ?: javaFile?.let { Uri.fromFile(it) }
}

internal fun buildTree(
    root: ArchiveExtractor.ExtractRoot,
    displayName: String,
    ranks: List<Pair<Regex, String>> = launchFileRanks,
): BrowsableNode {
    return when (root) {
        is ArchiveExtractor.ExtractRoot.Saf -> safToNode(root.doc, displayName, ranks)
        is ArchiveExtractor.ExtractRoot.FileRoot -> fileToNode(root.file, displayName, ranks)
    }
}

private fun safToNode(
    doc: DocumentFile,
    displayName: String,
    ranks: List<Pair<Regex, String>> = launchFileRanks,
): BrowsableNode {
    if (doc.isFile) {
        val rank = ranks.indexOfFirst { (re, _) -> re.matches(doc.name ?: "") }
        return BrowsableNode(
            name = displayName.ifBlank { doc.name ?: "?" },
            isFile = true, safFile = doc, javaFile = null, children = emptyList(),
            launchHint = if (rank >= 0) ranks[rank].second else null,
            rank = if (rank >= 0) rank else Int.MAX_VALUE,
        )
    }
    val children = (doc.listFiles().toList()).map { safToNode(it, it.name ?: "?", ranks) }
        .sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
    return BrowsableNode(
        name = displayName.ifBlank { doc.name ?: "?" },
        isFile = false, safFile = doc, javaFile = null, children = children,
        launchHint = null, rank = Int.MAX_VALUE,
    )
}

private fun fileToNode(
    file: File,
    displayName: String,
    ranks: List<Pair<Regex, String>> = launchFileRanks,
): BrowsableNode {
    if (file.isFile) {
        val rank = ranks.indexOfFirst { (re, _) -> re.matches(file.name) }
        return BrowsableNode(
            name = displayName.ifBlank { file.name },
            isFile = true, safFile = null, javaFile = file, children = emptyList(),
            launchHint = if (rank >= 0) ranks[rank].second else null,
            rank = if (rank >= 0) rank else Int.MAX_VALUE,
        )
    }
    val children = (file.listFiles() ?: emptyArray()).map { fileToNode(it, it.name, ranks) }
        .sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
    return BrowsableNode(
        name = displayName.ifBlank { file.name },
        isFile = false, safFile = null, javaFile = file, children = children,
        launchHint = null, rank = Int.MAX_VALUE,
    )
}

internal fun findBestLaunchFile(node: BrowsableNode): BrowsableNode? {
    val all = mutableListOf<BrowsableNode>()
    fun collect(n: BrowsableNode) {
        if (n.isFile && n.launchHint != null) all.add(n)
        for (c in n.children) collect(c)
    }
    collect(node)
    return all.minByOrNull { it.rank }
}

internal fun expandPathTo(root: BrowsableNode, target: BrowsableNode): Set<String> {
    val out = mutableSetOf<String>()
    fun walk(n: BrowsableNode): Boolean {
        if (n === target) return true
        for (c in n.children) {
            if (walk(c)) { out.add(n.name); return true }
        }
        return false
    }
    walk(root)
    return out
}

internal fun flatten(node: BrowsableNode, expanded: Set<String>, depth: Int): List<Pair<Int, BrowsableNode>> {
    val out = mutableListOf<Pair<Int, BrowsableNode>>()
    out.add(depth to node)
    if (node.name in expanded || depth == 0) {
        for (c in node.children) out.addAll(flatten(c, expanded, depth + 1))
    }
    return out
}
