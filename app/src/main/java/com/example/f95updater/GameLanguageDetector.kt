package com.example.f95updater

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class GameLanguageDetection(
    val languageTag: String,
    val confidence: Float,
    val sampledFiles: Int,
    val sampledCharacters: Int,
)

object GameLanguageDetector {
    private const val MAX_FILES_SCANNED = 20_000
    private const val MAX_SAMPLE_FILES = 24
    private const val MAX_FILE_BYTES = 96 * 1024
    private const val MAX_TOTAL_CHARACTERS = 300_000
    private const val MIN_FILE_LETTERS = 80
    private const val MIN_TOTAL_LETTERS = 240
    private const val MIN_RESULT_SHARE = 0.62
    private const val MIN_LEAD_MARGIN = 0.16

    private val textExtensions = setOf(
        "txt", "md", "rpy", "ks", "tjs", "json", "js", "html", "htm", "xml",
        "csv", "ini", "cfg", "yaml", "yml", "lua", "rb", "py",
    )
    private val ignoredPathSegments = setOf(
        "node_modules", "renpy/common", "www/js/libs", "www/js/plugins",
    )
    private val identifier by lazy { LanguageIdentification.getClient() }

    suspend fun detect(root: File): GameLanguageDetection? = withContext(Dispatchers.IO) {
        require(root.isDirectory && root.canRead()) {
            "Game folder is not readable: ${root.absolutePath}"
        }
        val files = candidateFiles(root)
        if (files.isEmpty()) return@withContext null

        val scores = mutableMapOf<String, Double>()
        var sampledFiles = 0
        var sampledCharacters = 0
        var sampledLetters = 0
        for (file in files) {
            if (sampledCharacters >= MAX_TOTAL_CHARACTERS) break
            val text = readTextSample(file) ?: continue
            val remaining = MAX_TOTAL_CHARACTERS - sampledCharacters
            val sample = if (text.length <= remaining) text else text.take(remaining)
            val letters = sample.count(Char::isLetter)
            if (letters < MIN_FILE_LETTERS) continue
            val languages = identifier.identifyPossibleLanguages(sample).await()
                .filter { it.languageTag != "und" && it.confidence >= 0.20f }
            if (languages.isEmpty()) continue
            val weight = letters.coerceAtMost(8_000).toDouble()
            languages.forEach { language ->
                val tag = normalizeLanguageTag(language.languageTag)
                scores[tag] = scores.getOrDefault(tag, 0.0) + language.confidence * weight
            }
            sampledFiles++
            sampledCharacters += sample.length
            sampledLetters += letters
        }
        if (sampledLetters < MIN_TOTAL_LETTERS || scores.isEmpty()) return@withContext null

        val ranked = scores.entries.sortedByDescending { it.value }
        val total = ranked.sumOf { it.value }
        val first = ranked.first()
        val share = first.value / total
        val secondShare = ranked.getOrNull(1)?.value?.div(total) ?: 0.0
        if (share < MIN_RESULT_SHARE || share - secondShare < MIN_LEAD_MARGIN) {
            AppLog.i(
                "GameLanguage",
                "Ambiguous language samples files=$sampledFiles chars=$sampledCharacters " +
                    "top=${first.key}:${"%.2f".format(share)}",
            )
            return@withContext null
        }
        GameLanguageDetection(
            languageTag = first.key,
            confidence = share.toFloat(),
            sampledFiles = sampledFiles,
            sampledCharacters = sampledCharacters,
        ).also {
            AppLog.i(
                "GameLanguage",
                "Detected language=${it.languageTag} confidence=${"%.2f".format(it.confidence)} " +
                    "files=${it.sampledFiles} chars=${it.sampledCharacters}",
            )
        }
    }

    private fun candidateFiles(root: File): List<File> {
        val files = mutableListOf<File>()
        val directories = ArrayDeque<File>()
        directories.add(root)
        var scanned = 0
        while (directories.isNotEmpty() && scanned < MAX_FILES_SCANNED) {
            val directory = directories.removeFirst()
            val children = directory.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
            for (file in children) {
                if (++scanned > MAX_FILES_SCANNED) break
                if (file.isDirectory) {
                    val relative = file.relativeTo(root).invariantSeparatorsPath.lowercase()
                    if (ignoredPathSegments.none {
                            relative == it || relative.startsWith("$it/")
                        }
                    ) {
                        directories.addLast(file)
                    }
                    continue
                }
                if (!file.isFile || !file.canRead()) continue
                if (file.extension.lowercase() !in textExtensions) continue
                if (file.length() !in 64..(2L * 1024 * 1024)) continue
                files += file
            }
        }
        return files.sortedWith(
            compareBy<File>(
                { samplePriority(it.relativeTo(root).invariantSeparatorsPath) },
                { it.length() },
                { it.absolutePath.lowercase() },
            ),
        ).take(MAX_SAMPLE_FILES)
    }

    private fun samplePriority(path: String): Int {
        val lower = path.lowercase()
        return when {
            listOf("dialog", "scenario", "message", "script", "text").any(lower::contains) -> 0
            lower.endsWith(".rpy") || lower.endsWith(".ks") || lower.endsWith(".tjs") -> 1
            lower.endsWith(".json") -> 2
            lower.endsWith(".txt") || lower.endsWith(".md") -> 3
            else -> 4
        }
    }

    private fun readTextSample(file: File): String? = runCatching {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(MAX_FILE_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            buffer.copyOf(total)
        }
        if (bytes.isEmpty()) return null
        val decoded = decodeText(bytes) ?: return null
        val controls = decoded.count { it.isISOControl() && it != '\r' && it != '\n' && it != '\t' }
        if (controls > decoded.length / 100) return null
        decoded.lineSequence()
            .map(String::trim)
            .filter { line ->
                line.length >= 4 && line.count(Char::isLetter) >= (line.length / 5).coerceAtLeast(2)
            }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.size >= 2) {
            when {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    decodeStrict(bytes.copyOfRange(2, bytes.size), "UTF-16LE")?.let { return it }
                bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    decodeStrict(bytes.copyOfRange(2, bytes.size), "UTF-16BE")?.let { return it }
            }
        }
        decodeStrict(bytes, "UTF-8")?.let { return it.removePrefix("\uFEFF") }

        val candidates = listOf(
            "windows-31j" to ::japaneseScriptCount,
            "EUC-KR" to ::hangulCount,
            "windows-1251" to ::cyrillicCount,
            "GB18030" to ::hanCount,
            "Big5" to ::hanCount,
        ).mapNotNull { (charset, scriptCount) ->
            val decoded = decodeStrict(bytes, charset) ?: return@mapNotNull null
            val evidence = scriptCount(decoded)
            if (evidence < 20) null else decoded to evidence
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun decodeStrict(bytes: ByteArray, charsetName: String): String? = runCatching {
        Charset.forName(charsetName)
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun japaneseScriptCount(text: String): Int =
        text.count { it in '\u3040'..'\u30FF' }

    private fun hangulCount(text: String): Int =
        text.count { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }

    private fun cyrillicCount(text: String): Int =
        text.count { it in '\u0400'..'\u04FF' }

    private fun hanCount(text: String): Int =
        text.count { it in '\u3400'..'\u4DBF' || it in '\u4E00'..'\u9FFF' }
}

internal fun normalizeLanguageTag(tag: String): String {
    val normalized = tag.trim().replace('_', '-')
    if (normalized.isBlank()) return "und"
    val parts = normalized.split('-').filter(String::isNotBlank)
    if (parts.isEmpty()) return "und"
    return buildList {
        add(
            when (parts.first().lowercase()) {
                "iw" -> "he"
                "in" -> "id"
                else -> parts.first().lowercase()
            },
        )
        parts.drop(1).forEach { part ->
            add(
                when {
                    part.length == 2 -> part.uppercase()
                    part.length == 4 -> part.lowercase().replaceFirstChar(Char::uppercase)
                    else -> part
                },
            )
        }
    }.joinToString("-")
}

internal fun preferredGameLanguage(title: String, detectedLanguage: String?): String? {
    val hasJapaneseKana = title.any {
        it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF'
    }
    return if (hasJapaneseKana) "ja" else detectedLanguage?.let(::normalizeLanguageTag)
        ?.takeUnless { it == "und" }
}
