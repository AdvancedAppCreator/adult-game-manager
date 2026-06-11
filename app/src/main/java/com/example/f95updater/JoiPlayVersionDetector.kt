package com.example.f95updater

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** A single version detection result with provenance. */
data class VersionCandidate(val source: String, val version: String, val detail: String? = null)

/**
 * Detects an installed JoiPlay game's version using multiple independent strategies:
 *   1. Title/folder regex (existing)
 *   2. JoiPlay's own metadata catalog (.joiback metadata.zip)
 *   3. SAF probe of engine-specific files in the game folder
 *
 * Also includes JoiPlay's user-set `version` field if present in games.json.
 */
object JoiPlayVersionDetector {

    suspend fun detect(context: Context, app: InstalledApp): List<VersionCandidate> = coroutineScope {
        if (app.source != AppSource.JoiPlay) return@coroutineScope emptyList()
        val title = app.label
        val folderName = app.storageFolderName ?: ""
        val gameId = app.joiPlayGameId ?: ""

        // Run all detectors in parallel.
        val regexDef = async(Dispatchers.Default) { detectFromRegex(title, folderName, app.storagePath) }
        val metaDef = async(Dispatchers.IO) {
            val v = JoiPlayMetadataCatalog.lookupVersion(context, title) ?: return@async null
            VersionCandidate("From JoiPlay catalog", v, "Matched by title")
        }
        val safDef = async(Dispatchers.IO) {
            runCatching { detectFromSaf(context, app) }
                .onFailure { AppLog.w("JoiPlayDetect", "SAF probe failed for $title", it) }
                .getOrNull()
        }
        // JoiPlay's own user-set version (from games.json's `version` field) â†’ also surface.
        val backupVer = JoiPlayBackupReader.cachedGames(context)
            .firstOrNull { it.id == gameId }?.version?.trim()?.ifBlank { null }
        val backupDef: VersionCandidate? = backupVer?.let {
            VersionCandidate("From JoiPlay", it, "Manually set in JoiPlay's edit dialog")
        }

        val out = mutableListOf<VersionCandidate>()
        backupDef?.let { out.add(it) }
        regexDef.await()?.let { out.add(it) }
        metaDef.await()?.let { out.add(it) }
        safDef.await()?.let { out.add(it) }
        out
    }

    // -----------------------------------------------------------------------
    // Method 1: regex over title/folder (reuse the same logic as the importer).
    // -----------------------------------------------------------------------
    private fun detectFromRegex(title: String, folderName: String, storagePath: String?): VersionCandidate? {
        val v1 = extractVersion(title)
        if (v1 != null) return VersionCandidate("From game title", v1, title)
        val v2 = extractVersion(folderName)
        if (v2 != null) return VersionCandidate("From folder name", v2, folderName)
        // Try parent folder if basename is generic.
        if (folderName.lowercase() in setOf("www", "game", "app", "src", "resources") && storagePath != null) {
            val parent = storagePath.replace('\\', '/').trimEnd('/')
                .substringBeforeLast('/', missingDelimiterValue = "")
                .substringAfterLast('/')
            val v3 = extractVersion(parent)
            if (v3 != null) return VersionCandidate("From folder name", v3, parent)
        }
        return null
    }

    private val versionPatterns = listOf(
        Regex("""(?:^|[^A-Za-z0-9])(v(?:er(?:sion)?)?)[._\- ]?(\d+(?:\.\d+)*[a-zA-Z0-9._\-]{0,16})""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[^A-Za-z0-9])()[Oo](\.\d+(?:\.\d+)*[a-zA-Z0-9._\-]{0,8})"""),
        Regex("""[\[(]()v?(\d+(?:\.\d+)+[a-zA-Z0-9._\-]*)[\])]""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[^A-Za-z0-9.])()(\d+\.\d+(?:\.\d+)*[a-zA-Z]?)"""),
        Regex("""[a-z]()(\d+\.\d+(?:\.\d+)+[a-zA-Z]?)"""),
        Regex("""(?:^|[^A-Za-z0-9])()(\d+-\d+-\d+(?:-\d+)*[a-zA-Z]?)"""),
    )
    private val trailingJunk = Regex(
        """[-_.](pc|win|win32|win64|windows|android|mac|osx|linux|free|full|pro|premium|patreon|public|release|build|setup|installer)\b.*$""",
        RegexOption.IGNORE_CASE
    )

    private fun extractVersion(s: String): String? {
        for (re in versionPatterns) {
            val m = re.find(s) ?: continue
            val hasV = m.groupValues[1].isNotBlank()
            var ver = m.groupValues[2].trimEnd('.', '-', '_', ' ').trimStart('-', '_', ' ')
            if (ver.startsWith('.')) ver = "0$ver"
            ver = trailingJunk.replace(ver, "").trimEnd('.', '-', '_', ' ')
            if (ver.isBlank() || ver.none { it.isDigit() }) continue
            if (!hasV && !ver.contains('.') && !ver.contains('-')) {
                val first = ver.takeWhile { it.isDigit() }
                if (first.length >= 4) continue
            }
            return ver
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Method 3: SAF probe â€” read engine-specific files inside the game folder.
    // -----------------------------------------------------------------------
    private suspend fun detectFromSaf(context: Context, app: InstalledApp): VersionCandidate? {
        val folderName = app.storageFolderName ?: return null
        val type = app.joiPlayType?.lowercase() ?: return null

        val rootUri = JoiPlayScanner.getRootUri(context) ?: return null
        val root = runCatching { DocumentFile.fromTreeUri(context, rootUri) }.getOrNull() ?: return null
        val gameDir = root.findFile(folderName) ?: return null

        return when (type) {
            "renpy", "legacyrenpy" -> probeRenPy(context, gameDir)
            "rpgmmv", "rpgmmz" -> probeRpgmHtml(context, gameDir)
            "tyrano" -> probeTyrano(context, gameDir)
            "twine", "html" -> probeHtmlIndex(context, gameDir)
            else -> null
        }
    }

    /** Ren'Py: look for `define config.version = "X"` in game/options.rpy or game/script.rpy. */
    private suspend fun probeRenPy(context: Context, gameDir: DocumentFile): VersionCandidate? {
        val gameSub = gameDir.findFile("game") ?: return null
        val candidates = listOf("options.rpy", "options.rpyc", "script.rpy")
        // Regex matches:  define config.version = "1.2.3"   /   define gui.about = "Version 1.2"
        val versionRe = Regex("""define\s+config\.version\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val altRe = Regex("""define\s+(?:gui\.about|build\.name|version)\s*=\s*["']([^"']{0,80})["']""", RegexOption.IGNORE_CASE)
        for (name in candidates) {
            val df = gameSub.findFile(name) ?: continue
            val text = readText(context, df, maxBytes = 64 * 1024) ?: continue
            versionRe.find(text)?.let {
                return VersionCandidate("From game files", it.groupValues[1], "Found in game/$name (config.version)")
            }
            altRe.find(text)?.let { m ->
                val v = m.groupValues[1].trim()
                if (v.any { it.isDigit() }) {
                    return VersionCandidate("From game files", v, "Found in game/$name")
                }
            }
        }
        return null
    }

    /** RPGM MV/MZ: package.json (NW.js manifest). Often contains "version" (sometimes engine, sometimes game). */
    private suspend fun probeRpgmHtml(context: Context, gameDir: DocumentFile): VersionCandidate? {
        // The "game folder" might already be the www directory in some imports.
        val www = gameDir.findFile("www") ?: gameDir
        val pkg = www.findFile("package.json") ?: gameDir.findFile("package.json")
        if (pkg != null) {
            val text = readText(context, pkg, maxBytes = 16 * 1024)
            if (text != null) {
                runCatching {
                    val obj = JSONObject(text)
                    val v = obj.optString("version", "").trim()
                    if (v.isNotBlank()) {
                        return VersionCandidate("From game files", v, "From package.json")
                    }
                }
            }
        }
        // Fallback: data/System.json has a "versionId" but that's an internal id, not user version.
        return null
    }

    private suspend fun probeTyrano(context: Context, gameDir: DocumentFile): VersionCandidate? {
        // Tyrano games often have data/system/Config.tjs or tyrano/version.txt
        val data = gameDir.findFile("data") ?: return null
        val sys = data.findFile("system") ?: return null
        val cfg = sys.findFile("Config.tjs") ?: return null
        val text = readText(context, cfg, maxBytes = 32 * 1024) ?: return null
        Regex("""version\s*[:=]\s*["']([^"']{1,40})["']""", RegexOption.IGNORE_CASE).find(text)?.let {
            val v = it.groupValues[1]
            if (v.any { c -> c.isDigit() }) {
                return VersionCandidate("From game files", v, "From data/system/Config.tjs")
            }
        }
        return null
    }

    private suspend fun probeHtmlIndex(context: Context, gameDir: DocumentFile): VersionCandidate? {
        val idx = gameDir.findFile("index.html") ?: return null
        val text = readText(context, idx, maxBytes = 32 * 1024) ?: return null
        // <meta name="version" content="X"> or  <title>Game vX.Y</title>
        Regex("""<meta\s+name=["']version["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(text)?.let {
            return VersionCandidate("From game files", it.groupValues[1], "From <meta version> tag")
        }
        return null
    }

    /** Read up to maxBytes of a document's text content. */
    private suspend fun readText(context: Context, doc: DocumentFile, maxBytes: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                    val sb = StringBuilder()
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { r ->
                        val buf = CharArray(4096)
                        var total = 0
                        while (total < maxBytes) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            sb.append(buf, 0, n)
                            total += n
                        }
                    }
                    sb.toString()
                }
            }.getOrNull()
        }
}
