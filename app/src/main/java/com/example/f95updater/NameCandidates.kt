package com.example.f95updater

import java.nio.charset.Charset

/** Where a [NameCandidate]'s text came from. [weight] biases scored-catalog aggregation
 *  (see [scoredCatalogTitleAnalysis]): README titles and specific (non-generic) executable
 *  basenames are the most reliable signals of the true game name, followed by JoiPlay-internal
 *  metadata (engine config/system titles), then app/launcher labels, then folder-derived
 *  fallbacks which are the weakest (most likely to be a generic wrapper/store name).
 *  [strong] sources (README, specific executable) are trusted enough that a non-exact index
 *  partial hit (prefix/substring/token-prefix — see [scoredCatalogTitleAnalysis]) coming from one, alone,
 *  can still be auto-mapped; weaker sources need corroboration from another independent
 *  source instead. */
internal enum class NameCandidateSource(val weight: Int, val strong: Boolean = false) {
    Readme(45, strong = true),
    SpecificExecutable(40, strong = true),
    JoiPlayInternalTitle(25),
    AppLabel(15),
    LauncherLabel(12),
    FolderName(8),
    BasenameFallback(4),
}

internal data class NameCandidate(val text: String, val source: NameCandidateSource)

internal enum class CatalogIdentityKind(val wireValue: String) {
    ProductCode("product_code"),
    DownloadAlias("download_alias"),
}

internal data class CatalogIdentityCandidate(
    val kind: CatalogIdentityKind,
    val value: String,
    val source: NameCandidateSource,
)

/** Executable basenames that are generic wrapper/engine/installer names carried by many
 *  unrelated games (JoiPlay execFile, Winlator executable path) and therefore useless — even
 *  actively misleading — as a title-matching signal. Matching is exact (case-insensitive) so
 *  specific names that merely contain a substring like "game" (e.g. "TamaraExposed3") are never
 *  filtered out. */
private val GENERIC_EXECUTABLE_BASENAMES = setOf(
    "game", "mygame", "start", "setup", "launcher", "payload", "update", "patch",
    "uninstall", "_uninst", "nwjc", "notification_helper", "config",
)

private val GENERIC_TITLE_KEYS = setOf(
    "game",
    "install",
    "installation",
    "installer",
    "setup",
    "start",
    "startup",
    "readme",
    "readmefirst",
    "verxxx",
    "kirikiroid",
    "reipatcher",
    "はじめに",
    "はじめにお読み下さい",
    "起動方法",
    "クレジット敬称略",
)
private val GENERIC_VERSION_TITLE =
    Regex("""(?i)^(?:ver(?:sion)?\s*)?v?\d+(?:\.\d+)+[a-z]?$""")

internal fun isGenericTitleCandidate(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return true
    if (GENERIC_VERSION_TITLE.matches(trimmed)) return true
    val key = CatalogRepository.normalizeTitle(trimmed)
    if (key in GENERIC_TITLE_KEYS) return true
    val lower = trimmed.lowercase()
    return lower.startsWith("uploaded by kimochi gaming") ||
        key.endsWith("fileexplorer") ||
        key.endsWith("filemanager")
}

internal fun isGenericExecutableBasename(basename: String): Boolean =
    basename.trim().lowercase() in GENERIC_EXECUTABLE_BASENAMES

/** Folder/basename text that is a generic wrapper/engine/platform directory carried by many
 *  unrelated games (JoiPlay's `www`/`game`/`app`/`src`/`resources`/`joiplay` subfolders, a
 *  Winlator `pc` prefix folder). Unlike [GENERIC_EXECUTABLE_BASENAMES] these are directory
 *  names, and — critically — they must never become a standalone [NameCandidate] text on
 *  their own: a folder literally named "game" or "pc" could otherwise exact/prefix-match an
 *  unrelated catalog title of the same generic word. A superset of [JOIPLAY_WRAPPER_FOLDERS]
 *  (kept separate rather than widening that shared constant, which also drives unrelated
 *  JoiPlay storage-size grouping logic in MainActivity). */
private val GENERIC_WRAPPER_FOLDER_NAMES = JOIPLAY_WRAPPER_FOLDERS + setOf("joiplay", "pc", "games")

internal fun isGenericWrapperFolderName(name: String): Boolean = name.trim().lowercase() in GENERIC_WRAPPER_FOLDER_NAMES

/** Extracts a [NameCandidate] from an executable path (JoiPlay execFile or a Winlator
 *  executable/dos path), stripping directories and the extension, and dropping it entirely
 *  when the basename is one of [GENERIC_EXECUTABLE_BASENAMES]. */
internal fun executableNameCandidate(path: String?): NameCandidate? {
    val cleanPath = path?.replace('\\', '/')?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val basenameWithExt = cleanPath.substringAfterLast('/')
    val basename = basenameWithExt.substringBeforeLast('.', missingDelimiterValue = basenameWithExt).trim()
    if (basename.isBlank() || isGenericExecutableBasename(basename)) return null
    return NameCandidate(basename, NameCandidateSource.SpecificExecutable)
}

private val PRODUCT_CODE = Regex(
    """(?i)(?<![\p{L}\p{N}])((?:(?:RJ|VJ|RE|BJ|VE)\d{5,})|(?:D_\d{4,}))(?![\p{L}\p{N}])""",
)
private val OTOMI_DOWNLOAD_ALIAS =
    Regex("""(?i)^otomi-games\.com_[a-z0-9_-]+(?:\.(?:rar|zip|7z))?$""")

internal fun normalizeDownloadAlias(value: String): String? {
    val basename = value.replace('\\', '/').substringAfterLast('/').trim()
    if (!OTOMI_DOWNLOAD_ALIAS.matches(basename)) return null
    val lower = basename.lowercase()
    return listOf(".rar", ".zip", ".7z")
        .firstOrNull(lower::endsWith)
        ?.let(lower::removeSuffix)
        ?: lower
}

private val CP932: Charset = Charset.forName("windows-31j")
private val LEGACY_DOS_CHARSETS: List<Charset> = listOf(
    Charset.forName("IBM437"),
    Charset.forName("IBM850"),
)

private fun containsJapanese(value: String): Boolean = value.any { ch ->
    ch in '\u3040'..'\u30ff' || ch in '\u3400'..'\u9fff'
}

/**
 * Repairs the reversible DOS-codepage mojibake produced when CP932 archive names are decoded as
 * CP437/CP850. The round-trip requirement prevents speculative recoding of ordinary Latin text.
 */
internal fun recoverJapaneseMojibake(value: String): String? {
    if (value.isBlank() || containsJapanese(value)) return null
    for (legacy in LEGACY_DOS_CHARSETS) {
        val recovered = runCatching {
            String(value.toByteArray(legacy), CP932)
        }.getOrNull() ?: continue
        if (!containsJapanese(recovered)) continue
        val roundTrip = runCatching {
            String(recovered.toByteArray(CP932), legacy)
        }.getOrNull()
        if (roundTrip == value) return recovered
    }
    return null
}

private fun rawNameCandidatesFor(app: InstalledApp): List<NameCandidate> {
    val out = mutableListOf<NameCandidate>()
    app.label.trim().takeIf { it.isNotBlank() }
        ?.let { out += NameCandidate(it, NameCandidateSource.AppLabel) }
    app.launcherLabel?.trim()?.takeIf { it.isNotBlank() && it != app.label }
        ?.let { out += NameCandidate(it, NameCandidateSource.LauncherLabel) }
    app.readmeTitle?.trim()?.takeIf { it.isNotBlank() }
        ?.let { out += NameCandidate(it, NameCandidateSource.Readme) }
    executableNameCandidate(app.joiPlayExecFile)?.let { out += it }
    executableNameCandidate(app.winlatorExecutablePath)?.let { out += it }
    executableNameCandidate(app.winlatorExecutableDosPath)?.let { out += it }
    joiplayInternalTitleLabels(app).forEach { out += NameCandidate(it, NameCandidateSource.JoiPlayInternalTitle) }

    val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
    val basename = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val parent = path?.substringBeforeLast('/', missingDelimiterValue = "")
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    val pathCandidateTexts = linkedSetOf<String>()
    app.storageFolderName?.trim()?.takeIf { it.isNotBlank() && !isGenericWrapperFolderName(it) }
        ?.let {
            out += NameCandidate(it, NameCandidateSource.FolderName)
            pathCandidateTexts += it.lowercase()
        }
    parent?.takeIf { basename?.lowercase() in GENERIC_WRAPPER_FOLDER_NAMES && !isGenericWrapperFolderName(it) }
        ?.let {
            out += NameCandidate(it, NameCandidateSource.FolderName)
            pathCandidateTexts += it.lowercase()
        }
    basename?.takeIf {
        !isGenericWrapperFolderName(it) && it.lowercase() !in pathCandidateTexts
    }
        ?.let { out += NameCandidate(it, NameCandidateSource.BasenameFallback) }
    return out
}

internal fun identityCandidatesFor(app: InstalledApp): List<CatalogIdentityCandidate> =
    buildList {
        rawNameCandidatesFor(app).forEach { candidate ->
            PRODUCT_CODE.findAll(candidate.text).forEach { match ->
                add(
                    CatalogIdentityCandidate(
                        kind = CatalogIdentityKind.ProductCode,
                        value = match.groupValues[1].uppercase(),
                        source = candidate.source,
                    ),
                )
            }
            normalizeDownloadAlias(candidate.text)?.let { alias ->
                add(
                    CatalogIdentityCandidate(
                        kind = CatalogIdentityKind.DownloadAlias,
                        value = alias,
                        source = candidate.source,
                    ),
                )
            }
        }
    }.distinctBy { Triple(it.kind, it.value, it.source) }

/** Full source-tagged name-candidate list for an installed app, used by
 *  [CatalogRepository.scoredTitleAnalysis] during catalog refresh. Covers: app label, launcher
 *  label, non-generic executable basename (JoiPlay execFile and Winlator executable paths),
 *  the stored README title, existing JoiPlay-internal metadata titles, and folder/basename
 *  fallbacks. This is distinct from [catalogMatchLabels] (which stays untouched for existing
 *  manual/relaxed search call sites) so refresh-time scoring can weight sources independently. */
internal fun nameCandidatesFor(app: InstalledApp): List<NameCandidate> {
    return rawNameCandidatesFor(app)
        .map { candidate ->
            val trimmed = candidate.text.trim()
            candidate.copy(text = recoverJapaneseMojibake(trimmed) ?: trimmed)
        }
        .filterNot { isGenericTitleCandidate(it.text) }
        .distinctBy { it.source to it.text.lowercase() }
}
