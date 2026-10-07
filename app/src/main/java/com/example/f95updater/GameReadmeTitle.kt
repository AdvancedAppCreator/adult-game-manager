package com.example.f95updater

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

private const val MAX_README_TITLE_BYTES = 64 * 1024

/** Only the first this-many lines of a README are inspected for a title candidate. */
private const val MAX_README_CONTENT_LINES = 30
private val README_WRAPPER_FOLDERS =
    setOf("www", "game", "app", "src", "resources", "bin", "binaries", "win32", "win64", "x86", "x64")

/** A README filename is recognized when its base name (without the .txt extension) ends in
 *  "readme" or "read me" (case-insensitive), optionally with an attached prefix such as a
 *  release/edition tag ("製品版Readme.txt"). Third-party docs that merely happen to end in
 *  "readme"/"read me" are rejected: a prefix containing whitespace ("Compressed information
 *  read me.txt", "Translation Readme.txt") reads as a descriptive phrase rather than a tag, and
 *  a small set of reject keywords catches attached-without-space cases too
 *  ("PatchReadme.txt", "DependencyReadme.txt"). */
private val README_CORE_REGEX = Regex("(?i)^(.*?)read\\s?me$")
private val README_REJECT_KEYWORDS = listOf(
    "translat", "patch", "crack", "compress", "depend", "prerequisite",
    "redist", "directx", "vcredist", "install", "uncensor", "font", "update",
)

/** Subfolder names that hold third-party/dependency material rather than the game itself.
 *  Their own "readme.txt" (bare, so not caught by [README_REJECT_KEYWORDS]) must not be
 *  mistaken for the game's title when scanning a game folder's immediate subdirectories. */
private val README_NON_CONTENT_FOLDERS = setOf(
    "redist", "vcredist", "directx", "dependencies", "dependency", "prerequisites",
    "prerequisite", "runtimes", "runtime", "crack", "patch", "patches", "translation",
    "translations", "font", "fonts",
)

internal fun isRecognizedReadmeFilename(name: String): Boolean {
    if (!name.endsWith(".txt", ignoreCase = true)) return false
    val base = name.substring(0, name.length - 4).trim()
    if (base.isEmpty()) return false
    val prefix = README_CORE_REGEX.find(base)?.groupValues?.get(1) ?: return false
    if (prefix.isEmpty()) return true
    if (prefix.any { it.isWhitespace() }) return false
    val prefixLower = prefix.lowercase()
    return README_REJECT_KEYWORDS.none { prefixLower.contains(it) }
}

internal fun isNonContentReadmeFolder(name: String): Boolean = name.trim().lowercase() in README_NON_CONTENT_FOLDERS

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(minOf(maxBytes, 8192))
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}

/** Bare "readme.txt"/"read me.txt" (no attached prefix) is preferred over a prefixed variant
 *  when a folder happens to contain both, for deterministic selection. */
private fun readmeFilenamePriority(name: String): Int {
    val base = name.substringBeforeLast('.', name).trim()
    return if (base.equals("readme", ignoreCase = true) || base.equals("read me", ignoreCase = true)) 0 else 1
}

internal fun readGameReadmeTitle(folder: File?): String? {
    val readme = folder
        ?.takeIf { it.isDirectory }
        ?.listFiles()
        ?.filter { it.isFile && isRecognizedReadmeFilename(it.name) }
        ?.sortedWith(compareBy({ readmeFilenamePriority(it.name) }, { it.name.lowercase() }))
        ?.firstOrNull()
        ?: return null
    if (readme.length() !in 1..MAX_README_TITLE_BYTES.toLong()) return null
    return runCatching { readGameReadmeTitle(readme.readBytes()) }.getOrNull()
}

internal fun readGameReadmeTitleNear(folder: File?): String? {
    readGameReadmeTitle(folder)?.let { return it }
    return folder
        ?.takeIf { it.name.lowercase() in README_WRAPPER_FOLDERS }
        ?.parentFile
        ?.let(::readGameReadmeTitle)
}

internal fun readGameReadmeTitle(context: Context, folder: DocumentFile?): String? {
    val readme = folder
        ?.takeIf { it.isDirectory }
        ?.listFiles()
        ?.filter { it.isFile && isRecognizedReadmeFilename(it.name.orEmpty()) }
        ?.sortedWith(compareBy({ readmeFilenamePriority(it.name.orEmpty()) }, { it.name.orEmpty().lowercase() }))
        ?.firstOrNull()
        ?: return null
    val length = readme.length()
    if (length > MAX_README_TITLE_BYTES) return null
    return runCatching {
        context.contentResolver.openInputStream(readme.uri)?.use { input ->
            readGameReadmeTitle(input.readAtMost(MAX_README_TITLE_BYTES + 1))
        }
    }.getOrNull()
}

/** Lines that visually wrap a section heading (e.g. "～挨拶～", "========") rather than
 *  containing the game title, even when they contain real letters (kanji greetings, etc). */
private val README_DECORATIVE_WRAP_CHARS = "~〜～=-*#☆★■□▼▽●○".toSet()

private fun isDecorativeReadmeLine(line: String): Boolean {
    if (line.length < 2) return false
    return line.first() in README_DECORATIVE_WRAP_CHARS && line.last() in README_DECORATIVE_WRAP_CHARS
}

/** Phrases that mark a line as disclaimer/translation/compression boilerplate rather than a
 *  title, so it isn't picked up by the plain (non-quoted) line fallback. */
private val README_LINE_REJECT_PHRASES = listOf(
    "thanks for reading", "compression is", "un-official", "unofficial",
    "compression tool", "compressed for", "compression done by", "image quality",
    "audio quality", "video quality", "elapsedtime", "please read", "translated by",
    "translation by", "readme", "read me",
)

private val README_VERSION_METADATA_REGEX = Regex(
    """(?i)^(?:\d{2,4}[/.-]\d{1,2}[/.-]\d{1,4}[\s_-]*)?(?:version|ver\.?|v)\s*\d[\w.-]*$"""
)

private fun isRejectedReadmeLine(line: String): Boolean {
    val lower = line.lowercase()
    return README_LINE_REJECT_PHRASES.any { lower.contains(it) } ||
        README_VERSION_METADATA_REGEX.matches(line)
}

private val README_SECTION_HEADINGS = listOf(
    "はじめに", "挨拶", "注意事項", "動作環境", "操作について", "起動方法",
    "introduction", "instructions", "requirements", "how to play",
)

private fun isReadmeSectionHeading(line: String): Boolean {
    val clean = line.trim().trimStart('■', '●', '◆', '▼', '▽', '#').trim()
    return README_SECTION_HEADINGS.any { clean.equals(it, ignoreCase = true) }
}

/** Quote pairs recognized for title extraction: Japanese 「...」/『...』 and ASCII "...". The
 *  surrounding quotes are stripped from the returned title. */
private val README_QUOTE_PATTERNS = listOf(
    Regex("「([^「」]{1,100})」"),
    Regex("『([^『』]{1,100})』"),
    Regex("\"([^\"]{1,100})\""),
)

private fun extractQuotedReadmeTitle(line: String): String? =
    README_QUOTE_PATTERNS.mapNotNull { it.find(line) }
        .minByOrNull { it.range.first }
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun isUsableReadmeTitle(candidate: String): Boolean {
    val normalized = CatalogRepository.normalizeTitle(candidate)
    return normalized.length >= 2 && normalized.any(Char::isLetter)
}

internal fun readGameReadmeTitle(bytes: ByteArray): String? {
    if (bytes.isEmpty() || bytes.size > MAX_README_TITLE_BYTES) return null
    val text = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        else -> decodeStrict(bytes, Charsets.UTF_8)
            ?: decodeStrict(bytes, Charset.forName("windows-31j"))
            ?: decodeStrict(bytes, Charset.forName("GB18030"))
            ?: return null
    }
    // Inspect the first useful section rather than simply the first letter-bearing line:
    // decorative banner lines and disclaimer/translation/compression boilerplate are skipped,
    // and a quoted title (「...」/『...』/"...") takes priority over the raw line when present.
    // Limited to the first MAX_README_CONTENT_LINES lines: real title lines are always near the
    // top, and this bounds inspection cost for large/pathological README files regardless of
    // MAX_README_TITLE_BYTES.
    val candidateLines = text.lineSequence().take(MAX_README_CONTENT_LINES).toList()
    val candidateTextLower = candidateLines.joinToString("\n").lowercase()
    if ((candidateTextLower.contains("compression is") ||
            candidateTextLower.contains("compression tool")) &&
        (candidateTextLower.contains("compressed for") ||
            candidateTextLower.contains("image quality") ||
            candidateTextLower.contains("audio quality"))
    ) {
        return null
    }
    for (rawLine in candidateLines) {
        val line = rawLine.trim().removePrefix("\uFEFF").take(160)
        if (line.isBlank() || isDecorativeReadmeLine(line) || isReadmeSectionHeading(line)) continue
        if (isRejectedReadmeLine(line)) continue
        extractQuotedReadmeTitle(line)?.let { quoted ->
            if (isUsableReadmeTitle(quoted)) return quoted
        }
        val lower = line.lowercase()
        if ((line.contains("この度は") || line.contains("今回は") || line.contains("御購入") ||
                line.contains("お買い上げ")) &&
            (line.contains("ありがとう") || line.contains("購入"))
        ) {
            continue
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) continue
        if (isUsableReadmeTitle(line)) return line
    }
    return null
}

private fun decodeStrict(bytes: ByteArray, charset: Charset): String? =
    try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        null
    }
