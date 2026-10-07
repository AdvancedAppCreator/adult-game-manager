package com.example.f95updater

import java.io.File
import org.json.JSONObject
import org.jsoup.parser.Parser

fun catalogMatchLabels(app: InstalledApp): List<String> {
    val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
    val basename = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val parent = path?.substringBeforeLast('/', missingDelimiterValue = "")
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    val labels = mutableListOf<String>()
    labels += listOfNotNull(app.label, app.launcherLabel)
    labels += listOfNotNull(app.readmeTitle)
    labels += joiplayInternalTitleLabels(app)
    labels += listOfNotNull(
        app.storageFolderName,
        basename,
        parent?.takeIf { basename in setOf("www", "game", "app", "src", "resources") },
    )
    return labels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

internal fun joiplayInternalTitleLabels(app: InstalledApp): List<String> {
    if (app.source != AppSource.JoiPlay && app.source != AppSource.Managed) return emptyList()
    val root = app.storagePath?.let { File(it) }?.takeIf { it.isDirectory } ?: return emptyList()
    val roots = buildList {
        add(root)
        val directHasGame = File(root, "game").isDirectory || File(root, "www").isDirectory || File(root, "data").isDirectory
        if (!directHasGame) {
            root.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && !isNonContentReadmeFolder(it.name) }
                ?.take(8)
                ?.forEach { add(it) }
        }
    }
    val out = linkedSetOf<String>()
    for (candidateRoot in roots) {
        readGameReadmeTitle(candidateRoot)?.let { out += it }
        readRenPyBuildInfoTitle(candidateRoot)?.let { out += it }
        readRenPyOptionsTitle(candidateRoot)?.let { out += it }
        readRpgmSystemTitle(candidateRoot)?.let { out += it }
        readHtmlTitle(candidateRoot)?.let { out += it }
    }
    return out.toList().take(8)
}

private fun readRenPyBuildInfoTitle(root: File): String? {
    val file = File(root, "game/cache/build_info.json")
    val text = readSmallTextFile(file, maxBytes = 128 * 1024) ?: return null
    return runCatching {
        JSONObject(text).optString("name", "")
            .takeIf { it.isNotBlank() }
            ?.let { cleanCatalogMetadataTitle(it) }
    }.getOrNull()
}

private fun readRenPyOptionsTitle(root: File): String? {
    val text = readSmallTextFile(File(root, "game/options.rpy"), maxBytes = 128 * 1024)
        ?: readSmallTextFile(File(root, "game/gui/about.rpy"), maxBytes = 128 * 1024)
        ?: return null
    return Regex("""(?:define\s+)?config\.name\s*=\s*(?:_\()?["'](.+?)["']""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { cleanCatalogMetadataTitle(it) }
}

private fun readRpgmSystemTitle(root: File): String? {
    val file = listOf(
        File(root, "www/data/System.json"),
        File(root, "data/System.json"),
    ).firstOrNull { it.isFile } ?: return null
    val text = readSmallTextFile(file, maxBytes = 128 * 1024) ?: return null
    return runCatching {
        JSONObject(text).optString("gameTitle", "")
            .takeIf { it.isNotBlank() }
            ?.let { cleanCatalogMetadataTitle(it) }
    }.getOrNull()
}

private fun readHtmlTitle(root: File): String? {
    val file = listOf(File(root, "www/index.html"), File(root, "index.html")).firstOrNull { it.isFile } ?: return null
    val text = readSmallTextFile(file, maxBytes = 128 * 1024) ?: return null
    return Regex("""(?is)<title[^>]*>(.*?)</title>""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(Regex("""\s+"""), " ")
        ?.let { cleanCatalogMetadataTitle(it) }
}

private fun readSmallTextFile(file: File, maxBytes: Int): String? {
    if (!file.isFile || file.length() <= 0L || file.length() > maxBytes) return null
    return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
}

private fun cleanCatalogMetadataTitle(value: String): String? {
    val cleaned = Parser.unescapeEntities(value, false).trim().take(160)
    if (cleaned.isBlank()) return null
    val normalized = CatalogRepository.normalizeTitle(cleaned)
    if (normalized.length < 3) return null
    if (normalized in setOf("game", "joiplay", "www", "index", "rmmzgame", "rmmvgame", "rpgmgame", "nwjs")) return null
    if (normalized.all { it.isDigit() }) return null
    return cleaned.takeIf { normalized.any { ch -> ch.isLetter() } }
}
