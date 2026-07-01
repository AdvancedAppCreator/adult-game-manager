package com.example.f95updater

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // JoiPlay resolves only raw file:// paths (its MediaStore-based content:// resolver
        // returns null for our FileProvider). Allow passing file:// URIs to it without crashing.
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder().build(),
        )
        AppLog.init(applicationContext)
        AppLog.i("App", "onCreate v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        CrashReporter.install(applicationContext)
        StaticContext.appContext = applicationContext
        setContent {
            val themeMode by ThemePrefs.observe(applicationContext).collectAsState(initial = AppThemeMode.System)
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                AppThemeMode.System -> systemDark
                AppThemeMode.Light -> false
                AppThemeMode.Dark -> true
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

// ---- Sort & filter ----
data class ApkPostInstall(val pkgName: String?, val sourceArchive: java.io.File?)
data class PendingArchiveDestination(val archive: java.io.File, val apkMode: Boolean)

enum class SortKey(val label: String) {
    Name("Name"),
    Installed("Install date"),
    LastUsed("Last used"),
    ThreadUpdated("Thread updated"),
    Size("Total size"),
    AppSize("App size"),
    DataSize("Data size"),
    CacheSize("Cache size"),
    Status("Update status"),
}

enum class LibraryLayoutMode(val label: String) {
    List("List"),
    Cards("Cards"),
}

private fun statusOrder(s: UpdateStatus): Int = when (s) {
    UpdateStatus.UpdateAvailable -> 0
    UpdateStatus.Unknown -> 1
    UpdateStatus.CheckFailed -> 2
    UpdateStatus.UpToDate -> 3
    UpdateStatus.NotMapped -> 4
}

private fun AppMapping.withPersonalFieldsFrom(existing: AppMapping?): AppMapping =
    if (existing == null) this else copy(
        userStatus = existing.userStatus,
        personalRating = existing.personalRating,
        personalNotes = existing.personalNotes,
        manualCorrectionNote = existing.manualCorrectionNote,
        manualInstalledVersion = existing.manualInstalledVersion,
        manualInstalledVersionFingerprint = existing.manualInstalledVersionFingerprint,
        manualInstalledDate = existing.manualInstalledDate,
        manualInstalledDateFingerprint = existing.manualInstalledDateFingerprint,
        manualInstalledDateSource = existing.manualInstalledDateSource,
        manualLocalIdentity = existing.manualLocalIdentity,
        mappedCatalogId = existing.mappedCatalogId,
        mappedCatalogSource = existing.mappedCatalogSource,
        mappedCatalogSourceId = existing.mappedCatalogSourceId,
        mappedCatalogTitle = existing.mappedCatalogTitle,
        mappedCatalogVersion = existing.mappedCatalogVersion,
        mappedCatalogUrl = existing.mappedCatalogUrl,
        mappedCatalogUpdatedAt = existing.mappedCatalogUpdatedAt,
        mappedCatalogPublishedAt = existing.mappedCatalogPublishedAt,
        mappedCatalogModifiedAt = existing.mappedCatalogModifiedAt,
    )

private fun AppMapping.hasPersonalFields(): Boolean =
    userStatus != UserGameStatus.None ||
        personalRating != null ||
        personalNotes.isNotBlank() ||
        manualCorrectionNote.isNotBlank() ||
        manualInstalledVersion.isNotBlank() ||
        manualInstalledDate > 0L

private fun installedVersionFingerprint(app: InstalledApp): String =
    listOf(
        app.source.name,
        app.versionName,
        app.versionCode.toString(),
        app.lastUpdateTime.toString(),
        app.storagePath.orEmpty(),
        app.storageFolderName.orEmpty(),
        app.joiPlayGameId.orEmpty(),
    ).joinToString("|")

private fun effectiveInstalledVersion(app: InstalledApp, mapping: AppMapping?): String {
    val manual = mapping?.manualInstalledVersion?.trim()?.ifBlank { null } ?: return app.versionName
    val fingerprint = mapping.manualInstalledVersionFingerprint
    return if (fingerprint.isBlank() || fingerprint == installedVersionFingerprint(app)) manual else app.versionName
}

private fun hasActiveManualInstalledVersion(app: InstalledApp, mapping: AppMapping?): Boolean =
    mapping?.manualInstalledVersion?.isNotBlank() == true &&
        mapping.manualInstalledVersionFingerprint == installedVersionFingerprint(app)

private fun effectiveInstalledDate(app: InstalledApp, mapping: AppMapping?): Long {
    val manual = mapping?.manualInstalledDate?.takeIf { it > 0L } ?: return app.firstInstallTime
    val fingerprint = mapping.manualInstalledDateFingerprint
    return if (fingerprint.isBlank() || fingerprint == installedVersionFingerprint(app)) manual else app.firstInstallTime
}

private fun hasActiveManualInstalledDate(app: InstalledApp, mapping: AppMapping?): Boolean =
    (mapping?.manualInstalledDate ?: 0L) > 0L &&
        mapping?.manualInstalledDateFingerprint == installedVersionFingerprint(app)

private fun catalogInstalledDateCandidate(game: CatalogGame?): Pair<Long, String>? {
    val catalogGame = game ?: return null
    return when {
        catalogGame.publishedAt > 0L -> catalogGame.publishedAt * 1000L to "catalog published date"
        catalogGame.modifiedAt > 0L -> catalogGame.modifiedAt * 1000L to "catalog modified date"
        catalogGame.ts > 0L -> catalogGame.ts * 1000L to "catalog update date"
        else -> null
    }
}

private fun localIdentityTokens(app: InstalledApp): List<String> {
    val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
    val pathBase = path?.substringAfterLast('/')
    val pathParent = path?.substringBeforeLast('/', missingDelimiterValue = "")?.substringAfterLast('/')
    val execBase = app.joiPlayExecFile
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.', missingDelimiterValue = "")
    val raw = buildList {
        add(app.packageName)
        addAll(catalogMatchLabels(app))
        add(app.joiPlayGameId)
        add(pathBase)
        add(pathParent)
        add(execBase)
    }
    val ignored = setOf("pc", "game", "www", "app", "src", "resources", "joiplay", "android")
    return raw.mapNotNull { value ->
        CatalogRepository.normalizeTitle(value.orEmpty()).takeIf { it.length >= 3 && it !in ignored }
    }.distinct()
}

private fun AppMapping.withLocalIdentityFrom(app: InstalledApp): AppMapping =
    copy(manualLocalIdentity = localIdentityTokens(app))

private fun AppMapping.withCatalogSnapshot(game: CatalogGame): AppMapping =
    copy(
        mappedCatalogId = game.thread_id,
        mappedCatalogSource = game.source,
        mappedCatalogSourceId = game.sourceId,
        mappedCatalogTitle = game.title,
        mappedCatalogVersion = game.version,
        mappedCatalogUrl = game.canonicalUrl,
        mappedCatalogUpdatedAt = game.ts,
        mappedCatalogPublishedAt = game.publishedAt,
        mappedCatalogModifiedAt = game.modifiedAt,
    )

private fun AppMapping.withExternalSnapshot(result: ExternalMirrorResult): AppMapping =
    copy(
        mappedCatalogId = result.threadId ?: result.mirrorUrl.hashCode(),
        mappedCatalogSource = if (result.sourceHost.equals("adultgameworld.com", ignoreCase = true)) {
            SOURCE_ADULTGAMEWORLD
        } else {
            SOURCE_F95ZONE
        },
        mappedCatalogSourceId = result.threadId?.toString(),
        mappedCatalogTitle = result.title,
        mappedCatalogVersion = result.version,
        mappedCatalogUrl = result.mirrorUrl,
        mappedCatalogUpdatedAt = 0L,
        mappedCatalogPublishedAt = 0L,
        mappedCatalogModifiedAt = 0L,
    )


private fun AppMapping.toCatalogSnapshot(): CatalogGame? {
    val fallbackUrl = f95Url?.takeIf { it.isNotBlank() }
    val id = mappedCatalogId ?: fallbackUrl?.hashCode() ?: return null
    val source = mappedCatalogSource
        ?: if (fallbackUrl?.contains("adultgameworld", ignoreCase = true) == true) {
            SOURCE_ADULTGAMEWORLD
        } else {
            SOURCE_F95ZONE
        }
    return CatalogGame(
        thread_id = id,
        title = mappedCatalogTitle.ifBlank { fallbackUrl.orEmpty() },
        version = mappedCatalogVersion ?: lastSeenVersion,
        source = source,
        sourceId = mappedCatalogSourceId,
        sourceUrl = mappedCatalogUrl ?: fallbackUrl,
        ts = mappedCatalogUpdatedAt,
        publishedAt = mappedCatalogPublishedAt,
        modifiedAt = mappedCatalogModifiedAt.takeIf { it > 0L } ?: mappedCatalogUpdatedAt,
    )
}

private fun mappedCatalogGame(mapping: AppMapping?, catalogById: Map<Int, CatalogGame>?): CatalogGame? {
    if (mapping == null) return null
    val tid = mapping.threadId ?: F95UrlParser.extractThreadId(mapping.f95Url)
    return tid?.let { catalogById?.get(it) } ?: mapping.toCatalogSnapshot()
}

private fun threadUpdatedAfterInstall(row: AppRow, catalogById: Map<Int, CatalogGame>?): Boolean {
    val installedAt = effectiveInstalledDate(row.installed, row.mapping)
    val updatedAt = mappedCatalogGame(row.mapping, catalogById)?.ts ?: 0L
    return installedAt > 0L && updatedAt > 0L && updatedAt * 1000L > installedAt
}

private fun personalRatingLabel(rating: Int?): String =
    rating?.let { "$it/5" } ?: "Unrated"

fun catalogMatchLabels(app: InstalledApp): List<String> {
    val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
    val basename = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val parent = path?.substringBeforeLast('/', missingDelimiterValue = "")
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    val labels = mutableListOf<String>()
    labels += listOfNotNull(app.label, app.launcherLabel)
    labels += joiplayInternalTitleLabels(app)
    labels += listOfNotNull(
        app.storageFolderName,
        basename,
        parent?.takeIf { basename in setOf("www", "game", "app", "src", "resources") },
    )
    return labels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

private val JOIPLAY_WRAPPER_FOLDERS = setOf("www", "game", "app", "src", "resources")

private fun joiplayInternalTitleLabels(app: InstalledApp): List<String> {
    if (app.source != AppSource.JoiPlay) return emptyList()
    val root = app.storagePath?.let { File(it) }?.takeIf { it.isDirectory } ?: return emptyList()
    val roots = buildList {
        add(root)
        val directHasGame = File(root, "game").isDirectory || File(root, "www").isDirectory || File(root, "data").isDirectory
        if (!directHasGame) {
            root.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.take(8)
                ?.forEach { add(it) }
        }
    }
    val out = linkedSetOf<String>()
    for (candidateRoot in roots) {
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

internal fun joiPlaySizeKey(app: InstalledApp): String? {
    if (app.source != AppSource.JoiPlay) return null
    val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
    if (!path.isNullOrBlank()) {
        val basename = path.substringAfterLast('/').ifBlank { path }
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            .substringAfterLast('/')
            .ifBlank { "" }
        if (basename.lowercase() in JOIPLAY_WRAPPER_FOLDERS && parent.isNotBlank()) {
            return parent
        }
    }
    return app.storageFolderName?.ifBlank { null } ?: path?.substringAfterLast('/')?.ifBlank { null }
}

internal fun effectiveInstalledSize(
    app: InstalledApp,
    joiPlaySizeInfo: JoiPlayScanner.SizeInfo?,
): Long =
    if (app.source == AppSource.JoiPlay) {
        (joiPlaySizeInfo?.totalBytes ?: 0L).takeIf { it > 0L } ?: app.totalSize
    } else {
        app.totalSize
    }

internal fun effectiveInstalledTotalSize(
    apps: List<InstalledApp>,
    joiPlaySizeInfo: Map<String, JoiPlayScanner.SizeInfo>,
): Long = apps.sumOf { app ->
    effectiveInstalledSize(app, joiPlaySizeKey(app)?.let { joiPlaySizeInfo[it] })
}

private fun joiPlaySizeTarget(app: InstalledApp): JoiPlayScanner.SizeTarget? {
    val key = joiPlaySizeKey(app) ?: return null
    val path = app.storagePath?.replace('\\', '/')?.trimEnd()
    val scanPath = path?.let {
        val basename = it.substringAfterLast('/').ifBlank { it }
        if (basename.lowercase() in JOIPLAY_WRAPPER_FOLDERS) {
            it.substringBeforeLast('/', missingDelimiterValue = it)
        } else {
            it
        }
    }
    return JoiPlayScanner.SizeTarget(
        key = key,
        label = app.label,
        storagePath = scanPath,
        folderName = key,
    )
}

private data class RelaxedCandidateResult(
    val games: List<CatalogGame>,
    val translatedQuery: String?,
)

private enum class PermissionRationale {
    AllFilesConfig,
    AllFilesInstallApk,
    AllFilesJoiPlayInstall,
    AllFilesUnusedFolders,
    AllFilesRenPySaves,
    UsageAccess,
}

private enum class RenPySlotSort(val label: String) {
    Modified("Modified"),
    FileName("File"),
    SaveName("Save name"),
    Size("Size"),
}

private enum class SaveReportFilter(val label: String) {
    All("All"),
    Associated("Associated"),
    Unassociated("Unassociated"),
}

private data class SaveCompareValue(
    val key: String,
    val type: String,
    val value: String,
)

private data class SaveCompareDiff(
    val key: String,
    val type: String,
    val leftValue: String?,
    val rightValue: String?,
)

private fun isWideEditorLayout(configuration: android.content.res.Configuration): Boolean {
    return configuration.screenWidthDp >= 700 || configuration.screenWidthDp > configuration.screenHeightDp
}

private fun Modifier.simpleVerticalScrollbar(scrollState: ScrollState): Modifier =
    drawWithContent {
        drawContent()
        val max = scrollState.maxValue
        if (max <= 0) return@drawWithContent
        val viewportHeight = size.height
        val totalHeight = viewportHeight + max
        val thumbHeight = (viewportHeight * (viewportHeight / totalHeight)).coerceAtLeast(28.dp.toPx())
        val thumbTop = (scrollState.value / max.toFloat()) * (viewportHeight - thumbHeight)
        drawRect(
            color = Color.Black.copy(alpha = 0.32f),
            topLeft = Offset(size.width - 10.dp.toPx(), thumbTop),
            size = Size(8.dp.toPx(), thumbHeight),
        )
    }

@Composable
private fun ScrollableColumnWithScrollbar(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
                .pointerInput(scrollState) {
                    detectDragGestures { change, dragAmount ->
                        val max = scrollState.maxValue
                        if (max <= 0) return@detectDragGestures
                        change.consume()
                        val viewportHeight = size.height.toFloat()
                        val totalHeight = viewportHeight + max
                        val thumbHeight = (viewportHeight * (viewportHeight / totalHeight)).coerceAtLeast(28.dp.toPx())
                        val trackHeight = (viewportHeight - thumbHeight).coerceAtLeast(1f)
                        scrollState.dispatchRawDelta(dragAmount.y * (max / trackHeight))
                    }
                }
                .simpleVerticalScrollbar(scrollState),
        )
    }
}

private suspend fun relaxedTitleCandidatesWithTranslation(
    catalog: CatalogRepository,
    labels: List<String>,
    limit: Int,
): RelaxedCandidateResult {
    val direct = catalog.relaxedTitleCandidates(labels, limit = limit)
    if (direct.isNotEmpty()) return RelaxedCandidateResult(direct, null)

    val translatedLabels = labels.mapNotNull { label ->
        EnglishTitleTranslator.translateIfNeeded(label)
            .onFailure { AppLog.w("Translate", "Failed translating '$label': ${it.message}") }
            .getOrNull()
    }.distinct()

    if (translatedLabels.isEmpty()) return RelaxedCandidateResult(emptyList(), null)
    return RelaxedCandidateResult(
        games = catalog.relaxedTitleCandidates(translatedLabels, limit = limit),
        translatedQuery = translatedLabels.firstOrNull(),
    )
}

private fun catalogMatchLogContext(app: InstalledApp, labels: List<String>): String {
    val normalized = labels.map { CatalogRepository.normalizeTitle(it) }
        .filter { it.isNotBlank() }
        .distinct()
    return "app='${app.label}' pkg=${app.packageName} source=${app.source} " +
        "labels=${labels.joinToString(prefix = "[", postfix = "]") { "'$it'" }} " +
        "norms=${normalized.joinToString(prefix = "[", postfix = "]") { "'$it'" }}"
}

private fun openExternalUrl(context: Context, url: String) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun shareAdultGameManager(context: Context, supportThreadUrl: String) {
    val shareText = """
        Adult Game Manager - Android companion for tracking adult game updates across installed APKs, JoiPlay games, and multiple catalog sources.
        Local-first: no game downloader, no analytics, and public releases/docs.

        Download: ${AppConfig.DEFAULT_RELEASES_URL}
        Support thread: $supportThreadUrl
        Help: ${AppConfig.DEFAULT_HELP_URL}
    """.trimIndent()
    val sendIntent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Adult Game Manager for Android")
        .putExtra(Intent.EXTRA_TEXT, shareText)
    context.startActivity(
        Intent.createChooser(sendIntent, "Share Adult Game Manager")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun allowCatalogAcronymMatch(app: InstalledApp, labels: List<String>): Boolean {
    if (app.source == AppSource.JoiPlay) return true
    if (labels.any { it != app.label && CatalogRepository.normalizeTitle(it).length in 3..8 }) return true
    val norm = CatalogRepository.normalizeTitle(app.label)
    if (norm.length !in 4..8 || app.label.any { it.isLowerCase() }) return false
    val lowerPkg = app.packageName.lowercase()
    val commonPrefixes = listOf("com.", "org.", "net.", "android.", "google.", "samsung.")
    return commonPrefixes.none { lowerPkg.startsWith(it) } && lowerPkg.startsWith(norm)
}

private data class AmbiguousCatalogMatch(
    val row: AppRow,
    val candidates: List<CatalogGame>,
    val via: String,
)

private data class AlreadyMatchedCatalogMatch(
    val item: AmbiguousCatalogMatch,
    val keptGame: CatalogGame,
)

enum class Tab { Installed, Catalog }

private enum class ScreenshotPanel(val title: String) {
    LaunchLibrary("AGM library"),
    LaunchCatalogFilters("Catalog filters"),
    LaunchAdvancedFilters("Advanced filters"),
    LaunchGameDetails("Game details"),
    LaunchReviewUnmapped("Review unmapped games"),
    LaunchF95Import("Import from F95 Updater"),
    SortMenu("Sort menu"),
    MainMenu("Main menu"),
    CatalogMenu("Catalog submenu"),
    JoiPlayMenu("JoiPlay submenu"),
    BackupMenu("Backup submenu"),
    DiagnosticsMenu("Help / diagnostics submenu"),
    About("About"),
    Support("Support"),
    JoiPlaySettings("JoiPlay settings"),
    JoiPlayWarning("JoiPlay install warning"),
    JoiPlayPicker("JoiPlay install picker"),
    ApkPicker("APK install picker"),
    ApkConfirm("APK install confirmation"),
    ExtractConfirm("Archive extraction confirmation"),
    JoiPlayDelete("JoiPlay delete confirmation"),
    CatalogMain("Catalog tab"),
    CatalogTagFilter("Catalog tag filter"),
}

/** Matches an unfinished `tag:<prefix>` token at the end of the apps-tab filter
 *  field (no trailing whitespace). Group 1 = the prefix, possibly empty. */
private val TAG_TOKEN_AT_END_APPS = Regex("""(?i)\btag:([\w-]*)$""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.Installed) }
    var screenshotCatalogQuery by remember { mutableStateOf<String?>(null) }
    var installedShellReady by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rootView = LocalView.current
    val scope = rememberCoroutineScope()
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    val catalog = remember { CatalogRepository(context.applicationContext) }
    val repo = remember { MappingRepository(context.applicationContext) }
    var catalogLabels by remember { mutableStateOf<CatalogLabelsV2?>(null) }
    LaunchedEffect(Unit) { catalog.sourceCatalogIndex(); catalogLabels = catalog.labels() }
    LaunchedEffect(Unit) {
        yield()
        installedShellReady = true
    }
    val mappings by repo.mappings.collectAsState(initial = emptyMap())
    val appConfig by AppConfigStore.observe(context.applicationContext).collectAsState()
    var screenshotCapturing by remember { mutableStateOf(false) }
    var rootSnackbarMsg by remember { mutableStateOf<String?>(null) }
    val rootSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(rootSnackbarMsg) {
        rootSnackbarMsg?.let {
            rootSnackbarHostState.showSnackbar(it)
            rootSnackbarMsg = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(rootSnackbarHostState) },
        floatingActionButton = {
            if (appConfig.diagnosticsEnabled) {
                val captureScreenshot = {
                    if (!screenshotCapturing) {
                        screenshotCapturing = true
                        scope.launch {
                            val appContext = context.applicationContext
                            runCatching {
                                val file = ScreenshotDiagnostics.capture(
                                    appContext,
                                    rootView,
                                    "manual-${System.currentTimeMillis()}",
                                )
                                AppLog.i("Screenshots", "Manual screenshot captured: ${file.name}")
                                rootSnackbarMsg = "Screenshot added to diagnostics logs"
                            }.onFailure {
                                AppLog.e("Screenshots", "Manual screenshot capture failed", it)
                                rootSnackbarMsg = "Screenshot failed: ${it.message}"
                            }
                            screenshotCapturing = false
                        }
                    }
                }
                if (compactHeight) SmallFloatingActionButton(
                    onClick = captureScreenshot,
                ) {
                    if (screenshotCapturing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.BugReport, contentDescription = "Add screenshot to diagnostics logs")
                    }
                } else FloatingActionButton(
                    onClick = {
                        if (screenshotCapturing) return@FloatingActionButton
                        screenshotCapturing = true
                        scope.launch {
                            val appContext = context.applicationContext
                            runCatching {
                                val file = ScreenshotDiagnostics.capture(
                                    appContext,
                                    rootView,
                                    "manual-${System.currentTimeMillis()}",
                                )
                                AppLog.i("Screenshots", "Manual screenshot captured: ${file.name}")
                                rootSnackbarMsg = "Screenshot added to diagnostics logs"
                            }.onFailure {
                                AppLog.e("Screenshots", "Manual screenshot capture failed", it)
                                rootSnackbarMsg = "Screenshot failed: ${it.message}"
                            }
                            screenshotCapturing = false
                        }
                    },
                ) {
                    if (screenshotCapturing) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.BugReport, contentDescription = "Add screenshot to diagnostics logs")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(modifier = if (compactHeight) Modifier.height(56.dp) else Modifier) {
                NavigationBarItem(
                    selected = tab == Tab.Installed,
                    onClick = { tab = Tab.Installed },
                    icon = { Icon(Icons.Default.Apps, null) },
                    label = if (compactHeight) null else ({ Text("Installed") }),
                )
                NavigationBarItem(
                    selected = tab == Tab.Catalog,
                    onClick = { tab = Tab.Catalog },
                    icon = { Icon(Icons.Default.MenuBook, null) },
                    label = if (compactHeight) null else ({ Text("Catalog") }),
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Installed -> {
                    if (!installedShellReady) {
                        StartupPlaceholder()
                    } else {
                        InstalledScreen(
                            catalog = catalog,
                            sharedRepo = repo,
                            sharedLabels = catalogLabels,
                            onLabelsChange = { catalogLabels = it },
                            onScreenshotTabChange = { tab = it },
                            onScreenshotCatalogQuery = { screenshotCatalogQuery = it },
                        )
                    }
                }
                Tab.Catalog -> CatalogScreen(
                    catalog = catalog,
                    labels = catalogLabels,
                    mappings = mappings,
                    screenshotQuery = screenshotCatalogQuery,
                    onOpenThread = { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                )
            }
        }
    }
}

@Composable
private fun StartupPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                "Loading Adult Game Manager…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun InstalledScreen(
    catalog: CatalogRepository,
    sharedRepo: MappingRepository,
    sharedLabels: CatalogLabelsV2?,
    onLabelsChange: (CatalogLabelsV2?) -> Unit,
    onScreenshotTabChange: (Tab) -> Unit = {},
    onScreenshotCatalogQuery: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val rootView = LocalView.current
    val scope = rememberCoroutineScope()
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = hasLegacyStorageAccess(context.applicationContext)
        AppLog.i("Permissions", "Legacy storage permission result granted=$granted grants=$grants")
    }
    val compactWidth = LocalConfiguration.current.screenWidthDp < 420
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    val repo = sharedRepo
    val scraper = remember { F95Scraper() }
    val searcher = remember { WebSearcher() }
    val appUpdater = remember { AppUpdater() }

    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var downloadProgress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val catalogLabels = sharedLabels

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var renPySaveAssociations by remember { mutableStateOf<Map<String, List<RenPySaveAssociation>>>(emptyMap()) }
    var renPySaveLocations by remember { mutableStateOf<List<RenPySaveLocation>>(emptyList()) }
    var renPyManualAssociations by remember { mutableStateOf<Map<String, RenPySaveManualAssociation>>(emptyMap()) }
    var renPySaveLastScannedAt by remember { mutableStateOf(0L) }
    var renPySaveScanning by remember { mutableStateOf(false) }
    var renPySaveScanRequest by remember { mutableStateOf(0) }
    var renPySaveScanManualRequest by remember { mutableStateOf(false) }
    var rpgmSaveAssociations by remember { mutableStateOf<Map<String, List<RpgmSaveLocation>>>(emptyMap()) }
    var rpgmSaveLocations by remember { mutableStateOf<List<RpgmSaveLocation>>(emptyList()) }
    var rpgmManualAssociations by remember { mutableStateOf<Map<String, RpgmSaveManualAssociation>>(emptyMap()) }
    var rpgmSaveLastScannedAt by remember { mutableStateOf(0L) }
    var rpgmSaveScanning by remember { mutableStateOf(false) }
    var rpgmSaveScanRequest by remember { mutableStateOf(0) }
    var rpgmSaveScanManualRequest by remember { mutableStateOf(false) }
    var joiPlaySizeInfo by remember { mutableStateOf<Map<String, JoiPlayScanner.SizeInfo>>(emptyMap()) }
    var joiPlaySizeScanning by remember { mutableStateOf(false) }
    var joiPlaySizeScanProgress by remember { mutableStateOf<JoiPlayScanner.SizeProgress?>(null) }
    var joiPlaySizeScanningFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var joiPlaySizeScanRequest by remember { mutableStateOf(0) }
    var joiPlaySizeScanForce by remember { mutableStateOf(false) }
    var pendingJoiPlaySizeScanAfterGrant by remember { mutableStateOf(false) }
    var autoJoiPlaySizeScanStarted by remember { mutableStateOf(false) }
    var hasUsage by remember { mutableStateOf(InstalledAppsScanner.hasUsageAccess(context)) }
    val mappings by repo.mappings.collectAsState(initial = emptyMap())
    var mappingOverrides by remember { mutableStateOf<Map<String, AppMapping>>(emptyMap()) }
    val hidden by repo.hidden.collectAsState(initial = emptySet())
    val themeMode by ThemePrefs.observe(context.applicationContext).collectAsState(initial = AppThemeMode.System)
    // Reactive app config — UI recomposes on Import / drop-in pickup / clear.
    val appConfig by AppConfigStore.observe(context.applicationContext).collectAsState()
    var catalogById by remember { mutableStateOf<Map<Int, CatalogGame>?>(null) }
    // Sentinel that other code paths can bump to re-trigger the catalogById load
    // (e.g. after a catalog sync completes, after a backup import).
    var catalogReloadTick by remember { mutableStateOf(0) }
    LaunchedEffect(catalogReloadTick) {
        runCatching { catalogById = catalog.gamesById() }
    }

    // ---- Persisted filter state ----
    val initialFilters = remember { FilterPrefs.load(context.applicationContext) }
    var showHidden by remember { mutableStateOf(initialFilters.showHidden) }

    var checking by remember { mutableStateOf(false) }
    var checkProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    var autoBackupList by remember { mutableStateOf<List<AutoBackupManager.BackupEntry>>(emptyList()) }
    var autoBackupDialogOpen by remember { mutableStateOf(false) }
    var autoBackupConfirmRestore by remember { mutableStateOf<AutoBackupManager.BackupEntry?>(null) }
    var supportDialogOpen by remember { mutableStateOf(false) }
    var topStatusOpen by remember { mutableStateOf(false) }
    var diagnosticsSummaryOpen by remember { mutableStateOf(false) }
    var matchResearchProgress by remember { mutableStateOf<MatchResearchProgress?>(null) }
    var permissionRationale by remember { mutableStateOf<PermissionRationale?>(null) }
    var installWarningOpen by remember { mutableStateOf(false) }
    var joiplaySettingsOpen by remember { mutableStateOf(false) }
    var installPickerOpen by remember { mutableStateOf(false) }
    var joiplayBackupFilePickerOpen by remember { mutableStateOf(false) }
    var backupScopedPickerOpen by remember { mutableStateOf(false) }
    var backupScopedRootUri by remember { mutableStateOf<Uri?>(null) }
    var importBackupPickerOpen by remember { mutableStateOf(false) }
    var importBackupAccessDisclosureOpen by remember { mutableStateOf(false) }
    var importBackupScopedRootUri by remember { mutableStateOf<Uri?>(null) }
    var exportBackupPickerOpen by remember { mutableStateOf(false) }
    var unusedFolderRootPickerOpen by remember { mutableStateOf(false) }
    var unusedFolderBackupPickerOpen by remember { mutableStateOf(false) }
    var unusedFolderSavePickerOpen by remember { mutableStateOf(false) }
    var unusedFolderRootPath by remember { mutableStateOf<String?>(null) }
    var unusedFolderReport by remember { mutableStateOf<JoiPlayUnusedFolderReport?>(null) }
    var unusedFolderScanning by remember { mutableStateOf(false) }
    var unusedFolderProgress by remember { mutableStateOf<JoiPlayUnusedFolderReporter.Progress?>(null) }
    var renPySaveLocationsOpen by remember { mutableStateOf(false) }
    var renPySaveAssociationPicker by remember { mutableStateOf<RenPySaveLocation?>(null) }
    var renPySaveEditorTarget by remember { mutableStateOf<AppRow?>(null) }
    var renPyAddFolderTarget by remember { mutableStateOf<AppRow?>(null) }
    var rpgmSaveLocationsOpen by remember { mutableStateOf(false) }
    var rpgmSaveAssociationPicker by remember { mutableStateOf<RpgmSaveLocation?>(null) }
    var rpgmSaveViewerTarget by remember { mutableStateOf<AppRow?>(null) }
    var rpgmAddFolderTarget by remember { mutableStateOf<AppRow?>(null) }
    var saveBackupBrowserOpen by remember { mutableStateOf(false) }
    var apkInstallPickerOpen by remember { mutableStateOf(false) }
    // Pending APK install confirmation: source file + non-persistent "delete on success".
    var apkInstallConfirm by remember { mutableStateOf<java.io.File?>(null) }
    // While > 0, the next extract flow result will be routed to the APK installer
    // (the user picked an archive in the "Install APK…" flow). We use the flow's
    // `extractedRoot` to know when the extraction completed, then show the file
    // browser in ApkInstall mode.
    var apkExtractMode by remember { mutableStateOf(false) }
    // Holds an APK file whose source archive should be deleted after a successful install.
    var apkDeleteSourceAfter by remember { mutableStateOf<java.io.File?>(null) }
    // Holds a pending extract confirmation: source file + chosen destination root.
    var extractConfirm by remember {
        mutableStateOf<Pair<java.io.File, ArchiveExtractor.ExtractRoot>?>(null)
    }
    var pendingArchiveDestination by remember { mutableStateOf<PendingArchiveDestination?>(null) }
    var upgradePrompt by remember { mutableStateOf<JoiPlayUpgradePrompt?>(null) }
    var archiveAnalysisInProgress by remember { mutableStateOf<java.io.File?>(null) }
    val extractFlow = remember { JoiPlayExtractFlow(context.applicationContext, scope) }
    val upgradeFlow = remember { JoiPlayArchiveUpgradeFlow(context.applicationContext, scope) }
    var upgradeGuidanceDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        upgradeGuidanceDismissed = JoiPlaySettingsStore.upgradeGuidanceDismissed(context.applicationContext)
    }
    // Last directory the user navigated to in any file picker (Install APK or
    // Install game in JoiPlay). Falls back to external-storage root the first
    // time. Updated after every pick so subsequent picks open at the same place.
    var pickerInitialPath by remember {
        mutableStateOf(android.os.Environment.getExternalStorageDirectory().absolutePath)
    }
    LaunchedEffect(Unit) {
        runCatching {
            val saved = JoiPlaySettingsStore.lastFilePickerDir(context.applicationContext)
            if (!saved.isNullOrBlank() && java.io.File(saved).isDirectory) pickerInitialPath = saved
        }
    }
    LaunchedEffect(Unit) {
        val saved = JoiPlaySettingsStore.backupFolderUri(context.applicationContext)
        val uri = saved?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (uri != null && hasPersistedReadPermission(context, uri)) {
            backupScopedRootUri = uri
            importBackupScopedRootUri = uri
            AppLog.i("Backup", "Restored scoped backup folder permission uri=$uri")
        } else if (uri != null) {
            JoiPlaySettingsStore.setBackupFolderUri(context.applicationContext, null)
            AppLog.w("Backup", "Saved scoped backup folder permission no longer exists uri=$uri")
        }
    }
    fun rememberPickerDir(file: java.io.File) {
        val parent = file.parentFile?.absolutePath ?: return
        pickerInitialPath = parent
        scope.launch { JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, parent) }
    }
    fun resolveExtractRoot(savedDest: String): ArchiveExtractor.ExtractRoot? = when {
        savedDest.startsWith("file://") -> {
            val p = Uri.decode(savedDest.removePrefix("file://"))
            val dir = java.io.File(p)
            if (dir.isDirectory && dir.canWrite()) ArchiveExtractor.ExtractRoot.FileRoot(dir) else null
        }
        else -> {
            val destUri = Uri.parse(savedDest)
            val destDoc = DocumentFile.fromTreeUri(context.applicationContext, destUri)
            if (destDoc != null && destDoc.isDirectory) ArchiveExtractor.ExtractRoot.Saf(destDoc) else null
        }
    }
    fun prepareArchiveExtract(file: java.io.File, apkMode: Boolean) {
        scope.launch {
            val savedDest = JoiPlaySettingsStore.extractDestUri(context.applicationContext)
            val root = savedDest?.let(::resolveExtractRoot)
            if (root != null) {
                if (apkMode) apkExtractMode = true
                extractConfirm = file to root
            } else {
                if (savedDest != null) {
                    snackbarMsg = "Saved destination is no longer accessible. Pick a folder for this extraction."
                }
                pendingArchiveDestination = PendingArchiveDestination(file, apkMode)
            }
        }
    }
    fun prepareJoiPlayArchiveInstall(file: java.io.File) {
        archiveAnalysisInProgress = file
        scope.launch {
            try {
                AppLog.i("JoiPlayUpgrade", "Inspecting archive for upgrade candidates: ${file.name}")
                val analysis = withContext(Dispatchers.IO) {
                    JoiPlayArchiveInspector.analyze(file)
                }
                val matches = JoiPlayArchiveInspector.findUpgradeMatches(analysis, apps)
                AppLog.i(
                    "JoiPlayUpgrade",
                    "Archive analysis: display='${analysis.displayName}' candidates=${analysis.candidateNames} " +
                        "entries=${analysis.entryCount} matches=${matches.map { it.label }}"
                )
                when (InstallRouting.routeUpgradeInspection(matches)) {
                    InstallRouting.UpgradeInspectionRoute.ShowUpgradePrompt -> {
                        upgradePrompt = JoiPlayUpgradePrompt(file, analysis, matches)
                    }
                    InstallRouting.UpgradeInspectionRoute.ExtractAsNewInstall -> {
                        AppLog.i("JoiPlayUpgrade", "No upgrade match found for ${file.name}; falling back to new install extraction")
                        prepareArchiveExtract(file, apkMode = false)
                    }
                }
            } catch (t: Exception) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.w("JoiPlayUpgrade", "Could not inspect archive ${file.absolutePath}", t)
                prepareArchiveExtract(file, apkMode = false)
            } finally {
                archiveAnalysisInProgress = null
            }
        }
    }
    // Multi-select: long-press a row to start, tap toggles, "Hide" / "Unhide" applies in bulk.
    val selection = remember { mutableStateListOf<String>() }
    val selectionMode = selection.isNotEmpty()

    // Counter bumped from the APK installer result callback to trigger a post-
    // install scan + catalog match. Done as a counter (not a Boolean) so multiple
    // installs back-to-back each re-run the effect.
    var pendingPostInstallRefresh by remember { mutableStateOf<Int?>(null) }

    // Auto-flush any queued crashes on launch.
    LaunchedEffect(Unit) {
        if (catalogLabels == null) onLabelsChange(catalog.labels())
        // Only auto-flush if upload is configured. Otherwise crashes silently queue locally
        // until the user taps "Save logs to Documents".
        if (AppConfigStore.current(context.applicationContext).hasCrashUpload) {
            val (ok, fail) = CrashReporter.flush(context.applicationContext)
            if (ok > 0 || fail > 0) {
                snackbarMsg = "Crash reports: $ok uploaded, $fail pending"
            }
        }
    }

    var sortKey by remember { mutableStateOf(initialFilters.sortKey) }
    var sortDesc by remember { mutableStateOf(initialFilters.sortDesc) }
    val activeFilters = remember { mutableStateListOf<UpdateStatus>().apply { addAll(initialFilters.activeStatuses) } }
    var nameFilter by remember { mutableStateOf(initialFilters.nameFilter) }
    var hasSavesOnlyFilter by remember { mutableStateOf(false) }
    var threadUpdatedAfterInstallFilter by remember { mutableStateOf(initialFilters.threadUpdatedAfterInstallOnly) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var libraryLayoutMode by remember { mutableStateOf(initialFilters.layoutMode) }
    var subCatalogOpen by remember { mutableStateOf(false) }
    var subJoiPlayOpen by remember { mutableStateOf(false) }
    var subSaveToolsOpen by remember { mutableStateOf(false) }
    var subBackupOpen by remember { mutableStateOf(false) }
    var subLogsOpen by remember { mutableStateOf(false) }
    var subThemeOpen by remember { mutableStateOf(false) }
    var overwriteManualMatches by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        overwriteManualMatches = MatchingPrefs.overwriteManualMatches(context.applicationContext)
    }
    var screenshotWalkthroughRunning by remember { mutableStateOf(false) }
    var launchScreenshotRunning by remember { mutableStateOf(false) }
    var screenshotPanel by remember { mutableStateOf<ScreenshotPanel?>(null) }
    val expanded = remember { mutableStateListOf<String>() }

    // Initial scan on first composition; lifecycle observer below catches subsequent ON_RESUMEs.
    LaunchedEffect(Unit) {
        AppLog.i("Scan", "Initial scan: entering LaunchedEffect")
        // Take an auto-backup if we just upgraded. Runs once per launch, cheap if no upgrade.
        runCatching { AutoBackupManager.maybeBackupOnUpgrade(context.applicationContext, repo) }
            .onFailure { AppLog.w("AutoBackup", "Upgrade-backup hook threw (ignored)", it) }
        runCatching {
            AppLog.i("Scan", "Initial scan: scanning Android packages")
            val android = InstalledAppsScanner.scan(context)
            AppLog.i("Scan", "Initial scan: android=${android.size}")
            // Validate that backup-listed JoiPlay games still exist on disk; mark missing
            // ones deleted so they stop appearing on every launch. Cheap: one tree walk
            // over the granted SAF root + best-effort File.exists() checks.
            val pruned = try {
                JoiPlayBackupReader.pruneMissingFolders(context.applicationContext)
            } catch (t: Throwable) {
                AppLog.w("Scan", "Prune-missing failed (ignored)", t); 0
            }
            if (pruned > 0) AppLog.i("Scan", "Pruned $pruned missing JoiPlay backup entries")
            val joiplayBackup = try {
                JoiPlayBackupReader.asInstalledApps(context.applicationContext)
            } catch (t: Throwable) {
                AppLog.w("Scan", "JoiPlay backup read failed (ignored)", t)
                emptyList()
            }
            AppLog.i("Scan", "Initial scan: joiplayBackup=${joiplayBackup.size}")
            val joiplayFolder = try {
                JoiPlayScanner.scan(context.applicationContext)
            } catch (t: Throwable) {
                AppLog.w("Scan", "JoiPlay folder scan failed (ignored)", t)
                emptyList()
            }
            AppLog.i("Scan", "Initial scan: joiplayFolder=${joiplayFolder.size}")
            val joiplay = mergeJoiPlaySources(backup = joiplayBackup, folder = joiplayFolder)
            apps = (android + joiplay).sortedBy { it.label.lowercase() }
            AppLog.i("Scan", "Initial scan DONE: total=${apps.size}")
        }.onFailure {
            AppLog.e("Scan", "Initial scan FATAL", it)
            CrashReporter.logCaught(context.applicationContext, "initial_scan", it)
            snackbarMsg = "Initial app scan failed: ${it.message}"
        }
    }

    // Rescan on resume so newly installed/uninstalled apps appear.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsage = InstalledAppsScanner.hasUsageAccess(context)
                scope.launch {
                    runCatching { AppConfigStore.reload(context.applicationContext) }
                        .onFailure { AppLog.w("AppConfig", "Resume config reload failed", it) }
                    runCatching {
                        val android = InstalledAppsScanner.scan(context)
                        val joiplayBackup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                        val joiplayFolder = JoiPlayScanner.scan(context.applicationContext)
                        val joiplay = mergeJoiPlaySources(backup = joiplayBackup, folder = joiplayFolder)
                        apps = (android + joiplay).sortedBy { it.label.lowercase() }
                    }.onFailure {
                        AppLog.e("Scan", "Resume scan failed", it)
                        CrashReporter.logCaught(context.applicationContext, "resume_scan", it)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Launcher that receives JoiPlay's return value (if any). On result we dump every
    // detail to the log so we can inspect what JoiPlay actually sends back.
    val joiplayResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val rc = result.resultCode
        val data = result.data
        AppLog.i("JoiPlayResult", "=== JoiPlay returned: resultCode=$rc (RESULT_OK=${android.app.Activity.RESULT_OK}, RESULT_CANCELED=${android.app.Activity.RESULT_CANCELED}) ===")
        AppLog.i("JoiPlayResult", "  intent=${data}")
        AppLog.i("JoiPlayResult", "  action=${data?.action}")
        AppLog.i("JoiPlayResult", "  data uri=${data?.data}")
        AppLog.i("JoiPlayResult", "  type=${data?.type}")
        AppLog.i("JoiPlayResult", "  categories=${data?.categories}")
        AppLog.i("JoiPlayResult", "  component=${data?.component}")
        AppLog.i("JoiPlayResult", "  package=${data?.`package`}")
        AppLog.i("JoiPlayResult", "  flags=0x${java.lang.Integer.toHexString(data?.flags ?: 0)}")
        val extras = data?.extras
        if (extras == null) {
            AppLog.i("JoiPlayResult", "  extras: <null>")
        } else {
            val keys = extras.keySet().toList()
            AppLog.i("JoiPlayResult", "  extras: ${keys.size} keys")
            for (k in keys) {
                val v = runCatching { extras.get(k) }.getOrNull()
                AppLog.i("JoiPlayResult", "    [$k] (${v?.javaClass?.simpleName}) = $v")
            }
        }
        AppLog.i("JoiPlayResult", "=== end ===")
        snackbarMsg = "JoiPlay finished (rc=$rc, extras=${extras?.keySet()?.size ?: 0}). Check logs."
    }
    // Tracks what to clean up after the system installer returns: optional package
    // name to verify the install succeeded, and optional source-archive file to
    // delete (set when user checked "delete after install").
    var apkPostInstall by remember { mutableStateOf<ApkPostInstall?>(null) }
    var apkInstalling by remember { mutableStateOf(false) }
    // PackageInstaller session callback — fires when install completes/fails
    androidx.compose.runtime.DisposableEffect(Unit) {
        ApkInstaller.onResult = { pkg, success, msg ->
            AppLog.i("ApkInstall", "Session callback: success=$success msg=$msg pkg=$pkg")
            apkInstalling = false
            val pending = apkPostInstall
            apkPostInstall = null
            if (pending != null) {
                if (success) {
                    pending.sourceArchive?.let { src ->
                        runCatching {
                            if (src.exists() && src.delete()) {
                                AppLog.i("ApkInstall", "Deleted source ${src.absolutePath}")
                                snackbarMsg = "Installed ✓ Source deleted."
                            } else {
                                snackbarMsg = "Installed ✓ (could not delete source)."
                            }
                        }
                    } ?: run { snackbarMsg = "Install complete ✓" }
                    // Targeted single-package refresh instead of full rescan
                    val installedPkg = pending.pkgName ?: pkg
                    if (installedPkg != null) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val pm = context.packageManager
                                val pi = pm.getPackageInfo(installedPkg, android.content.pm.PackageManager.GET_META_DATA)
                                val ai = pi.applicationInfo ?: return@runCatching
                                val label = ai.loadLabel(pm).toString()
                                val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                                    pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
                                val newApp = InstalledApp(
                                    packageName = pi.packageName,
                                    label = label,
                                    versionName = pi.versionName ?: "",
                                    versionCode = vCode,
                                    firstInstallTime = pi.firstInstallTime,
                                    lastUpdateTime = pi.lastUpdateTime,
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    // Add or replace in the apps list
                                    apps = apps.filterNot { it.packageName == installedPkg } + newApp
                                }
                                // Catalog-match the new app
                                val catalogRepo = catalog
                                val game = catalogRepo.fuzzyMatch(label)
                                if (game != null) {
                                    val tid = game.thread_id
                                    val url = "https://f95zone.to/threads/$tid/"
                                    repo.upsert(AppMapping(
                                        packageName = installedPkg,
                                        f95Url = url,
                                        threadId = tid,
                                        matchSource = "catalog-auto:post-install",
                                    ))
                                }
                            }.onFailure { AppLog.w("ApkInstall", "Single-pkg refresh failed", it) }
                        }
                    } else {
                        // No package name known — fall back to full rescan
                        pendingPostInstallRefresh = (pendingPostInstallRefresh ?: 0) + 1
                    }
                } else {
                    snackbarMsg = msg.ifBlank { "Install cancelled or failed; source kept." }
                }
            }
        }
        onDispose {
            ApkInstaller.onResult = null
            ApkInstaller.unregisterReceiver(context)
        }
    }
    // Legacy launcher kept for URI-based fallback (extracted APK without File handle)
    val apkInstallResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val rc = result.resultCode
        AppLog.i("ApkInstall", "Legacy installer returned rc=$rc")
        val pending = apkPostInstall
        apkPostInstall = null
        if (pending == null) return@rememberLauncherForActivityResult
        // Best-effort: check if package appeared
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val installed = pending.pkgName?.let { pkg ->
                var found = false
                for (attempt in 1..5) {
                    found = runCatching {
                        context.packageManager.getPackageInfo(pkg, 0)
                        true
                    }.getOrDefault(false)
                    if (found) break
                    kotlinx.coroutines.delay(500L * attempt)
                }
                found
            } ?: (rc == android.app.Activity.RESULT_OK)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (installed) {
                    pending.sourceArchive?.let { src ->
                        runCatching {
                            if (src.exists() && src.delete()) {
                                snackbarMsg = "Installed. Source archive deleted."
                            } else {
                                snackbarMsg = "Installed (could not delete source)."
                            }
                        }
                    } ?: run { snackbarMsg = "Install complete." }
                    pendingPostInstallRefresh = (pendingPostInstallRefresh ?: 0) + 1
                } else {
                    snackbarMsg = "Install cancelled or failed; source kept."
                }
            }
        }
    }
    data class RefreshProgress(
        val current: Int,
        val total: Int,
        val matched: Int,
        val startedAtMs: Long,
    ) {
        val etaSecondsRemaining: Long?
            get() {
                if (current <= 0) return null
                val elapsed = System.currentTimeMillis() - startedAtMs
                val perItem = elapsed / current.toDouble()
                val remaining = (total - current).coerceAtLeast(0)
                return (perItem * remaining / 1000.0).toLong()
            }
    }
    data class BulkFuzzyProgress(
        val current: Int,
        val total: Int,
        val updated: Int,
        val notFound: Int,
    )
    var refreshProgress by remember { mutableStateOf<RefreshProgress?>(null) }
    var refreshCancelled by remember { mutableStateOf(false) }
    var refreshCancelledNote by remember { mutableStateOf(false) }
    var ambiguousCatalogMatches by remember { mutableStateOf<List<AmbiguousCatalogMatch>>(emptyList()) }
    var unmappedReviewMatches by remember { mutableStateOf<List<AmbiguousCatalogMatch>>(emptyList()) }
    var alreadyMatchedReviewMatches by remember { mutableStateOf<List<AlreadyMatchedCatalogMatch>>(emptyList()) }
    var unmappedReviewOpen by remember { mutableStateOf(false) }
    var unmappedReviewLoading by remember { mutableStateOf(false) }
    var bulkFuzzyProgress by remember { mutableStateOf<BulkFuzzyProgress?>(null) }
    var unmatchedFoundPromptOpen by remember { mutableStateOf(false) }

    fun liveMappings(): Map<String, AppMapping> = mappings + mappingOverrides

    fun rememberMapping(mapping: AppMapping) {
        mappingOverrides = mappingOverrides + (mapping.packageName to mapping)
    }

    fun currentUnmappedRows(): List<AppRow> = apps.mapNotNull { app ->
        val m = mappings[app.packageName]
        if (m?.notOnF95 == true) return@mapNotNull null
        if (m?.f95Url.isNullOrBlank()) AppRow(app, m, UpdateStatus.NotMapped) else null
    }.sortedBy { it.installed.label.lowercase() }

    fun currentManualMatchedRows(): List<AppRow> = apps.mapNotNull { app ->
        val m = mappings[app.packageName]
        if (m?.notOnF95 == true) return@mapNotNull null
        if (m?.matchSource?.startsWith("manual") == true && !m.f95Url.isNullOrBlank()) {
            AppRow(app, m, UpdateStatus.Unknown)
        } else {
            null
        }
    }.sortedBy { it.installed.label.lowercase() }

    suspend fun buildCurrentUnmappedReviewItems(): List<AmbiguousCatalogMatch> {
        return currentUnmappedRows().map { row ->
            val labels = catalogMatchLabels(row.installed)
            val allowAcronym = allowCatalogAcronymMatch(row.installed, labels)
            val ambiguous = catalog.ambiguousTitleMatch(labels, allowAcronym)
            AmbiguousCatalogMatch(
                row = row,
                candidates = ambiguous?.candidates?.take(12).orEmpty(),
                via = ambiguous?.via ?: "current-unmapped",
            )
        }
    }

    suspend fun buildManualMatchedReviewItems(): List<AmbiguousCatalogMatch> {
        val byId = catalog.gamesById()
        return currentManualMatchedRows().map { row ->
            val labels = catalogMatchLabels(row.installed)
            val allowAcronym = allowCatalogAcronymMatch(row.installed, labels)
            val ambiguous = catalog.ambiguousTitleMatch(labels, allowAcronym)
            val currentTid = row.mapping?.threadId ?: F95UrlParser.extractThreadId(row.mapping?.f95Url)
            val current = currentTid?.let { byId[it] }
            AmbiguousCatalogMatch(
                row = row,
                candidates = (listOfNotNull(current) + ambiguous?.candidates.orEmpty())
                    .distinctBy { it.thread_id }
                    .take(12),
                via = if (current != null) "manual-match" else ambiguous?.via ?: "manual-match",
            )
        }
    }

    fun previouslyMappedCandidate(candidates: List<CatalogGame>, extraMappings: Collection<AppMapping?> = emptyList()): CatalogGame? {
        if (candidates.isEmpty()) return null
        val knownMappings = liveMappings().values + extraMappings.filterNotNull()
        val mappedThreadIds = knownMappings.mapNotNull { m ->
            m.threadId ?: F95UrlParser.extractThreadId(m.f95Url)
        }.toSet()
        val mappedUrls = knownMappings.mapNotNull { it.f95Url?.trim()?.takeIf { url -> url.isNotBlank() } }.toSet()
        return candidates.firstOrNull { game ->
            game.f95ThreadIdOrNull?.let { it in mappedThreadIds } == true ||
                game.canonicalUrl in mappedUrls
        }
    }

    fun previouslyMappedByLocalIdentity(
        row: AppRow,
        byId: Map<Int, CatalogGame>,
        extraMappings: Collection<AppMapping?> = emptyList(),
    ): CatalogGame? {
        val tokens = localIdentityTokens(row.installed).toSet()
        if (tokens.isEmpty()) return null
        val knownMappings = (liveMappings().values + extraMappings.filterNotNull()).filter { !it.f95Url.isNullOrBlank() }
        val matched = knownMappings.firstOrNull { mapping ->
            mapping.manualLocalIdentity.any { it in tokens }
        } ?: knownMappings.firstOrNull { mapping ->
            if (mapping.manualLocalIdentity.isNotEmpty()) return@firstOrNull false
            val legacyToken = CatalogRepository.normalizeTitle(mapping.packageName.removePrefix("joiplay:"))
            legacyToken.length >= 4 && tokens.any { token ->
                token == legacyToken ||
                    token.contains(legacyToken) ||
                    legacyToken.contains(token) ||
                    token.take(10) == legacyToken.take(10)
            }
        } ?: return null
        val tid = matched.threadId ?: F95UrlParser.extractThreadId(matched.f95Url)
        val game = tid?.let { byId[it] } ?: matched.toCatalogSnapshot() ?: return null
        val updated = matched.takeIf { it.manualLocalIdentity.isEmpty() }
            ?.copy(manualLocalIdentity = tokens.toList())
        if (updated != null) {
            rememberMapping(updated)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { repo.upsert(updated) }
            }
        }
        AppLog.i(
            "CatalogRefresh",
            "ALREADY_MATCHED local-identity ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))} " +
                "-> tid=${game.thread_id} title='${game.title}' tokens=${tokens.intersect(matched.manualLocalIdentity.toSet()).ifEmpty { tokens }}"
        )
        return game
    }

    // Reusable catalog refresh — auto-matches a row set against the loaded catalog.
    // Returns Pair(matched, unmatched). Caller is responsible for snackbar messaging.
    // Updates [refreshProgress] for the foreground dialog; honors [refreshCancelled].
    suspend fun refreshFromCatalog(targetRows: List<AppRow>): Pair<Int, Int> {
        refreshCancelled = false
        refreshProgress = RefreshProgress(0, targetRows.size, 0, System.currentTimeMillis())
        return try {
            AppLog.i("CatalogRefresh", "Started for ${targetRows.size} rows")
            val byId = catalog.gamesById()
            val byTitle = catalog.gamesByNormalizedTitle()
            AppLog.i("CatalogRefresh", "Loaded catalog: byId=${byId.size}, byTitle=${byTitle.size}")
            var matched = 0
            var unmatched = 0
            val ambiguous = mutableListOf<AmbiguousCatalogMatch>()
            val alreadyMatched = mutableListOf<AlreadyMatchedCatalogMatch>()
            val startMs = refreshProgress!!.startedAtMs
            for ((idx, row) in targetRows.withIndex()) {
                if (refreshCancelled) {
                    AppLog.i("CatalogRefresh", "Cancelled at ${idx}/${targetRows.size}")
                    break
                }
                val current = liveMappings()[row.installed.packageName] ?: row.mapping
                if (current?.notOnF95 == true) {
                    unmatched++
                    refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                    continue
                }
                val isManualMapping = current?.matchSource?.startsWith("manual") == true
                if (isManualMapping && !overwriteManualMatches) {
                    matched++
                    AppLog.i("CatalogRefresh", "PRESERVE manual ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))}")
                    refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                    continue
                }
                val tidFromMapping = current?.threadId
                val tidFromUrl = F95UrlParser.extractThreadId(current?.f95Url)
                val labels = catalogMatchLabels(row.installed)
                val matchContext = catalogMatchLogContext(row.installed, labels)
                val allowAcronym = allowCatalogAcronymMatch(row.installed, labels)
                val norm = CatalogRepository.normalizeTitle(labels.firstOrNull() ?: row.installed.label)
                if (!current?.f95Url.isNullOrBlank() && !overwriteManualMatches) {
                    val preserved = tidFromMapping?.let { byId[it] } ?: tidFromUrl?.let { byId[it] }
                    matched++
                    AppLog.i(
                        "CatalogRefresh",
                        "PRESERVE mapped $matchContext -> url=${current?.f95Url} " +
                            "catalog=${preserved?.thread_id ?: "missing"} v=${preserved?.version}"
                    )
                    repo.upsert(
                        current!!.copy(
                            lastSeenVersion = preserved?.version ?: current.lastSeenVersion,
                            lastChecked = System.currentTimeMillis(),
                            threadId = current.threadId ?: tidFromUrl,
                        )
                    )
                    refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                    continue
                }
                var via = ""
                var candidate = if (!isManualMapping) tidFromMapping?.let { byId[it] }?.also { via = "tid-mapping" } else null
                if (candidate == null) {
                    candidate = if (!isManualMapping) tidFromUrl?.let { byId[it] }?.also { via = "tid-url" } else null
                }
                if (candidate == null) {
                    val titleMatch = catalog.bestTitleMatch(labels, allowAcronym)
                    candidate = titleMatch?.game
                    via = titleMatch?.via.orEmpty()
                }
                val candidateIsManual = isManualMapping
                if (candidate != null && !candidateIsManual) {
                    val c = candidate!!
                    val trusted = labels.any { CatalogRepository.likelySameTitle(it, c.title, allowAcronym) }
                    if (!trusted) {
                        AppLog.w(
                            "CatalogRefresh",
                            "DROP stale mapping $matchContext -> tid=${c.thread_id} title='${c.title}' " +
                                "catalogNorm='${CatalogRepository.normalizeTitle(c.title)}' via=$via"
                        )
                        repo.remove(row.installed.packageName)
                        candidate = null
                        via = ""
                    }
                }
                if (candidate == null) {
                    val titleMatch = catalog.bestTitleMatch(labels, allowAcronym)
                    candidate = titleMatch?.game
                    via = titleMatch?.via.orEmpty()
                }
                if (candidate != null) {
                    matched++
                    AppLog.i(
                        "CatalogRefresh",
                        "MATCH $matchContext -> tid=${candidate.thread_id} title='${candidate.title}' " +
                            "catalogNorm='${CatalogRepository.normalizeTitle(candidate.title)}' via=$via v=${candidate.version}"
                    )
                    repo.upsert(
                        AppMapping(
                            packageName = row.installed.packageName,
                            f95Url = candidate.canonicalUrl,
                            lastSeenVersion = candidate.version,
                            lastChecked = System.currentTimeMillis(),
                            acknowledgedVersion = current?.acknowledgedVersion,
                            threadId = candidate.f95ThreadIdOrNull,
                            matchSource = "catalog-auto:$via",
                        ).withPersonalFieldsFrom(current).withCatalogSnapshot(candidate)
                    )
                } else {
                    val ambiguousMatch = catalog.ambiguousTitleMatch(labels, allowAcronym)
                    if (ambiguousMatch != null) {
                        val item = AmbiguousCatalogMatch(
                            row = row,
                            candidates = ambiguousMatch.candidates.take(12),
                            via = ambiguousMatch.via,
                        )
                        val prior = previouslyMappedCandidate(item.candidates, targetRows.map { it.mapping })
                            ?: previouslyMappedByLocalIdentity(row, byId, targetRows.map { it.mapping })
                        if (prior != null) alreadyMatched += AlreadyMatchedCatalogMatch(item, prior)
                        else ambiguous += item
                        AppLog.i(
                            "CatalogRefresh",
                            "AMBIGUOUS $matchContext via=${ambiguousMatch.via}: " +
                                ambiguousMatch.candidates.take(12).joinToString { "${it.thread_id} '${it.title}'" }
                        )
                    } else {
                        val item = AmbiguousCatalogMatch(
                            row = row,
                            candidates = emptyList(),
                            via = "none",
                        )
                        val prior = previouslyMappedByLocalIdentity(row, byId, targetRows.map { it.mapping })
                        if (prior != null) alreadyMatched += AlreadyMatchedCatalogMatch(item, prior)
                        else ambiguous += item
                    }
                    unmatched++
                    AppLog.i("CatalogRefresh", "NO MATCH $matchContext primaryNorm='$norm'")
                }
                refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
            }
            ambiguousCatalogMatches = emptyList()
            unmappedReviewMatches = ambiguous
            alreadyMatchedReviewMatches = alreadyMatched
            unmatchedFoundPromptOpen = (ambiguous.isNotEmpty() || alreadyMatched.isNotEmpty()) && !refreshCancelled
            AppLog.i("CatalogRefresh", "Done: matched=$matched unmatched=$unmatched ambiguous=${ambiguous.size} alreadyMatched=${alreadyMatched.size} cancelled=$refreshCancelled")
            matched to unmatched
        } catch (t: Throwable) {
            AppLog.e("CatalogRefresh", "Failed", t)
            CrashReporter.logCaught(context.applicationContext, "refresh_from_catalog", t)
            0 to targetRows.size
        } finally {
            val wasCancelled = refreshCancelled
            refreshProgress = null
            if (wasCancelled) refreshCancelledNote = true
        }
    }

    var joiplayImportBusy by remember { mutableStateOf(false) }
    var joiplayImportError by remember { mutableStateOf<String?>(null) }
    // After the APK installer returns, rescan + match new packages against catalog.
    // Counter-based so successive installs each re-trigger.
    LaunchedEffect(pendingPostInstallRefresh) {
        val v = pendingPostInstallRefresh ?: return@LaunchedEffect
        if (v == 0) return@LaunchedEffect
        runCatching {
            val androidApps = InstalledAppsScanner.scan(context)
            val joiplayBackup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
            val joiplayFolder = JoiPlayScanner.scan(context.applicationContext)
            val joiplay = mergeJoiPlaySources(backup = joiplayBackup, folder = joiplayFolder)
            apps = (androidApps + joiplay).sortedBy { it.label.lowercase() }
            val freshRows = apps.map { a ->
                AppRow(a, mappings[a.packageName], UpdateStatus.Unknown)
            }
            refreshFromCatalog(freshRows)
        }.onFailure { AppLog.w("ApkInstall", "Post-install scan/match failed", it) }
    }
    // First-run state machine (declared here so the JoiPlay picker can reference it):
    //   isFirstRunFlow=true while the user is in the welcome -> JoiPlay-import sequence.
    //   firstRunRefreshPending fires the full catalog refresh once the sequence ends.
    var isFirstRunFlow by remember { mutableStateOf(false) }
    var firstRunRefreshPending by remember { mutableStateOf(false) }
    var firstRunHintOpen by remember { mutableStateOf(false) }
    var f95MigrationPromptOpen by remember { mutableStateOf(false) }
    var f95MigrationError by remember { mutableStateOf<String?>(null) }
    var joiplayBackupPickerFirstRun by remember { mutableStateOf(false) }
    var joiplayBackupAccessDisclosureFirstRun by remember { mutableStateOf<Boolean?>(null) }

    fun askForJoiPlayBackupFolderAccess(firstRun: Boolean) {
        val existingRoot = backupScopedRootUri ?: importBackupScopedRootUri
        if (existingRoot != null) {
            backupScopedRootUri = existingRoot
            importBackupScopedRootUri = existingRoot
            joiplayBackupPickerFirstRun = firstRun
            backupScopedPickerOpen = true
        } else if (hasAllFilesAccess()) {
            joiplayBackupPickerFirstRun = firstRun
            joiplayBackupFilePickerOpen = true
        } else {
            joiplayBackupAccessDisclosureFirstRun = firstRun
        }
    }

    suspend fun setManualInstalledVersion(row: AppRow, version: String) {
        val cleaned = version.trim()
        if (cleaned.isBlank()) return
        val existing = repo.get()[row.installed.packageName]
        val updated = (existing ?: AppMapping(packageName = row.installed.packageName)).copy(
            manualInstalledVersion = cleaned,
            manualInstalledVersionFingerprint = installedVersionFingerprint(row.installed),
        )
        rememberMapping(updated)
        repo.upsert(updated)
        snackbarMsg = "Set installed version $cleaned for ${row.installed.label}"
    }

    suspend fun setManualInstalledDate(row: AppRow, dateMs: Long, source: String) {
        if (dateMs <= 0L) return
        val existing = repo.get()[row.installed.packageName]
        val updated = (existing ?: AppMapping(packageName = row.installed.packageName)).copy(
            manualInstalledDate = dateMs,
            manualInstalledDateFingerprint = installedVersionFingerprint(row.installed),
            manualInstalledDateSource = source,
        )
        rememberMapping(updated)
        repo.upsert(updated)
        snackbarMsg = "Set installed date ${fmtDate(dateMs)} for ${row.installed.label}"
    }

    fun startMappingBackupImport(uri: Uri) {
        scope.launch {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().use { it.readText() }
                } ?: error("cannot open backup file")
                repo.importJson(text, replace = false)
            }.onSuccess { s ->
                val parts = mutableListOf<String>()
                if (s.mappings > 0) parts.add("${s.mappings} mappings")
                if (s.hidden > 0) parts.add("${s.hidden} hidden")
                if (s.joiplayGames > 0) parts.add("${s.joiplayGames} JoiPlay games")
                if (s.joiplayOverrides > 0) parts.add("${s.joiplayOverrides} version overrides")
                snackbarMsg = if (parts.isEmpty()) "Nothing imported" else "Imported " + parts.joinToString(", ")
                if (s.joiplayGames > 0) {
                    val android = InstalledAppsScanner.scan(context)
                    val backup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                    val folder = JoiPlayScanner.scan(context.applicationContext)
                    apps = (android + mergeJoiPlaySources(backup, folder)).sortedBy { it.label.lowercase() }
                }
                catalogReloadTick++
            }.onFailure {
                snackbarMsg = "Import failed: ${it.message}"
            }
        }
    }

    fun startJoiPlayBackupImport(uri: Uri, firstRun: Boolean) {
        joiplayImportBusy = true
        scope.launch {
            runCatching { JoiPlayBackupReader.import(context.applicationContext, uri) }
                .onSuccess { count ->
                    AppLog.i("JoiPlay", "Imported backup, $count games")
                    val android = InstalledAppsScanner.scan(context)
                    val joiplayBackup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                    val joiplayFolder = JoiPlayScanner.scan(context.applicationContext)
                    val joiplay = mergeJoiPlaySources(backup = joiplayBackup, folder = joiplayFolder)
                    apps = (android + joiplay).sortedBy { it.label.lowercase() }
                    joiplayImportBusy = false

                    if (firstRun) {
                        firstRunRefreshPending = true
                        snackbarMsg = "Imported $count JoiPlay games; matching all apps to catalog…"
                    } else {
                        val joiplayPackages = joiplay.map { it.packageName }.toSet()
                        val joiplayRows = (android + joiplay).filter { it.packageName in joiplayPackages }
                            .map { app ->
                                val m = liveMappings()[app.packageName] ?: repo.get()[app.packageName]
                                AppRow(app, m, UpdateStatus.Unknown)
                            }
                        snackbarMsg = "Imported $count games; matching to catalog…"
                        val (matched, _) = refreshFromCatalog(joiplayRows)
                        snackbarMsg = "Imported $count JoiPlay games, $matched matched to catalog"
                    }
                }
                .onFailure { t ->
                    AppLog.e("JoiPlay", "Backup import failed", t)
                    joiplayImportBusy = false
                    val msg = t.message ?: "unknown error"
                    if (firstRun) {
                        joiplayImportError = msg
                        firstRunHintOpen = true
                    } else {
                        snackbarMsg = "JoiPlay import failed: $msg"
                    }
                }
        }
    }

    val joiplayBackupFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val firstRun = joiplayBackupPickerFirstRun
        if (uri == null) {
            joiplayBackupPickerFirstRun = false
            if (firstRun) firstRunHintOpen = true
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { AppLog.w("JoiPlay", "Could not persist backup folder permission", it) }
        backupScopedRootUri = uri
        importBackupScopedRootUri = uri
        scope.launch { JoiPlaySettingsStore.setBackupFolderUri(context.applicationContext, uri.toString()) }
        backupScopedPickerOpen = true
    }

    val mappingBackupFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { AppLog.w("Backup", "Could not persist backup folder permission", it) }
        backupScopedRootUri = uri
        importBackupScopedRootUri = uri
        scope.launch { JoiPlaySettingsStore.setBackupFolderUri(context.applicationContext, uri.toString()) }
        importBackupPickerOpen = true
    }

    // Declare delete-flow state early so the SAF picker (below) can reference it.
    var joiPlayDeleteConfirm by remember { mutableStateOf<AppRow?>(null) }
    var joiPlayGrantAskFor by remember { mutableStateOf<AppRow?>(null) }

    val joiplayPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                JoiPlayScanner.setRootUri(context.applicationContext, uri)
                AppLog.i("JoiPlay", "Set folder uri=$uri")
                if (pendingJoiPlaySizeScanAfterGrant) {
                    pendingJoiPlaySizeScanAfterGrant = false
                    joiPlaySizeScanForce = true
                    joiPlaySizeScanRequest++
                }
                // If user was in the middle of trying to delete a JoiPlay game, continue that flow.
                val pendingDelete = joiPlayGrantAskFor
                joiPlayGrantAskFor = null
                if (pendingDelete != null) {
                    joiPlayDeleteConfirm = pendingDelete
                }
            }
        } else {
            joiPlayGrantAskFor = null
        }
    }

    fun requestJoiPlaySizeScan(force: Boolean) {
        if (joiPlaySizeScanning) {
            snackbarMsg = "JoiPlay storage scan already running"
            return
        }

        scope.launch {
            val root = JoiPlayScanner.getRootUri(context.applicationContext)
            if (root == null || !hasPersistedReadPermission(context, root)) {
                pendingJoiPlaySizeScanAfterGrant = true
                joiPlayGrantAskFor = null
                snackbarMsg = "Choose your JoiPlay games folder to scan storage sizes"
                joiplayPicker.launch(null)
            } else {
                joiPlaySizeScanForce = force
                joiPlaySizeScanRequest++
            }
        }
    }

    fun requestRenPySaveScan() {
        if (renPySaveScanning) return
        if (!hasAllFilesAccess()) {
            permissionRationale = PermissionRationale.AllFilesRenPySaves
            return
        }
        renPySaveScanManualRequest = true
        renPySaveScanRequest++
    }

    fun updateRenPySaveLocations(locations: List<RenPySaveLocation>) {
        val sorted = locations.sortedWith(
            compareByDescending<RenPySaveLocation> { it.associatedPackageName != null }
                .thenByDescending { it.confidence }
                .thenBy { it.saveDirPath.lowercase() }
        )
        renPySaveLocations = sorted
        renPySaveAssociations = RenPySaveScanner.associationsByPackage(sorted)
    }

    fun persistRenPySaveState() {
        scope.launch {
            RenPySaveScanner.saveSnapshot(context.applicationContext, renPySaveLocations, renPySaveLastScannedAt)
            RenPySaveScanner.saveManualAssociations(context.applicationContext, renPyManualAssociations)
        }
    }

    fun manuallyAssociateRenPyLocation(location: RenPySaveLocation, app: InstalledApp) {
        val manual = RenPySaveManualAssociation(
            saveDirPath = location.saveDirPath,
            packageName = app.packageName,
            label = app.label,
        )
        renPyManualAssociations = renPyManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
        updateRenPySaveLocations(
            renPySaveLocations.map {
                if (it.saveDirPath == location.saveDirPath) {
                    it.copy(
                        associatedPackageName = app.packageName,
                        associatedLabel = app.label,
                        confidence = 100,
                        reason = "Manually associated",
                    )
                } else it
            }
        )
        persistRenPySaveState()
        renPySaveAssociationPicker = null
        snackbarMsg = "Associated saves with ${app.label}"
    }

    fun addRenPySaveFolderToGame(row: AppRow, folderPath: String) {
        scope.launch {
            val location = RenPySaveScanner.verifiedLocationForFolder(folderPath, row.installed)
            if (location == null) {
                snackbarMsg = "No verified Ren'Py .save files found in that folder"
                return@launch
            }
            val manual = RenPySaveManualAssociation(
                saveDirPath = location.saveDirPath,
                packageName = row.installed.packageName,
                label = row.installed.label,
            )
            renPyManualAssociations = renPyManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
            updateRenPySaveLocations(
                (renPySaveLocations.filterNot { it.saveDirPath == location.saveDirPath } + location)
            )
            persistRenPySaveState()
            snackbarMsg = "Added Ren'Py saves to ${row.installed.label}"
        }
    }

    fun updateRpgmSaveLocations(locations: List<RpgmSaveLocation>) {
        val sorted = locations.sortedWith(
            compareByDescending<RpgmSaveLocation> { it.associatedPackageName != null }
                .thenByDescending { it.confidence }
                .thenBy { it.saveDirPath.lowercase() }
        )
        rpgmSaveLocations = sorted
        rpgmSaveAssociations = RpgmSaveScanner.associationsByPackage(sorted)
    }

    fun persistRpgmSaveState() {
        scope.launch {
            RpgmSaveScanner.saveSnapshot(context.applicationContext, rpgmSaveLocations, rpgmSaveLastScannedAt)
            RpgmSaveScanner.saveManualAssociations(context.applicationContext, rpgmManualAssociations)
        }
    }

    fun requestRpgmSaveScan() {
        if (rpgmSaveScanning) return
        if (!hasAllFilesAccess()) {
            permissionRationale = PermissionRationale.AllFilesRenPySaves
            return
        }
        rpgmSaveScanManualRequest = true
        rpgmSaveScanRequest++
    }

    fun addRpgmSaveFolderToGame(row: AppRow, folderPath: String) {
        scope.launch {
            val location = RpgmSaveScanner.verifiedLocationForFolder(folderPath, row.installed)
            if (location == null) {
                snackbarMsg = "No verified RPGM saves found in that folder"
                return@launch
            }
            val manual = RpgmSaveManualAssociation(
                saveDirPath = location.saveDirPath,
                packageName = row.installed.packageName,
                label = row.installed.label,
            )
            rpgmManualAssociations = rpgmManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
            updateRpgmSaveLocations(rpgmSaveLocations.filterNot { it.saveDirPath == location.saveDirPath } + location)
            persistRpgmSaveState()
            snackbarMsg = "Added RPGM saves to ${row.installed.label}"
        }
    }

    fun manuallyAssociateRpgmLocation(location: RpgmSaveLocation, app: InstalledApp) {
        val manual = RpgmSaveManualAssociation(
            saveDirPath = location.saveDirPath,
            packageName = app.packageName,
            label = app.label,
        )
        rpgmManualAssociations = rpgmManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
        updateRpgmSaveLocations(
            rpgmSaveLocations.map {
                if (it.saveDirPath == location.saveDirPath) {
                    it.copy(
                        associatedPackageName = app.packageName,
                        associatedLabel = app.label,
                        confidence = 100,
                        reason = "Manually associated",
                    )
                } else it
            }
        )
        persistRpgmSaveState()
        rpgmSaveAssociationPicker = null
        snackbarMsg = "Associated RPGM saves with ${app.label}"
    }

    fun clearRpgmManualAssociation(location: RpgmSaveLocation) {
        val key = location.saveDirPath.replace('\\', '/')
        rpgmManualAssociations = rpgmManualAssociations - key
        updateRpgmSaveLocations(
            rpgmSaveLocations.map {
                if (it.saveDirPath == location.saveDirPath && it.reason == "Manually associated") {
                    it.copy(
                        associatedPackageName = null,
                        associatedLabel = null,
                        confidence = 0,
                        reason = null,
                    )
                } else it
            }
        )
        persistRpgmSaveState()
        snackbarMsg = "Cleared manual RPGM save association"
    }

    fun clearRenPyManualAssociation(location: RenPySaveLocation) {
        val key = location.saveDirPath.replace('\\', '/')
        renPyManualAssociations = renPyManualAssociations - key
        updateRenPySaveLocations(
            renPySaveLocations.map {
                if (it.saveDirPath == location.saveDirPath && it.reason == "Manually associated") {
                    it.copy(
                        associatedPackageName = null,
                        associatedLabel = null,
                        confidence = 0,
                        reason = null,
                    )
                } else it
            }
        )
        persistRenPySaveState()
        snackbarMsg = "Cleared manual save association"
    }

    LaunchedEffect(renPySaveScanRequest, apps) {
        if (renPySaveScanRequest == 0 || renPySaveScanning || apps.isEmpty()) return@LaunchedEffect
        val manualRequest = renPySaveScanManualRequest
        renPySaveScanManualRequest = false
        if (!manualRequest && !hasAllFilesAccess()) {
            AppLog.i("RenPySaves", "Skipping automatic save scan because All files access is not granted")
            return@LaunchedEffect
        }
        renPySaveScanning = true
        try {
            val manual = RenPySaveScanner.loadManualAssociations(context.applicationContext)
            renPyManualAssociations = manual
            val result = RenPySaveScanner.scan(apps)
            val locations = RenPySaveScanner.applyManualAssociations(result.locations, manual, apps)
            renPySaveAssociations = RenPySaveScanner.associationsByPackage(locations)
            renPySaveLocations = locations
            renPySaveLastScannedAt = result.lastScannedAt
            RenPySaveScanner.saveSnapshot(context.applicationContext, locations, result.lastScannedAt)
            val gameCount = renPySaveAssociations.size
            val folderCount = renPySaveLocations.size
            val unmatched = renPySaveLocations.count { it.associatedPackageName == null }
            if (manualRequest) {
                snackbarMsg = "Ren'Py saves: $folderCount folder${if (folderCount == 1) "" else "s"}, $gameCount associated game${if (gameCount == 1) "" else "s"}, $unmatched unmatched"
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (manualRequest) {
                AppLog.e("RenPySaves", "Manual scan failed", t)
                CrashReporter.logCaught(context.applicationContext, "renpy_save_scan", t)
                snackbarMsg = "Ren'Py save scan failed: ${t.message}"
            } else {
                AppLog.w("RenPySaves", "Automatic scan failed (not shown to user): ${t.message}", t)
            }
        } finally {
            renPySaveScanning = false
        }
    }

    LaunchedEffect(rpgmSaveScanRequest, apps) {
        if (rpgmSaveScanRequest == 0 || rpgmSaveScanning || apps.isEmpty()) return@LaunchedEffect
        val manualRequest = rpgmSaveScanManualRequest
        rpgmSaveScanManualRequest = false
        if (!manualRequest && !hasAllFilesAccess()) {
            AppLog.i("RpgmSaves", "Skipping automatic save scan because All files access is not granted")
            return@LaunchedEffect
        }
        rpgmSaveScanning = true
        try {
            val manual = RpgmSaveScanner.loadManualAssociations(context.applicationContext)
            rpgmManualAssociations = manual
            val result = RpgmSaveScanner.scan(apps)
            val locations = RpgmSaveScanner.applyManualAssociations(result.locations, manual, apps)
            rpgmSaveAssociations = RpgmSaveScanner.associationsByPackage(locations)
            rpgmSaveLocations = locations
            rpgmSaveLastScannedAt = result.lastScannedAt
            RpgmSaveScanner.saveSnapshot(context.applicationContext, locations, result.lastScannedAt)
            if (manualRequest) {
                val gameCount = rpgmSaveAssociations.size
                val unmatched = rpgmSaveLocations.count { it.associatedPackageName == null }
                snackbarMsg = "RPGM saves: ${rpgmSaveLocations.size} folder${if (rpgmSaveLocations.size == 1) "" else "s"}, $gameCount associated game${if (gameCount == 1) "" else "s"}, $unmatched unmatched"
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (manualRequest) {
                AppLog.e("RpgmSaves", "Manual scan failed", t)
                snackbarMsg = "RPGM save scan failed: ${t.message}"
            } else {
                AppLog.w("RpgmSaves", "Automatic scan failed (not shown to user): ${t.message}", t)
            }
        } finally {
            rpgmSaveScanning = false
        }
    }

    LaunchedEffect(Unit) {
        joiPlaySizeInfo = JoiPlayScanner.loadSizeInfo(context.applicationContext)
        renPyManualAssociations = RenPySaveScanner.loadManualAssociations(context.applicationContext)
        val snapshot = RenPySaveScanner.loadSnapshot(context.applicationContext)
        renPySaveLocations = snapshot.locations
        renPySaveAssociations = RenPySaveScanner.associationsByPackage(snapshot.locations)
        renPySaveLastScannedAt = snapshot.lastScannedAt
        rpgmManualAssociations = RpgmSaveScanner.loadManualAssociations(context.applicationContext)
        val rpgmSnapshot = RpgmSaveScanner.loadSnapshot(context.applicationContext)
        rpgmSaveLocations = rpgmSnapshot.locations
        rpgmSaveAssociations = RpgmSaveScanner.associationsByPackage(rpgmSnapshot.locations)
        rpgmSaveLastScannedAt = rpgmSnapshot.lastScannedAt
    }

    LaunchedEffect(apps) {
        if (!autoJoiPlaySizeScanStarted && apps.any { it.source == AppSource.JoiPlay }) {
            autoJoiPlaySizeScanStarted = true
            val root = JoiPlayScanner.getRootUri(context.applicationContext)
            if (root != null && hasPersistedReadPermission(context, root)) {
                joiPlaySizeScanForce = false
                joiPlaySizeScanRequest++
            }
        }
    }

    LaunchedEffect(joiPlaySizeScanRequest) {
        if (joiPlaySizeScanRequest == 0 || joiPlaySizeScanning) return@LaunchedEffect
        val force = joiPlaySizeScanForce
        val targets = apps.asSequence()
            .filter { it.source == AppSource.JoiPlay }
            .mapNotNull { joiPlaySizeTarget(it) }
            .distinctBy { it.key }
            .toList()
        if (targets.isEmpty()) return@LaunchedEffect
        val targetKeys = targets.map { it.key }.toSet()
        val before = JoiPlayScanner.loadSizeInfo(context.applicationContext)
        joiPlaySizeInfo = before
        val toScan = if (force) targetKeys else targetKeys.filter { before[it]?.lastScannedAt ?: 0L <= 0L }.toSet()
        if (toScan.isEmpty()) return@LaunchedEffect

        joiPlaySizeScanning = true
        joiPlaySizeScanningFolders = toScan
        joiPlaySizeScanProgress = null
        snackbarMsg = if (force) "Refreshing JoiPlay storage sizes…" else "Scanning JoiPlay storage sizes…"
        AppLog.i("JoiPlaySize", "Starting size scan force=$force folders=${toScan.size}")
        runCatching {
            val updated = JoiPlayScanner.computeTargetSizes(
                context.applicationContext,
                targets = targets.filter { it.key in toScan },
                recompute = force,
            ) { progress ->
                joiPlaySizeScanProgress = progress
            }
            joiPlaySizeInfo = updated
            val android = InstalledAppsScanner.scan(context)
            val backup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
            val folder = JoiPlayScanner.scan(context.applicationContext)
            apps = (android + mergeJoiPlaySources(backup, folder)).sortedBy { it.label.lowercase() }
            val scannedCount = toScan.count { updated[it]?.lastScannedAt ?: 0L > 0L }
            snackbarMsg = "JoiPlay storage scan complete ($scannedCount games)"
            AppLog.i("JoiPlaySize", "Size scan complete scanned=$scannedCount totalCache=${updated.size}")
        }.onFailure { t ->
            AppLog.e("JoiPlaySize", "Size scan failed", t)
            CrashReporter.logCaught(context.applicationContext, "joiplay_size_scan", t)
            snackbarMsg = "JoiPlay storage scan failed: ${t.message}"
        }
        joiPlaySizeScanning = false
        joiPlaySizeScanningFolders = emptySet()
        joiPlaySizeScanProgress = null
    }

    var sourceFilter by remember { mutableStateOf(initialFilters.sourceFilter) }
    var manualOnlyFilter by remember { mutableStateOf(initialFilters.manualOnly) }
    val androidOnlySizeSorts = setOf(SortKey.AppSize, SortKey.DataSize, SortKey.CacheSize)
    val availableSortKeys = remember(sourceFilter) {
        SortKey.values().filter { key -> sourceFilter == AppSource.Android || key !in androidOnlySizeSorts }
    }
    LaunchedEffect(sourceFilter, sortKey) {
        if (sortKey in androidOnlySizeSorts && sourceFilter != AppSource.Android) {
            sortKey = SortKey.Size
            sortDesc = true
        }
    }

    // Persist filter state whenever any of these change.
    LaunchedEffect(sortKey, sortDesc, nameFilter, sourceFilter, showHidden,
        manualOnlyFilter, threadUpdatedAfterInstallFilter, libraryLayoutMode, activeFilters.toList()) {
        FilterPrefs.save(
            context.applicationContext,
            FilterPrefs.State(
                sortKey = sortKey,
                sortDesc = sortDesc,
                nameFilter = nameFilter,
                sourceFilter = sourceFilter,
                activeStatuses = activeFilters.toList(),
                activeTags = parseTagFilters(nameFilter),
                showHidden = showHidden,
                manualOnly = manualOnlyFilter,
                threadUpdatedAfterInstallOnly = threadUpdatedAfterInstallFilter,
                layoutMode = libraryLayoutMode,
            )
        )
    }

    val visibleApps: List<InstalledApp> = remember(apps, hidden, showHidden) {
        if (showHidden) apps.filter { it.packageName in hidden }
        else apps.filter { it.packageName !in hidden }
    }

    val rows: List<AppRow> = remember(visibleApps, mappings, sortKey, sortDesc,
        activeFilters.toList(), nameFilter, sourceFilter, manualOnlyFilter, hasSavesOnlyFilter,
        threadUpdatedAfterInstallFilter,
        renPySaveAssociations, rpgmSaveAssociations, catalogById, catalogLabels, joiPlaySizeInfo) {
        val bySource = if (sourceFilter == null) visibleApps else visibleApps.filter { it.source == sourceFilter }

        // Parse "tag:xxx" and free-text from the search box.
        val parsed = parseSearchQuery(nameFilter)
        val byQuery = bySource.filter { app ->
            val matchesText = parsed.freeText.isBlank() ||
                app.label.contains(parsed.freeText, ignoreCase = true) ||
                app.packageName.contains(parsed.freeText, ignoreCase = true)
            if (!matchesText) return@filter false

            if (parsed.tags.isEmpty()) return@filter true
            val mapping = mappings[app.packageName]
            val tid = mapping?.threadId ?: F95UrlParser.extractThreadId(mapping?.f95Url)
            val game = tid?.let { catalogById?.get(it) }
            val tagNames = if (game != null && catalogLabels != null) {
                val sl = catalogLabels.forSource(game.source)
                game.tags.mapNotNull { sl?.tags?.get(it.toString())?.lowercase() } +
                    game.prefixes.mapNotNull { sl?.prefixes?.get(it.toString())?.lowercase() }
            } else emptyList()
            parsed.tags.all { tq -> tagNames.any { it.contains(tq) } }
        }

        val joined = byQuery.map { app ->
            val m = mappings[app.packageName]
            val status = when {
                m == null || m.f95Url.isNullOrBlank() -> UpdateStatus.NotMapped
                m.lastSeenVersion == null -> UpdateStatus.Unknown
                m.acknowledgedVersion != null && m.acknowledgedVersion == m.lastSeenVersion -> UpdateStatus.UpToDate
                VersionCompare.matchesInstalled(m.lastSeenVersion, effectiveInstalledVersion(app, m)) -> UpdateStatus.UpToDate
                else -> UpdateStatus.UpdateAvailable
            }
            AppRow(app, m, status)
        }
        val filteredByStatus = if (activeFilters.isEmpty()) joined
            else joined.filter { it.status in activeFilters }
        val filteredByManual = if (!manualOnlyFilter) filteredByStatus
            else filteredByStatus.filter { it.mapping?.matchSource?.startsWith("manual") == true }
        val filteredByThreadUpdated = if (!threadUpdatedAfterInstallFilter) filteredByManual
            else filteredByManual.filter { threadUpdatedAfterInstall(it, catalogById) }
        val filtered = if (!hasSavesOnlyFilter) filteredByThreadUpdated
            else filteredByThreadUpdated.filter {
                renPySaveAssociations[it.installed.packageName].orEmpty().isNotEmpty() ||
                    rpgmSaveAssociations[it.installed.packageName].orEmpty().isNotEmpty()
            }
        val sorted = when (sortKey) {
            SortKey.Name -> filtered.sortedBy { it.installed.label.lowercase() }
            SortKey.Installed -> filtered.sortedBy { effectiveInstalledDate(it.installed, it.mapping) }
            SortKey.LastUsed -> filtered.sortedBy { it.installed.lastUsedTime }
            SortKey.ThreadUpdated -> filtered.sortedBy { row ->
                mappedCatalogGame(row.mapping, catalogById)?.ts ?: 0L
            }
            SortKey.Size -> filtered.sortedBy { row ->
                effectiveInstalledSize(row.installed, joiPlaySizeKey(row.installed)?.let { joiPlaySizeInfo[it] })
            }
            SortKey.AppSize -> filtered.sortedBy { it.installed.apkSize }
            SortKey.DataSize -> filtered.sortedBy { it.installed.dataSize }
            SortKey.CacheSize -> filtered.sortedBy { it.installed.cacheSize }
            SortKey.Status -> filtered.sortedWith(
                compareBy<AppRow> { statusOrder(it.status) }.thenBy { it.installed.label.lowercase() }
            )
        }
        if (sortDesc) sorted.reversed() else sorted
    }

    val joiPlayApps = remember(apps) { apps.filter { it.source == AppSource.JoiPlay } }
    val joiPlayTotalSize = remember(joiPlayApps, joiPlaySizeInfo) {
        effectiveInstalledTotalSize(joiPlayApps, joiPlaySizeInfo)
    }
    val joiPlayLastSizeScan = remember(joiPlayApps, joiPlaySizeInfo) {
        joiPlayApps.mapNotNull { app -> joiPlaySizeKey(app)?.let { joiPlaySizeInfo[it]?.lastScannedAt } }
            .filter { it > 0L }
            .maxOrNull() ?: 0L
    }
    val joiPlayStorageSummary = remember(joiPlayApps, joiPlayTotalSize, joiPlaySizeScanning, joiPlayLastSizeScan) {
        if (joiPlayApps.isEmpty()) null
        else buildString {
            append("JoiPlay ${joiPlayApps.size}, ${fmtSize(joiPlayTotalSize)}")
            if (joiPlaySizeScanning) append(" • scanning")
            else if (joiPlayLastSizeScan > 0L) append(" • scanned ${fmtDate(joiPlayLastSizeScan)}")
        }
    }
    val renPySaveSummary = remember(renPySaveLocations, renPySaveAssociations, renPySaveScanning, renPySaveLastScannedAt) {
        val folderCount = renPySaveLocations.size
        if (renPySaveScanning) "Ren'Py saves scanning"
        else if (folderCount > 0) buildString {
            append("Ren'Py saves $folderCount / ${renPySaveAssociations.size} games")
            if (renPySaveLastScannedAt > 0L) append(" • scanned ${fmtDate(renPySaveLastScannedAt)}")
        }
        else null
    }
    val rpgmSaveSummary = remember(rpgmSaveLocations, rpgmSaveAssociations, rpgmSaveScanning, rpgmSaveLastScannedAt) {
        val folderCount = rpgmSaveLocations.size
        if (rpgmSaveScanning) "RPGM saves scanning"
        else if (folderCount > 0) buildString {
            append("RPGM saves $folderCount / ${rpgmSaveAssociations.size} games")
            if (rpgmSaveLastScannedAt > 0L) append(" • scanned ${fmtDate(rpgmSaveLastScannedAt)}")
        }
        else null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { snackbarHostState.showSnackbar(it); snackbarMsg = null }
    }

    var dialogApp by remember { mutableStateOf<AppRow?>(null) }
    var returnToUnmappedAfterDialog by remember { mutableStateOf(false) }
    var fullSizeImageUrl by remember { mutableStateOf<String?>(null) }
    var joiPlayDetecting by remember { mutableStateOf<AppRow?>(null) }
    var joiPlayVersionDialog by remember {
        mutableStateOf<Triple<AppRow, List<VersionCandidate>, (() -> Unit)>?>(null)
    }
    var manualVersionTarget by remember { mutableStateOf<AppRow?>(null) }
    var manualDateTarget by remember { mutableStateOf<Pair<AppRow, CatalogGame?>?>(null) }
    var aboutOpen by remember { mutableStateOf(false) }
    var joiPlayInstalled by remember { mutableStateOf(false) }
    var autoSyncStatus by remember { mutableStateOf<String?>(null) }

    fun closeScreenshotOverlays() {
        sortMenuOpen = false
        menuOpen = false
        subCatalogOpen = false
        subJoiPlayOpen = false
        subSaveToolsOpen = false
        subBackupOpen = false
        subLogsOpen = false
        subThemeOpen = false
        aboutOpen = false
        supportDialogOpen = false
        installWarningOpen = false
        joiplaySettingsOpen = false
        installPickerOpen = false
        apkInstallPickerOpen = false
        backupScopedPickerOpen = false
        importBackupPickerOpen = false
        importBackupAccessDisclosureOpen = false
        exportBackupPickerOpen = false
        autoBackupDialogOpen = false
        apkInstallConfirm = null
        extractConfirm = null
        joiPlayDeleteConfirm = null
        screenshotPanel = null
    }

    fun startScreenshotWalkthrough() {
        if (screenshotWalkthroughRunning) return
        screenshotWalkthroughRunning = true
        snackbarMsg = null
        scope.launch {
            val appContext = context.applicationContext
            suspend fun snap(name: String) {
                kotlinx.coroutines.delay(700)
                ScreenshotDiagnostics.capture(appContext, rootView, name)
            }
            runCatching {
                ScreenshotDiagnostics.clear(appContext)
                closeScreenshotOverlays()
                onScreenshotTabChange(Tab.Installed)
                onScreenshotCatalogQuery(null)
                snap("01-installed-main")

                screenshotPanel = ScreenshotPanel.SortMenu
                snap("02-sort-menu")

                screenshotPanel = ScreenshotPanel.MainMenu
                snap("03-main-menu")

                screenshotPanel = ScreenshotPanel.CatalogMenu
                snap("04-catalog-submenu")

                screenshotPanel = ScreenshotPanel.JoiPlayMenu
                snap("05-joiplay-submenu")

                screenshotPanel = ScreenshotPanel.BackupMenu
                snap("06-backup-submenu")

                screenshotPanel = ScreenshotPanel.DiagnosticsMenu
                snap("07-help-diagnostics-submenu")

                screenshotPanel = ScreenshotPanel.About
                snap("08-about-dialog")

                if (appConfig.donationUrl.isNotBlank() || appConfig.stripeDonationUrl.isNotBlank()) {
                    screenshotPanel = ScreenshotPanel.Support
                    snap("09-support-dialog")
                }

                screenshotPanel = ScreenshotPanel.JoiPlaySettings
                snap("10-joiplay-settings")

                screenshotPanel = ScreenshotPanel.JoiPlayWarning
                snap("11-joiplay-install-warning")

                screenshotPanel = ScreenshotPanel.JoiPlayPicker
                snap("12-joiplay-install-picker")

                screenshotPanel = ScreenshotPanel.ApkPicker
                snap("13-apk-install-picker")

                screenshotPanel = ScreenshotPanel.ApkConfirm
                snap("14-apk-install-confirm")

                screenshotPanel = ScreenshotPanel.ExtractConfirm
                snap("15-archive-extract-confirm")

                screenshotPanel = ScreenshotPanel.JoiPlayDelete
                snap("16-joiplay-delete-confirm")

                screenshotPanel = ScreenshotPanel.CatalogMain
                snap("17-catalog-main")

                screenshotPanel = ScreenshotPanel.CatalogTagFilter
                snap("18-catalog-tag-filter")

                screenshotPanel = null

                val count = ScreenshotDiagnostics.files(appContext).size
                snackbarMsg = "Saved $count screenshots to Documents/AdultGameManager/screenshots"
            }.onFailure {
                AppLog.e("Screenshots", "Walkthrough capture failed", it)
                snackbarMsg = "Screenshot capture failed: ${it.message}"
            }
            closeScreenshotOverlays()
            screenshotWalkthroughRunning = false
        }
    }

    fun startLaunchScreenshotSet() {
        if (launchScreenshotRunning) return
        launchScreenshotRunning = true
        snackbarMsg = null
        scope.launch {
            val appContext = context.applicationContext
            suspend fun snap(name: String) {
                kotlinx.coroutines.delay(700)
                ScreenshotDiagnostics.capture(appContext, rootView, name)
            }
            runCatching {
                ScreenshotDiagnostics.clear(appContext)
                closeScreenshotOverlays()

                onScreenshotTabChange(Tab.Installed)
                onScreenshotCatalogQuery(null)
                screenshotPanel = ScreenshotPanel.LaunchLibrary
                snap("agm-01-library")

                onScreenshotTabChange(Tab.Catalog)
                onScreenshotCatalogQuery(null)
                screenshotPanel = ScreenshotPanel.LaunchCatalogFilters
                snap("agm-02-catalog-source-platform-filters")

                screenshotPanel = ScreenshotPanel.LaunchAdvancedFilters
                snap("agm-03-catalog-advanced-filters")

                screenshotPanel = ScreenshotPanel.LaunchGameDetails
                snap("agm-04-game-details")

                onScreenshotTabChange(Tab.Installed)
                screenshotPanel = ScreenshotPanel.LaunchReviewUnmapped
                snap("agm-05-review-unmapped")

                screenshotPanel = ScreenshotPanel.LaunchF95Import
                snap("agm-06-f95-updater-import")

                screenshotPanel = null
                val count = ScreenshotDiagnostics.files(appContext).size
                snackbarMsg = "Saved $count launch screenshots to Documents/AdultGameManager/screenshots"
            }.onFailure {
                AppLog.e("Screenshots", "Launch screenshot capture failed", it)
                snackbarMsg = "Launch screenshots failed: ${it.message}"
            }
            closeScreenshotOverlays()
            launchScreenshotRunning = false
        }
    }

    // Detect JoiPlay package presence (used by first-run hint and other heuristics).
    LaunchedEffect(Unit) {
        joiPlayInstalled = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("cyou.joiplay.joiplay", 0)
            true
        }.getOrDefault(false)
    }

    // First-run hint: shown the first time the app launches.
    LaunchedEffect(Unit) {
        val firstRunMarker = java.io.File(context.applicationContext.filesDir, ".firstrun_done")
        if (!firstRunMarker.exists()) {
            isFirstRunFlow = true
            if (F95MigrationImport.isF95UpdaterInstalled(context.applicationContext)) {
                f95MigrationPromptOpen = true
            } else {
                firstRunHintOpen = true
            }
            runCatching { firstRunMarker.createNewFile() }
        }
    }

    // After the user dismisses the welcome flow (and any subsequent JoiPlay import),
    // run the full catalog refresh once. Watches both `apps` and the pending flag so
    // the JoiPlay-imported games are included if applicable.
    // Builds the row set from `apps` directly (bypasses any user filters / search box).
    LaunchedEffect(firstRunRefreshPending, apps.size) {
        if (firstRunRefreshPending && apps.isNotEmpty()) {
            // Clear flags first, then dispatch the actual work to `scope` so the long-running
            // refresh isn't tied to this LaunchedEffect's key — otherwise setting
            // firstRunRefreshPending=false re-keys this effect and cancels its own coroutine
            // partway through (observed as LeftCompositionCancellationException in refreshFromCatalog).
            firstRunRefreshPending = false
            isFirstRunFlow = false
            val snapshot = apps.toList()
            val mappingsSnapshot = mappings.toMap()
            // Show the progress dialog immediately so there's no UI gap between the
            // JoiPlay-import dialog closing and refreshFromCatalog actually starting
            // (which can include a catalog download on first install).
            refreshCancelled = false
            refreshProgress = RefreshProgress(0, snapshot.size, 0, System.currentTimeMillis())
            scope.launch {
                if (catalog.gamesById().isEmpty()) {
                    AppLog.i("FirstRun", "Catalog not ready yet; syncing before first-run match")
                    when (val r = catalog.sync()) {
                        is CatalogSyncResult.Updated -> {
                            AppLog.i("FirstRun", "Catalog ready: ${r.gameCount} games")
                            onLabelsChange(catalog.labels())
                        }
                        CatalogSyncResult.NotModified -> AppLog.i("FirstRun", "Catalog 304 (cached)")
                        is CatalogSyncResult.Error -> AppLog.w("FirstRun", "Catalog sync failed: ${r.message}")
                    }
                }
                AppLog.i("FirstRun", "Running full catalog refresh on ${snapshot.size} apps")
                val allRows = snapshot.map { app ->
                    val m = mappingsSnapshot[app.packageName]
                    AppRow(app, m, UpdateStatus.Unknown)
                }
                val (matched, _) = refreshFromCatalog(allRows)
                snackbarMsg = "First-run match: $matched matched"
            }
        }
    }

    // Auto-sync on startup: catalog refresh, labels refresh, app-update check.
    // Runs in background so the UI doesn't block. The first-run "refresh all apps from catalog"
    // is deferred until AFTER the welcome dialog flow completes (see firstRunRefreshPending).
    LaunchedEffect(Unit) {
        autoSyncStatus = "Syncing catalog…"
        runCatching {
            when (val r = catalog.sync()) {
                is CatalogSyncResult.Updated -> {
                    AppLog.i("Startup", "Catalog auto-sync: ${r.gameCount} games (${r.sizeBytes} B)")
                    onLabelsChange(catalog.labels())
                    catalogReloadTick = catalogReloadTick + 1
                    Unit
                }
                CatalogSyncResult.NotModified -> {
                    AppLog.i("Startup", "Catalog auto-sync: 304 not modified")
                    if (catalogById.isNullOrEmpty()) {
                        catalogReloadTick = catalogReloadTick + 1
                    }
                    Unit
                }
                is CatalogSyncResult.Error -> AppLog.w("Startup", "Catalog auto-sync failed: ${r.message}")
            }
        }.onFailure { AppLog.w("Startup", "Catalog auto-sync threw", it) }

        autoSyncStatus = "Checking for app update…"
        runCatching {
            val result = appUpdater.check(context)
            if (result is UpdateCheckResult.Available) {
                updateCheckResult = result
                AppLog.i("Startup", "App update available: v${result.info.versionName}")
            } else {
                AppLog.i("Startup", "App update check: $result")
            }
        }.onFailure { AppLog.w("Startup", "App update check threw", it) }
        autoSyncStatus = null
    }

    val topStatusText = checkProgress?.let { (cur, total) -> "Checking $cur / $total" }
        ?: autoSyncStatus
        ?: listOfNotNull(
            "${rows.size} of ${visibleApps.size} apps",
            joiPlayStorageSummary,
            renPySaveSummary,
            rpgmSaveSummary,
        ).joinToString(" • ")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val versionName = remember {
                        runCatching {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: ""
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                showHidden -> "Hidden (${rows.size})"
                                compactWidth || compactHeight -> "AGM v$versionName"
                                else -> "Adult Game Manager v$versionName"
                            },
                            fontSize = if (compactHeight) 20.sp else TextUnit.Unspecified,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        IconButton(onClick = { topStatusOpen = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Info, contentDescription = "List status")
                        }
                    }
                },
                actions = {
                    // Donate (☕) — shown if any donation URL is configured.
                    val hasBmc = appConfig.donationUrl.isNotBlank()
                    val hasStripe = appConfig.stripeDonationUrl.isNotBlank()
                    if (!compactWidth && !compactHeight && (hasBmc || hasStripe)) {
                        IconButton(onClick = {
                            if (hasBmc && hasStripe) {
                                supportDialogOpen = true
                            } else {
                                val url = if (hasStripe) appConfig.stripeDonationUrl else appConfig.donationUrl
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { snackbarMsg = "Could not open browser: ${it.message}" }
                            }
                        }) {
                            // U+2615 hot beverage; falls back fine on every Android emoji font.
                            Text("\u2615", fontSize = 20.sp)
                        }
                    }
                    IconButton(onClick = {
                        if (checking) return@IconButton
                        val mapped = rows.filter { !it.mapping?.f95Url.isNullOrBlank() }
                        if (mapped.isEmpty()) {
                            snackbarMsg = "No mapped apps to check. Add a URL or import mappings first."
                            return@IconButton
                        }
                        checking = true
                        checkProgress = 0 to mapped.size
                        scope.launch {
                            try {
                                checkAll(mapped, scraper, repo) { done, total ->
                                    checkProgress = done to total
                                }
                            } finally {
                                checking = false
                                val total = checkProgress?.second ?: 0
                                checkProgress = null
                                snackbarMsg = "Check complete ($total apps)"
                            }
                        }
                    }) {
                        if (checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Check now")
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            availableSortKeys.forEach { k ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (k == sortKey) {
                                                Icon(
                                                    if (sortDesc) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            } else {
                                                Spacer(Modifier.width(22.dp))
                                            }
                                            Text(k.label)
                                        }
                                    },
                                    onClick = {
                                        if (k == sortKey) sortDesc = !sortDesc
                                        else { sortKey = k; sortDesc = false }
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = {
                            menuOpen = false
                            subCatalogOpen = false; subJoiPlayOpen = false
                            subSaveToolsOpen = false; subBackupOpen = false; subLogsOpen = false
                        }) {
                            // --- TOP LEVEL: most-used actions
                            if ((compactWidth || compactHeight) && (hasBmc || hasStripe)) {
                                DropdownMenuItem(
                                    text = { Text("Support / donate") },
                                    leadingIcon = { Text("\u2615", fontSize = 20.sp) },
                                    onClick = {
                                        menuOpen = false
                                        if (hasBmc && hasStripe) {
                                            supportDialogOpen = true
                                        } else {
                                            val url = if (hasStripe) appConfig.stripeDonationUrl else appConfig.donationUrl
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }.onFailure { snackbarMsg = "Could not open browser: ${it.message}" }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Refresh from catalog") },
                                leadingIcon = { Icon(Icons.Default.Sync, null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        snackbarMsg = "Matching against catalog…"
                                        // Always scan ALL apps, not just the filtered/visible rows,
                                        // so search box and filter chips don't accidentally hide
                                        // apps that need matching.
                                        val allRows = apps.map { app ->
                                            val m = mappings[app.packageName]
                                            AppRow(app, m, UpdateStatus.Unknown)
                                        }
                                        val (m, u) = refreshFromCatalog(allRows)
                                        snackbarMsg = "Catalog refresh: $m / ${m + u} matched"
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Check for app update") },
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        snackbarMsg = "Checking for app update…"
                                        updateCheckResult = appUpdater.check(context)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showHidden) "Show visible apps" else "Show hidden apps (${hidden.size})") },
                                leadingIcon = { Icon(if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) },
                                onClick = { menuOpen = false; showHidden = !showHidden }
                            )
                            DropdownMenuItem(
                                text = { Text("View type: ${libraryLayoutMode.label}") },
                                leadingIcon = { Icon(if (libraryLayoutMode == LibraryLayoutMode.List) Icons.Default.ViewList else Icons.Default.GridView, null) },
                                onClick = {
                                    libraryLayoutMode = if (libraryLayoutMode == LibraryLayoutMode.List) {
                                        LibraryLayoutMode.Cards
                                    } else {
                                        LibraryLayoutMode.List
                                    }
                                    menuOpen = false
                                }
                            )

                            HorizontalDivider()

                            // --- SUBMENU: Catalog
                            DropdownMenuItem(
                                text = { Text("Catalog \u2026") },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                trailingIcon = {
                                    Icon(
                                        if (subCatalogOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                    )
                                },
                                onClick = { subCatalogOpen = !subCatalogOpen }
                            )
                            if (subCatalogOpen) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
                                DropdownMenuItem(
                                    text = { Text("Sync catalog now") },
                                    onClick = {
                                        menuOpen = false; subCatalogOpen = false
                                        scope.launch {
                                            AppLog.i("Catalog", "Sync requested")
                                            snackbarMsg = "Syncing catalog…"
                                            when (val r = catalog.sync()) {
                                                is CatalogSyncResult.Updated -> {
                                                    onLabelsChange(catalog.labels())
                                                    snackbarMsg = "Catalog updated: ${r.gameCount} games (${fmtSize(r.sizeBytes)})"
                                                }
                                                CatalogSyncResult.NotModified -> snackbarMsg = "Catalog already up-to-date"
                                                is CatalogSyncResult.Error -> snackbarMsg = "Catalog sync failed: ${r.message}"
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Review unmapped games") },
                                    onClick = {
                                        menuOpen = false; subCatalogOpen = false
                                        unmappedReviewLoading = true
                                        scope.launch {
                                            try {
                                                val items = buildCurrentUnmappedReviewItems()
                                                val already = items.mapNotNull { item ->
                                                    previouslyMappedCandidate(item.candidates)?.let { AlreadyMatchedCatalogMatch(item, it) }
                                                }
                                                alreadyMatchedReviewMatches = already
                                                unmappedReviewMatches = items.filterNot { item ->
                                                    already.any { it.item.row.installed.packageName == item.row.installed.packageName }
                                                }
                                                unmappedReviewOpen = true
                                            } finally {
                                                unmappedReviewLoading = false
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = overwriteManualMatches,
                                                onCheckedChange = null,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Overwrite manual matches on refresh")
                                        }
                                    },
                                    onClick = {
                                        overwriteManualMatches = !overwriteManualMatches
                                        scope.launch {
                                            MatchingPrefs.setOverwriteManualMatches(context.applicationContext, overwriteManualMatches)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Auto-hide non-games") },
                                    onClick = {
                                        menuOpen = false; subCatalogOpen = false
                                        scope.launch {
                                            var hidden = 0
                                            // Scan all apps, not just visible rows, so the filter
                                            // doesn't accidentally exclude candidates.
                                            for (app in apps) {
                                                val mapping = mappings[app.packageName]
                                                if (app.source == AppSource.Android &&
                                                    mapping?.threadId == null &&
                                                    NonGamesDetector.isLikelyNonGame(context, app.packageName)
                                                ) {
                                                    repo.hide(app.packageName); hidden++
                                                }
                                            }
                                            snackbarMsg = "Auto-hid $hidden likely non-games"
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset all acknowledgements") },
                                    onClick = {
                                        menuOpen = false; subCatalogOpen = false
                                        scope.launch { repo.resetAllAcknowledgements(); snackbarMsg = "Acknowledgements cleared" }
                                    }
                                )
                                        }
                                    }
                                }
                            }

                            // --- SUBMENU: Install + JoiPlay
                            DropdownMenuItem(
                                text = { Text("Install / JoiPlay \u2026") },
                                leadingIcon = { Icon(Icons.Default.GetApp, null) },
                                trailingIcon = {
                                    Icon(
                                        if (subJoiPlayOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                    )
                                },
                                onClick = { subJoiPlayOpen = !subJoiPlayOpen }
                            )
                            if (subJoiPlayOpen) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
                                DropdownMenuItem(
                                    text = { Text("Install APK\u2026") },
                                    leadingIcon = { Icon(Icons.Default.Android, null) },
                                    onClick = {
                                        menuOpen = false; subJoiPlayOpen = false
                                        if (hasAllFilesAccess()) {
                                            apkInstallPickerOpen = true
                                        } else {
                                            permissionRationale = PermissionRationale.AllFilesInstallApk
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Install game in JoiPlay\u2026") },
                                    leadingIcon = { Icon(Icons.Default.SportsEsports, null) },
                                    onClick = {
                                        menuOpen = false; subJoiPlayOpen = false
                                        if (!hasAllFilesAccess()) {
                                            permissionRationale = PermissionRationale.AllFilesJoiPlayInstall
                                        } else {
                                            scope.launch {
                                                val dismissed = JoiPlaySettingsStore.installWarningDismissed(context.applicationContext)
                                                if (dismissed) {
                                                    installPickerOpen = true
                                                } else installWarningOpen = true
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("JoiPlay settings\u2026") },
                                    onClick = {
                                        menuOpen = false; subJoiPlayOpen = false
                                        joiplaySettingsOpen = true
                                    }
                                )
                                DropdownMenuItem(
                                   text = {
                                       Text(
                                           if (joiPlaySizeScanning) "Scanning JoiPlay storage\u2026"
                                           else "Refresh JoiPlay storage sizes"
                                       )
                                   },
                                   leadingIcon = {
                                       if (joiPlaySizeScanning) {
                                           CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                       } else {
                                           Icon(Icons.Default.Storage, null)
                                       }
                                   },
                                   enabled = !joiPlaySizeScanning,
                                   onClick = {
                                       menuOpen = false; subJoiPlayOpen = false
                                       requestJoiPlaySizeScan(force = true)
                                   }
                                )
                                       }
                                    }
                                }
                            }

                            // --- SUBMENU: Save tools
                            DropdownMenuItem(
                                text = { Text("Save tools \u2026") },
                                leadingIcon = { Icon(Icons.Default.Save, null) },
                                trailingIcon = {
                                    Icon(
                                       if (subSaveToolsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                       null,
                                    )
                                },
                                onClick = { subSaveToolsOpen = !subSaveToolsOpen }
                            )
                            if (subSaveToolsOpen) {
                                Column(
                                    modifier = Modifier
                                       .fillMaxWidth()
                                       .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                       Box(
                                           modifier = Modifier
                                               .width(4.dp)
                                               .fillMaxHeight()
                                               .background(MaterialTheme.colorScheme.primary)
                                       )
                                       Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
                                DropdownMenuItem(
                                   text = {
                                      Text(
                                          if (renPySaveScanning) "Scanning Ren'Py saves\u2026"
                                          else "Scan Ren'Py save folders"
                                      )
                                   },
                                   leadingIcon = {
                                      if (renPySaveScanning) {
                                          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                      } else {
                                          Icon(Icons.Default.Save, null)
                                      }
                                   },
                                   enabled = !renPySaveScanning,
                                   onClick = {
                                      menuOpen = false; subSaveToolsOpen = false
                                      requestRenPySaveScan()
                                   }
                                )
                                DropdownMenuItem(
                                   text = { Text("Show Ren'Py save folders") },
                                   leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                   enabled = renPySaveLocations.isNotEmpty() && !renPySaveScanning,
                                   onClick = {
                                      menuOpen = false; subSaveToolsOpen = false
                                      renPySaveLocationsOpen = true
                                   }
                                )
                                DropdownMenuItem(
                                   text = {
                                      Text(
                                         if (rpgmSaveScanning) "Scanning RPGM saves\u2026"
                                         else "Scan RPGM save folders"
                                      )
                                   },
                                   leadingIcon = {
                                      if (rpgmSaveScanning) {
                                         CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                      } else {
                                         Icon(Icons.Default.Save, null)
                                      }
                                   },
                                   enabled = !rpgmSaveScanning,
                                   onClick = {
                                      menuOpen = false; subSaveToolsOpen = false
                                      requestRpgmSaveScan()
                                   }
                                )
                                DropdownMenuItem(
                                   text = { Text("Browse save backups") },
                                   leadingIcon = { Icon(Icons.Default.History, null) },
                                   enabled = renPySaveLocations.isNotEmpty() || rpgmSaveLocations.isNotEmpty(),
                                   onClick = {
                                      menuOpen = false; subSaveToolsOpen = false
                                      saveBackupBrowserOpen = true
                                   }
                                )
                                DropdownMenuItem(
                                  text = { Text("Show RPGM save folders") },
                                  leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                  enabled = rpgmSaveLocations.isNotEmpty() && !rpgmSaveScanning,
                                   onClick = {
                                      menuOpen = false; subSaveToolsOpen = false
                                      rpgmSaveLocationsOpen = true
                                   }
                                )
                                       }
                                    }
                                }
                            }

                            // --- SUBMENU: Backup
                            DropdownMenuItem(
                                text = { Text("Backup & config \u2026") },
                                leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                                trailingIcon = {
                                    Icon(
                                        if (subBackupOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                    )
                                },
                                onClick = { subBackupOpen = !subBackupOpen }
                            )
                            if (subBackupOpen) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
                                DropdownMenuItem(
                                    text = { Text("Export backup") },
                                    onClick = {
                                        menuOpen = false; subBackupOpen = false
                                        exportBackupPickerOpen = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import backup") },
                                    onClick = {
                                        menuOpen = false; subBackupOpen = false
                                        val existingRoot = importBackupScopedRootUri ?: backupScopedRootUri
                                        if (existingRoot != null) {
                                            importBackupScopedRootUri = existingRoot
                                            importBackupPickerOpen = true
                                        } else {
                                            importBackupAccessDisclosureOpen = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import JoiPlay backup (.joiback)\u2026") },
                                    onClick = {
                                        menuOpen = false; subBackupOpen = false
                                        askForJoiPlayBackupFolderAccess(firstRun = false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("JoiPlay cleanup review\u2026") },
                                    leadingIcon = {
                                        if (unusedFolderScanning) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Folder, null)
                                        }
                                    },
                                    enabled = !unusedFolderScanning,
                                    onClick = {
                                        menuOpen = false; subBackupOpen = false
                                        if (hasAllFilesAccess()) {
                                            unusedFolderRootPickerOpen = true
                                        } else {
                                            permissionRationale = PermissionRationale.AllFilesUnusedFolders
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Restore auto-backup\u2026") },
                                    onClick = {
                                        menuOpen = false; subBackupOpen = false
                                        scope.launch {
                                            autoBackupList = AutoBackupManager.list(context.applicationContext)
                                            autoBackupDialogOpen = true
                                        }
                                    }
                                )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Theme: ${themeMode.label} \u2026") },
                                leadingIcon = { Icon(Icons.Default.Palette, null) },
                                trailingIcon = {
                                    Icon(
                                        if (subThemeOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                    )
                                },
                                onClick = { subThemeOpen = !subThemeOpen },
                            )
                            if (subThemeOpen) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
                                            AppThemeMode.values().forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode.label) },
                                                    leadingIcon = {
                                                        RadioButton(
                                                            selected = themeMode == mode,
                                                            onClick = null,
                                                        )
                                                    },
                                                    onClick = {
                                                        menuOpen = false; subThemeOpen = false
                                                        scope.launch {
                                                            ThemePrefs.set(context.applicationContext, mode)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider()

                            val hasAllFiles = hasAllFilesAccess()
                            DropdownMenuItem(
                                text = { Text(if (hasAllFiles) "Revoke all files access" else "Grant all files access") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = {
                                    menuOpen = false
                                    permissionRationale = PermissionRationale.AllFilesConfig
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (hasUsage) "Revoke usage data access" else "Grant usage data access") },
                                leadingIcon = { Icon(Icons.Default.Schedule, null) },
                                onClick = {
                                    menuOpen = false
                                    permissionRationale = PermissionRationale.UsageAccess
                                }
                            )

                            // --- SUBMENU: Help (browser docs + about + diagnostics + support)
                            DropdownMenuItem(
                                text = { Text("Help \u2026") },
                                leadingIcon = { Icon(Icons.Default.HelpOutline, null) },
                                trailingIcon = {
                                    Icon(
                                        if (subLogsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                    )
                                },
                                onClick = { subLogsOpen = !subLogsOpen }
                            )
                            if (subLogsOpen) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Column(modifier = Modifier.padding(start = 16.dp).fillMaxWidth()) {
                                DropdownMenuItem(
                                    text = { Text("Documentation (browser)") },
                                    leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) },
                                    onClick = {
                                        menuOpen = false; subLogsOpen = false
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW,
                                                    Uri.parse(AppConfig.DEFAULT_HELP_URL))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }.onFailure { snackbarMsg = "Could not open browser: ${it.message}" }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("About") },
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                    onClick = { menuOpen = false; subLogsOpen = false; aboutOpen = true }
                                )
                                DropdownMenuItem(
                                   text = { Text("Copy diagnostics summary") },
                                   leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                   onClick = {
                                       menuOpen = false; subLogsOpen = false
                                       diagnosticsSummaryOpen = true
                                   }
                                )
                                DropdownMenuItem(
                                   text = { Text("Save local diagnostics") },
                                   leadingIcon = { Icon(Icons.Default.BugReport, null) },
                                   onClick = {
                                       menuOpen = false; subLogsOpen = false
                                       scope.launch {
                                           AppLog.i("User", "Save local diagnostics requested")
                                           val logResult = AppLog.saveLocally(context.applicationContext)
                                           val (crashCount, crashResult) = CrashReporter.saveAllLocally(context.applicationContext)
                                           snackbarMsg = if (crashCount > 0)
                                               "Saved logs and $crashCount reports to $crashResult"
                                           else "Saved logs to $logResult"
                                       }
                                   }
                                )
                                if (appConfig.hasCrashUpload) {
                                   DropdownMenuItem(
                                       text = { Text("Upload crash logs (${CrashReporter.pendingCount(context.applicationContext)})") },
                                       onClick = {
                                           menuOpen = false; subLogsOpen = false
                                           scope.launch {
                                               val (ok, fail) = CrashReporter.flush(context.applicationContext)
                                               snackbarMsg = if (ok == 0 && fail == 0) "No crash logs to send"
                                               else "Crash logs: $ok sent, $fail still queued"
                                           }
                                       }
                                   )
                                   DropdownMenuItem(
                                       text = { Text("Upload app logs + screenshots (${(AppLog.diskBytes() / 1024)} KB)") },
                                       onClick = {
                                           menuOpen = false; subLogsOpen = false
                                           scope.launch {
                                               AppLog.i("User", "Manual log upload requested")
                                               val (ok, fail) = AppLog.upload(context.applicationContext)
                                               snackbarMsg = "Logs: $ok sent, $fail failed"
                                           }
                                       }
                                   )
                                }
                                val hasBmc = appConfig.donationUrl.isNotBlank()
                                val hasStripe = appConfig.stripeDonationUrl.isNotBlank()
                                if (hasBmc || hasStripe) {
                                    DropdownMenuItem(
                                        text = { Text(if (hasBmc && hasStripe) "Support the project" else "Support link") },
                                        leadingIcon = { Text("\u2615", fontSize = 18.sp) },
                                        onClick = {
                                            menuOpen = false; subLogsOpen = false
                                            if (hasBmc && hasStripe) {
                                                supportDialogOpen = true
                                            } else {
                                                val url = if (hasStripe) appConfig.stripeDonationUrl else appConfig.donationUrl
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                }.onFailure { snackbarMsg = "Could not open browser: ${it.message}" }
                                            }
                                        }
                                    )
                                }
                                if (appConfig.diagnosticsEnabled) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (matchResearchProgress != null) "Uploading match research..." else "Upload match research snapshot") },
                                        leadingIcon = {
                                            if (matchResearchProgress != null) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.CloudUpload, null)
                                            }
                                        },
                                        enabled = matchResearchProgress == null,
                                        onClick = {
                                            menuOpen = false; subLogsOpen = false
                                            scope.launch {
                                                AppLog.i("MatchResearch", "Snapshot upload requested")
                                                matchResearchProgress = MatchResearchProgress("Starting")
                                                val result = runCatching {
                                                    MatchResearchCollector.collectSaveUpload(
                                                        context = context.applicationContext,
                                                        repo = repo,
                                                        catalog = catalog,
                                                    ) { progress ->
                                                        matchResearchProgress = progress
                                                    }
                                                }
                                                matchResearchProgress = null
                                                result.onSuccess { upload ->
                                                    snackbarMsg = if (upload.uploaded) {
                                                        "Uploaded match snapshot: ${upload.blobName}"
                                                    } else {
                                                        "Snapshot saved locally; upload failed: ${upload.error}"
                                                    }
                                                    AppLog.i(
                                                        "MatchResearch",
                                                        "Snapshot result uploaded=${upload.uploaded} local=${upload.localFile.absolutePath} blob=${upload.blobName} err=${upload.error}"
                                                    )
                                                }.onFailure {
                                                    AppLog.e("MatchResearch", "Snapshot failed", it)
                                                    snackbarMsg = "Match snapshot failed: ${it.message}"
                                                }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (launchScreenshotRunning) "Capturing launch screenshots..." else "Capture launch screenshots") },
                                        leadingIcon = {
                                            if (launchScreenshotRunning) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.PhotoCamera, null)
                                            }
                                        },
                                        enabled = !launchScreenshotRunning && !screenshotWalkthroughRunning,
                                        onClick = {
                                            menuOpen = false; subLogsOpen = false
                                            startLaunchScreenshotSet()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (screenshotWalkthroughRunning) "Capturing screenshots..." else "Capture walkthrough screenshots") },
                                        leadingIcon = {
                                            if (screenshotWalkthroughRunning) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.BugReport, null)
                                            }
                                        },
                                        enabled = !screenshotWalkthroughRunning && !launchScreenshotRunning,
                                        onClick = {
                                            menuOpen = false; subLogsOpen = false
                                            startScreenshotWalkthrough()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save logs to Documents") },
                                        leadingIcon = { Icon(Icons.Default.BugReport, null) },
                                        onClick = {
                                            menuOpen = false; subLogsOpen = false
                                            scope.launch {
                                                AppLog.i("User", "Save logs locally requested")
                                                val logResult = AppLog.saveLocally(context.applicationContext)
                                                val (crashCount, crashResult) = CrashReporter.saveAllLocally(context.applicationContext)
                                                snackbarMsg = if (crashCount > 0)
                                                    "Saved logs and $crashCount crashes to $crashResult"
                                                else "Saved logs to $logResult"
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (AppLog.verbose) "Verbose logs: ON" else "Verbose logs: off") },
                                        onClick = {
                                            menuOpen = false; subLogsOpen = false
                                            AppLog.verbose = !AppLog.verbose
                                            AppLog.i("User", "Verbose logging set to ${AppLog.verbose}")
                                            snackbarMsg = if (AppLog.verbose) "Verbose logging ON" else "Verbose logging OFF"
                                        }
                                    )
                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = nameFilter,
                onValueChange = { nameFilter = it },
                placeholder = { Text("Filter: text + tag:harem tag:incest") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (nameFilter.isNotEmpty()) {
                        IconButton(onClick = { nameFilter = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            // Tag autocomplete chips — appear when the filter ends with `tag:<prefix>`
            // (no trailing whitespace). Same UX as the Catalog tab. Tap inserts the
            // full tag name and a trailing space.
            val tagSuggestions: List<String> = remember(nameFilter, catalogLabels) {
                val labels = catalogLabels ?: return@remember emptyList()
                val match = TAG_TOKEN_AT_END_APPS.find(nameFilter) ?: return@remember emptyList()
                val prefix = match.groupValues[1].lowercase()
                labels.allLabelNames.asSequence()
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                    .distinct()
                    .sortedBy { it.length }
                    .take(8)
                    .toList()
            }
            if (tagSuggestions.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (suggestion in tagSuggestions) {
                        AssistChip(
                            onClick = {
                                val match = TAG_TOKEN_AT_END_APPS.find(nameFilter) ?: return@AssistChip
                                val before = nameFilter.substring(0, match.range.first)
                                nameFilter = before + "tag:" + suggestion + " "
                            },
                            label = { Text(suggestion, fontSize = 12.sp) },
                        )
                    }
                }
            }
            if (compactHeight) {
                CompactInstalledFilterRow(
                    active = activeFilters,
                    sourceFilter = sourceFilter,
                    onSourceChange = { sourceFilter = it },
                    manualOnly = manualOnlyFilter,
                    onManualOnlyChange = { manualOnlyFilter = it },
                    threadUpdatedAfterInstall = threadUpdatedAfterInstallFilter,
                    onThreadUpdatedAfterInstallChange = { threadUpdatedAfterInstallFilter = it },
                    hasSavesOnly = hasSavesOnlyFilter,
                    onHasSavesOnlyChange = { hasSavesOnlyFilter = it },
                )
            } else {
                FilterBar(
                    active = activeFilters,
                    sourceFilter = sourceFilter,
                    onSourceChange = { sourceFilter = it },
                    manualOnly = manualOnlyFilter,
                    onManualOnlyChange = { manualOnlyFilter = it },
                    threadUpdatedAfterInstall = threadUpdatedAfterInstallFilter,
                    onThreadUpdatedAfterInstallChange = { threadUpdatedAfterInstallFilter = it },
                    hasSavesOnly = hasSavesOnlyFilter,
                    onHasSavesOnlyChange = { hasSavesOnlyFilter = it },
                )
            }
            HorizontalDivider()
            if (apkInstalling) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "Installing\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (joiPlaySizeScanning) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        val progress = joiPlaySizeScanProgress
                        Text(
                            if (progress != null) {
                                "Scanning JoiPlay storage ${progress.folderIndex} / ${progress.folderTotal}: ${progress.folderName}"
                            } else {
                                "Scanning JoiPlay storage sizes…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (selectionMode) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { selection.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                        Text(
                            "${selection.size} selected",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            val all = rows.map { it.installed.packageName }
                            if (selection.size == all.size) selection.clear()
                            else { selection.clear(); selection.addAll(all) }
                        }) {
                            Text(if (selection.size == rows.size && rows.isNotEmpty()) "Clear" else "All")
                        }
                        if (showHidden) {
                            TextButton(onClick = {
                                val pkgs = selection.toList()
                                scope.launch {
                                    for (p in pkgs) repo.unhide(p)
                                    snackbarMsg = "Unhid ${pkgs.size} app${if (pkgs.size == 1) "" else "s"}"
                                    selection.clear()
                                }
                            }) { Text("Unhide") }
                        } else {
                            TextButton(onClick = {
                                val pkgs = selection.toList()
                                scope.launch {
                                    for (p in pkgs) repo.hide(p)
                                    snackbarMsg = "Hid ${pkgs.size} app${if (pkgs.size == 1) "" else "s"}"
                                    selection.clear()
                                }
                            }) { Text("Hide") }
                        }
                    }
                }
                HorizontalDivider()
            }
            if (!hasUsage) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Optional: grant Usage access (menu) so the app can show \"Last used\" dates.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (libraryLayoutMode == LibraryLayoutMode.Cards && rows.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 210.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(rows, key = { it.installed.packageName }) { row ->
                        val pkg = row.installed.packageName
                        val isExpanded = pkg in expanded
                        val catalogGame: CatalogGame? = remember(catalogById, row.mapping) {
                            mappedCatalogGame(row.mapping, catalogById)
                        }
                        val coverUrl = catalogGame?.cover?.takeIf { it.isNotBlank() }
                        NiceGameCard(
                            row = row,
                            joiPlaySizeInfo = joiPlaySizeKey(row.installed)?.let { joiPlaySizeInfo[it] },
                            isJoiPlaySizeScanning = joiPlaySizeKey(row.installed) in joiPlaySizeScanningFolders,
                            renPySaves = renPySaveAssociations[row.installed.packageName].orEmpty(),
                            rpgmSaves = rpgmSaveAssociations[row.installed.packageName].orEmpty(),
                            expanded = isExpanded,
                            catalogGame = catalogGame,
                            catalogCover = coverUrl,
                            catalogLabels = catalogLabels,
                            selected = pkg in selection,
                            selectionMode = selectionMode,
                            onToggleSelect = {
                                if (pkg in selection) selection.remove(pkg) else selection.add(pkg)
                            },
                            onLongPress = {
                                if (pkg in selection) selection.remove(pkg) else selection.add(pkg)
                            },
                            onToggleExpand = {
                                if (selectionMode) {
                                    if (pkg in selection) selection.remove(pkg) else selection.add(pkg)
                                } else {
                                    if (isExpanded) expanded.remove(pkg) else expanded.add(pkg)
                                }
                            },
                            onShowCover = { fullSizeImageUrl = it },
                            onSnack = { snackbarMsg = it },
                            onEdit = { dialogApp = row },
                            onSetInstalledVersion = { manualVersionTarget = row },
                            onSetInstalledDate = { manualDateTarget = row to catalogGame },
                            onOpenRenPySaves = { renPySaveEditorTarget = row },
                            onAddRenPySaveFolder = { renPyAddFolderTarget = row },
                            onOpenRpgmSaves = { rpgmSaveViewerTarget = row },
                            onAddRpgmSaveFolder = { rpgmAddFolderTarget = row },
                            onLaunch = {
                                if (row.installed.source == AppSource.JoiPlay) {
                                    val err = runCatching { JoiPlayLauncher.launch(context, row.installed) }
                                        .getOrElse { it.message ?: "unknown error" }
                                    if (err != null) {
                                        AppLog.w("Launch", "JoiPlay launch failed: $err; opening JoiPlay main")
                                        snackbarMsg = err
                                        runCatching {
                                            val main = context.packageManager.getLaunchIntentForPackage("cyou.joiplay.joiplay")
                                            if (main != null) context.startActivity(main)
                                        }
                                    }
                                } else {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                                    if (launchIntent != null) context.startActivity(launchIntent)
                                    else snackbarMsg = "No launcher activity for ${row.installed.label}"
                                }
                            },
                            onUninstall = {
                                if (row.installed.source == AppSource.JoiPlay) {
                                    scope.launch {
                                        val hasUri = JoiPlayScanner.getRootUri(context.applicationContext) != null
                                        if (hasUri) {
                                            joiPlayDeleteConfirm = row
                                        } else {
                                            joiPlayGrantAskFor = row
                                        }
                                    }
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure { snackbarMsg = "Uninstall failed: ${it.message}" }
                                }
                            },
                            onRefreshOne = {
                                scope.launch {
                                    if (row.installed.source == AppSource.JoiPlay) {
                                        joiPlayDetecting = row
                                        val candidates = runCatching {
                                            JoiPlayVersionDetector.detect(context.applicationContext, row.installed)
                                        }.getOrElse {
                                            AppLog.w("Detect", "version detect failed", it)
                                            emptyList()
                                        }
                                        joiPlayDetecting = null
                                        val distinct = candidates.map { it.version }.distinct()
                                        suspend fun applyAndCheckSource(chosen: String?) {
                                            if (chosen != null) {
                                                setManualInstalledVersion(row, chosen)
                                                val android = InstalledAppsScanner.scan(context)
                                                val backup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                                                val folder = JoiPlayScanner.scan(context.applicationContext)
                                                apps = (android + mergeJoiPlaySources(backup, folder))
                                                    .sortedBy { it.label.lowercase() }
                                            }
                                            if (!row.mapping?.f95Url.isNullOrBlank()) {
                                                checkOne(row, scraper, repo)
                                            } else {
                                                val found = searcher.findF95Thread(row.installed.label)
                                                if (!found.isNullOrBlank()) {
                                                    repo.upsert(
                                                        AppMapping(
                                                            packageName = pkg,
                                                            f95Url = found,
                                                            lastSeenVersion = null,
                                                            acknowledgedVersion = null,
                                                            lastChecked = 0L,
                                                            matchSource = "search-auto",
                                                        ).withPersonalFieldsFrom(row.mapping)
                                                    )
                                                    val freshRow = AppRow(row.installed, AppMapping(pkg, found, matchSource = "search-auto").withPersonalFieldsFrom(row.mapping), row.status)
                                                    checkOne(freshRow, scraper, repo)
                                                }
                                            }
                                            snackbarMsg = if (chosen != null)
                                                "Set version $chosen for ${row.installed.label}"
                                            else "Refreshed ${row.installed.label}"
                                        }
                                        when {
                                            candidates.isEmpty() -> {
                                                snackbarMsg = "No version detected. Use Set installed version to enter it manually."
                                                applyAndCheckSource(null)
                                            }
                                            distinct.size == 1 -> applyAndCheckSource(distinct[0])
                                            else -> {
                                                joiPlayVersionDialog = Triple(row, candidates) {
                                                    /* dialog closed without selecting */
                                                }
                                            }
                                        }
                                    } else if (!row.mapping?.f95Url.isNullOrBlank()) {
                                        checkOne(row, scraper, repo)
                                        snackbarMsg = "Refreshed ${row.installed.label}"
                                    } else {
                                        snackbarMsg = "Searching catalog for ${row.installed.label}…"
                                        val found = searcher.findF95Thread(row.installed.label)
                                        if (found.isNullOrBlank()) {
                                            snackbarMsg = "Unable to fetch URL for ${row.installed.label}"
                                        } else {
                                            repo.upsert(
                                                AppMapping(
                                                    packageName = pkg,
                                                    f95Url = found,
                                                    lastSeenVersion = null,
                                                    acknowledgedVersion = null,
                                                    lastChecked = 0L,
                                                    matchSource = "search-auto",
                                                ).withPersonalFieldsFrom(row.mapping)
                                            )
                                            val freshRow = AppRow(row.installed, AppMapping(pkg, found, matchSource = "search-auto").withPersonalFieldsFrom(row.mapping), row.status)
                                            checkOne(freshRow, scraper, repo)
                                            snackbarMsg = "Found URL & refreshed ${row.installed.label}"
                                        }
                                    }
                                }
                            },
                            onOpen = {
                                val target = row.mapping?.f95Url
                                    ?: "https://www.google.com/search?q=" +
                                        Uri.encode("\"${row.installed.label}\" f95zone")
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                            },
                        )
                    }
                }
            } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 96.dp)
            ) {
                if (rows.isEmpty() && apps.isNotEmpty()) {
                    item {
                        // Filters are hiding everything — guide them to clear.
                        EmptyState(
                            title = "No matches",
                            body = "Your filters or search are hiding all rows. Clear the search box or the filter chips above.",
                            actionLabel = null,
                            onAction = null,
                        )
                    }
                } else if (rows.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Welcome to Adult Game Manager",
                            body = if (joiPlayInstalled) {
                                "JoiPlay is installed on your device. To track your JoiPlay games, " +
                                        "open JoiPlay \u2192 Settings \u2192 Backup, export your games, " +
                                        if (hasAllFilesAccess()) {
                                            "then tap below and pick the .joiback file.\n\n"
                                        } else {
                                            "then tap below, grant access to that backup folder, and pick the .joiback file.\n\n"
                                        } +
                                        "Android-installed adult games will be detected automatically once you tap \u201CRefresh All\u201D."
                            } else {
                                "This app tracks adult game updates across installed APKs and JoiPlay games.\n\n" +
                                        "Android-installed adult games will be detected automatically once you tap \u201CRefresh All\u201D."
                            },
                            actionLabel = "Import JoiPlay backup",
                            onAction = {
                                askForJoiPlayBackupFolderAccess(firstRun = false)
                            },
                        )
                    }
                }
                items(rows, key = { it.installed.packageName }) { row ->
                    val pkg = row.installed.packageName
                    val isExpanded = pkg in expanded
                    // Resolve catalog match synchronously from the pre-loaded byId map (no per-row suspend).
                    val catalogGame: CatalogGame? = remember(catalogById, row.mapping) {
                        mappedCatalogGame(row.mapping, catalogById)
                    }
                    val coverUrl: String? = catalogGame?.cover?.takeIf { it.isNotBlank() }
                    AppRowCard(
                        row = row,
                        joiPlaySizeInfo = joiPlaySizeKey(row.installed)?.let { joiPlaySizeInfo[it] },
                        isJoiPlaySizeScanning = joiPlaySizeKey(row.installed) in joiPlaySizeScanningFolders,
                        renPySaves = renPySaveAssociations[row.installed.packageName].orEmpty(),
                        rpgmSaves = rpgmSaveAssociations[row.installed.packageName].orEmpty(),
                        expanded = isExpanded,
                        catalogGame = catalogGame,
                        catalogCover = coverUrl,
                        catalogLabels = catalogLabels,
                        selected = pkg in selection,
                        selectionMode = selectionMode,
                        onToggleSelect = {
                            if (pkg in selection) selection.remove(pkg) else selection.add(pkg)
                        },
                        onLongPress = {
                            if (pkg in selection) selection.remove(pkg) else selection.add(pkg)
                        },
                        onToggleExpand = {
                            if (selectionMode) {
                                if (pkg in selection) selection.remove(pkg) else selection.add(pkg)
                            } else {
                                if (isExpanded) expanded.remove(pkg) else expanded.add(pkg)
                            }
                        },
                        onShowCover = { fullSizeImageUrl = it },
                        onSnack = { snackbarMsg = it },
                        onEdit = { dialogApp = row },
                        onSetInstalledVersion = { manualVersionTarget = row },
                        onSetInstalledDate = { manualDateTarget = row to catalogGame },
                        onOpenRenPySaves = { renPySaveEditorTarget = row },
                        onAddRenPySaveFolder = { renPyAddFolderTarget = row },
                        onOpenRpgmSaves = { rpgmSaveViewerTarget = row },
                        onAddRpgmSaveFolder = { rpgmAddFolderTarget = row },
                        onLaunch = {
                            if (row.installed.source == AppSource.JoiPlay) {
                                val err = runCatching { JoiPlayLauncher.launch(context, row.installed) }
                                    .getOrElse { it.message ?: "unknown error" }
                                if (err != null) {
                                    AppLog.w("Launch", "JoiPlay launch failed: $err; opening JoiPlay main")
                                    snackbarMsg = err
                                    // Fallback: just open JoiPlay's launcher screen.
                                    runCatching {
                                        val main = context.packageManager.getLaunchIntentForPackage("cyou.joiplay.joiplay")
                                        if (main != null) context.startActivity(main)
                                    }
                                }
                            } else {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                                if (launchIntent != null) context.startActivity(launchIntent)
                                else snackbarMsg = "No launcher activity for ${row.installed.label}"
                            }
                        },
                        onUninstall = {
                            if (row.installed.source == AppSource.JoiPlay) {
                                scope.launch {
                                    val hasUri = JoiPlayScanner.getRootUri(context.applicationContext) != null
                                    if (hasUri) {
                                        joiPlayDeleteConfirm = row
                                    } else {
                                        // First-time: ask for SAF permission with explanation.
                                        joiPlayGrantAskFor = row
                                    }
                                }
                            } else {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure { snackbarMsg = "Uninstall failed: ${it.message}" }
                            }
                        },
                        onRefreshOne = {
                            scope.launch {
                                if (row.installed.source == AppSource.JoiPlay) {
                                    // JoiPlay: detect the installed version via 3 methods,
                                    // then run the normal catalog update check.
                                    joiPlayDetecting = row
                                    val candidates = runCatching {
                                        JoiPlayVersionDetector.detect(context.applicationContext, row.installed)
                                    }.getOrElse {
                                        AppLog.w("Detect", "version detect failed", it)
                                        emptyList()
                                    }
                                    joiPlayDetecting = null

                                    val distinct = candidates.map { it.version }.distinct()
                                    suspend fun applyAndCheckSource(chosen: String?) {
                                        if (chosen != null) {
                                            setManualInstalledVersion(row, chosen)
                                            // Force the InstalledApp list to refresh with the new override.
                                            val android = InstalledAppsScanner.scan(context)
                                            val backup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                                            val folder = JoiPlayScanner.scan(context.applicationContext)
                                            apps = (android + mergeJoiPlaySources(backup, folder))
                                                .sortedBy { it.label.lowercase() }
                                        }
                                        // Now run the existing catalog update check / search.
                                        if (!row.mapping?.f95Url.isNullOrBlank()) {
                                            checkOne(row, scraper, repo)
                                        } else {
                                            val found = searcher.findF95Thread(row.installed.label)
                                            if (!found.isNullOrBlank()) {
                                                repo.upsert(
                                                    AppMapping(
                                                        packageName = pkg,
                                                        f95Url = found,
                                                        lastSeenVersion = null,
                                                        acknowledgedVersion = null,
                                                        lastChecked = 0L,
                                                        matchSource = "search-auto",
                                                    ).withPersonalFieldsFrom(row.mapping)
                                                )
                                                val freshRow = AppRow(row.installed, AppMapping(pkg, found, matchSource = "search-auto").withPersonalFieldsFrom(row.mapping), row.status)
                                                checkOne(freshRow, scraper, repo)
                                            }
                                        }
                                        snackbarMsg = if (chosen != null)
                                            "Set version $chosen for ${row.installed.label}"
                                        else "Refreshed ${row.installed.label}"
                                    }

                                    when {
                                        candidates.isEmpty() -> {
                                            snackbarMsg = "No version detected. Use Set installed version to enter it manually."
                                            applyAndCheckSource(null)
                                        }
                                        distinct.size == 1 -> {
                                            applyAndCheckSource(distinct[0])
                                        }
                                        else -> {
                                            // Conflict — let the user pick.
                                            joiPlayVersionDialog = Triple(row, candidates) {
                                                /* dialog closed without selecting */
                                            }
                                        }
                                    }
                                } else if (!row.mapping?.f95Url.isNullOrBlank()) {
                                    checkOne(row, scraper, repo)
                                    snackbarMsg = "Refreshed ${row.installed.label}"
                                } else {
                                    snackbarMsg = "Searching catalog for ${row.installed.label}…"
                                    val found = searcher.findF95Thread(row.installed.label)
                                    if (found.isNullOrBlank()) {
                                        snackbarMsg = "Unable to fetch URL for ${row.installed.label}"
                                    } else {
                                        repo.upsert(
                                            AppMapping(
                                                packageName = pkg,
                                                f95Url = found,
                                                lastSeenVersion = null,
                                                acknowledgedVersion = null,
                                                lastChecked = 0L,
                                                matchSource = "search-auto",
                                            ).withPersonalFieldsFrom(row.mapping)
                                        )
                                        val freshRow = AppRow(row.installed, AppMapping(pkg, found, matchSource = "search-auto").withPersonalFieldsFrom(row.mapping), row.status)
                                        checkOne(freshRow, scraper, repo)
                                        snackbarMsg = "Found URL & refreshed ${row.installed.label}"
                                    }
                                }
                            }
                        },
                        onOpen = {
                            val target = row.mapping?.f95Url
                                ?: "https://www.google.com/search?q=" +
                                    Uri.encode("\"${row.installed.label}\" f95zone")
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                        }
                    )
                }
            }
            }
        }
    }

    dialogApp?.let { row ->
        val isHidden = row.installed.packageName in hidden
        fun afterDialogMapping(packageName: String) {
            if (returnToUnmappedAfterDialog) {
                unmappedReviewMatches = unmappedReviewMatches.filterNot { it.row.installed.packageName == packageName }
                unmappedReviewOpen = true
                returnToUnmappedAfterDialog = false
            }
        }
        EditMappingDialog(
            row = row,
            isHidden = isHidden,
            catalog = catalog,
            searcher = searcher,
            onDismiss = {
                dialogApp = null
                if (returnToUnmappedAfterDialog) {
                    unmappedReviewOpen = true
                    returnToUnmappedAfterDialog = false
                }
            },
            onSave = { newUrl, userStatus, personalRating, personalNotes, correctionNote ->
                scope.launch {
                    val tid = F95UrlParser.extractThreadId(newUrl)
                    val mapping = AppMapping(
                        packageName = row.installed.packageName,
                        f95Url = newUrl.ifBlank { null },
                        lastSeenVersion = row.mapping?.lastSeenVersion,
                        lastChecked = row.mapping?.lastChecked ?: 0L,
                        acknowledgedVersion = row.mapping?.acknowledgedVersion,
                        threadId = tid ?: row.mapping?.threadId,
                        notOnF95 = false,
                        matchSource = "manual",
                        userStatus = userStatus,
                        personalRating = personalRating,
                        personalNotes = personalNotes.trim(),
                        manualCorrectionNote = correctionNote.trim(),
                    ).withLocalIdentityFrom(row.installed)
                    AppLog.i("ManualMapping", "SAVE ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))} url=${mapping.f95Url} thread=${mapping.threadId} identity=${mapping.manualLocalIdentity}")
                    rememberMapping(mapping)
                    repo.upsert(mapping)
                    dialogApp = null
                    afterDialogMapping(row.installed.packageName)
                }
            },
            onPickCatalog = { game ->
                scope.launch {
                    val mapping = AppMapping(
                            packageName = row.installed.packageName,
                            f95Url = game.canonicalUrl,
                            lastSeenVersion = game.version,
                            lastChecked = System.currentTimeMillis(),
                            acknowledgedVersion = row.mapping?.acknowledgedVersion,
                            threadId = game.f95ThreadIdOrNull,
                            notOnF95 = false,
                            matchSource = "manual",
                        ).withPersonalFieldsFrom(row.mapping).withLocalIdentityFrom(row.installed).withCatalogSnapshot(game)
                    AppLog.i("ManualMapping", "PICK ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))} -> tid=${game.thread_id} title='${game.title}' identity=${mapping.manualLocalIdentity}")
                    rememberMapping(mapping)
                    repo.upsert(mapping)
                    snackbarMsg = "Mapped to '${game.title}'"
                    dialogApp = null
                    afterDialogMapping(row.installed.packageName)
                }
            },
            onPickExternal = { external ->
                scope.launch {
                    val mapping = AppMapping(
                            packageName = row.installed.packageName,
                            f95Url = external.mirrorUrl,
                            lastSeenVersion = external.version ?: row.mapping?.lastSeenVersion,
                            lastChecked = System.currentTimeMillis(),
                            acknowledgedVersion = row.mapping?.acknowledgedVersion,
                            threadId = if (external.sourceHost == "f95zone.to") external.threadId else null,
                            notOnF95 = false,
                            matchSource = "manual-external:${external.sourceHost.ifBlank { "external" }}",
                        ).withPersonalFieldsFrom(row.mapping).withLocalIdentityFrom(row.installed).withExternalSnapshot(external)
                    AppLog.i("ManualMapping", "PICK_EXTERNAL ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))} -> ${external.sourceHost}:${external.threadId} '${external.title}' identity=${mapping.manualLocalIdentity}")
                    rememberMapping(mapping)
                    repo.upsert(mapping)
                    snackbarMsg = "Mapped to external source: ${external.title}"
                    dialogApp = null
                    afterDialogMapping(row.installed.packageName)
                }
            },
            onMarkNotOnF95 = {
                scope.launch {
                    repo.upsert(
                        AppMapping(
                            packageName = row.installed.packageName,
                            f95Url = null,
                            lastSeenVersion = null,
                            lastChecked = System.currentTimeMillis(),
                            acknowledgedVersion = null,
                            threadId = null,
                            notOnF95 = true,
                            matchSource = "manual",
                        ).withPersonalFieldsFrom(row.mapping)
                    )
                    dialogApp = null
                    afterDialogMapping(row.installed.packageName)
                }
            },
            onClearNotOnF95 = {
                scope.launch {
                    val m = row.mapping ?: return@launch
                    repo.upsert(m.copy(notOnF95 = false))
                    dialogApp = null
                }
            },
            onClear = {
                scope.launch {
                    val m = row.mapping
                    if (m?.hasPersonalFields() == true) {
                        repo.upsert(
                            m.copy(
                                f95Url = null,
                                lastSeenVersion = null,
                                lastChecked = 0L,
                                acknowledgedVersion = null,
                                threadId = null,
                                notOnF95 = false,
                                matchSource = null,
                            )
                        )
                    } else {
                        repo.remove(row.installed.packageName)
                    }
                    dialogApp = null
                    returnToUnmappedAfterDialog = false
                }
            },
            onMarkInstalled = {
                scope.launch {
                    val m = row.mapping ?: return@launch
                    repo.upsert(m.copy(acknowledgedVersion = m.lastSeenVersion))
                    dialogApp = null
                }
            },
            onToggleHide = {
                scope.launch {
                    if (isHidden) repo.unhide(row.installed.packageName)
                    else repo.hide(row.installed.packageName)
                    dialogApp = null
                    returnToUnmappedAfterDialog = false
                }
            }
        )
    }

    ambiguousCatalogMatches.firstOrNull()?.let { ambiguous ->
        AmbiguousCatalogDialog(
            item = ambiguous,
            onPick = { game ->
                scope.launch {
                    val row = ambiguous.row
                    val mapping = AppMapping(
                            packageName = row.installed.packageName,
                            f95Url = game.canonicalUrl,
                            lastSeenVersion = game.version,
                            lastChecked = System.currentTimeMillis(),
                            acknowledgedVersion = row.mapping?.acknowledgedVersion,
                            threadId = game.f95ThreadIdOrNull,
                            notOnF95 = false,
                            matchSource = "manual-ambiguous:${ambiguous.via}",
                        ).withPersonalFieldsFrom(row.mapping).withLocalIdentityFrom(row.installed).withCatalogSnapshot(game)
                    AppLog.i("ManualMapping", "PICK_AMBIGUOUS ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))} -> tid=${game.thread_id} title='${game.title}' identity=${mapping.manualLocalIdentity}")
                    rememberMapping(mapping)
                    repo.upsert(mapping)
                    snackbarMsg = "Mapped ${row.installed.label} to '${game.title}'"
                    ambiguousCatalogMatches = ambiguousCatalogMatches.drop(1)
                }
            },
            onNone = {
                scope.launch {
                    val row = ambiguous.row
                    repo.upsert(
                        AppMapping(
                            packageName = row.installed.packageName,
                            f95Url = null,
                            lastSeenVersion = null,
                            lastChecked = System.currentTimeMillis(),
                            acknowledgedVersion = null,
                            threadId = null,
                            notOnF95 = true,
                            matchSource = "manual-ambiguous-none",
                        ).withPersonalFieldsFrom(row.mapping)
                    )
                    snackbarMsg = "Marked ${row.installed.label} as not in catalog"
                    ambiguousCatalogMatches = ambiguousCatalogMatches.drop(1)
                }
            },
            onSkip = { ambiguousCatalogMatches = ambiguousCatalogMatches.drop(1) },
            onDismiss = { ambiguousCatalogMatches = emptyList() },
        )
    }

    if (unmappedReviewOpen) {
        UnmappedReviewDialog(
            items = unmappedReviewMatches,
            alreadyMatchedItems = alreadyMatchedReviewMatches,
            onDismiss = { unmappedReviewOpen = false },
            onDismissAlreadyMatched = {
                alreadyMatchedReviewMatches = emptyList()
                if (unmappedReviewMatches.isEmpty()) unmappedReviewOpen = false
                snackbarMsg = "Dismissed already matched games from this review"
            },
            onKeepAlreadyMatched = {
                scope.launch {
                    val kept = alreadyMatchedReviewMatches
                    kept.forEach { already ->
                        val row = already.item.row
                        val game = already.keptGame
                        val mapping = AppMapping(
                            packageName = row.installed.packageName,
                            f95Url = game.canonicalUrl,
                            lastSeenVersion = game.version,
                            lastChecked = System.currentTimeMillis(),
                            acknowledgedVersion = row.mapping?.acknowledgedVersion,
                            threadId = game.f95ThreadIdOrNull,
                            notOnF95 = false,
                            matchSource = "manual:previous",
                        ).withPersonalFieldsFrom(row.mapping).withLocalIdentityFrom(row.installed).withCatalogSnapshot(game)
                        rememberMapping(mapping)
                        repo.upsert(mapping)
                    }
                    alreadyMatchedReviewMatches = emptyList()
                    if (unmappedReviewMatches.isEmpty()) unmappedReviewOpen = false
                    snackbarMsg = "Kept ${kept.size} already matched game${if (kept.size == 1) "" else "s"}"
                }
            },
            onPick = { item, game ->
                scope.launch {
                    val row = item.row
                    val mapping = AppMapping(
                        packageName = row.installed.packageName,
                        f95Url = game.canonicalUrl,
                        lastSeenVersion = game.version,
                        lastChecked = System.currentTimeMillis(),
                        acknowledgedVersion = row.mapping?.acknowledgedVersion,
                        threadId = game.f95ThreadIdOrNull,
                        notOnF95 = false,
                        matchSource = "manual",
                    ).withPersonalFieldsFrom(row.mapping).withLocalIdentityFrom(row.installed).withCatalogSnapshot(game)
                    AppLog.i("ManualMapping", "REVIEW_PICK ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))} -> tid=${game.thread_id} title='${game.title}' identity=${mapping.manualLocalIdentity}")
                    rememberMapping(mapping)
                    repo.upsert(mapping)
                    unmappedReviewMatches = unmappedReviewMatches.filterNot { it.row.installed.packageName == row.installed.packageName }
                    alreadyMatchedReviewMatches = alreadyMatchedReviewMatches.filterNot { it.item.row.installed.packageName == row.installed.packageName }
                    snackbarMsg = "Mapped ${row.installed.label} to '${game.title}'"
                }
            },
            onNone = { item ->
                scope.launch {
                    val row = item.row
                    val mapping = AppMapping(
                        packageName = row.installed.packageName,
                        f95Url = null,
                        lastSeenVersion = null,
                        lastChecked = System.currentTimeMillis(),
                        acknowledgedVersion = null,
                        threadId = null,
                        notOnF95 = true,
                        matchSource = "manual",
                    ).withPersonalFieldsFrom(row.mapping)
                    rememberMapping(mapping)
                    repo.upsert(mapping)
                    unmappedReviewMatches = unmappedReviewMatches.filterNot { it.row.installed.packageName == row.installed.packageName }
                    alreadyMatchedReviewMatches = alreadyMatchedReviewMatches.filterNot { it.item.row.installed.packageName == row.installed.packageName }
                    snackbarMsg = "Marked ${row.installed.label} as not in catalog"
                }
            },
            onSearch = { item ->
                dialogApp = item.row
                returnToUnmappedAfterDialog = true
                unmappedReviewOpen = false
            },
            onAddManualMatches = {
                unmappedReviewLoading = true
                scope.launch {
                    try {
                        val existingPackages = unmappedReviewMatches.map { it.row.installed.packageName }.toSet()
                        val manualItems = buildManualMatchedReviewItems()
                            .filterNot { it.row.installed.packageName in existingPackages }
                        if (manualItems.isEmpty()) {
                            snackbarMsg = "No additional manually matched games."
                        } else {
                            unmappedReviewMatches = (unmappedReviewMatches + manualItems)
                                .sortedBy { it.row.installed.label.lowercase() }
                            snackbarMsg = "Added ${manualItems.size} manually matched games."
                        }
                    } finally {
                        unmappedReviewLoading = false
                    }
                }
            },
            onSelectClosestFuzzy = {
                scope.launch {
                    var updated = 0
                    var notFound = 0
                    val mappedPackages = mutableSetOf<String>()
                    val items = unmappedReviewMatches
                    bulkFuzzyProgress = BulkFuzzyProgress(0, items.size, 0, 0)
                    try {
                        for ((index, item) in items.withIndex()) {
                            bulkFuzzyProgress = BulkFuzzyProgress(index, items.size, updated, notFound)
                            val row = item.row
                            val closest = relaxedTitleCandidatesWithTranslation(
                                catalog = catalog,
                                labels = catalogMatchLabels(row.installed),
                                limit = 1,
                            ).games.firstOrNull()
                            if (closest == null) {
                                notFound++
                                bulkFuzzyProgress = BulkFuzzyProgress(index + 1, items.size, updated, notFound)
                                continue
                            }
                            repo.upsert(
                                AppMapping(
                                    packageName = row.installed.packageName,
                                    f95Url = closest.canonicalUrl,
                                    lastSeenVersion = closest.version,
                                    lastChecked = System.currentTimeMillis(),
                                    acknowledgedVersion = row.mapping?.acknowledgedVersion,
                                    threadId = closest.f95ThreadIdOrNull,
                                    notOnF95 = false,
                                    matchSource = "manual",
                                ).withPersonalFieldsFrom(row.mapping)
                            )
                            mappedPackages += row.installed.packageName
                            updated++
                            bulkFuzzyProgress = BulkFuzzyProgress(index + 1, items.size, updated, notFound)
                        }
                    } finally {
                        bulkFuzzyProgress = null
                    }
                    unmappedReviewMatches = unmappedReviewMatches.filterNot { it.row.installed.packageName in mappedPackages }
                    snackbarMsg = "Closest fuzzy: $updated unmapped games mapped, $notFound without suggestions"
                }
            },
        )
    }

    bulkFuzzyProgress?.let { p ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Selecting closest fuzzy matches") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val ratio = if (p.total > 0) p.current.toFloat() / p.total else 0f
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${p.current} of ${p.total} checked")
                    Text("${p.updated} mapped, ${p.notFound} without suggestions", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { },
        )
    }

    if (unmappedReviewLoading) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Preparing unmapped review") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Finding current unmapped games and fuzzy suggestions...")
                }
            },
            confirmButton = { },
        )
    }

    if (unmatchedFoundPromptOpen) {
        AlertDialog(
            onDismissRequest = { unmatchedFoundPromptOpen = false },
            title = { Text("Unmatched games found") },
            text = {
                Text(
                    buildString {
                        if (alreadyMatchedReviewMatches.isNotEmpty()) {
                            append("${alreadyMatchedReviewMatches.size} entries match games you already mapped before. ")
                        }
                        if (unmappedReviewMatches.isNotEmpty()) {
                            append("${unmappedReviewMatches.size} entries still need review. ")
                        }
                        append("Open the review window to keep already matched games or handle new/unmatched ones?")
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    unmatchedFoundPromptOpen = false
                    unmappedReviewOpen = true
                }) { Text("Review now") }
            },
            dismissButton = {
                TextButton(onClick = { unmatchedFoundPromptOpen = false }) { Text("Later") }
            },
        )
    }

    fullSizeImageUrl?.let { url ->
        FullSizeImageDialog(url = url, onDismiss = { fullSizeImageUrl = null })
    }

    if (aboutOpen) {
        AboutDialog(
            onDismiss = { aboutOpen = false },
            onOpenHelp = {
                runCatching {
                    openExternalUrl(context, AppConfig.DEFAULT_HELP_URL)
                }.onFailure { snackbarMsg = "Could not open help: ${it.message}" }
            },
            onShareApp = {
                runCatching {
                    shareAdultGameManager(context, appConfig.effectiveSupportThreadUrl)
                }.onFailure { snackbarMsg = "Could not open share sheet: ${it.message}" }
            },
            onReportIssue = {
                runCatching {
                    openExternalUrl(context, appConfig.issueReportUrl)
                }.onFailure { snackbarMsg = "Could not open issue page: ${it.message}" }
            },
            onOpenSupport = {
                runCatching {
                    openExternalUrl(context, appConfig.effectiveSupportThreadUrl)
                }.onFailure { snackbarMsg = "Could not open browser: ${it.message}" }
            },
        )
    }

    if (topStatusOpen) {
        AlertDialog(
            onDismissRequest = { topStatusOpen = false },
            title = { Text("List status") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(topStatusText)
                    Text(
                        "This summarizes the current list, JoiPlay storage scans, and detected save folders. Use filters and menu actions to change what appears.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { topStatusOpen = false }) { Text("OK") }
            },
        )
    }

    if (diagnosticsSummaryOpen) {
        val summary = remember(apps, rows, hasUsage, appConfig) {
            buildDiagnosticsSummary(
                context = context,
                appCount = apps.size,
                visibleCount = rows.size,
                hasUsage = hasUsage,
                hasAllFiles = hasAllFilesAccess(),
                config = appConfig,
            )
        }
        AlertDialog(
            onDismissRequest = { diagnosticsSummaryOpen = false },
            title = { Text("Diagnostics summary") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Copy this when reporting a problem. It contains app/device/version state, not logs or personal files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(summary))
                    snackbarMsg = "Diagnostics summary copied"
                    diagnosticsSummaryOpen = false
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { diagnosticsSummaryOpen = false }) { Text("Close") } },
        )
    }

    // Foreground "Refresh from catalog" progress dialog with ETA + cancel.
    refreshProgress?.let { p ->
        AlertDialog(
            onDismissRequest = { /* not dismissible by tap-outside */ },
            title = { Text("Refreshing from catalog") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ratio = if (p.total > 0) p.current.toFloat() / p.total else 0f
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${p.current} of ${p.total} scanned  \u2022  ${p.matched} matched",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val eta = p.etaSecondsRemaining
                    Text(
                        when {
                            eta == null -> "Estimating remaining time\u2026"
                            eta <= 0    -> "Wrapping up\u2026"
                            eta < 60    -> "About ${eta}s remaining"
                            else        -> "About ${eta / 60}m ${eta % 60}s remaining"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { refreshCancelled = true },
                    enabled = !refreshCancelled,
                ) { Text(if (refreshCancelled) "Cancelling\u2026" else "Cancel") }
            }
        )
    }

    if (refreshCancelledNote) {
        AlertDialog(
            onDismissRequest = { refreshCancelledNote = false },
            title = { Text("Refresh cancelled") },
            text = {
                Text(
                    "The catalog refresh was cancelled. You can run it again any time from " +
                            "Menu \u2192 Refresh from catalog.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { refreshCancelledNote = false }) { Text("OK") }
            }
        )
    }

    if (f95MigrationPromptOpen) {
        AlertDialog(
            onDismissRequest = { /* keep first-run flow explicit */ },
            title = { Text(if (f95MigrationError == null) "Import from F95 Updater?" else "Import failed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "F95 Updater is installed on this device. Adult Game Manager can import your mappings, hidden games, JoiPlay backup data, version overrides, and personal tracking fields.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    f95MigrationError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "This reads only the local backup export exposed by F95 Updater. It does not contact F95Zone or upload your data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            F95MigrationImport.importFromInstalledF95Updater(context.applicationContext, replace = false)
                        }.onSuccess { summary ->
                            f95MigrationError = null
                            f95MigrationPromptOpen = false
                            firstRunHintOpen = true
                            snackbarMsg = "Imported ${summary.mappings} mappings and ${summary.joiplayGames} JoiPlay games from F95 Updater"
                        }.onFailure {
                            AppLog.e("Migration", "F95 Updater import failed", it)
                            f95MigrationError = it.message ?: "Could not import from F95 Updater."
                        }
                    }
                }) { Text(if (f95MigrationError == null) "Import" else "Try again") }
            },
            dismissButton = {
                TextButton(onClick = {
                    f95MigrationError = null
                    f95MigrationPromptOpen = false
                    firstRunHintOpen = true
                }) { Text("Skip") }
            },
        )
    }

    if (firstRunHintOpen) {
        AlertDialog(
            onDismissRequest = { /* not dismissible by tapping outside during first-run */ },
            title = { Text(if (joiplayImportError != null) "Couldn't read that file" else "Welcome to Adult Game Manager!") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (joiplayImportError != null) {
                        Text(
                            "That file isn't a valid JoiPlay backup (.joiback).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Details: ${joiplayImportError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Try picking a different file, or skip for now.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            "Track adult game updates across installed APKs, JoiPlay games, and multiple catalog sources.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (joiPlayInstalled) {
                            Spacer(Modifier.height(4.dp))
                            Text("JoiPlay detected on your device.", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "To track your JoiPlay games:\n" +
                                        "1. Open JoiPlay \u2192 Settings \u2192 Backup \u2192 Backup my games\n" +
                                        if (hasAllFilesAccess()) {
                                            "2. Tap \u201CImport JoiPlay backup\u201D below and pick the .joiback file\n"
                                        } else {
                                            "2. Tap \u201CImport JoiPlay backup\u201D below and grant access to the backup folder\n"
                                        } +
                                        "3. Pick the .joiback file in Adult Game Manager's picker\n" +
                                        "4. We'll match each game to known catalog entries automatically",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("Quick tips:", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "\u2022 Android-installed adult games are detected automatically\n" +
                                        "\u2022 Search by title or by tag (type tag:harem to filter)\n" +
                                        "\u2022 Tap a row to expand details / copy text\n" +
                                        "\u2022 Advanced file tools are optional and ask before using broader permissions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "You can browse the catalog without extra permissions. APK installs, archive extraction, JoiPlay folder tools, and last-used sorting ask only when you use those features.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "After you continue, we'll match visible installed apps against the local catalog. You can review or correct matches later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (joiPlayInstalled) {
                    TextButton(onClick = {
                        joiplayImportError = null
                        firstRunHintOpen = false
                        askForJoiPlayBackupFolderAccess(firstRun = true)
                    }) { Text(if (joiplayImportError != null) "Pick another file" else "Import JoiPlay backup") }
                } else {
                    TextButton(onClick = {
                        firstRunHintOpen = false
                        firstRunRefreshPending = true
                    }) { Text("Continue") }
                }
            },
            dismissButton = if (joiPlayInstalled) {
                {
                    TextButton(onClick = {
                        joiplayImportError = null
                        firstRunHintOpen = false
                        firstRunRefreshPending = true
                    }) { Text("Skip") }
                }
            } else null,
        )
    }

    joiplayBackupAccessDisclosureFirstRun?.let { firstRun ->
        AlertDialog(
            onDismissRequest = {
                joiplayBackupAccessDisclosureFirstRun = null
                if (firstRun) firstRunHintOpen = true
            },
            title = { Text("Allow access to your JoiPlay backup folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Adult Game Manager needs read access to the folder that contains your JoiPlay .joiback backup so it can import your game list.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "On the next screen, choose the folder where JoiPlay saved the backup, then tap Allow. After that, Adult Game Manager will show its own picker with the .joiback files in that folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "This does not require All files access.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    joiplayBackupAccessDisclosureFirstRun = null
                    joiplayBackupPickerFirstRun = firstRun
                    joiplayBackupFolderPicker.launch(null)
                }) { Text("Open folder picker") }
            },
            dismissButton = {
                TextButton(onClick = {
                    joiplayBackupAccessDisclosureFirstRun = null
                    if (firstRun) firstRunHintOpen = true
                }) { Text("Cancel") }
            },
        )
    }

    if (importBackupAccessDisclosureOpen) {
        AlertDialog(
            onDismissRequest = { importBackupAccessDisclosureOpen = false },
            title = { Text("Allow access to your backup folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Adult Game Manager needs read access to the folder that contains your backup JSON file so it can restore mappings, hidden apps, and saved JoiPlay data.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "On the next screen, choose the folder containing the JSON backup, then tap Allow. After that, Adult Game Manager will show its own picker with JSON files in that folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "This does not require All files access.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    importBackupAccessDisclosureOpen = false
                    mappingBackupFolderPicker.launch(null)
                }) { Text("Open folder picker") }
            },
            dismissButton = {
                TextButton(onClick = { importBackupAccessDisclosureOpen = false }) { Text("Cancel") }
            },
        )
    }

    // Blocking dialog shown while a JoiPlay backup is being imported.
    if (joiplayImportBusy) {
        AlertDialog(
            onDismissRequest = { /* not dismissible */ },
            title = { Text("Importing JoiPlay backup\u2026") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("This usually takes a few seconds.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }

    matchResearchProgress?.let { progress ->
        MatchResearchProgressDialog(progress = progress)
    }

    // Progress dialog shown while running JoiPlay version detection.
    joiPlayDetecting?.let { row ->
        AlertDialog(
            onDismissRequest = { /* not cancellable */ },
            title = { Text("Detecting version…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(row.installed.label, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }

    // Version conflict resolution dialog.
    joiPlayVersionDialog?.let { (row, candidates, _) ->
        AlertDialog(
            onDismissRequest = { joiPlayVersionDialog = null },
            title = { Text("Pick installed version") },
            text = {
                Column {
                    Text(
                        "Detected multiple versions for ${row.installed.label}. Which is correct?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    candidates.forEach { c ->
                        TextButton(
                            onClick = {
                                joiPlayVersionDialog = null
                                scope.launch {
                                    setManualInstalledVersion(row, c.version)
                                    val android = InstalledAppsScanner.scan(context)
                                    val backup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                                    val folder = JoiPlayScanner.scan(context.applicationContext)
                                    apps = (android + mergeJoiPlaySources(backup, folder))
                                        .sortedBy { it.label.lowercase() }
                                    // Now run catalog update check.
                                    if (!row.mapping?.f95Url.isNullOrBlank()) {
                                        checkOne(row, scraper, repo)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "${c.version}  —  ${c.source}",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                c.detail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { joiPlayVersionDialog = null }) { Text("Cancel") }
            }
        )
    }

    manualVersionTarget?.let { row ->
        val catalogVersion = row.mapping?.lastSeenVersion?.trim()?.ifBlank { null }
        var versionText by remember(row.installed.packageName, row.mapping?.manualInstalledVersion) {
            mutableStateOf(
                row.mapping?.manualInstalledVersion
                    ?.takeIf { hasActiveManualInstalledVersion(row.installed, row.mapping) }
                    ?: effectiveInstalledVersion(row.installed, row.mapping)
            )
        }
        AlertDialog(
            onDismissRequest = { manualVersionTarget = null },
            title = { Text("Set installed version") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.installed.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Detected now: ${row.installed.versionName.ifBlank { "unknown" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = versionText,
                        onValueChange = { versionText = it },
                        label = { Text("Installed version") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (catalogVersion != null) {
                        OutlinedButton(
                            onClick = { versionText = catalogVersion },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Use catalog version: $catalogVersion")
                        }
                    }
                    Text(
                        "This override is used like installed-version evidence and is ignored after the app's installed evidence changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleaned = versionText.trim()
                        if (cleaned.isNotBlank()) {
                            scope.launch { setManualInstalledVersion(row, cleaned) }
                            manualVersionTarget = null
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = row.mapping?.manualInstalledVersion?.isNotBlank() == true,
                        onClick = {
                            scope.launch {
                                val existing = repo.get()[row.installed.packageName]
                                if (existing != null) {
                                    repo.upsert(
                                        existing.copy(
                                            manualInstalledVersion = "",
                                            manualInstalledVersionFingerprint = "",
                                        )
                                    )
                                }
                                snackbarMsg = "Cleared installed-version override for ${row.installed.label}"
                            }
                            manualVersionTarget = null
                        }
                    ) { Text("Clear") }
                    TextButton(onClick = { manualVersionTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    manualDateTarget?.let { target ->
        val row = target.first
        val catalogGame = target.second
        val catalogDate = catalogInstalledDateCandidate(catalogGame)
        val activeManual = hasActiveManualInstalledDate(row.installed, row.mapping)
        val shownDate = effectiveInstalledDate(row.installed, row.mapping)
        AlertDialog(
            onDismissRequest = { manualDateTarget = null },
            title = { Text("Set installed date") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.installed.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Current installed date: ${fmtDateTime(shownDate)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    catalogDate?.let { (dateMs, source) ->
                        OutlinedButton(
                            onClick = {
                                scope.launch { setManualInstalledDate(row, dateMs, source) }
                                manualDateTarget = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Use catalog date: ${fmtDate(dateMs)}")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val initial = shownDate.takeIf { it > 0L } ?: System.currentTimeMillis()
                            val cal = Calendar.getInstance().apply { timeInMillis = initial }
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    val picked = Calendar.getInstance().apply {
                                        clear()
                                        set(year, month, day, 12, 0, 0)
                                    }.timeInMillis
                                    scope.launch { setManualInstalledDate(row, picked, "manual date") }
                                    manualDateTarget = null
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH),
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Pick date manually")
                    }
                    Text(
                        "This override is used for installed-date comparisons and is ignored after the app's installed evidence changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                Row {
                    TextButton(
                        enabled = activeManual,
                        onClick = {
                            scope.launch {
                                val existing = repo.get()[row.installed.packageName]
                                if (existing != null) {
                                    repo.upsert(
                                        existing.copy(
                                            manualInstalledDate = 0L,
                                            manualInstalledDateFingerprint = "",
                                            manualInstalledDateSource = "",
                                        )
                                    )
                                }
                                snackbarMsg = "Cleared installed-date override for ${row.installed.label}"
                            }
                            manualDateTarget = null
                        }
                    ) { Text("Clear") }
                    TextButton(onClick = { manualDateTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    // Auto-prompt for SAF when deleting a JoiPlay game for the first time.
    joiPlayGrantAskFor?.let { row ->
        AlertDialog(
            onDismissRequest = { joiPlayGrantAskFor = null },
            title = { Text("Grant folder access?") },
            text = {
                Column {
                    Text(
                        "To delete \"${row.installed.label}\" from disk, this app needs permission " +
                                "to the JoiPlay games folder.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "On the next screen, navigate to your JoiPlay games root (usually " +
                                "Internal storage → JoiPlay → games) and tap \"Use this folder\". " +
                                "This grant is only used after you confirm a delete, and only for the selected JoiPlay game folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { joiplayPicker.launch(null) }) { Text("Grant access") }
            },
            dismissButton = {
                TextButton(onClick = { joiPlayGrantAskFor = null }) { Text("Cancel") }
            }
        )
    }

    var joiPlayDeleting by remember { mutableStateOf<String?>(null) }
    joiPlayDeleteConfirm?.let { row ->
        AlertDialog(
            onDismissRequest = { joiPlayDeleteConfirm = null },
            title = { Text("Delete JoiPlay game?") },
            text = {
                Column {
                    Text("This will permanently delete the folder for:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(row.installed.label, fontWeight = FontWeight.SemiBold)
                    row.installed.storagePath?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("This cannot be undone.", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "If you are unsure, cancel and make a backup in JoiPlay first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = row.installed.storageFolderName
                    if (name.isNullOrBlank()) {
                        snackbarMsg = "No folder name to delete"
                        joiPlayDeleteConfirm = null
                    } else {
                        // Close the confirm dialog and switch to a non-dismissible progress
                        // dialog. SAF directory-delete can take several seconds for large game
                        // folders; the previous UI sat frozen with no feedback.
                        joiPlayDeleteConfirm = null
                        joiPlayDeleting = row.installed.label
                        scope.launch {
                            val ok = JoiPlayScanner.deleteFolder(
                                context.applicationContext, name,
                                storagePath = row.installed.storagePath,
                            )
                            if (ok) {
                                // Persist deletion so backup-cached entries don't reappear on launch.
                                row.installed.joiPlayGameId?.let {
                                    JoiPlayBackupReader.markDeleted(context.applicationContext, it)
                                }
                                val android = InstalledAppsScanner.scan(context)
                                val backup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                                val folder = JoiPlayScanner.scan(context.applicationContext)
                                apps = (android + mergeJoiPlaySources(backup, folder)).sortedBy { it.label.lowercase() }
                                snackbarMsg = "Deleted ${row.installed.label}"
                            } else {
                                snackbarMsg = "Delete failed — re-grant JoiPlay games folder in menu"
                            }
                            joiPlayDeleting = null
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { joiPlayDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }

    joiPlayDeleting?.let { label ->
        AlertDialog(
            onDismissRequest = { /* not dismissible while in-flight */ },
            title = { Text("Deleting game") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(label, fontWeight = FontWeight.SemiBold)
                        Text("Removing folder…", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (autoBackupDialogOpen) {
        AlertDialog(
            onDismissRequest = { autoBackupDialogOpen = false },
            title = { Text("Auto-backups") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (autoBackupList.isEmpty()) {
                        Text(
                            "No auto-backups yet. One will be created automatically the next time the app is updated.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            "Auto-backups are created on every app upgrade. Restoring will overwrite your current mappings, hidden list, and JoiPlay backup snapshot — but a fresh \"prerestore\" backup is taken first so you can roll back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        for (e in autoBackupList) {
                            Surface(
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (e.versionCode > 0) "From v${e.versionCode} \u2022 ${e.displayDate}"
                                            else e.displayDate,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "${fmtSize(e.sizeBytes)} \u2022 ${e.file.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { autoBackupConfirmRestore = e }) { Text("Restore") }
                                    IconButton(onClick = {
                                        scope.launch {
                                            AutoBackupManager.delete(e)
                                            autoBackupList = AutoBackupManager.list(context.applicationContext)
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete this backup",
                                             modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { autoBackupDialogOpen = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        val path = AutoBackupManager.writeBackup(
                            context.applicationContext, repo, label = "manual"
                        )
                        autoBackupList = AutoBackupManager.list(context.applicationContext)
                        snackbarMsg = "Backup saved: ${java.io.File(path).name}"
                    }
                }) { Text("Snapshot now") }
            },
        )
    }

    autoBackupConfirmRestore?.let { entry ->
        AlertDialog(
            onDismissRequest = { autoBackupConfirmRestore = null },
            title = { Text("Restore backup?") },
            text = {
                Column {
                    Text(
                        if (entry.versionCode > 0) "Backup taken from v${entry.versionCode} on ${entry.displayDate}."
                        else "Backup from ${entry.displayDate}.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This will REPLACE current mappings, hidden list, JoiPlay backup snapshot, and version overrides. A fresh \"prerestore\" backup will be saved first so you can undo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = entry
                    autoBackupConfirmRestore = null
                    autoBackupDialogOpen = false
                    scope.launch {
                        val summary = runCatching {
                            AutoBackupManager.restore(context.applicationContext, repo, e)
                        }.getOrElse {
                            snackbarMsg = "Restore failed: ${it.message}"
                            return@launch
                        }
                        // Refresh in-memory apps list so restored hidden/mappings take effect.
                        val android = InstalledAppsScanner.scan(context)
                        val backup = JoiPlayBackupReader.asInstalledApps(context.applicationContext)
                        val folder = JoiPlayScanner.scan(context.applicationContext)
                        apps = (android + mergeJoiPlaySources(backup, folder))
                            .sortedBy { it.label.lowercase() }
                        snackbarMsg = "Restored: ${summary.mappings} mappings, ${summary.hidden} hidden, " +
                            "${summary.joiplayGames} JoiPlay games"
                    }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { autoBackupConfirmRestore = null }) { Text("Cancel") }
            },
        )
    }

    if (joiplayBackupFilePickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Pick a JoiPlay backup",
            allowedExtensions = setOf("joiback"),
            onCancel = {
                val firstRun = joiplayBackupPickerFirstRun
                joiplayBackupPickerFirstRun = false
                joiplayBackupFilePickerOpen = false
                if (firstRun) firstRunHintOpen = true
            },
            onPick = { file ->
                val firstRun = joiplayBackupPickerFirstRun
                joiplayBackupPickerFirstRun = false
                joiplayBackupFilePickerOpen = false
                rememberPickerDir(file)
                startJoiPlayBackupImport(Uri.fromFile(file), firstRun)
            },
        )
    }
    if (backupScopedPickerOpen) {
        val rootUri = backupScopedRootUri
        if (rootUri != null) {
            ScopedFilePickerDialog(
                rootUri = rootUri,
                title = "Pick a JoiPlay backup",
                allowedExtensions = setOf("joiback"),
                onCancel = {
                    val firstRun = joiplayBackupPickerFirstRun
                    joiplayBackupPickerFirstRun = false
                    backupScopedPickerOpen = false
                    if (firstRun) firstRunHintOpen = true
                },
                onPick = { uri, _ ->
                    val firstRun = joiplayBackupPickerFirstRun
                    joiplayBackupPickerFirstRun = false
                    backupScopedPickerOpen = false
                    startJoiPlayBackupImport(uri, firstRun)
                },
            )
        }
    }
    if (exportBackupPickerOpen) {
        FolderPickerDialog(
            initialPath = pickerInitialPath,
            onCancel = { exportBackupPickerOpen = false },
            onPick = { folderPath ->
                exportBackupPickerOpen = false
                pickerInitialPath = folderPath
                scope.launch {
                    JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, folderPath)
                    runCatching {
                        val outFile = java.io.File(folderPath, "adult-game-manager-backup.json")
                        outFile.writeText(repo.exportJson())
                        snackbarMsg = "Exported mappings to ${outFile.absolutePath}"
                    }.onFailure {
                        snackbarMsg = "Export failed: ${it.message}"
                    }
                }
            },
        )
    }
    if (unusedFolderRootPickerOpen) {
        FolderPickerDialog(
            initialPath = pickerInitialPath,
            onCancel = { unusedFolderRootPickerOpen = false },
            onPick = { folderPath ->
                unusedFolderRootPickerOpen = false
                unusedFolderRootPath = folderPath
                pickerInitialPath = folderPath
                AppLog.i("JoiPlayUnused", "Selected report root='$folderPath'")
                scope.launch { JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, folderPath) }
                unusedFolderBackupPickerOpen = true
            },
        )
    }
    if (unusedFolderBackupPickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Pick JoiPlay backup for comparison",
            allowedExtensions = setOf("joiback"),
            onCancel = { unusedFolderBackupPickerOpen = false },
            onPick = { file ->
                unusedFolderBackupPickerOpen = false
                rememberPickerDir(file)
                val rootPath = unusedFolderRootPath
                if (rootPath.isNullOrBlank()) {
                    snackbarMsg = "Pick the root folder first."
                    return@FilePickerDialog
                }
                unusedFolderScanning = true
                unusedFolderProgress = JoiPlayUnusedFolderReporter.Progress(stage = "Opening JoiPlay backup")
                scope.launch {
                    runCatching {
                        AppLog.i(
                            "JoiPlayUnused",
                            "Selected report backup='${file.absolutePath}' size=${file.length()} root='$rootPath'"
                        )
                        val games = JoiPlayBackupReader.readGames(context.applicationContext, file)
                        unusedFolderProgress = JoiPlayUnusedFolderReporter.Progress(
                            stage = "Parsed backup",
                            current = games.size,
                            total = games.size,
                            detail = "${games.size} games",
                        )
                        AppLog.i("JoiPlayUnused", "Parsed selected backup games=${games.size}")
                        JoiPlayUnusedFolderReporter.buildReport(java.io.File(rootPath), games) { progress ->
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                unusedFolderProgress = progress
                            }
                        }
                    }.onSuccess { report ->
                        unusedFolderReport = report
                        AppLog.i(
                            "JoiPlayUnused",
                            "Report shown backupGames=${report.backupGameCount} underRoot=${report.backupGamesUnderRoot} " +
                                "inUseFolders=${report.inUseFolders.size} probablyUnusedFolders=${report.probablyUnusedFolders.size} " +
                                "missingReferencedFolders=${report.missingReferencedFolders.size} " +
                                "scannedParents=${report.scannedParentPaths.size}"
                        )
                        snackbarMsg = "Found ${report.probablyUnusedFolders.size} probably unused folders"
                    }.onFailure { t ->
                        AppLog.e("JoiPlayUnused", "Scan failed", t)
                        snackbarMsg = "Unused-folder scan failed: ${t.message}"
                    }
                    unusedFolderScanning = false
                    unusedFolderProgress = null
                }
            },
        )
    }
    if (unusedFolderSavePickerOpen) {
        FolderPickerDialog(
            initialPath = pickerInitialPath,
            onCancel = { unusedFolderSavePickerOpen = false },
            onPick = { folderPath ->
                unusedFolderSavePickerOpen = false
                pickerInitialPath = folderPath
                val report = unusedFolderReport
                if (report == null) {
                    snackbarMsg = "No report to save."
                    return@FolderPickerDialog
                }
                scope.launch {
                    JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, folderPath)
                    runCatching {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
                        val outFile = java.io.File(folderPath, "joiplay-unused-folders-$stamp.txt")
                        outFile.writeText(report.asText())
                        outFile.absolutePath
                    }.onSuccess { path ->
                        snackbarMsg = "Saved report to $path"
                    }.onFailure { t ->
                        snackbarMsg = "Save failed: ${t.message}"
                    }
                }
            },
        )
    }
    if (unusedFolderScanning) {
        JoiPlayUnusedFolderProgressDialog(progress = unusedFolderProgress)
    }
    unusedFolderReport?.let { report ->
        JoiPlayUnusedFolderReportDialog(
            report = report,
            onDismiss = { unusedFolderReport = null },
            onCopy = {
                clipboard.setText(AnnotatedString(report.asText()))
                snackbarMsg = "Copied report to clipboard"
            },
            onSave = { unusedFolderSavePickerOpen = true },
        )
    }
    if (renPySaveLocationsOpen) {
        RenPySaveLocationsDialog(
            locations = renPySaveLocations,
            lastScannedAt = renPySaveLastScannedAt,
            associationActionsEnabled = !renPySaveScanning,
            onDismiss = { renPySaveLocationsOpen = false },
            onAssociate = { renPySaveAssociationPicker = it },
            onClearAssociation = { clearRenPyManualAssociation(it) },
            onCopy = {
                clipboard.setText(AnnotatedString(renPySaveLocations.asRenPySaveReportText(renPySaveLastScannedAt)))
                snackbarMsg = "Copied Ren'Py save locations"
            },
        )
    }
    renPySaveAssociationPicker?.let { location ->
        RenPySaveAssociationPickerDialog(
            location = location,
            apps = apps,
            onDismiss = { renPySaveAssociationPicker = null },
            onAssociate = { app -> manuallyAssociateRenPyLocation(location, app) },
        )
    }
    renPySaveEditorTarget?.let { row ->
        val locations = renPySaveLocations.filter { it.associatedPackageName == row.installed.packageName }
        RenPySaveEditorDialog(
            app = row.installed,
            locations = locations,
            onDismiss = { renPySaveEditorTarget = null },
        )
    }
    renPyAddFolderTarget?.let { row ->
        FolderPickerDialog(
            initialPath = row.installed.storagePath ?: android.os.Environment.getExternalStorageDirectory().absolutePath,
            onCancel = { renPyAddFolderTarget = null },
            onPick = { folder ->
                renPyAddFolderTarget = null
                addRenPySaveFolderToGame(row, folder)
            },
        )
    }
    if (rpgmSaveLocationsOpen) {
        RpgmSaveLocationsDialog(
            locations = rpgmSaveLocations,
            lastScannedAt = rpgmSaveLastScannedAt,
            associationActionsEnabled = !rpgmSaveScanning,
            onDismiss = { rpgmSaveLocationsOpen = false },
            onAssociate = { rpgmSaveAssociationPicker = it },
            onClearAssociation = { clearRpgmManualAssociation(it) },
        )
    }
    rpgmSaveAssociationPicker?.let { location ->
        RpgmSaveAssociationPickerDialog(
            location = location,
            apps = apps,
            onDismiss = { rpgmSaveAssociationPicker = null },
            onAssociate = { app -> manuallyAssociateRpgmLocation(location, app) },
        )
    }
    rpgmSaveViewerTarget?.let { row ->
        RpgmSaveViewerDialog(
            app = row.installed,
            locations = rpgmSaveLocations.filter { it.associatedPackageName == row.installed.packageName },
            onDismiss = { rpgmSaveViewerTarget = null },
        )
    }
    if (saveBackupBrowserOpen) {
        SaveBackupBrowserDialog(
            renPyLocations = renPySaveLocations,
            rpgmLocations = rpgmSaveLocations,
            onDismiss = { saveBackupBrowserOpen = false },
        )
    }
    rpgmAddFolderTarget?.let { row ->
        FolderPickerDialog(
            initialPath = row.installed.storagePath ?: android.os.Environment.getExternalStorageDirectory().absolutePath,
            onCancel = { rpgmAddFolderTarget = null },
            onPick = { folder ->
                rpgmAddFolderTarget = null
                addRpgmSaveFolderToGame(row, folder)
            },
        )
    }
    if (importBackupPickerOpen) {
        val rootUri = importBackupScopedRootUri ?: backupScopedRootUri
        if (rootUri != null) {
            ScopedFilePickerDialog(
                rootUri = rootUri,
                title = "Pick a backup to import",
                allowedExtensions = setOf("json"),
                onCancel = { importBackupPickerOpen = false },
                onPick = { uri, _ ->
                    importBackupPickerOpen = false
                    startMappingBackupImport(uri)
                },
            )
        } else {
            importBackupPickerOpen = false
            importBackupAccessDisclosureOpen = true
        }
    }
    if (installWarningOpen) {
        JoiPlayInstallWarningDialog(
            onDismiss = { installWarningOpen = false },
            onContinue = { dontShow ->
                installWarningOpen = false
                scope.launch {
                    if (dontShow) JoiPlaySettingsStore.setInstallWarningDismissed(context.applicationContext, true)
                    installPickerOpen = true
                }
            },
        )
    }
    if (installPickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Pick a file to install in JoiPlay",
            // Includes launch files AND archives. If the user picks an archive we route
            // into the extract flow first, then prompt for the launch file inside.
            allowedExtensions = InstallRouting.joiPlayPickerExtensions,
            onCancel = { installPickerOpen = false },
            onPick = { file ->
                installPickerOpen = false
                rememberPickerDir(file)
                when (InstallRouting.routeJoiPlayPick(file.name)) {
                    InstallRouting.JoiPlayPickRoute.InspectArchiveForUpgrade -> {
                        prepareJoiPlayArchiveInstall(file)
                    }
                    InstallRouting.JoiPlayPickRoute.LaunchFile -> {
                        scope.launch {
                            val uri = Uri.fromFile(file)
                            val intent = JoiPlayInstaller.buildIntent(context, uri)
                            if (intent == null) {
                                snackbarMsg = "JoiPlay can't handle that file (see logs)."
                            } else {
                                runCatching { joiplayResultLauncher.launch(intent) }
                                    .onFailure { snackbarMsg = "JoiPlay launch failed: ${it.message}" }
                            }
                        }
                    }
                    InstallRouting.JoiPlayPickRoute.Unsupported -> {
                        snackbarMsg = "JoiPlay can't handle that file type."
                    }
                }
            },
        )
    }
    archiveAnalysisInProgress?.let { file ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Analyzing archive") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Checking whether this is an update for an installed JoiPlay game...")
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = { },
        )
    }
    upgradePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { upgradePrompt = null },
            title = { Text("Upgrade an existing JoiPlay game?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This archive looks like \"${prompt.analysis.displayName}\". If it is an update, choose the existing JoiPlay game to replace. The current folder will be renamed to a backup first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    prompt.matches.forEach { app ->
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppLog.i(
                                        "JoiPlayUpgrade",
                                        "User selected upgrade target '${app.label}' for archive ${prompt.archive.name}"
                                    )
                                    upgradePrompt = null
                                    upgradeFlow.start(prompt.archive, app)
                                },
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(app.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    app.storagePath ?: app.storageFolderName ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val archive = prompt.archive
                    upgradePrompt = null
                    prepareArchiveExtract(archive, apkMode = false)
                }) { Text("Install as new") }
            },
            dismissButton = {
                TextButton(onClick = { upgradePrompt = null }) { Text("Cancel") }
            },
        )
    }
    if (apkInstallPickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Pick an APK or archive to install",
            allowedExtensions = InstallRouting.apkPickerExtensions,
            onCancel = { apkInstallPickerOpen = false },
            onPick = { file ->
                apkInstallPickerOpen = false
                rememberPickerDir(file)
                when (InstallRouting.routeApkPick(file.name)) {
                    InstallRouting.ApkPickRoute.InstallApk -> {
                        // Direct APK install — show confirm dialog with optional delete-source.
                        apkInstallConfirm = file
                    }
                    InstallRouting.ApkPickRoute.ExtractArchiveForApk -> {
                        // Archive — route into extract flow with APK target mode.
                        prepareArchiveExtract(file, apkMode = true)
                    }
                    InstallRouting.ApkPickRoute.Unsupported -> {
                        snackbarMsg = "Can't install that file type."
                    }
                }
            },
        )
    }
    apkInstallConfirm?.let { apk ->
        val canDeleteSource = remember(apk) { runCatching { apk.canWrite() }.getOrDefault(false) }
        var deleteAfter by remember(apk) { mutableStateOf(false) }
        LaunchedEffect(apk) {
            deleteAfter = JoiPlaySettingsStore.deleteAfterInstall(context)
        }
        AlertDialog(
            onDismissRequest = { apkInstallConfirm = null },
            title = { Text("Install APK?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("From", style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(apk.absolutePath, style = MaterialTheme.typography.bodySmall,
                         fontWeight = FontWeight.SemiBold)
                    Text(
                        "Only install APKs from sources you trust. Android's system installer will open and ask for confirmation before anything is installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (canDeleteSource) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    deleteAfter = !deleteAfter
                                    scope.launch { JoiPlaySettingsStore.setDeleteAfterInstall(context, deleteAfter) }
                                },
                        ) {
                            Checkbox(checked = deleteAfter, onCheckedChange = {
                                deleteAfter = it
                                scope.launch { JoiPlaySettingsStore.setDeleteAfterInstall(context, it) }
                            })
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Delete the APK after a successful install",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = apk
                    val deleteSrc = if (canDeleteSource && deleteAfter) target else null
                    apkInstallConfirm = null
                    val pkgName = runCatching {
                        context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.packageName
                    }.getOrNull()
                    AppLog.i("ApkInstall", "Launching session install: pkg=$pkgName file=${target.name} deleteSrc=${deleteSrc != null}")
                    apkPostInstall = ApkPostInstall(pkgName, deleteSrc)
                    apkInstalling = true
                    snackbarMsg = "Preparing install…"
                    scope.launch {
                        runCatching { ApkInstaller.installApk(context, target, pkgName) }
                            .onFailure {
                                apkPostInstall = null
                                apkInstalling = false
                                snackbarMsg = "Installer failed: ${it.message}"
                                AppLog.e("ApkInstall", "Session install failed", it)
                            }
                    }
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { apkInstallConfirm = null }) { Text("Cancel") }
            },
        )
    }
    if (joiplaySettingsOpen) {
        JoiPlaySettingsDialog(
            onDismiss = { joiplaySettingsOpen = false },
            onSourceChange = {}, onDestChange = {},
        )
    }
    pendingArchiveDestination?.let { pending ->
        FolderPickerDialog(
            initialPath = pickerInitialPath,
            onCancel = { pendingArchiveDestination = null },
            onPick = { absolutePath ->
                val dir = java.io.File(absolutePath)
                if (!dir.isDirectory || !dir.canWrite()) {
                    snackbarMsg = "Can't write to that destination folder."
                    return@FolderPickerDialog
                }
                pendingArchiveDestination = null
                pickerInitialPath = dir.absolutePath
                scope.launch { JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, dir.absolutePath) }
                if (pending.apkMode) apkExtractMode = true
                extractConfirm = pending.archive to ArchiveExtractor.ExtractRoot.FileRoot(dir)
            },
        )
    }
    extractConfirm?.let { (file, root) ->
        val canDeleteSource = remember(file) { runCatching { file.canWrite() }.getOrDefault(false) }
        var deleteAfter by remember(file) { mutableStateOf(false) }
        LaunchedEffect(file) {
            deleteAfter = JoiPlaySettingsStore.deleteAfterInstall(context)
        }
        AlertDialog(
            onDismissRequest = { extractConfirm = null },
            title = { Text("Extract archive?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text("From", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(file.absolutePath, style = MaterialTheme.typography.bodySmall,
                             fontWeight = FontWeight.SemiBold)
                    }
                    Column {
                        Text("To", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when (root) {
                                is ArchiveExtractor.ExtractRoot.FileRoot -> root.file.absolutePath
                                is ArchiveExtractor.ExtractRoot.Saf -> root.doc.uri.toString()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "A subfolder will be created here for the extracted game.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (canDeleteSource) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    deleteAfter = !deleteAfter
                                    scope.launch { JoiPlaySettingsStore.setDeleteAfterInstall(context, deleteAfter) }
                                },
                        ) {
                            Checkbox(checked = deleteAfter, onCheckedChange = {
                                deleteAfter = it
                                scope.launch { JoiPlaySettingsStore.setDeleteAfterInstall(context, it) }
                            })
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Delete the archive after a successful extraction",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val src = if (canDeleteSource && deleteAfter) file else null
                    extractConfirm = null
                    extractFlow.start(Uri.fromFile(file), root, deleteSourceOnSuccess = src)
                }) { Text("Extract") }
            },
            dismissButton = {
                TextButton(onClick = { extractConfirm = null }) { Text("Cancel") }
            },
        )
    }

    if (extractFlow.inProgress) {
        ExtractProgressDialog(
            archiveName = extractFlow.archiveName ?: "archive",
            phase = extractFlow.phase,
            progress = extractFlow.progress,
            onCancel = { extractFlow.cancelInProgress() },
        )
    }
    extractFlow.passwordPromptFor?.let {
        PasswordPromptDialog(
            archiveName = extractFlow.archiveName ?: "archive",
            onCancel = { extractFlow.cancelInProgress() },
            onSubmit = { extractFlow.submitPassword(it) },
        )
    }
    extractFlow.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { extractFlow.acknowledgeError() },
            title = { Text("Extraction failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { extractFlow.acknowledgeError() }) { Text("OK") } },
        )
    }
    extractFlow.extractedRoot?.let { root ->
        val isApkMode = apkExtractMode
        ExtractedFileBrowser(
            rootName = extractFlow.archiveName ?: "Extracted game",
            root = root,
            mode = if (isApkMode) ExtractTargetMode.ApkInstall else ExtractTargetMode.JoiPlay,
            onCancel = {
                extractFlow.acknowledgeResult()
                apkExtractMode = false
            },
            onPick = { uri ->
                extractFlow.acknowledgeResult()
                if (isApkMode) {
                    apkExtractMode = false
                    // We need a File handle for the picker tree (always file:// when
                    // routed via MANAGE_EXTERNAL_STORAGE). Then run through the same
                    // confirm dialog so the user can opt-in to delete-after-install.
                    val path = uri.path
                    if (uri.scheme == "file" && path != null) {
                        apkInstallConfirm = java.io.File(path)
                    } else {
                        // Fallback: fire the intent directly via the URI; we won't be
                        // able to delete the source post-install.
                        scope.launch {
                            val intent = ApkInstaller.buildIntentForUri(context, uri)
                            if (intent == null) {
                                snackbarMsg = "Can't install from that URI."
                            } else {
                                apkPostInstall = ApkPostInstall(null, null)
                                runCatching { apkInstallResultLauncher.launch(intent) }
                                    .onFailure {
                                        apkPostInstall = null
                                        snackbarMsg = "Installer failed: ${it.message}"
                                    }
                            }
                        }
                    }
                } else {
                    scope.launch {
                        val intent = JoiPlayInstaller.buildIntent(context, uri)
                        if (intent == null) {
                            snackbarMsg = "JoiPlay can't handle that file."
                        } else {
                            runCatching { joiplayResultLauncher.launch(intent) }
                                .onFailure { snackbarMsg = "JoiPlay launch failed: ${it.message}" }
                        }
                    }
                }
            },
        )
    }
    if (upgradeFlow.inProgress) {
        ExtractProgressDialog(
            archiveName = upgradeFlow.archiveName ?: "archive",
            phase = upgradeFlow.phase,
            progress = upgradeFlow.progress,
            onCancel = { upgradeFlow.cancelInProgress() },
        )
    }
    upgradeFlow.passwordPromptFor?.let {
        PasswordPromptDialog(
            archiveName = upgradeFlow.archiveName ?: "archive",
            onCancel = { upgradeFlow.cancelPasswordPrompt() },
            onSubmit = { upgradeFlow.submitPassword(it) },
        )
    }
    upgradeFlow.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { upgradeFlow.acknowledgeError() },
            title = { Text("Upgrade failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { upgradeFlow.acknowledgeError() }) { Text("OK") } },
        )
    }
    upgradeFlow.result?.let { result ->
        if (upgradeGuidanceDismissed) {
            LaunchedEffect(result) {
                snackbarMsg = "Upgraded ${result.app.label}. Backup kept: ${java.io.File(result.backupFolder).name}"
                upgradeFlow.acknowledgeResult()
            }
        } else {
            var dontShowAgain by remember(result) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { upgradeFlow.acknowledgeResult() },
                title = { Text("Upgrade extracted") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Try launching the game to confirm the update worked. The old version is still kept as a backup folder.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("New: ${result.newFolder}", style = MaterialTheme.typography.bodySmall)
                        Text("Backup: ${result.backupFolder}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Save data copied: ${result.saveItemsCopied}. If something is wrong, rename the new folder aside and rename the backup folder back to the original name.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dontShowAgain = !dontShowAgain },
                        ) {
                            Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                            Spacer(Modifier.width(4.dp))
                            Text("Don't show this again", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (dontShowAgain) {
                            upgradeGuidanceDismissed = true
                            scope.launch { JoiPlaySettingsStore.setUpgradeGuidanceDismissed(context.applicationContext, true) }
                        }
                        val err = JoiPlayLauncher.launch(context, result.app)
                        if (err != null) snackbarMsg = err
                        upgradeFlow.acknowledgeResult()
                    }) { Text("Launch") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (dontShowAgain) {
                            upgradeGuidanceDismissed = true
                            scope.launch { JoiPlaySettingsStore.setUpgradeGuidanceDismissed(context.applicationContext, true) }
                        }
                        upgradeFlow.acknowledgeResult()
                    }) { Text("Done") }
                },
            )
        }
    }

    if (supportDialogOpen) {
        AlertDialog(
            onDismissRequest = { supportDialogOpen = false },
            title = { Text("Support the project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Thanks for considering a tip! Choose whichever option is easier for you — both go to the same developer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().clickable {
                            supportDialogOpen = false
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appConfig.stripeDonationUrl)))
                            }.onFailure { snackbarMsg = "Could not open browser: ${it.message}" }
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("\uD83D\uDCB3", fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Credit card / Apple Pay / Google Pay",
                                     fontWeight = FontWeight.SemiBold)
                                Text("Direct card / wallet link",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Surface(
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().clickable {
                            supportDialogOpen = false
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appConfig.donationUrl)))
                            }.onFailure { snackbarMsg = "Could not open browser: ${it.message}" }
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("\u2615", fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Support link", fontWeight = FontWeight.SemiBold)
                                Text("Optional external support page",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { supportDialogOpen = false }) { Text("Close") }
            },
        )
    }
    permissionRationale?.let { rationale ->
        PermissionRationaleDialog(
            rationale = rationale,
            onDismiss = { permissionRationale = null },
            onOpenSettings = {
                permissionRationale = null
                when (rationale) {
                    PermissionRationale.AllFilesConfig,
                    PermissionRationale.AllFilesInstallApk,
                    PermissionRationale.AllFilesJoiPlayInstall,
                    PermissionRationale.AllFilesUnusedFolders,
                    PermissionRationale.AllFilesRenPySaves -> {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            requestAllFilesAccess(context)
                        } else {
                            val permissions = buildList {
                                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                                    add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }.toTypedArray()
                            legacyStoragePermissionLauncher.launch(permissions)
                        }
                    }
                    PermissionRationale.UsageAccess -> context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }

    screenshotPanel?.let {
        ScreenshotDemoOverlay(panel = it)
    }

    updateCheckResult?.let { result ->
        UpdateDialog(
            result = result,
            downloadProgress = downloadProgress,
            onDismiss = { updateCheckResult = null; downloadProgress = null },
            onDownloadAndInstall = { info ->
                scope.launch {
                    downloadProgress = 0L to (info.size.takeIf { it > 0 } ?: 1L)
                    runCatching {
                        val apk = appUpdater.download(context, info) { d, t ->
                            downloadProgress = d to (if (t > 0) t else d)
                        }
                        appUpdater.install(context, apk)
                    }.onFailure {
                        snackbarMsg = "Download failed: ${it.message}"
                        downloadProgress = null
                    }
                    updateCheckResult = null
                }
            }
        )
    }
}

@Composable
private fun SourceFilterRow(sourceFilter: AppSource?, onChange: (AppSource?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = sourceFilter == AppSource.Android,
            onClick = { onChange(if (sourceFilter == AppSource.Android) null else AppSource.Android) },
            label = { Text("Android", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Android, null, modifier = Modifier.size(14.dp)) },
        )
        FilterChip(
            selected = sourceFilter == AppSource.JoiPlay,
            onClick = { onChange(if (sourceFilter == AppSource.JoiPlay) null else AppSource.JoiPlay) },
            label = { Text("JoiPlay", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.SportsEsports, null, modifier = Modifier.size(14.dp)) },
        )
    }
}

@Composable
private fun CompactInstalledFilterRow(
    active: SnapshotStateList<UpdateStatus>,
    sourceFilter: AppSource?,
    onSourceChange: (AppSource?) -> Unit,
    manualOnly: Boolean,
    onManualOnlyChange: (Boolean) -> Unit,
    threadUpdatedAfterInstall: Boolean,
    onThreadUpdatedAfterInstallChange: (Boolean) -> Unit,
    hasSavesOnly: Boolean,
    onHasSavesOnlyChange: (Boolean) -> Unit,
) {
    InstalledFilterDropdownRow(
        active = active,
        sourceFilter = sourceFilter,
        onSourceChange = onSourceChange,
        manualOnly = manualOnly,
        onManualOnlyChange = onManualOnlyChange,
        threadUpdatedAfterInstall = threadUpdatedAfterInstall,
        onThreadUpdatedAfterInstallChange = onThreadUpdatedAfterInstallChange,
        hasSavesOnly = hasSavesOnly,
        onHasSavesOnlyChange = onHasSavesOnlyChange,
        verticalPadding = 2.dp,
    )
}

@Composable
private fun FilterBar(
    active: SnapshotStateList<UpdateStatus>,
    sourceFilter: AppSource?,
    onSourceChange: (AppSource?) -> Unit,
    manualOnly: Boolean,
    onManualOnlyChange: (Boolean) -> Unit,
    threadUpdatedAfterInstall: Boolean,
    onThreadUpdatedAfterInstallChange: (Boolean) -> Unit,
    hasSavesOnly: Boolean,
    onHasSavesOnlyChange: (Boolean) -> Unit,
) {
    InstalledFilterDropdownRow(
        active = active,
        sourceFilter = sourceFilter,
        onSourceChange = onSourceChange,
        manualOnly = manualOnly,
        onManualOnlyChange = onManualOnlyChange,
        threadUpdatedAfterInstall = threadUpdatedAfterInstall,
        onThreadUpdatedAfterInstallChange = onThreadUpdatedAfterInstallChange,
        hasSavesOnly = hasSavesOnly,
        onHasSavesOnlyChange = onHasSavesOnlyChange,
        verticalPadding = 6.dp,
    )
}

@Composable
private fun InstalledFilterDropdownRow(
    active: SnapshotStateList<UpdateStatus>,
    sourceFilter: AppSource?,
    onSourceChange: (AppSource?) -> Unit,
    manualOnly: Boolean,
    onManualOnlyChange: (Boolean) -> Unit,
    threadUpdatedAfterInstall: Boolean,
    onThreadUpdatedAfterInstallChange: (Boolean) -> Unit,
    hasSavesOnly: Boolean,
    onHasSavesOnlyChange: (Boolean) -> Unit,
    verticalPadding: androidx.compose.ui.unit.Dp,
) {
    val statusOptions = remember {
        UpdateStatus.values()
            .filterNot { it == UpdateStatus.CheckFailed || it == UpdateStatus.Unknown }
            .sortedBy { statusLabel(it) }
    }
    var statusMenuOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var libraryFilterMenuOpen by remember { mutableStateOf(false) }
    val selectedStatus = active.firstOrNull()
    val statusText = selectedStatus?.let { statusLabel(it) } ?: "All"
    val sourceText = when (sourceFilter) {
        null -> "All"
        AppSource.Android -> "Android"
        AppSource.JoiPlay -> "JoiPlay"
    }
    val libraryFilterCount =
        (if (manualOnly) 1 else 0) +
            (if (threadUpdatedAfterInstall) 1 else 0) +
            (if (hasSavesOnly) 1 else 0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            OutlinedButton(onClick = { statusMenuOpen = true }) {
                Text("Status: $statusText", fontSize = 12.sp)
            }
            DropdownMenu(expanded = statusMenuOpen, onDismissRequest = { statusMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("All") },
                    onClick = {
                        active.clear()
                        statusMenuOpen = false
                    },
                )
                statusOptions.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(statusLabel(s)) },
                        onClick = {
                            active.clear()
                            active.add(s)
                            statusMenuOpen = false
                        },
                    )
                }
            }
        }
        Box {
            OutlinedButton(onClick = { sourceMenuOpen = true }) {
                Text("Source: $sourceText", fontSize = 12.sp)
            }
            DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("All") },
                    onClick = {
                        onSourceChange(null)
                        sourceMenuOpen = false
                    },
                )
                listOf(AppSource.Android, AppSource.JoiPlay).forEach { source ->
                    DropdownMenuItem(
                        text = { Text(if (source == AppSource.Android) "Android" else "JoiPlay") },
                        onClick = {
                            onSourceChange(source)
                            sourceMenuOpen = false
                        },
                    )
                }
            }
        }
        Box {
            OutlinedButton(onClick = { libraryFilterMenuOpen = true }) {
                Icon(Icons.Default.FilterList, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (libraryFilterCount > 0) "Filters ($libraryFilterCount)" else "Filters",
                    fontSize = 12.sp,
                )
            }
            DropdownMenu(
                expanded = libraryFilterMenuOpen,
                onDismissRequest = { libraryFilterMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Manually matched") },
                    leadingIcon = { Checkbox(checked = manualOnly, onCheckedChange = null) },
                    onClick = { onManualOnlyChange(!manualOnly) },
                )
                DropdownMenuItem(
                    text = { Text("Thread updated after install") },
                    leadingIcon = { Checkbox(checked = threadUpdatedAfterInstall, onCheckedChange = null) },
                    onClick = { onThreadUpdatedAfterInstallChange(!threadUpdatedAfterInstall) },
                )
                DropdownMenuItem(
                    text = { Text("Has saves") },
                    leadingIcon = { Checkbox(checked = hasSavesOnly, onCheckedChange = null) },
                    onClick = { onHasSavesOnlyChange(!hasSavesOnly) },
                )
                if (libraryFilterCount > 0) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Clear filters") },
                        leadingIcon = { Icon(Icons.Default.Close, null) },
                        onClick = {
                            onManualOnlyChange(false)
                            onThreadUpdatedAfterInstallChange(false)
                            onHasSavesOnlyChange(false)
                            libraryFilterMenuOpen = false
                        },
                    )
                }
            }
        }
    }
}

private fun statusLabel(s: UpdateStatus): String = when (s) {
    UpdateStatus.UpdateAvailable -> "Update"
    UpdateStatus.UpToDate -> "Current"
    UpdateStatus.Unknown -> "Unknown"
    UpdateStatus.NotMapped -> "Unmapped"
    UpdateStatus.CheckFailed -> "Failed"
}

private fun statusColor(s: UpdateStatus): Color = when (s) {
    UpdateStatus.UpdateAvailable -> Color(0xFFE57373)
    UpdateStatus.UpToDate -> Color(0xFF81C784)
    UpdateStatus.NotMapped -> Color.Gray
    UpdateStatus.Unknown -> Color(0xFFFFB74D)
    UpdateStatus.CheckFailed -> Color(0xFFBA68C8)
}

private fun versionEvidenceSummary(app: InstalledApp, mapping: AppMapping?): String {
    val installed = effectiveInstalledVersion(app, mapping).ifBlank { "unknown" }
    val installedSource = when (app.source) {
        AppSource.Android -> "Android PackageManager versionName"
        AppSource.JoiPlay -> "JoiPlay row/folder metadata; use Detect version for file/marker candidates"
    }
    if (hasActiveManualInstalledVersion(app, mapping)) {
        val latest = mapping?.lastSeenVersion?.let { "latest catalog $it" } ?: "no catalog version"
        return "$installed from manual installed-version override; $latest"
    }
    val latest = mapping?.lastSeenVersion?.let { "latest catalog $it" } ?: "no catalog version"
    return "$installed from $installedSource; $latest"
}

private fun updateDecisionSummary(app: InstalledApp, mapping: AppMapping?, status: UpdateStatus): String =
    when {
        mapping == null || mapping.f95Url.isNullOrBlank() -> "Unmapped: no source URL/catalog entry."
        mapping.lastSeenVersion == null -> "Unknown: mapped, but latest catalog version is unknown."
        mapping.acknowledgedVersion != null && mapping.acknowledgedVersion == mapping.lastSeenVersion ->
            "Current: user acknowledged ${mapping.lastSeenVersion} as installed."
        VersionCompare.matchesInstalled(mapping.lastSeenVersion, effectiveInstalledVersion(app, mapping)) ->
            "Current: installed '${effectiveInstalledVersion(app, mapping)}' structurally matches catalog '${mapping.lastSeenVersion}'."
        status == UpdateStatus.UpdateAvailable ->
            "Update: installed '${effectiveInstalledVersion(app, mapping).ifBlank { "unknown" }}' differs from catalog '${mapping.lastSeenVersion}'."
        else -> "${statusLabel(status)}: installed '${effectiveInstalledVersion(app, mapping).ifBlank { "unknown" }}', catalog '${mapping.lastSeenVersion}'."
    }

private val dateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
private val dateTimeFmt: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

private fun fmtDate(epochMs: Long): String =
    if (epochMs <= 0L) "—" else dateFmt.format(Date(epochMs))

private fun fmtDateTime(epochMs: Long): String =
    if (epochMs <= 0L) "—" else dateTimeFmt.format(Date(epochMs))

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

private fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean {
    return context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isReadPermission
    }
}

@Composable
private fun GameActionDropdown(
    row: AppRow,
    renPySaves: List<RenPySaveAssociation>,
    rpgmSaves: List<RpgmSaveLocation>,
    expanded: Boolean,
    actionMenuOpen: Boolean,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onOpen: () -> Unit,
    onRefreshOne: () -> Unit,
    onEdit: () -> Unit,
    onSetInstalledVersion: () -> Unit,
    onSetInstalledDate: () -> Unit,
    onOpenRenPySaves: () -> Unit,
    onAddRenPySaveFolder: () -> Unit,
    onOpenRpgmSaves: () -> Unit,
    onAddRpgmSaveFolder: () -> Unit,
    onToggleExpand: () -> Unit,
    onUninstall: () -> Unit,
) {
    var groupOpen by remember { mutableStateOf<GameActionGroup?>(null) }
    fun closeAll() {
        groupOpen = null
        onDismiss()
    }
    DropdownMenu(
        expanded = actionMenuOpen,
        onDismissRequest = {
            groupOpen = null
            onDismiss()
        },
    ) {
        Text(
            "Game actions",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        DropdownMenuItem(
            text = { Text("Launch game/app") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
            onClick = { closeAll(); onLaunch() },
        )
        DropdownMenuItem(
            text = { Text(if (row.mapping?.f95Url != null) "Open thread/source" else "Search thread/source") },
            leadingIcon = {
                Icon(if (row.mapping?.f95Url != null) Icons.Default.OpenInBrowser else Icons.Default.Search, null)
            },
            onClick = { closeAll(); onOpen() },
        )
        DropdownMenuItem(
            text = { Text("Refresh / check update") },
            leadingIcon = { Icon(Icons.Default.Refresh, null) },
            onClick = { closeAll(); onRefreshOne() },
        )
        DropdownMenuItem(
            text = { Text("Match, notes & status") },
            leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { closeAll(); onEdit() },
        )
        DropdownMenuItem(
            text = { Text("Set installed info") },
            leadingIcon = { Icon(Icons.Default.Event, null) },
            trailingIcon = { Icon(Icons.Default.ChevronLeft, null) },
            onClick = { groupOpen = GameActionGroup.InstalledInfo },
        )
        DropdownMenuItem(
            text = { Text("Storage & saves") },
            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
            trailingIcon = { Icon(Icons.Default.ChevronLeft, null) },
            onClick = { groupOpen = GameActionGroup.StorageSaves },
        )
        DropdownMenuItem(
            text = { Text(if (expanded) "Hide details" else "Show details") },
            leadingIcon = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
            onClick = { closeAll(); onToggleExpand() },
        )
    }
    DropdownMenu(
        expanded = groupOpen != null,
        onDismissRequest = { groupOpen = null },
        offset = DpOffset(x = (-292).dp, y = 0.dp),
    ) {
        groupOpen?.let { group ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            ) {
                Column {
                    GameActionSubmenu(
                        group = group,
                        row = row,
                        renPySaves = renPySaves,
                        rpgmSaves = rpgmSaves,
                        onDismiss = ::closeAll,
                        onRefreshOne = onRefreshOne,
                        onEdit = onEdit,
                        onSetInstalledVersion = onSetInstalledVersion,
                        onSetInstalledDate = onSetInstalledDate,
                        onOpenRenPySaves = onOpenRenPySaves,
                        onAddRenPySaveFolder = onAddRenPySaveFolder,
                        onOpenRpgmSaves = onOpenRpgmSaves,
                        onAddRpgmSaveFolder = onAddRpgmSaveFolder,
                        onUninstall = onUninstall,
                    )
                }
            }
        }
    }
}

private enum class GameActionGroup(val title: String) {
    InstalledInfo("Set installed info"),
    StorageSaves("Storage & saves"),
}

@Composable
private fun GameActionSubmenu(
    group: GameActionGroup,
    row: AppRow,
    renPySaves: List<RenPySaveAssociation>,
    rpgmSaves: List<RpgmSaveLocation>,
    onDismiss: () -> Unit,
    onRefreshOne: () -> Unit,
    onEdit: () -> Unit,
    onSetInstalledVersion: () -> Unit,
    onSetInstalledDate: () -> Unit,
    onOpenRenPySaves: () -> Unit,
    onAddRenPySaveFolder: () -> Unit,
    onOpenRpgmSaves: () -> Unit,
    onAddRpgmSaveFolder: () -> Unit,
    onUninstall: () -> Unit,
) {
    fun runAction(action: () -> Unit) {
        onDismiss()
        action()
    }
    Text(
        group.title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    when (group) {
        GameActionGroup.InstalledInfo -> {
            DropdownMenuItem(
                text = { Text("Set installed version") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { runAction(onSetInstalledVersion) },
            )
            DropdownMenuItem(
                text = { Text("Set installed date") },
                leadingIcon = { Icon(Icons.Default.Event, null) },
                onClick = { runAction(onSetInstalledDate) },
            )
        }
        GameActionGroup.StorageSaves -> {
            DropdownMenuItem(
                text = { Text("Open Ren'Py save editor") },
                leadingIcon = { Icon(Icons.Default.Save, null) },
                enabled = renPySaves.isNotEmpty(),
                onClick = { runAction(onOpenRenPySaves) },
            )
            DropdownMenuItem(
                text = { Text("Add Ren'Py save folder") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick = { runAction(onAddRenPySaveFolder) },
            )
            DropdownMenuItem(
                text = { Text("Open RPGM save editor") },
                leadingIcon = { Icon(Icons.Default.Save, null) },
                enabled = rpgmSaves.isNotEmpty(),
                onClick = { runAction(onOpenRpgmSaves) },
            )
            DropdownMenuItem(
                text = { Text("Add RPGM save folder") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick = { runAction(onAddRpgmSaveFolder) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (row.installed.source == AppSource.JoiPlay) "Delete JoiPlay game" else "Uninstall Android app") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { runAction(onUninstall) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NiceGameCard(
    row: AppRow,
    joiPlaySizeInfo: JoiPlayScanner.SizeInfo?,
    isJoiPlaySizeScanning: Boolean,
    renPySaves: List<RenPySaveAssociation>,
    rpgmSaves: List<RpgmSaveLocation>,
    expanded: Boolean,
    catalogGame: CatalogGame?,
    catalogCover: String?,
    catalogLabels: CatalogLabelsV2?,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onSetInstalledVersion: () -> Unit,
    onSetInstalledDate: () -> Unit,
    onOpenRenPySaves: () -> Unit,
    onAddRenPySaveFolder: () -> Unit,
    onOpenRpgmSaves: () -> Unit,
    onAddRpgmSaveFolder: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onRefreshOne: () -> Unit,
    onOpen: () -> Unit,
    onShowCover: (String) -> Unit,
    onSnack: (String) -> Unit,
) {
    var actionMenuOpen by remember { mutableStateOf(false) }
    val displayTotalSize = effectiveInstalledSize(row.installed, joiPlaySizeInfo)
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else actionMenuOpen = true },
                    onLongClick = onLongPress,
                ),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    row.installed.source == AppSource.JoiPlay ->
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                    else -> MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!catalogCover.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = catalogCover,
                        contentDescription = "Cover",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(118.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onShowCover(catalogCover) },
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
                Text(
                    row.installed.label + (row.installed.launcherLabel?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                catalogGame?.title?.takeIf { ct ->
                    ct.isNotBlank() &&
                        CatalogRepository.normalizeTitle(ct) != CatalogRepository.normalizeTitle(row.installed.label)
                }?.let {
                    Text(
                        "Catalog: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                catalogGame?.let { cg ->
                    val prefixNames = catalogLabels?.let { l -> cg.prefixes.mapNotNull { l.prefixName(cg.source, it.toString()) } }
                    if (!prefixNames.isNullOrEmpty()) {
                        Text(
                            prefixNames.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    listOfNotNull(
                        effectiveInstalledVersion(row.installed, row.mapping).ifBlank { "?" },
                        row.mapping?.lastSeenVersion?.let { "→ $it" },
                    ).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(statusColor(row.status), MaterialTheme.shapes.small))
                    Text(statusLabel(row.status), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (isJoiPlaySizeScanning) "Scanning…" else fmtSize(displayTotalSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                val saveParts = buildList {
                    if (renPySaves.isNotEmpty()) add("Ren'Py saves ${renPySaves.sumOf { it.saveCount }}")
                    if (rpgmSaves.isNotEmpty()) add("RPGM saves ${rpgmSaves.sumOf { it.saveCount }}")
                }
                if (saveParts.isNotEmpty()) {
                    Text(
                        saveParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        GameActionDropdown(
            row = row,
            renPySaves = renPySaves,
            rpgmSaves = rpgmSaves,
            expanded = expanded,
            actionMenuOpen = actionMenuOpen,
            onDismiss = { actionMenuOpen = false },
            onLaunch = onLaunch,
            onOpen = onOpen,
            onRefreshOne = onRefreshOne,
            onEdit = onEdit,
            onSetInstalledVersion = onSetInstalledVersion,
            onSetInstalledDate = onSetInstalledDate,
            onOpenRenPySaves = onOpenRenPySaves,
            onAddRenPySaveFolder = onAddRenPySaveFolder,
            onOpenRpgmSaves = onOpenRpgmSaves,
            onAddRpgmSaveFolder = onAddRpgmSaveFolder,
            onToggleExpand = onToggleExpand,
            onUninstall = onUninstall,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppRowCard(
    row: AppRow,
    joiPlaySizeInfo: JoiPlayScanner.SizeInfo?,
    isJoiPlaySizeScanning: Boolean,
    renPySaves: List<RenPySaveAssociation>,
    rpgmSaves: List<RpgmSaveLocation>,
    expanded: Boolean,
    catalogGame: CatalogGame?,
    catalogCover: String?,
    catalogLabels: CatalogLabelsV2?,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onSetInstalledVersion: () -> Unit,
    onSetInstalledDate: () -> Unit,
    onOpenRenPySaves: () -> Unit,
    onAddRenPySaveFolder: () -> Unit,
    onOpenRpgmSaves: () -> Unit,
    onAddRpgmSaveFolder: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onRefreshOne: () -> Unit,
    onOpen: () -> Unit,
    onShowCover: (String) -> Unit,
    onSnack: (String) -> Unit,
) {
    val color = statusColor(row.status)
    val context = LocalContext.current
    val compactWidth = LocalConfiguration.current.screenWidthDp < 420
    val displayTotalSize = effectiveInstalledSize(row.installed, joiPlaySizeInfo)
    var actionMenuOpen by remember { mutableStateOf(false) }
    var details by remember(row.installed.packageName) { mutableStateOf<AppDetails?>(null) }
    LaunchedEffect(expanded, row.installed.packageName) {
        if (expanded && details == null) {
            details = withContext(kotlinx.coroutines.Dispatchers.IO) {
                AppDetailsProvider.get(context, row.installed.packageName)
            }
        }
    }
    // Resolve native app icon for Android rows (cheap, cached by Compose remember).
    val appIcon: android.graphics.drawable.Drawable? = null  // disabled per user request — only thumbnail
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else actionMenuOpen = true },
                onLongClick = onLongPress,
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                row.installed.source == AppSource.JoiPlay ->
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp)) {
                Surface(color = color, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxSize()) {}
            }
            Spacer(Modifier.width(6.dp))
            // Catalog cover thumbnail (clickable for full-size). Only if mapped + cover known.
            if (!catalogCover.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = catalogCover,
                    contentDescription = "Cover",
                    modifier = Modifier
                        .size(width = 40.dp, height = 56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onShowCover(catalogCover) },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.installed.label + (row.installed.launcherLabel?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Show catalog title if mapped AND it differs from the installed label
                // (case-insensitive alphanum compare). Gives a clear hint when the mapping
                // chose a different name than the local app shows.
                catalogGame?.title?.takeIf { ct ->
                    ct.isNotBlank() &&
                        ct.lowercase().filter { it.isLetterOrDigit() } !=
                        row.installed.label.lowercase().filter { it.isLetterOrDigit() }
                }?.let { catalogTitle ->
                    Text(
                        text = "Catalog: $catalogTitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                catalogGame?.let { cg ->
                    val prefixNames = catalogLabels?.let { l -> cg.prefixes.mapNotNull { l.prefixName(cg.source, it.toString()) } }
                    if (!prefixNames.isNullOrEmpty()) {
                        Text(
                            prefixNames.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val installedVer = effectiveInstalledVersion(row.installed, row.mapping).ifBlank { "?" }
                val latest = row.mapping?.lastSeenVersion
                Text(
                    text = if (latest != null) "$installedVer → $latest" else installedVer,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                row.mapping?.let { mapping ->
                    val personalParts = buildList {
                        if (mapping.userStatus != UserGameStatus.None) add(mapping.userStatus.label)
                        mapping.personalRating?.let { add("Rating $it/5") }
                        if (mapping.personalNotes.isNotBlank()) add("Notes")
                    }
                    if (personalParts.isNotEmpty()) {
                        Text(
                            text = personalParts.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (renPySaves.isNotEmpty()) {
                    val folderCount = renPySaves.size
                    val saveCount = renPySaves.sumOf { it.saveCount }
                    Text(
                        text = "Ren'Py saves: $saveCount in $folderCount folder${if (folderCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (rpgmSaves.isNotEmpty()) {
                    val folderCount = rpgmSaves.size
                    val saveCount = rpgmSaves.sumOf { it.saveCount }
                    Text(
                        text = "RPGM saves: $saveCount in $folderCount folder${if (folderCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Inst ${fmtDate(effectiveInstalledDate(row.installed, row.mapping))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    if (row.installed.lastUsedTime > 0L) {
                        Text(
                            text = "Used ${fmtDate(row.installed.lastUsedTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    catalogGame?.ts?.takeIf { it > 0L }?.let { ts ->
                        Text(
                            text = "Upd ${fmtDate(ts * 1000L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    if (row.installed.source == AppSource.JoiPlay && (joiPlaySizeInfo?.lastScannedAt ?: 0L) > 0L) {
                       Text(
                           text = "Size scan ${fmtDate(joiPlaySizeInfo?.lastScannedAt ?: 0L)}",
                           style = MaterialTheme.typography.bodySmall,
                           color = MaterialTheme.colorScheme.onSurfaceVariant,
                           fontSize = 10.sp,
                       )
                    }
                    Text(
                       text = if (isJoiPlaySizeScanning) "Scanning…" else fmtSize(displayTotalSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box {
                    IconButton(onClick = { actionMenuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Game actions", modifier = Modifier.size(20.dp))
                    }
                    GameActionDropdown(
                        row = row,
                        renPySaves = renPySaves,
                        rpgmSaves = rpgmSaves,
                        expanded = expanded,
                        actionMenuOpen = actionMenuOpen,
                        onDismiss = { actionMenuOpen = false },
                        onLaunch = onLaunch,
                        onOpen = onOpen,
                        onRefreshOne = onRefreshOne,
                        onEdit = onEdit,
                        onSetInstalledVersion = onSetInstalledVersion,
                        onSetInstalledDate = onSetInstalledDate,
                        onOpenRenPySaves = onOpenRenPySaves,
                        onAddRenPySaveFolder = onAddRenPySaveFolder,
                        onOpenRpgmSaves = onOpenRpgmSaves,
                        onAddRpgmSaveFolder = onAddRpgmSaveFolder,
                        onToggleExpand = onToggleExpand,
                        onUninstall = onUninstall,
                    )
                }
            }
        }
        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DetailRow("Package", row.installed.packageName)
                row.installed.launcherLabel?.let { DetailRow("Home-screen name", it) }
                row.installed.storagePath?.let { path ->
                    DetailRowWithAction(
                        key = "Storage path",
                        value = path,
                        actionIcon = Icons.Default.FolderOpen,
                        actionDesc = "Open folder",
                        onAction = {
                            val res = FolderOpener.open(context, path)
                            AppLog.i("OpenFolder", "${if (res.ok) "ok" else "fail"}: ${res.message} (path=$path)")
                            onSnack(res.message)
                        }
                    )
                }
                DetailRow("Version code", row.installed.versionCode.toString())
                DetailRow("Installed date", fmtDateTime(effectiveInstalledDate(row.installed, row.mapping)))
                val installedDateSource = if (hasActiveManualInstalledDate(row.installed, row.mapping)) {
                    row.mapping?.manualInstalledDateSource?.ifBlank { "manual override" }
                } else {
                    row.installed.installedDateSource.takeIf { it.isNotBlank() }
                }
                installedDateSource?.let { DetailRow("Installed-date source", it) }
                DetailRow("Last update", fmtDate(row.installed.lastUpdateTime))
                mappedCatalogGame(row.mapping, catalogGame?.let { mapOf(it.thread_id to it) })?.ts?.takeIf { it > 0L }?.let { ts ->
                    DetailRow("Thread updated", fmtDateTime(ts * 1000L))
                }
                if (row.installed.source == AppSource.JoiPlay) {
                    DetailRow("Total size", if (isJoiPlaySizeScanning) "Scanning…" else fmtSize(displayTotalSize))
                    DetailRow("Size last scanned", fmtDateTime(joiPlaySizeInfo?.lastScannedAt ?: 0L))
                    joiPlaySizeInfo?.let { info ->
                        if (info.gameBytes > 0L) DetailRow("Game files", fmtSize(info.gameBytes))
                        if (info.saveBytes > 0L) DetailRow("Saves", fmtSize(info.saveBytes))
                        if (info.backupBytes > 0L) {
                            DetailRow("Backups / rollback folders", fmtSize(info.backupBytes))
                        }
                        if (info.otherBytes > 0L) DetailRow("Other files", fmtSize(info.otherBytes))
                    }
                } else {
                    DetailRow("APK size", fmtSize(row.installed.apkSize))
                    DetailRow("Data size", fmtSize(row.installed.dataSize))
                    DetailRow("Cache size", fmtSize(row.installed.cacheSize))
                    DetailRow("Total size", fmtSize(row.installed.totalSize))
                }
                DetailRow("Installed version", effectiveInstalledVersion(row.installed, row.mapping).ifBlank { "—" })
                if (hasActiveManualInstalledVersion(row.installed, row.mapping)) {
                    DetailRow("Installed-version override", row.mapping?.manualInstalledVersion.orEmpty())
                }
                if (hasActiveManualInstalledDate(row.installed, row.mapping)) {
                    DetailRow("Installed-date override", fmtDateTime(row.mapping?.manualInstalledDate ?: 0L))
                }
                row.mapping?.let { mapping ->
                    if (mapping.userStatus != UserGameStatus.None) {
                        DetailRow("User status", mapping.userStatus.label)
                    }
                    mapping.personalRating?.let { DetailRow("Your rating", "$it / 5") }
                    if (mapping.personalNotes.isNotBlank()) {
                        DetailRow("Your notes", mapping.personalNotes)
                    }
                    if (mapping.manualCorrectionNote.isNotBlank()) {
                        DetailRow("Manual correction", mapping.manualCorrectionNote)
                    }
                }
                row.mapping?.f95Url?.let { DetailRow("Source URL", it) }
                row.mapping?.acknowledgedVersion?.let { DetailRow("Acknowledged", it) }
                if (row.mapping?.lastChecked != null && row.mapping.lastChecked > 0L) {
                    DetailRow("Last checked", fmtDateTime(row.mapping.lastChecked))
                }
                DetailRow("Installed-version source", versionEvidenceSummary(row.installed, row.mapping))
                DetailRow("Update decision", updateDecisionSummary(row.installed, row.mapping, row.status))
                row.mapping?.matchSource?.let { DetailRow("Match source", it) }
                if (renPySaves.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onOpenRenPySaves) { Text("Open Ren'Py save editor") }
                    TextButton(onClick = onAddRenPySaveFolder) { Text("Add another Ren'Py save folder") }
                    DetailRow("Ren'Py save folders", renPySaves.size.toString())
                } else {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onAddRenPySaveFolder) { Text("Add Ren'Py save folder") }
                }
                if (renPySaves.isNotEmpty()) {
                    renPySaves.forEachIndexed { index, save ->
                        DetailRow(
                            if (renPySaves.size == 1) "Save path" else "Save path ${index + 1}",
                            save.saveDirPath,
                        )
                        DetailRow("Save files", save.saveCount.toString())
                        DetailRow("Save owner", save.ownerId)
                        save.renpyVersion?.takeIf { it.isNotBlank() }?.let { DetailRow("Ren'Py version", it) }
                        save.sampleSaveNames.takeIf { it.isNotEmpty() }?.let { names ->
                            DetailRow("Save names", names.joinToString(" • "))
                        }
                        DetailRow("Last save", fmtDateTime(save.latestModified))
                        DetailRow("Association", "${save.confidence}% • ${save.reason}")
                    }
                }
                if (rpgmSaves.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onOpenRpgmSaves) { Text("Open RPGM save viewer") }
                    TextButton(onClick = onAddRpgmSaveFolder) { Text("Add another RPGM save folder") }
                    DetailRow("RPGM save folders", rpgmSaves.size.toString())
                    rpgmSaves.forEachIndexed { index, save ->
                        DetailRow(
                            if (rpgmSaves.size == 1) "RPGM save path" else "RPGM save path ${index + 1}",
                            save.saveDirPath,
                        )
                        DetailRow("RPGM save files", save.saveCount.toString())
                        DetailRow("RPGM save owner", save.ownerId)
                        DetailRow("RPGM last save", fmtDateTime(save.latestModified))
                        DetailRow("RPGM association", "${save.confidence}% • ${save.reason}")
                    }
                } else {
                    TextButton(onClick = onAddRpgmSaveFolder) { Text("Add RPGM save folder") }
                }
                Spacer(Modifier.height(4.dp))
                if (details == null) {
                    Text("Loading details…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else details?.let { d ->
                    DetailRow("Installed by", d.installerPackage ?: "(sideload)")
                    DetailRow("UID", d.uid.toString())
                    DetailRow("Min SDK", d.minSdk?.toString() ?: "?")
                    DetailRow("Target SDK", d.targetSdk.toString())
                    DetailRow("Process", d.processName)
                    DetailRow("Splits", d.splitCount.toString())
                    d.sourceDir?.let { DetailRow("APK path", it) }
                    d.nativeLibDir?.let { DetailRow("Native libs", it) }
                    d.dataDir?.let { DetailRow("Data dir", it) }
                    if (d.externalDataBytes > 0) DetailRow("External data", fmtSize(d.externalDataBytes))
                    if (d.externalCacheBytes > 0) DetailRow("External cache", fmtSize(d.externalCacheBytes))
                }
                catalogGame?.let { g ->
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    DetailRow("Catalog title", g.title)
                    g.creator?.let { DetailRow("Developer", it) }
                    g.version?.let { DetailRow("Catalog version", it) }
                    g.rating?.let { DetailRow("Rating", "%.2f / 5".format(it)) }
                    val labels = catalogLabels
                    if (labels != null) {
                        val prefixNames = g.prefixes.mapNotNull { labels.prefixName(g.source, it.toString()) }
                        if (prefixNames.isNotEmpty()) DetailRow("Type", prefixNames.joinToString(" • "))
                        val tagNames = g.tags.mapNotNull { labels.tagName(g.source, it.toString()) }
                        if (tagNames.isNotEmpty()) DetailRow("Tags", tagNames.joinToString(", "))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun DetailRowWithAction(
    key: String,
    value: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionDesc: String,
    onAction: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
            )
        }
        IconButton(onClick = onAction, modifier = Modifier.size(28.dp)) {
            Icon(actionIcon, contentDescription = actionDesc, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MatchResearchProgressDialog(progress: MatchResearchProgress) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Uploading match research snapshot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress.stage)
                }
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${progress.current} / ${progress.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (!progress.detail.isNullOrBlank()) {
                    Text(
                        progress.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun JoiPlayUnusedFolderProgressDialog(
    progress: JoiPlayUnusedFolderReporter.Progress?,
) {
    val current = progress?.current ?: 0
    val total = progress?.total ?: 0
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Checking JoiPlay folders") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress?.stage ?: "Preparing scan")
                }
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$current / $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (!progress?.detail.isNullOrBlank()) {
                    Text(
                        progress?.detail ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun JoiPlayUnusedFolderReportDialog(
    report: JoiPlayUnusedFolderReport,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Probably unused JoiPlay folders") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Root: ${report.rootPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("Unused folders: ${report.probablyUnusedFolders.size}") })
                    AssistChip(onClick = {}, label = { Text("In-use folders: ${report.inUseFolders.size}") })
                }
                Text(
                    "This report does not delete anything. Treat it as a review aid: verify folders manually before deleting them in a file manager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Selected backup has ${report.backupGameCount} games. " +
                        "Compared ${report.backupGamesUnderRoot} backup games under the root across " +
                        "${report.scannedParentPaths.size} scanned folders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (report.inaccessibleParentPaths.isNotEmpty()) {
                    Text(
                        "${report.inaccessibleParentPaths.size} folders could not be read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        ReportHeader("Probably unused", report.probablyUnusedFolders.size)
                    }
                    if (report.probablyUnusedFolders.isEmpty()) {
                        item { ReportEmptyRow() }
                    } else {
                        items(report.probablyUnusedFolders) { entry ->
                            ReportFolderRow(entry, showTitle = false)
                        }
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                        ReportHeader("In JoiPlay backup", report.inUseFolders.size)
                    }
                    if (report.inUseFolders.isEmpty()) {
                        item { ReportEmptyRow() }
                    } else {
                        items(report.inUseFolders) { entry ->
                            ReportFolderRow(entry, showTitle = true)
                        }
                    }
                    if (report.missingReferencedFolders.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            ReportHeader("Backup reference missing on disk", report.missingReferencedFolders.size)
                        }
                        items(report.missingReferencedFolders) { entry ->
                            ReportFolderRow(entry, showTitle = true)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onSave) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RenPySaveLocationsDialog(
    locations: List<RenPySaveLocation>,
    lastScannedAt: Long,
    associationActionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onAssociate: (RenPySaveLocation) -> Unit,
    onClearAssociation: (RenPySaveLocation) -> Unit,
    onCopy: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(SaveReportFilter.All) }
    val associatedCount = locations.count { it.associatedPackageName != null }
    val unassociatedCount = locations.count { it.associatedPackageName == null }
    fun matches(location: RenPySaveLocation): Boolean {
        val q = query.trim()
        val statusOk = when (statusFilter) {
            SaveReportFilter.All -> true
            SaveReportFilter.Associated -> location.associatedPackageName != null
            SaveReportFilter.Unassociated -> location.associatedPackageName == null
        }
        val queryOk = q.isBlank() ||
            location.saveDirPath.contains(q, ignoreCase = true) ||
            location.ownerId.contains(q, ignoreCase = true) ||
            location.associatedLabel?.contains(q, ignoreCase = true) == true ||
            location.associatedPackageName?.contains(q, ignoreCase = true) == true ||
            location.sampleSaveNames.any { it.contains(q, ignoreCase = true) }
        return statusOk && queryOk
    }
    val filtered = locations.filter { matches(it) }
    val associated = filtered.filter { it.associatedPackageName != null }
    val unassociated = filtered.filter { it.associatedPackageName == null }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("Ren'Py save locations") },
        text = {
            val controlsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("Found: ${locations.size}") })
                    AssistChip(onClick = {}, label = { Text("Associated: $associatedCount") })
                    AssistChip(onClick = {}, label = { Text("Unmatched: $unassociatedCount") })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search save folders") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SaveReportFilter.values().forEach { filter ->
                        FilterChip(
                            selected = statusFilter == filter,
                            onClick = { statusFilter = filter },
                            label = { Text(filter.label, fontSize = 12.sp) },
                        )
                    }
                }
                Text(
                    "${filtered.size} matching folder${if (filtered.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    buildString {
                        append("Private app saves under /data/data are not readable without root. ")
                        append("This report lists verified Ren'Py save folders found in accessible storage.")
                        if (lastScannedAt > 0L) append(" Last scanned ${fmtDateTime(lastScannedAt)}.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                LazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (statusFilter != SaveReportFilter.Unassociated) {
                        item { ReportHeader("Associated", associated.size) }
                        if (associated.isEmpty()) item { ReportEmptyRow() }
                        else items(associated) { location ->
                            RenPySaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
                        }
                    }
                    if (statusFilter != SaveReportFilter.Associated) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            ReportHeader("Unassociated", unassociated.size)
                        }
                        if (unassociated.isEmpty()) item { ReportEmptyRow() }
                        else items(unassociated) { location ->
                            RenPySaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
                        }
                    }
                }
            }
            if (wideDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        controlsPane()
                    }
                    listPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    controlsPane()
                    listPane(Modifier.fillMaxWidth().heightIn(max = 430.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RenPySaveLocationRow(
    location: RenPySaveLocation,
    associationActionsEnabled: Boolean,
    onAssociate: (RenPySaveLocation) -> Unit,
    onClearAssociation: (RenPySaveLocation) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(location.saveDirPath, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        val association = if (location.associatedPackageName != null) {
            "Associated: ${location.associatedLabel ?: location.associatedPackageName} (${location.confidence}%)"
        } else {
            "Unassociated"
        }
        Text(association, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        location.reason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Owner: ${location.ownerId} • saves: ${location.saveCount} • latest: ${fmtDateTime(location.latestModified)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        location.renpyVersion?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (location.sampleSaveNames.isNotEmpty()) {
            Text(
                "Samples: ${location.sampleSaveNames.joinToString(" • ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = associationActionsEnabled,
                onClick = { onAssociate(location) },
            ) {
                Text(if (location.associatedPackageName == null) "Associate" else "Reassign")
            }
            if (location.reason == "Manually associated") {
                TextButton(
                    enabled = associationActionsEnabled,
                    onClick = { onClearAssociation(location) },
                ) {
                    Text("Clear manual")
                }
            }
        }
    }
}

@Composable
private fun RenPySaveAssociationPickerDialog(
    location: RenPySaveLocation,
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onAssociate: (InstalledApp) -> Unit,
) {
    var query by remember { mutableStateOf(location.ownerId) }
    val filtered = remember(apps, query) {
        val q = query.trim()
        apps.asSequence()
            .filter { app ->
                q.isBlank() ||
                    app.label.contains(q, ignoreCase = true) ||
                    app.packageName.contains(q, ignoreCase = true) ||
                    app.storageFolderName?.contains(q, ignoreCase = true) == true
            }
            .sortedWith(compareBy<InstalledApp> { it.source != AppSource.JoiPlay }.thenBy { it.label.lowercase() })
            .take(80)
            .toList()
    }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("Associate Ren'Py saves") },
        text = {
            val controlsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    location.saveDirPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find game") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
            }
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                LazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item { ReportEmptyRow() }
                    } else {
                        items(filtered, key = { it.packageName }) { app ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssociate(app) },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(app.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        buildString {
                                            append(app.packageName)
                                            if (app.source == AppSource.JoiPlay) append(" • JoiPlay")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    app.storagePath?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (wideDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.width(340.dp), content = { controlsPane() })
                    listPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    controlsPane()
                    listPane(Modifier.fillMaxWidth().heightIn(max = 430.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RpgmSaveLocationsDialog(
    locations: List<RpgmSaveLocation>,
    lastScannedAt: Long,
    associationActionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onAssociate: (RpgmSaveLocation) -> Unit,
    onClearAssociation: (RpgmSaveLocation) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(SaveReportFilter.All) }
    val associatedCount = locations.count { it.associatedPackageName != null }
    val unassociatedCount = locations.count { it.associatedPackageName == null }
    fun matches(location: RpgmSaveLocation): Boolean {
        val q = query.trim()
        val statusOk = when (statusFilter) {
            SaveReportFilter.All -> true
            SaveReportFilter.Associated -> location.associatedPackageName != null
            SaveReportFilter.Unassociated -> location.associatedPackageName == null
        }
        val queryOk = q.isBlank() ||
            location.saveDirPath.contains(q, ignoreCase = true) ||
            location.ownerId.contains(q, ignoreCase = true) ||
            location.associatedLabel?.contains(q, ignoreCase = true) == true ||
            location.associatedPackageName?.contains(q, ignoreCase = true) == true
        return statusOk && queryOk
    }
    val filtered = locations.filter { matches(it) }
    val associated = filtered.filter { it.associatedPackageName != null }
    val unassociated = filtered.filter { it.associatedPackageName == null }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("RPGM save locations") },
        text = {
            val controlsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("Found: ${locations.size}") })
                    AssistChip(onClick = {}, label = { Text("Associated: $associatedCount") })
                    AssistChip(onClick = {}, label = { Text("Unmatched: $unassociatedCount") })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search save folders") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SaveReportFilter.values().forEach { filter ->
                        FilterChip(
                            selected = statusFilter == filter,
                            onClick = { statusFilter = filter },
                            label = { Text(filter.label, fontSize = 12.sp) },
                        )
                    }
                }
                Text(
                    "${filtered.size} matching folder${if (filtered.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Last scanned: ${fmtDateTime(lastScannedAt)}. RPGM support currently discovers and lists MV/MZ saves; value editing will come after codec-safe write verification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                LazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (statusFilter != SaveReportFilter.Unassociated) {
                        item { ReportHeader("Associated", associated.size) }
                        if (associated.isEmpty()) item { ReportEmptyRow() }
                        else items(associated) { location ->
                            RpgmSaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
                        }
                    }
                    if (statusFilter != SaveReportFilter.Associated) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            ReportHeader("Unassociated", unassociated.size)
                        }
                        if (unassociated.isEmpty()) item { ReportEmptyRow() }
                        else items(unassociated) { location ->
                            RpgmSaveLocationRow(location, associationActionsEnabled, onAssociate, onClearAssociation)
                        }
                    }
                }
            }
            if (wideDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        controlsPane()
                    }
                    listPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    controlsPane()
                    listPane(Modifier.fillMaxWidth().heightIn(max = 430.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RpgmSaveLocationRow(
    location: RpgmSaveLocation,
    associationActionsEnabled: Boolean,
    onAssociate: (RpgmSaveLocation) -> Unit,
    onClearAssociation: (RpgmSaveLocation) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(location.saveDirPath, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (location.associatedPackageName != null) {
                "Associated: ${location.associatedLabel ?: location.associatedPackageName} (${location.confidence}%)"
            } else "Unassociated",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        location.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "Owner: ${location.ownerId} • saves: ${location.saveCount} • latest: ${fmtDateTime(location.latestModified)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = associationActionsEnabled,
                onClick = { onAssociate(location) },
            ) {
                Text(if (location.associatedPackageName == null) "Associate" else "Reassign")
            }
            if (location.reason == "Manually associated") {
                TextButton(
                    enabled = associationActionsEnabled,
                    onClick = { onClearAssociation(location) },
                ) {
                    Text("Clear manual")
                }
            }
        }
    }
}

@Composable
private fun RpgmSaveAssociationPickerDialog(
    location: RpgmSaveLocation,
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onAssociate: (InstalledApp) -> Unit,
) {
    var query by remember { mutableStateOf(location.ownerId) }
    val filtered = remember(apps, query) {
        val q = query.trim()
        apps.asSequence()
            .filter { app ->
                q.isBlank() ||
                    app.label.contains(q, ignoreCase = true) ||
                    app.packageName.contains(q, ignoreCase = true) ||
                    app.storageFolderName?.contains(q, ignoreCase = true) == true
            }
            .sortedWith(compareBy<InstalledApp> { it.source != AppSource.JoiPlay }.thenBy { it.label.lowercase() })
            .take(80)
            .toList()
    }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(if (wideDialog) Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f) else Modifier),
        properties = DialogProperties(usePlatformDefaultWidth = !wideDialog),
        title = { Text("Associate RPGM saves") },
        text = {
            val controlsPane: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    location.saveDirPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find game") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
            }
            val listPane: @Composable (Modifier) -> Unit = { modifier ->
                LazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item { ReportEmptyRow() }
                    } else {
                        items(filtered, key = { it.packageName }) { app ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAssociate(app) },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(app.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        buildString {
                                            append(app.packageName)
                                            if (app.source == AppSource.JoiPlay) append(" • JoiPlay")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    app.storagePath?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (wideDialog) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.width(340.dp), content = { controlsPane() })
                    listPane(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    controlsPane()
                    listPane(Modifier.fillMaxWidth().heightIn(max = 430.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RpgmSaveViewerDialog(
    app: InstalledApp,
    locations: List<RpgmSaveLocation>,
    onDismiss: () -> Unit,
) {
    var slots by remember(locations) { mutableStateOf<Map<String, List<RpgmSaveSlot>>?>(null) }
    var selectedSlot by remember { mutableStateOf<RpgmSaveSlot?>(null) }
    var compareOpen by remember { mutableStateOf(false) }
    val wideDialog = isWideEditorLayout(LocalConfiguration.current)
    val contentHeight = if (wideDialog) (LocalConfiguration.current.screenHeightDp * 0.58f).dp else 600.dp
    LaunchedEffect(locations) {
        slots = withContext(Dispatchers.IO) {
            locations.associate { location -> location.saveDirPath to RpgmSaveScanner.listSaveSlots(location) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("RPGM saves") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wideDialog) Modifier.height(contentHeight) else Modifier.heightIn(max = contentHeight)),
                verticalArrangement = Arrangement.spacedBy(if (wideDialog) 6.dp else 8.dp),
            ) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "RPGM support lists verified MV/MZ saves and can edit scalar JSON values when the save codec can be re-encoded and verified safely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (wideDialog) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                val loaded = slots
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading RPGM saves…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val allSlots = locations.flatMap { loaded[it.saveDirPath].orEmpty() }
                    val controlsPane: @Composable () -> Unit = {
                        TextButton(
                            enabled = allSlots.size >= 2,
                            onClick = { compareOpen = true },
                        ) {
                            Text("Compare saves")
                        }
                    }
                    val listPane: @Composable (Modifier) -> Unit = { modifier ->
                        LazyColumn(
                            modifier = modifier,
                            verticalArrangement = Arrangement.spacedBy(if (wideDialog) 6.dp else 8.dp),
                        ) {
                            locations.forEach { location ->
                                item { RenPySaveLocationHeader(location.saveDirPath, loaded[location.saveDirPath].orEmpty().size) }
                                val locationSlots = loaded[location.saveDirPath].orEmpty()
                                if (locationSlots.isEmpty()) item { ReportEmptyRow() }
                                else items(locationSlots) { slot ->
                                    RpgmSaveSlotRow(slot = slot, onClick = { selectedSlot = slot })
                                }
                            }
                        }
                    }
                    if (wideDialog) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(320.dp).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                content = { controlsPane() },
                            )
                            listPane(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        controlsPane()
                        listPane(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    selectedSlot?.let { slot ->
        RpgmSaveSlotDetailDialog(
            slot = slot,
            onDismiss = { selectedSlot = null },
        )
    }
    if (compareOpen) {
        val loaded = slots.orEmpty()
        RpgmSaveCompareDialog(
            slots = locations.flatMap { loaded[it.saveDirPath].orEmpty() },
            onDismiss = { compareOpen = false },
        )
    }
}

@Composable
private fun RpgmSaveCompareDialog(
    slots: List<RpgmSaveSlot>,
    onDismiss: () -> Unit,
) {
    var left by remember(slots) { mutableStateOf(slots.getOrNull(0)) }
    var right by remember(slots) { mutableStateOf(slots.getOrNull(1)) }
    var diffs by remember { mutableStateOf<List<SaveCompareDiff>?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(left?.filePath, right?.filePath) {
        val l = left
        val r = right
        diffs = null
        warning = null
        if (l != null && r != null && l.filePath != r.filePath) {
            val leftInspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(l) }
            val rightInspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(r) }
            warning = listOfNotNull(leftInspection.warning, rightInspection.warning).distinct().joinToString("\n").ifBlank { null }
            diffs = buildSaveDiffs(
                leftInspection.values.map { SaveCompareValue(it.path, it.type, it.displayValue) },
                rightInspection.values.map { SaveCompareValue(it.path, it.type, it.displayValue) },
            )
        } else {
            diffs = emptyList()
        }
    }
    SaveCompareDialogContent(
        title = "Compare RPGM saves",
        slots = slots.map { it.filePath to it.fileName },
        leftPath = left?.filePath,
        rightPath = right?.filePath,
        warning = warning,
        diffs = diffs,
        onLeft = { path -> left = slots.firstOrNull { it.filePath == path } },
        onRight = { path -> right = slots.firstOrNull { it.filePath == path } },
        onDismiss = onDismiss,
    )
}

@Composable
private fun RpgmSaveSlotRow(slot: RpgmSaveSlot, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(slot.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Modified: ${fmtDateTime(slot.modifiedAt)} • Size: ${fmtSize(slot.sizeBytes)} • ${slot.codec}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            slot.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class SaveBackupBrowserEntry(
    val engine: String,
    val gameLabel: String,
    val slotName: String,
    val backup: RenPySaveBackup,
)

@Composable
private fun SaveBackupBrowserDialog(
    renPyLocations: List<RenPySaveLocation>,
    rpgmLocations: List<RpgmSaveLocation>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<SaveBackupBrowserEntry>>(emptyList()) }
    LaunchedEffect(renPyLocations, rpgmLocations) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            buildList {
                for (location in renPyLocations) {
                    val label = location.associatedLabel ?: location.ownerId
                    for (slot in RenPySaveScanner.listSaveSlots(location)) {
                        for (backup in RenPySaveEditor.listBackups(slot)) {
                            add(SaveBackupBrowserEntry("Ren'Py", label, slot.fileName, backup))
                        }
                    }
                }
                for (location in rpgmLocations) {
                    val label = location.associatedLabel ?: location.ownerId
                    for (slot in RpgmSaveScanner.listSaveSlots(location)) {
                        for (backup in RpgmSaveEditor.listBackups(slot)) {
                            add(SaveBackupBrowserEntry("RPGM", label, slot.fileName, backup))
                        }
                    }
                }
            }.sortedByDescending { it.backup.createdAt }
        }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.88f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Save backup browser") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Backups are grouped from currently scanned Ren'Py/RPGM save folders. Open a slot editor to restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading backups…", style = MaterialTheme.typography.bodySmall)
                    }
                    entries.isEmpty() -> Text("No AGM-managed save backups found.", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(entries) { entry ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${entry.engine} • ${entry.gameLabel}", fontWeight = FontWeight.SemiBold)
                                    Text("Slot: ${entry.slotName}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${entry.backup.fileName} • ${fmtDateTime(entry.backup.createdAt)} • ${fmtSize(entry.backup.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(entry.backup.filePath)) }) {
                                        Text("Copy backup path")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RpgmSaveSlotDetailDialog(
    slot: RpgmSaveSlot,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wideEditor = isWideEditorLayout(LocalConfiguration.current)
    val valuesScroll = rememberScrollState()
    var inspection by remember(slot.filePath) { mutableStateOf<RpgmEditInspection?>(null) }
    var editTarget by remember { mutableStateOf<RpgmEditableValue?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<String?>(null) }
    var sessionBackup by remember(slot.filePath) { mutableStateOf<SaveEditSessionBackup?>(null) }
    var backups by remember(slot.filePath) { mutableStateOf<List<RenPySaveBackup>>(emptyList()) }
    var restorePickerOpen by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<RenPySaveBackup?>(null) }
    var valueFilter by remember { mutableStateOf("") }
    var wholeWordFilter by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var syncTarget by remember(slot.filePath) { mutableStateOf<SaveSyncMirrorTarget?>(null) }
    var overwriteSyncToo by remember(slot.filePath) { mutableStateOf(false) }
    val stagedValues = remember(slot.filePath) { mutableStateMapOf<String, String>() }
    LaunchedEffect(slot.filePath) {
        inspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(slot) }
        backups = withContext(Dispatchers.IO) { RpgmSaveEditor.listBackups(slot) }
        syncTarget = SaveSyncMirror.findSyncTarget(slot.filePath)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(slot.fileName) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (wideEditor) 620.dp else 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!wideEditor) {
                    Text(
                        "${slot.codec} • ${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                            if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    editMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    syncTarget?.let { target ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !editing) { overwriteSyncToo = !overwriteSyncToo },
                        ) {
                            Checkbox(
                                checked = overwriteSyncToo,
                                enabled = !editing,
                                onCheckedChange = { overwriteSyncToo = it },
                            )
                            Column {
                                Text("Overwrite sync too", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${target.fileName} • ${fmtDateTime(target.modifiedAt)} • ${fmtSize(target.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (backups.isNotEmpty()) {
                        TextButton(enabled = !editing, onClick = { restorePickerOpen = true }) {
                            Text("Restore backup… (${backups.size})")
                        }
                    }
                    HorizontalDivider()
                    Text("Editable values", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                val loaded = inspection
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Inspecting save…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    loaded.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    val q = valueFilter.trim()
                    val values = loaded.values.filter { value ->
                        val effectiveValue = stagedValues[value.path] ?: value.displayValue
                        (typeFilter == null || value.type == typeFilter) &&
                            SaveSearchMatcher.matchesAny(
                                query = q,
                                wholeWord = wholeWordFilter,
                                fields = listOf(value.path, effectiveValue, value.type),
                            )
                    }
                    val filterControls: @Composable ColumnScope.() -> Unit = {
                        if (wideEditor) {
                            Text(
                                "${slot.codec} • ${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                                    if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            editMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                            syncTarget?.let { target ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !editing) { overwriteSyncToo = !overwriteSyncToo },
                                ) {
                                    Checkbox(
                                        checked = overwriteSyncToo,
                                        enabled = !editing,
                                        onCheckedChange = { overwriteSyncToo = it },
                                    )
                                    Column {
                                        Text("Overwrite sync too", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${target.fileName} • ${fmtDateTime(target.modifiedAt)} • ${fmtSize(target.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (backups.isNotEmpty()) {
                                TextButton(enabled = !editing, onClick = { restorePickerOpen = true }) {
                                    Text("Restore backup… (${backups.size})")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = valueFilter,
                            onValueChange = { valueFilter = it },
                            label = { Text("Filter values") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (valueFilter.isNotBlank()) {
                                    IconButton(onClick = { valueFilter = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear filter")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = wholeWordFilter,
                                onClick = { wholeWordFilter = !wholeWordFilter },
                                label = { Text("Whole word", fontSize = 12.sp) },
                            )
                            listOf(null, "int", "float", "bool", "string").forEach { type ->
                                FilterChip(
                                    selected = typeFilter == type,
                                    onClick = { typeFilter = if (typeFilter == type) null else type },
                                    label = { Text(type ?: "All", fontSize = 12.sp) },
                                )
                            }
                        }
                        Text("${values.size} of ${loaded.values.size} values", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (stagedValues.isNotEmpty()) {
                            Text(
                                "${stagedValues.size} staged edit${if (stagedValues.size == 1) "" else "s"} - tap Save to write",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    val valueList: @Composable (Modifier) -> Unit = { modifier ->
                        if (values.isEmpty()) {
                            ReportEmptyRow()
                        } else {
                            ScrollableColumnWithScrollbar(
                                modifier = modifier,
                                scrollState = valuesScroll,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                values.forEach { value ->
                                    val staged = stagedValues[value.path]
                                    val displayValue = staged ?: value.displayValue
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !editing) { editTarget = value },
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(value.path, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${value.type}: $displayValue" + if (staged != null) " (staged)" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (staged != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (wideEditor) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(270.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                content = filterControls,
                            )
                            valueList(
                                Modifier
                                    .weight(1f)
                                    .heightIn(max = 520.dp)
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            content = filterControls,
                        )
                        valueList(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !editing && stagedValues.isNotEmpty(),
                    onClick = {
                        editing = true
                        scope.launch {
                            var backup = sessionBackup
                            var message = ""
                            var saved = 0
                            val pending = stagedValues.toList()
                            for ((path, newValue) in pending) {
                                val (result, nextBackup) = RpgmSaveEditor.edit(slot, path, newValue, backup)
                                backup = nextBackup
                                message = result.message
                                if (!result.ok) break
                                saved++
                            }
                            sessionBackup = backup
                            val allSaved = saved == pending.size
                            if (allSaved) {
                                if (overwriteSyncToo) {
                                    syncTarget?.let { target ->
                                        val syncResult = SaveSyncMirror.overwriteSyncTarget(slot.filePath, target)
                                        message = "$message ${syncResult.message}"
                                    }
                                }
                                stagedValues.clear()
                                inspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RpgmSaveEditor.listBackups(slot) }
                                syncTarget = SaveSyncMirror.findSyncTarget(slot.filePath)
                            }
                            editMessage = if (saved > 1 && allSaved) {
                                "Saved $saved edits. $message"
                            } else {
                                message.ifBlank { "Saved $saved edits." }
                            }
                            editing = false
                        }
                    },
                ) { Text(if (editing) "Saving…" else "Save") }
                TextButton(onClick = onDismiss, enabled = !editing) { Text("Close") }
            }
        },
    )
    editTarget?.let { value ->
        RpgmValueEditDialog(
            value = value,
            busy = editing,
            onDismiss = { if (!editing) editTarget = null },
            onSave = { newValue ->
                val original = inspection?.values?.firstOrNull { it.path == value.path }?.displayValue ?: value.displayValue
                if (newValue == original) {
                    stagedValues.remove(value.path)
                } else {
                    stagedValues[value.path] = newValue
                }
                editMessage = "Staged ${value.path}. Tap Save to write changes."
                editTarget = null
            },
        )
    }
    if (restorePickerOpen) {
        RenPyBackupPickerDialog(
            backups = backups,
            onDismiss = { if (!editing) restorePickerOpen = false },
            onPick = { backup ->
                restorePickerOpen = false
                restoreTarget = backup
            },
        )
    }
    restoreTarget?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!editing) restoreTarget = null },
            title = { Text("Restore RPGM backup?") },
            text = {
                Text("Restore ${backup.fileName}? AGM will first back up the current RPGM save, then replace it with this backup.")
            },
            confirmButton = {
                TextButton(
                    enabled = !editing,
                    onClick = {
                        editing = true
                        scope.launch {
                            val result = RpgmSaveEditor.restoreBackup(slot, backup)
                            editMessage = result.message
                            if (result.ok) {
                                inspection = withContext(Dispatchers.IO) { RpgmSaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RpgmSaveEditor.listBackups(slot) }
                                sessionBackup = null
                                restoreTarget = null
                            }
                            editing = false
                        }
                    },
                ) {
                    Text(if (editing) "Restoring…" else "Restore")
                }
            },
            dismissButton = {
                TextButton(enabled = !editing, onClick = { restoreTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RpgmValueEditDialog(
    value: RpgmEditableValue,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(value.path) { mutableStateOf(value.displayValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${value.path}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type: ${value.type}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("New value") },
                    enabled = !busy,
                    singleLine = value.type != "string",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A backup is created before writing. Editing the wrong value can break game progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onSave(text) }) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenPySaveEditorDialog(
    app: InstalledApp,
    locations: List<RenPySaveLocation>,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val wideEditor = isWideEditorLayout(configuration)
    val contentHeight = if (wideEditor) (configuration.screenHeightDp * 0.58f).dp else 600.dp
    var slots by remember(locations) { mutableStateOf<Map<String, List<RenPySaveSlot>>?>(null) }
    var selectedSlot by remember { mutableStateOf<RenPySaveSlot?>(null) }
    var compareOpen by remember { mutableStateOf(false) }
    var slotSort by remember { mutableStateOf(RenPySlotSort.Modified) }
    var slotSortDesc by remember { mutableStateOf(true) }
    LaunchedEffect(locations) {
        slots = withContext(Dispatchers.IO) {
            locations.associate { location -> location.saveDirPath to RenPySaveScanner.listSaveSlots(location) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Ren'Py save editor") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wideEditor) Modifier.height(contentHeight) else Modifier.heightIn(max = contentHeight)),
                verticalArrangement = Arrangement.spacedBy(if (wideEditor) 6.dp else 8.dp),
            ) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap a save to inspect/edit direct store.* scalar values. A backup is created before writes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (wideEditor) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                val loaded = slots
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading save slots…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val allSlots = locations.flatMap { loaded[it.saveDirPath].orEmpty() }
                    val sortControls: @Composable () -> Unit = {
                        TextButton(
                            enabled = allSlots.size >= 2,
                            onClick = { compareOpen = true },
                        ) {
                            Text("Compare saves")
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RenPySlotSort.values().forEach { sort ->
                                FilterChip(
                                    selected = slotSort == sort,
                                    onClick = {
                                        if (slotSort == sort) slotSortDesc = !slotSortDesc
                                        else {
                                            slotSort = sort
                                            slotSortDesc = sort == RenPySlotSort.Modified || sort == RenPySlotSort.Size
                                        }
                                    },
                                    label = {
                                        Text(
                                            sort.label + if (slotSort == sort) {
                                                if (slotSortDesc) " ↓" else " ↑"
                                            } else "",
                                            fontSize = 12.sp,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    val saveList: @Composable (Modifier) -> Unit = { modifier ->
                        LazyColumn(
                            modifier = modifier,
                            verticalArrangement = Arrangement.spacedBy(if (wideEditor) 6.dp else 8.dp),
                        ) {
                            locations.forEach { location ->
                                item {
                                    RenPySaveLocationHeader(location.saveDirPath, loaded[location.saveDirPath].orEmpty().size)
                                }
                                val locationSlots = loaded[location.saveDirPath].orEmpty()
                                if (locationSlots.isEmpty()) {
                                    item { ReportEmptyRow() }
                                } else {
                                    val sortedSlots = sortRenPySlots(locationSlots, slotSort, slotSortDesc)
                                    items(sortedSlots) { slot ->
                                        RenPySaveSlotRow(
                                            slot = slot,
                                            onClick = { selectedSlot = slot },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (wideEditor) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(320.dp)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                sortControls()
                            }
                            saveList(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    } else {
                        sortControls()
                        saveList(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    selectedSlot?.let { slot ->
        RenPySaveSlotDetailDialog(
            slot = slot,
            onDismiss = { selectedSlot = null },
        )
    }
    if (compareOpen) {
        val loaded = slots.orEmpty()
        RenPySaveCompareDialog(
            slots = locations.flatMap { loaded[it.saveDirPath].orEmpty() },
            onDismiss = { compareOpen = false },
        )
    }
}

@Composable
private fun RenPySaveLocationHeader(path: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            path,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sortRenPySlots(
    slots: List<RenPySaveSlot>,
    sort: RenPySlotSort,
    desc: Boolean,
): List<RenPySaveSlot> {
    val sorted = when (sort) {
        RenPySlotSort.Modified -> slots.sortedBy { it.modifiedAt }
        RenPySlotSort.FileName -> slots.sortedBy { it.fileName.lowercase() }
        RenPySlotSort.SaveName -> slots.sortedBy { (it.saveName ?: it.fileName).lowercase() }
        RenPySlotSort.Size -> slots.sortedBy { it.sizeBytes }
    }
    return if (desc) sorted.reversed() else sorted
}

private fun buildSaveDiffs(left: List<SaveCompareValue>, right: List<SaveCompareValue>): List<SaveCompareDiff> {
    val leftMap = left.associateBy { it.key }
    val rightMap = right.associateBy { it.key }
    return (leftMap.keys + rightMap.keys)
        .distinct()
        .sorted()
        .mapNotNull { key ->
            val l = leftMap[key]
            val r = rightMap[key]
            if (l?.value == r?.value) null
            else SaveCompareDiff(
                key = key,
                type = l?.type ?: r?.type ?: "",
                leftValue = l?.value,
                rightValue = r?.value,
            )
        }
}

@Composable
private fun RenPySaveCompareDialog(
    slots: List<RenPySaveSlot>,
    onDismiss: () -> Unit,
) {
    var left by remember(slots) { mutableStateOf(slots.getOrNull(0)) }
    var right by remember(slots) { mutableStateOf(slots.getOrNull(1)) }
    var diffs by remember { mutableStateOf<List<SaveCompareDiff>?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(left?.filePath, right?.filePath) {
        val l = left
        val r = right
        diffs = null
        warning = null
        if (l != null && r != null && l.filePath != r.filePath) {
            val leftInspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(l) }
            val rightInspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(r) }
            warning = listOfNotNull(leftInspection.warning, rightInspection.warning).distinct().joinToString("\n").ifBlank { null }
            diffs = buildSaveDiffs(
                leftInspection.variables.map { SaveCompareValue(it.key, it.type, it.displayValue) },
                rightInspection.variables.map { SaveCompareValue(it.key, it.type, it.displayValue) },
            )
        } else {
            diffs = emptyList()
        }
    }
    SaveCompareDialogContent(
        title = "Compare Ren'Py saves",
        slots = slots.map { it.filePath to it.fileName },
        leftPath = left?.filePath,
        rightPath = right?.filePath,
        warning = warning,
        diffs = diffs,
        onLeft = { path -> left = slots.firstOrNull { it.filePath == path } },
        onRight = { path -> right = slots.firstOrNull { it.filePath == path } },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SaveCompareDialogContent(
    title: String,
    slots: List<Pair<String, String>>,
    leftPath: String?,
    rightPath: String?,
    warning: String?,
    diffs: List<SaveCompareDiff>?,
    onLeft: (String) -> Unit,
    onRight: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val wideEditor = isWideEditorLayout(configuration)
    val dialogWidthFraction = if (wideEditor) 0.86f else 0.96f
    val dialogHeightFraction = if (wideEditor) 0.88f else 0.92f
    val contentMaxHeight = if (wideEditor) {
        (configuration.screenHeightDp * 0.62f).dp
    } else {
        600.dp
    }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    val diffScroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(dialogWidthFraction)
            .fillMaxHeight(dialogHeightFraction),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                warning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                val loaded = diffs
                if (loaded == null) {
                    Text("Left save", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        slots.forEach { (path, name) ->
                            FilterChip(
                                selected = leftPath == path,
                                onClick = { onLeft(path) },
                                label = { Text(name, fontSize = 12.sp) },
                            )
                        }
                    }
                    Text("Right save", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        slots.forEach { (path, name) ->
                            FilterChip(
                                selected = rightPath == path,
                                onClick = { onRight(path) },
                                label = { Text(name, fontSize = 12.sp) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Comparing…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val controls: @Composable ColumnScope.() -> Unit = {
                        Text("Left save", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            slots.forEach { (path, name) ->
                                FilterChip(
                                    selected = leftPath == path,
                                    onClick = { onLeft(path) },
                                    label = { Text(name, fontSize = 12.sp) },
                                )
                            }
                        }
                        Text("Right save", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            slots.forEach { (path, name) ->
                                FilterChip(
                                    selected = rightPath == path,
                                    onClick = { onRight(path) },
                                    label = { Text(name, fontSize = 12.sp) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Filter differences") },
                            placeholder = { Text("key, value, or type") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotBlank()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear filter")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(null, "int", "float", "bool", "string").forEach { type ->
                                FilterChip(
                                    selected = typeFilter == type,
                                    onClick = { typeFilter = if (typeFilter == type) null else type },
                                    label = { Text(type ?: "All", fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                    val q = query.trim()
                    val filteredDiffs = loaded.filter { diff ->
                        val typeOk = typeFilter == null || diff.type == typeFilter
                        val queryOk = q.isBlank() ||
                            diff.key.contains(q, ignoreCase = true) ||
                            diff.type.contains(q, ignoreCase = true) ||
                            diff.leftValue?.contains(q, ignoreCase = true) == true ||
                            diff.rightValue?.contains(q, ignoreCase = true) == true
                        typeOk && queryOk
                    }
                    val diffList: @Composable (Modifier) -> Unit = { modifier ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${filteredDiffs.size} of ${loaded.size} differing value${if (loaded.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (filteredDiffs.isEmpty()) {
                                ReportEmptyRow()
                            } else {
                                ScrollableColumnWithScrollbar(
                                    scrollState = diffScroll,
                                    modifier = modifier,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    filteredDiffs.forEach { diff -> SaveCompareDiffRow(diff) }
                                }
                            }
                        }
                    }
                    if (wideEditor) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                content = controls,
                            )
                            diffList(
                                Modifier
                                    .weight(1f)
                                    .heightIn(max = contentMaxHeight)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = controls)
                        diffList(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 330.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SaveCompareDiffRow(diff: SaveCompareDiff) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(diff.key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text("Type: ${diff.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "A: ${diff.leftValue ?: "(missing)"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "B: ${diff.rightValue ?: "(missing)"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RenPySaveSlotRow(slot: RenPySaveSlot, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember(slot.filePath, slot.modifiedAt) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(slot.filePath, slot.modifiedAt) {
        thumbnail = withContext(Dispatchers.IO) { RenPySaveEditor.extractThumbnail(context.applicationContext, slot) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumbnail != null) {
            coil.compose.AsyncImage(
                model = thumbnail,
                contentDescription = "Save thumbnail",
                modifier = Modifier
                    .size(width = 72.dp, height = 44.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else if (slot.hasScreenshot) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 44.dp)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(slot.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            slot.saveName?.takeIf { it.isNotBlank() }?.let {
                Text("Name: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Modified: ${fmtDateTime(slot.modifiedAt)} • Size: ${fmtSize(slot.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slot.renpyVersion?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Screenshot: ${if (slot.hasScreenshot) "yes" else "no"} • Entries: ${slot.entries.joinToString(", ").ifBlank { "legacy/non-zip" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RenPySaveSlotDetailDialog(
    slot: RenPySaveSlot,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val wideEditor = isWideEditorLayout(LocalConfiguration.current)
    val variablesScroll = rememberScrollState()
    var inspection by remember(slot.filePath) { mutableStateOf<RenPyEditInspection?>(null) }
    var editTarget by remember { mutableStateOf<RenPyEditableVariable?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editMessage by remember { mutableStateOf<String?>(null) }
    var sessionBackup by remember(slot.filePath) { mutableStateOf<SaveEditSessionBackup?>(null) }
    var variableFilter by remember { mutableStateOf("") }
    var wholeWordFilter by remember { mutableStateOf(false) }
    var variableTypeFilter by remember { mutableStateOf<String?>(null) }
    var backups by remember(slot.filePath) { mutableStateOf<List<RenPySaveBackup>>(emptyList()) }
    var restorePickerOpen by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<RenPySaveBackup?>(null) }
    var syncTarget by remember(slot.filePath) { mutableStateOf<SaveSyncMirrorTarget?>(null) }
    var overwriteSyncToo by remember(slot.filePath) { mutableStateOf(false) }
    val stagedVariables = remember(slot.filePath) { mutableStateMapOf<String, String>() }
    LaunchedEffect(slot.filePath) {
        inspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(slot) }
        backups = withContext(Dispatchers.IO) { RenPySaveEditor.listBackups(slot) }
        syncTarget = SaveSyncMirror.findSyncTarget(slot.filePath)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(slot.fileName) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (wideEditor) 620.dp else 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!wideEditor) {
                    Text(
                        "${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                            if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    editMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    syncTarget?.let { target ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !editing) { overwriteSyncToo = !overwriteSyncToo },
                        ) {
                            Checkbox(
                                checked = overwriteSyncToo,
                                enabled = !editing,
                                onCheckedChange = { overwriteSyncToo = it },
                            )
                            Column {
                                Text("Overwrite sync too", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${target.fileName} • ${fmtDateTime(target.modifiedAt)} • ${fmtSize(target.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (backups.isNotEmpty()) {
                        TextButton(enabled = !editing, onClick = { restorePickerOpen = true }) {
                            Text("Restore backup… (${backups.size})")
                        }
                    }
                    HorizontalDivider()
                    Text("Editable variables", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                val loaded = inspection
                if (loaded == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Inspecting save…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    loaded.warning?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    val query = variableFilter.trim()
                    val filteredVariables = loaded.variables.filter { variable ->
                        val effectiveValue = stagedVariables[variable.key] ?: variable.displayValue
                        val typeMatches = variableTypeFilter == null || variable.type == variableTypeFilter
                        val queryMatches = SaveSearchMatcher.matchesAny(
                            query = query,
                            wholeWord = wholeWordFilter,
                            fields = listOf(variable.key, effectiveValue, variable.type),
                        )
                        typeMatches && queryMatches
                    }
                    val filterControls: @Composable ColumnScope.() -> Unit = {
                        if (wideEditor) {
                            Text(
                                "${fmtDateTime(slot.modifiedAt)} • ${fmtSize(slot.sizeBytes)}" +
                                    if (backups.isNotEmpty()) " • backups ${backups.size}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            editMessage?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            syncTarget?.let { target ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !editing) { overwriteSyncToo = !overwriteSyncToo },
                                ) {
                                    Checkbox(
                                        checked = overwriteSyncToo,
                                        enabled = !editing,
                                        onCheckedChange = { overwriteSyncToo = it },
                                    )
                                    Column {
                                        Text("Overwrite sync too", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${target.fileName} • ${fmtDateTime(target.modifiedAt)} • ${fmtSize(target.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (backups.isNotEmpty()) {
                                TextButton(enabled = !editing, onClick = { restorePickerOpen = true }) {
                                    Text("Restore backup… (${backups.size})")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = variableFilter,
                            onValueChange = { variableFilter = it },
                            label = { Text("Filter variables") },
                            placeholder = { Text("name, value, or type") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (variableFilter.isNotBlank()) {
                                    IconButton(onClick = { variableFilter = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear filter")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = wholeWordFilter,
                                onClick = { wholeWordFilter = !wholeWordFilter },
                                label = { Text("Whole word", fontSize = 12.sp) },
                            )
                            listOf(null, "int", "float", "bool", "string").forEach { type ->
                                FilterChip(
                                    selected = variableTypeFilter == type,
                                    onClick = { variableTypeFilter = if (variableTypeFilter == type) null else type },
                                    label = { Text(type ?: "All", fontSize = 12.sp) },
                                )
                            }
                        }
                        Text(
                            "${filteredVariables.size} of ${loaded.variables.size} variables",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (stagedVariables.isNotEmpty()) {
                            Text(
                                "${stagedVariables.size} staged edit${if (stagedVariables.size == 1) "" else "s"} - tap Save to write",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    val valueList: @Composable (Modifier) -> Unit = { modifier ->
                        if (filteredVariables.isEmpty()) {
                            ReportEmptyRow()
                        } else {
                            ScrollableColumnWithScrollbar(
                                modifier = modifier,
                                scrollState = variablesScroll,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                filteredVariables.forEach { variable ->
                                    val staged = stagedVariables[variable.key]
                                    val displayValue = staged ?: variable.displayValue
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !editing) { editTarget = variable },
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(variable.key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${variable.type}: $displayValue" + if (staged != null) " (staged)" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (staged != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (wideEditor) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier.width(270.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                content = filterControls,
                            )
                            valueList(
                                Modifier
                                    .weight(1f)
                                    .heightIn(max = 520.dp)
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            content = filterControls,
                        )
                        valueList(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !editing && stagedVariables.isNotEmpty(),
                    onClick = {
                        editing = true
                        scope.launch {
                            var backup = sessionBackup
                            var message = ""
                            var saved = 0
                            val pending = stagedVariables.toList()
                            for ((key, newValue) in pending) {
                                val (result, nextBackup) = RenPySaveEditor.edit(slot, key, newValue, backup)
                                backup = nextBackup
                                message = result.message
                                if (!result.ok) break
                                saved++
                            }
                            sessionBackup = backup
                            val allSaved = saved == pending.size
                            if (allSaved) {
                                if (overwriteSyncToo) {
                                    syncTarget?.let { target ->
                                        val syncResult = SaveSyncMirror.overwriteSyncTarget(slot.filePath, target)
                                        message = "$message ${syncResult.message}"
                                    }
                                }
                                stagedVariables.clear()
                                inspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RenPySaveEditor.listBackups(slot) }
                                syncTarget = SaveSyncMirror.findSyncTarget(slot.filePath)
                            }
                            editMessage = if (saved > 1 && allSaved) {
                                "Saved $saved edits. $message"
                            } else {
                                message.ifBlank { "Saved $saved edits." }
                            }
                            editing = false
                        }
                    },
                ) { Text(if (editing) "Saving…" else "Save") }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(slot.filePath))
                    },
                    enabled = !editing,
                ) {
                    Text("Copy path")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    editTarget?.let { variable ->
        RenPyVariableEditDialog(
            variable = variable,
            busy = editing,
            onDismiss = { if (!editing) editTarget = null },
            onSave = { newValue ->
                val original = inspection?.variables?.firstOrNull { it.key == variable.key }?.displayValue ?: variable.displayValue
                if (newValue == original) {
                    stagedVariables.remove(variable.key)
                } else {
                    stagedVariables[variable.key] = newValue
                }
                editMessage = "Staged ${variable.key}. Tap Save to write changes."
                editTarget = null
            },
        )
    }
    if (restorePickerOpen) {
        RenPyBackupPickerDialog(
            backups = backups,
            onDismiss = { if (!editing) restorePickerOpen = false },
            onPick = { backup ->
                restorePickerOpen = false
                restoreTarget = backup
            },
        )
    }
    restoreTarget?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!editing) restoreTarget = null },
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "Restore ${backup.fileName}? AGM will first back up the current save, then replace it with this backup.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !editing,
                    onClick = {
                        editing = true
                        scope.launch {
                            val result = RenPySaveEditor.restoreBackup(slot, backup)
                            editMessage = result.message
                            if (result.ok) {
                                inspection = withContext(Dispatchers.IO) { RenPySaveEditor.inspect(slot) }
                                backups = withContext(Dispatchers.IO) { RenPySaveEditor.listBackups(slot) }
                                restoreTarget = null
                            }
                            editing = false
                        }
                    },
                ) {
                    Text(if (editing) "Restoring…" else "Restore")
                }
            },
            dismissButton = {
                TextButton(enabled = !editing, onClick = { restoreTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RenPyVariableEditDialog(
    variable: RenPyEditableVariable,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(variable.key) { mutableStateOf(variable.displayValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${variable.key}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type: ${variable.type}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("New value") },
                    enabled = !busy,
                    singleLine = variable.type != "string",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A .bak copy is created before writing. The game may show an unsigned-save warning because the original Ren'Py signature is invalidated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onSave(value) }) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun List<RenPySaveLocation>.asRenPySaveReportText(lastScannedAt: Long): String {
    val locations = this
    return buildString {
        appendLine("Ren'Py save locations")
        appendLine("last scanned: ${fmtDateTime(lastScannedAt)}")
        appendLine("found: ${locations.size}")
        appendLine("associated: ${locations.count { it.associatedPackageName != null }}")
        appendLine("unassociated: ${locations.count { it.associatedPackageName == null }}")
        locations.forEach { location ->
            appendLine()
            appendLine(location.saveDirPath)
            appendLine("  owner: ${location.ownerId}")
            appendLine("  saves: ${location.saveCount}")
            appendLine("  latest: ${fmtDateTime(location.latestModified)}")
            if (!location.renpyVersion.isNullOrBlank()) appendLine("  renpy: ${location.renpyVersion}")
            if (location.sampleSaveNames.isNotEmpty()) appendLine("  samples: ${location.sampleSaveNames.joinToString(" | ")}")
            if (location.associatedPackageName != null) {
                appendLine("  associated: ${location.associatedLabel ?: location.associatedPackageName} (${location.associatedPackageName})")
                appendLine("  confidence: ${location.confidence}")
                appendLine("  reason: ${location.reason ?: "Associated"}")
            } else {
                appendLine("  associated: no")
            }
        }
    }
}

@Composable
private fun PermissionRationaleDialog(
    rationale: PermissionRationale,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val (title, body, confirm) = when (rationale) {
        PermissionRationale.AllFilesConfig -> Triple(
            "Manage all files access",
            "All files access lets Adult Game Manager auto-read app_config.json from Documents/AdultGameManager and use file-based tools like APK/JoiPlay installs and folder reports. You can grant or revoke it in Android settings.",
            "Open settings",
        )
        PermissionRationale.AllFilesInstallApk -> Triple(
            "All files access for APK installs",
            "Adult Game Manager needs All files access only when you use file-based tools like Install APK. It lets the app browse your download/game folders, read APKs inside archives, and remember the last folder you used. You can revoke it any time in Android settings.",
            "Open settings",
        )
        PermissionRationale.AllFilesJoiPlayInstall -> Triple(
            "All files access for JoiPlay installs",
            "Install game in JoiPlay uses a direct file picker so it can hand JoiPlay real game files or extract archives to your chosen folder. Leave this off if you only browse the catalog or track Android-installed games.",
            "Open settings",
        )
        PermissionRationale.AllFilesUnusedFolders -> Triple(
            "All files access for folder reports",
            "The unused-folder report compares your selected JoiPlay backup with folders next to your games. All files access lets the app scan those folders directly. The report is read-only and never deletes folders.",
            "Open settings",
        )
        PermissionRationale.AllFilesRenPySaves -> Triple(
            "All files access for Ren'Py saves",
            "Ren'Py save discovery scans shared storage for verified .save files, including Downloads, Documents, Android/data public save folders, and custom game folders. It is read-only and cannot access private /data/data saves without root.",
            "Open settings",
        )
        PermissionRationale.UsageAccess -> Triple(
            "Manage usage data access",
            "Usage data access lets Adult Game Manager show Last used dates for installed apps so sorting is more useful. You can grant or revoke it in Android settings.",
            "Open settings",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                Text(
                    "If you do not use this feature, leave the permission off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun RenPyBackupPickerDialog(
    backups: List<RenPySaveBackup>,
    onDismiss: () -> Unit,
    onPick: (RenPySaveBackup) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Pick a backup to restore. AGM will back up the current save before replacing it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (backups.isEmpty()) {
                        item { ReportEmptyRow() }
                    } else {
                        items(backups) { backup ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(backup) },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f).widthIn(max = 220.dp)) {
                                        Text(
                                            backup.fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${fmtDateTime(backup.createdAt)} • ${fmtSize(backup.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text("Select", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun buildDiagnosticsSummary(
    context: android.content.Context,
    appCount: Int,
    visibleCount: Int,
    hasUsage: Boolean,
    hasAllFiles: Boolean,
    config: AppConfig,
): String {
    val version = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).let { pi ->
            "v${pi.versionName} (${if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()})"
        }
    }.getOrDefault("unknown")
    return buildString {
        appendLine("Adult Game Manager diagnostics summary")
        appendLine("app: $version")
        appendLine("package: ${context.packageName}")
        appendLine("android: SDK ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("installed entries: $appCount")
        appendLine("visible rows: $visibleCount")
        appendLine("all files access: $hasAllFiles")
        appendLine("usage access: $hasUsage")
        appendLine("diagnostics menu enabled: ${config.diagnosticsEnabled}")
        appendLine("diagnostics upload configured: ${config.hasCrashUpload}")
        appendLine("catalog configured: ${config.catalogUrl.isNotBlank()}")
        appendLine("version feeds configured: ${config.effectiveVersionInfoUrls.size}")
    }
}

@Composable
private fun ReportHeader(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ReportEmptyRow() {
    Text(
        "- (none)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReportFolderRow(entry: JoiPlayUnusedFolderEntry, showTitle: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (showTitle && !entry.title.isNullOrBlank()) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            entry.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Parsed search box: separates `tag:xxx` terms from free-text. */
data class ParsedQuery(val freeText: String, val tags: List<String>)

fun parseSearchQuery(raw: String): ParsedQuery {
    if (raw.isBlank()) return ParsedQuery("", emptyList())
    val tags = mutableListOf<String>()
    val free = StringBuilder()
    raw.split(Regex("\\s+")).forEach { tok ->
        if (tok.length > 4 && tok.startsWith("tag:", ignoreCase = true)) {
            val t = tok.substring(4).trim().lowercase()
            if (t.isNotEmpty()) tags.add(t)
        } else if (tok.isNotEmpty()) {
            if (free.isNotEmpty()) free.append(' ')
            free.append(tok)
        }
    }
    return ParsedQuery(free.toString().trim(), tags)
}

fun parseTagFilters(raw: String): List<String> = parseSearchQuery(raw).tags

@Composable
private fun EmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Inbox,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenHelp: () -> Unit,
    onShareApp: () -> Unit,
    onReportIssue: () -> Unit,
    onOpenSupport: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("About Adult Game Manager") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Android companion for tracking adult game updates across installed APKs " +
                            "and JoiPlay games " +
                            "(Ren'Py, RPG Maker, HTML, etc.).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Local-first and privacy-minded: no site login, no hosted account, " +
                            "no analytics SDK, and no automatic game downloader. Releases, " +
                            "changelogs, and docs are hosted publicly on GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text("Quick tips:", style = MaterialTheme.typography.labelLarge)
                Text(
                    "• Tap a row to expand details / copy text\n" +
                            "• Tap the cover thumbnail to view it full-size\n" +
                            "• Tap the refresh icon on a row to check that game\n" +
                            "• Long-press a row for multi-select (bulk hide / unhide)\n" +
                            "• In the search box, type tag:harem tag:incest to filter by tags\n" +
                            "• For JoiPlay games, import your .joiback from Menu\n" +
                            "• Full help with search at the link below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = {
                    onOpenHelp()
                    onDismiss()
                }) { Text("Open help") }
                TextButton(onClick = {
                    onShareApp()
                    onDismiss()
                }) { Text("Share app") }
                TextButton(onClick = {
                    onReportIssue()
                    onDismiss()
                }) { Text("Report issue") }
                TextButton(onClick = { onOpenSupport(); onDismiss() }) { Text("Support thread") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun ScreenshotDemoOverlay(panel: ScreenshotPanel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(panel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    screenshotPanelSubtitle(panel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                screenshotPanelRows(panel).forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.first, modifier = Modifier.width(34.dp), fontSize = 20.sp)
                        Text(row.second, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (panel in setOf(ScreenshotPanel.ApkConfirm, ScreenshotPanel.ExtractConfirm, ScreenshotPanel.JoiPlayDelete)) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {}) { Text("Cancel") }
                        TextButton(onClick = {}) {
                            Text(
                                when (panel) {
                                    ScreenshotPanel.ApkConfirm -> "Install"
                                    ScreenshotPanel.ExtractConfirm -> "Extract"
                                    ScreenshotPanel.JoiPlayDelete -> "Delete"
                                    else -> "OK"
                                },
                                color = if (panel == ScreenshotPanel.JoiPlayDelete) MaterialTheme.colorScheme.error else LocalContentColor.current,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun screenshotPanelSubtitle(panel: ScreenshotPanel): String = when (panel) {
    ScreenshotPanel.LaunchLibrary -> "Track Android APKs and JoiPlay games in one Android-focused library."
    ScreenshotPanel.LaunchCatalogFilters -> "Browse the multi-source catalog and narrow results by source and platform."
    ScreenshotPanel.LaunchAdvancedFilters -> "Keep the main catalog clean while advanced filters stay one tap away."
    ScreenshotPanel.LaunchGameDetails -> "Review source details, version, platforms, tags, and open the original source page."
    ScreenshotPanel.LaunchReviewUnmapped -> "Quickly map installed Android/JoiPlay games to catalog entries."
    ScreenshotPanel.LaunchF95Import -> "First-run migration can import an existing F95 Updater library."
    ScreenshotPanel.SortMenu -> "Sort installed games by name, install date, thread update, size, and update status."
    ScreenshotPanel.MainMenu -> "Primary actions for catalog refresh, updates, installs, save tools, backup, and help."
    ScreenshotPanel.CatalogMenu -> "Catalog maintenance actions."
    ScreenshotPanel.JoiPlayMenu -> "Install APKs, install local games into JoiPlay, refresh JoiPlay storage sizes, and open JoiPlay settings."
    ScreenshotPanel.BackupMenu -> "AGM backup/restore, JoiPlay backup import, and unused-folder reports."
    ScreenshotPanel.DiagnosticsMenu -> "Diagnostics are only visible when diagnosticsEnabled is true."
    ScreenshotPanel.About -> "Version, project description, sharing, issue reporting, and support links."
    ScreenshotPanel.Support -> "Optional donation links."
    ScreenshotPanel.JoiPlaySettings -> "Configure source and destination folders used by JoiPlay extraction."
    ScreenshotPanel.JoiPlayWarning -> "Safety warning before handing files to JoiPlay."
    ScreenshotPanel.JoiPlayPicker -> "Custom file picker for launch files and archives."
    ScreenshotPanel.ApkPicker -> "Custom file picker for APK and archive install flows."
    ScreenshotPanel.ApkConfirm -> "Confirmation screen before Android's package installer opens."
    ScreenshotPanel.ExtractConfirm -> "Confirmation screen before extracting an archive."
    ScreenshotPanel.JoiPlayDelete -> "Confirmation screen before deleting a JoiPlay game folder."
    ScreenshotPanel.CatalogMain -> "Browse the full multi-source catalog with ratings, update dates, tags, and engine prefixes."
    ScreenshotPanel.CatalogTagFilter -> "Search supports tag: filters across both tags and thread prefixes."
}

private fun screenshotPanelRows(panel: ScreenshotPanel): List<Pair<String, String>> = when (panel) {
    ScreenshotPanel.LaunchLibrary -> listOf("Library" to "Installed APKs + JoiPlay games", "Update status" to "Mapped, unknown, ignored, and hidden", "Actions" to "Open, map, backup, refresh, and install")
    ScreenshotPanel.LaunchCatalogFilters -> listOf("Source" to "All sources, F95Zone, AdultGameWorld", "Platform" to "Android, Windows, Mac, Linux", "Search" to "Title, developer, source, and tag: filters", "Result" to "F95Zone and AdultGameWorld entries side by side")
    ScreenshotPanel.LaunchAdvancedFilters -> listOf("Status" to "Completed, on-hold, abandoned", "Engine/type" to "Ren'Py, RPGM, Unity, HTML, VN", "Rating" to "Minimum rating slider", "Install state" to "Installed-only or not-installed-only")
    ScreenshotPanel.LaunchGameDetails -> listOf("Source" to "AdultGameWorld or F95Zone", "Version" to "Latest catalog version", "Platforms" to "Android / Windows / Mac where available", "Tags" to "Prefixes and source tags", "Open" to "Jump to the original source page")
    ScreenshotPanel.LaunchReviewUnmapped -> listOf("Suggestions" to "Multi-source name matching", "Review" to "Accept, skip, or manually choose a match", "Coverage" to "Uses F95Zone and AdultGameWorld catalog entries", "Layout" to "Portrait and unfolded screens supported")
    ScreenshotPanel.LaunchF95Import -> listOf("Detect" to "Finds installed F95 Updater data", "Permission" to "Requests access before reading legacy files", "Import" to "Copies mappings into AGM", "Continue" to "AGM remains the new multi-source Android manager")
    ScreenshotPanel.SortMenu -> listOf("↕" to "Thread updated", "↕" to "Update status", "↕" to "Total size", "↕" to "Last used")
    ScreenshotPanel.MainMenu -> listOf("↻" to "Refresh from catalog", "⬇" to "Check for app update", "💾" to "Save tools ...", "?" to "Help ...")
    ScreenshotPanel.CatalogMenu -> listOf("⇄" to "Sync catalog now", "🏷" to "Refresh labels", "🧹" to "Clear catalog cache")
    ScreenshotPanel.JoiPlayMenu -> listOf("📦" to "Install APK", "➕" to "Install game in JoiPlay", "📊" to "Refresh JoiPlay storage sizes", "⚙" to "JoiPlay settings")
    ScreenshotPanel.BackupMenu -> listOf("💾" to "Export AGM backup", "📂" to "Import AGM backup", "📥" to "Import JoiPlay backup", "↩" to "Restore automatic backup")
    ScreenshotPanel.DiagnosticsMenu -> listOf("📸" to "Capture walkthrough screenshots", "🐞" to "Save logs to Documents", "☁" to "Upload app logs + screenshots")
    ScreenshotPanel.About -> listOf("ℹ" to "Adult Game Manager", "📱" to "Android + JoiPlay update tracking", "↗" to "Share app, report issues, and open support")
    ScreenshotPanel.Support -> listOf("💳" to "Card / wallet support", "☕" to "Support link")
    ScreenshotPanel.JoiPlaySettings -> listOf("📁" to "Source folder: Documents/AdultGameManager", "📁" to "Destination folder: Games/JoiPlay", "✓" to "Remember file picker location")
    ScreenshotPanel.JoiPlayWarning -> listOf("!" to "Only install files from sources you trust", "✓" to "Continue to custom file picker", "☐" to "Don't show again")
    ScreenshotPanel.JoiPlayPicker -> listOf("📁" to "Games", "📁" to "Downloads", "🔴" to "Game.exe", "🟣" to "GameArchive.zip")
    ScreenshotPanel.ApkPicker -> listOf("📁" to "Download", "🟣" to "GameName.apk", "🟣" to "GameBundle.xapk", "🟣" to "ArchivedGame.zip")
    ScreenshotPanel.ApkConfirm -> listOf("📦" to "From: /storage/emulated/0/Download/GameName.apk", "☑" to "Delete the APK after a successful install")
    ScreenshotPanel.ExtractConfirm -> listOf("🟣" to "From: /storage/emulated/0/Download/GameArchive.zip", "📁" to "To: /storage/emulated/0/Games/JoiPlay", "☑" to "Delete the archive after successful extraction")
    ScreenshotPanel.JoiPlayDelete -> listOf("🗑" to "This will permanently delete the folder for: Example JoiPlay Game", "!" to "This cannot be undone.")
    ScreenshotPanel.CatalogMain -> listOf("🔎" to "Search title/dev + tag:harem tag:incest", "🏷" to "Ren'Py • VN • Completed", "⭐" to "Rating 4.6 • Updated today", "📖" to "Open source details")
    ScreenshotPanel.CatalogTagFilter -> listOf("🔎" to "tag:renpy", "🏷" to "Matches prefix: Ren'Py", "🎮" to "Filter chips: Completed, VN, RPGM, Unity", "✅" to "Installed-only / not-installed filters")
}

@Composable
private fun UpdateDialog(
    result: UpdateCheckResult,
    downloadProgress: Pair<Long, Long>?,
    onDismiss: () -> Unit,
    onDownloadAndInstall: (AppUpdateInfo) -> Unit,
) {
    when (result) {
        is UpdateCheckResult.UpToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Up to date") },
                text = { Text("You are running v${result.currentVersionName}, which is the latest version.") },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            )
        }
        is UpdateCheckResult.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update check failed") },
                text = { Text(result.message) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            )
        }
        is UpdateCheckResult.Available -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Update available") },
                text = {
                    Column {
                        Text("Installed: v${result.currentVersionName}")
                        Text("Available: v${result.info.versionName}")
                        if (result.info.size > 0) {
                            Text("Size: ${fmtSize(result.info.size)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (result.info.released.isNotBlank()) {
                            Text("Released: ${result.info.released}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (result.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(result.info.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        downloadProgress?.let { (d, t) ->
                            Spacer(Modifier.height(12.dp))
                            val frac = if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                            Text("${fmtSize(d)} / ${fmtSize(t)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onDownloadAndInstall(result.info) },
                        enabled = downloadProgress == null,
                    ) { Text(if (downloadProgress == null) "Download & install" else "Downloading…") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun CatalogResultRow(game: CatalogGame, onClick: () -> Unit) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(game.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            val sub = buildString {
                append(game.source.sourceDisplayName)
                game.version?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" \u2022 ")
                    append("v")
                    append(it)
                }
                game.creator?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(it)
                }
                if (isNotEmpty()) append(" \u2022 ")
                append("id ").append(game.sourceId ?: game.thread_id.toString())
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogResultList(
    games: List<CatalogGame>,
    onPick: (CatalogGame) -> Unit,
    modifier: Modifier = Modifier,
    fillAvailable: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillAvailable) Modifier.fillMaxHeight() else Modifier.heightIn(max = 160.dp))
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (g in games) {
            CatalogResultRow(game = g, onClick = { onPick(g) })
        }
    }
}

@Composable
private fun ExternalMirrorResultList(
    results: List<ExternalMirrorResult>,
    onPick: (ExternalMirrorResult) -> Unit,
    modifier: Modifier = Modifier,
    fillAvailable: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillAvailable) Modifier.fillMaxHeight() else Modifier.heightIn(max = 160.dp))
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (result in results) {
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(result) },
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(result.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = buildString {
                        append(result.sourceHost.ifBlank { "external source" })
                        result.threadId?.let { append(" • linked catalog id ").append(it) }
                        result.version?.takeIf { it.isNotBlank() }?.let {
                            append(" • ")
                            append(it)
                        }
                    }
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditMappingSearchResults(
    query: String,
    searching: Boolean,
    results: List<CatalogGame>,
    showRelaxedResults: Boolean,
    findingRelaxed: Boolean,
    relaxedResults: List<CatalogGame>,
    translatedRelaxedQuery: String?,
    showExternalResults: Boolean,
    searchingExternal: Boolean,
    externalResults: List<ExternalMirrorResult>,
    onPickCatalog: (CatalogGame) -> Unit,
    onPickExternal: (ExternalMirrorResult) -> Unit,
    modifier: Modifier = Modifier,
    fillResults: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Search catalog", style = MaterialTheme.typography.titleSmall)
        when {
            showExternalResults -> {
                Text(
                    "External source results are not from the built-in catalog. Selections are saved as manual/external.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                when {
                    searchingExternal -> Text("Searching external sources…", style = MaterialTheme.typography.bodySmall)
                    externalResults.isEmpty() -> Text("No external source results.", style = MaterialTheme.typography.bodySmall)
                    else -> ExternalMirrorResultList(
                        results = externalResults,
                        onPick = onPickExternal,
                        modifier = if (fillResults) Modifier.weight(1f, fill = true) else Modifier,
                        fillAvailable = fillResults,
                    )
                }
            }
            showRelaxedResults && findingRelaxed -> {
                Text("Fuzzy finding…", style = MaterialTheme.typography.bodySmall)
            }
            showRelaxedResults && relaxedResults.isEmpty() -> {
                Text("No fuzzy matches.", style = MaterialTheme.typography.bodySmall)
            }
            showRelaxedResults -> {
                Text(
                    translatedRelaxedQuery?.let { "Fuzzy matches translated from: $it" } ?: "Fuzzy matches",
                    style = MaterialTheme.typography.bodySmall,
                )
                CatalogResultList(
                    games = relaxedResults,
                    onPick = onPickCatalog,
                    modifier = if (fillResults) Modifier.weight(1f, fill = true) else Modifier,
                    fillAvailable = fillResults,
                )
            }
            query.isBlank() -> {
                Text("Type a title to search the catalog.", style = MaterialTheme.typography.bodySmall)
            }
            searching && results.isEmpty() -> {
                Text("Searching…", style = MaterialTheme.typography.bodySmall)
            }
            results.isEmpty() -> {
                Text("No matches in catalog.", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Text("Search matches", style = MaterialTheme.typography.bodySmall)
                CatalogResultList(
                    games = results,
                    onPick = onPickCatalog,
                    modifier = if (fillResults) Modifier.weight(1f, fill = true) else Modifier,
                    fillAvailable = fillResults,
                )
            }
        }
    }
}

@Composable
private fun EditMappingDialog(
    row: AppRow,
    isHidden: Boolean,
    catalog: CatalogRepository,
    searcher: WebSearcher,
    onDismiss: () -> Unit,
    onSave: (String, UserGameStatus, Int?, String, String) -> Unit,
    onPickCatalog: (CatalogGame) -> Unit,
    onPickExternal: (ExternalMirrorResult) -> Unit,
    onMarkNotOnF95: () -> Unit,
    onClearNotOnF95: () -> Unit,
    onClear: () -> Unit,
    onMarkInstalled: () -> Unit,
    onToggleHide: () -> Unit,
) {
    var url by remember { mutableStateOf(row.mapping?.f95Url ?: "") }
    var userStatus by remember(row.installed.packageName) { mutableStateOf(row.mapping?.userStatus ?: UserGameStatus.None) }
    var personalRating by remember(row.installed.packageName) { mutableStateOf(row.mapping?.personalRating) }
    var personalNotes by remember(row.installed.packageName) { mutableStateOf(row.mapping?.personalNotes.orEmpty()) }
    var manualCorrectionNote by remember(row.installed.packageName) { mutableStateOf(row.mapping?.manualCorrectionNote.orEmpty()) }
    var query by remember { mutableStateOf(row.installed.label) }
    var results by remember { mutableStateOf<List<CatalogGame>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var relaxedResults by remember(row.installed.packageName) { mutableStateOf<List<CatalogGame>>(emptyList()) }
    var findingRelaxed by remember { mutableStateOf(false) }
    var translatedRelaxedQuery by remember { mutableStateOf<String?>(null) }
    var relaxedFindNonce by remember { mutableStateOf(0) }
    var showRelaxedResults by remember { mutableStateOf(false) }
    var externalResults by remember(row.installed.packageName) { mutableStateOf<List<ExternalMirrorResult>>(emptyList()) }
    var searchingExternal by remember { mutableStateOf(false) }
    var externalSearchNonce by remember { mutableStateOf(0) }
    var showExternalResults by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val dialogScrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val splitSearchLayout = configuration.screenWidthDp >= 700
    val notOnF95 = row.mapping?.notOnF95 == true
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        searchFocusRequester.requestFocus()
    }
    LaunchedEffect(showRelaxedResults, findingRelaxed, relaxedResults.size) {
        if (!splitSearchLayout && showRelaxedResults && !findingRelaxed) {
            kotlinx.coroutines.delay(80)
            dialogScrollState.animateScrollTo(dialogScrollState.maxValue)
            searchFocusRequester.requestFocus()
        }
    }
    // Live search: debounce ~150 ms after typing, then query the local catalog.
    LaunchedEffect(query) {
        showRelaxedResults = false
        relaxedResults = emptyList()
        translatedRelaxedQuery = null
        showExternalResults = false
        externalResults = emptyList()
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(60)
        results = runCatching { catalog.search(q, limit = 25) }.getOrDefault(emptyList())
        searching = false
    }
    LaunchedEffect(relaxedFindNonce) {
        if (relaxedFindNonce == 0) return@LaunchedEffect
        val q = query.trim()
        if (q.isEmpty()) { relaxedResults = emptyList(); showRelaxedResults = true; return@LaunchedEffect }
        findingRelaxed = true
        showRelaxedResults = true
        val relaxed = runCatching {
            relaxedTitleCandidatesWithTranslation(catalog, listOf(q), limit = 12)
        }.getOrDefault(RelaxedCandidateResult(emptyList(), null))
        relaxedResults = relaxed.games
        translatedRelaxedQuery = relaxed.translatedQuery
        findingRelaxed = false
    }
    LaunchedEffect(externalSearchNonce) {
        if (externalSearchNonce == 0) return@LaunchedEffect
        val q = query.trim()
        if (q.isEmpty()) { externalResults = emptyList(); showExternalResults = true; return@LaunchedEffect }
        searchingExternal = true
        showExternalResults = true
        externalResults = runCatching {
            searcher.searchExternalMirrors(q, limit = 8)
        }.onFailure {
            AppLog.w("ExternalMirror", "Search failed for '$q': ${it.message}")
        }.getOrDefault(emptyList())
        searchingExternal = false
    }

    @Composable
    fun SearchResultContent(modifier: Modifier = Modifier, fillResults: Boolean = false) {
        EditMappingSearchResults(
            query = query,
            searching = searching,
            results = results,
            showRelaxedResults = showRelaxedResults,
            findingRelaxed = findingRelaxed,
            relaxedResults = relaxedResults,
            translatedRelaxedQuery = translatedRelaxedQuery,
            showExternalResults = showExternalResults,
            searchingExternal = searchingExternal,
            externalResults = externalResults,
            onPickCatalog = onPickCatalog,
            onPickExternal = onPickExternal,
            modifier = modifier,
            fillResults = fillResults,
        )
    }

    @Composable
    fun DetailsAndSearchControls(showResultsInline: Boolean, modifier: Modifier = Modifier) {
        Column(modifier = modifier) {
            Text(row.installed.packageName, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Installed APK: ${row.installed.versionName.ifBlank { "?" }}", style = MaterialTheme.typography.bodySmall)
            Text("Install date: ${fmtDate(row.installed.firstInstallTime)}", style = MaterialTheme.typography.bodySmall)
            Text("Last update: ${fmtDate(row.installed.lastUpdateTime)}", style = MaterialTheme.typography.bodySmall)
            Text("Last used: ${fmtDate(row.installed.lastUsedTime)}", style = MaterialTheme.typography.bodySmall)
            Text("App size: ${fmtSize(row.installed.apkSize)}", style = MaterialTheme.typography.bodySmall)
            Text("Data size: ${fmtSize(row.installed.dataSize)}", style = MaterialTheme.typography.bodySmall)
            Text("Cache size: ${fmtSize(row.installed.cacheSize)}", style = MaterialTheme.typography.bodySmall)
            Text("Total size: ${fmtSize(row.installed.totalSize)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Latest catalog version: ${row.mapping?.lastSeenVersion ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("Acknowledged: ${row.mapping?.acknowledgedVersion ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text("Personal status", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UserGameStatus.entries.forEach { status ->
                    FilterChip(
                        selected = userStatus == status,
                        onClick = { userStatus = status },
                        label = { Text(status.label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Personal rating", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = personalRating == null,
                    onClick = { personalRating = null },
                    label = { Text("Unrated") },
                )
                (1..5).forEach { rating ->
                    FilterChip(
                        selected = personalRating == rating,
                        onClick = { personalRating = rating },
                        label = { Text("$rating") },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = personalNotes,
                onValueChange = { personalNotes = it },
                label = { Text("Personal notes") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = manualCorrectionNote,
                onValueChange = { manualCorrectionNote = it },
                label = { Text("Manual correction note") },
                placeholder = { Text("Why this match/version is correct") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (notOnF95) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Marked as not in catalog — auto-matching is skipped for this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            if (showResultsInline) {
                SearchResultContent()
                Spacer(Modifier.height(8.dp))
            } else {
                Text("Search catalog", style = MaterialTheme.typography.titleSmall)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {
                        relaxedFindNonce++
                        searchFocusRequester.requestFocus()
                    },
                    enabled = !findingRelaxed,
                    label = { Text(if (findingRelaxed) "Finding…" else "Fuzzy find") },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {
                        externalSearchNonce++
                        searchFocusRequester.requestFocus()
                    },
                    enabled = !searchingExternal,
                    label = { Text(if (searchingExternal) "Searching…" else "Search external sources") },
                    leadingIcon = { Icon(Icons.Default.Public, null, modifier = Modifier.size(16.dp)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Relaxed choices save as manual matches. External source choices save as manual/external.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Title to search") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester),
            )
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            Text("Or paste a thread URL", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Source/thread URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.installed.label,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DiagnosticsScreenshotIconButton(namePrefix = "game-settings-dialog")
            }
        },
        text = {
            if (splitSearchLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailsAndSearchControls(
                        showResultsInline = false,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(dialogScrollState),
                    )
                    VerticalDivider()
                    SearchResultContent(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        fillResults = true,
                    )
                }
            } else {
                DetailsAndSearchControls(
                    showResultsInline = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(dialogScrollState),
                )
            }
        },
        confirmButton = {
            if (splitSearchLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onClear) { Text("Clear URL") }
                    TextButton(
                        onClick = if (notOnF95) onClearNotOnF95 else onMarkNotOnF95
                    ) { Text(if (notOnF95) "In catalog after all" else "Not in catalog") }
                    TextButton(onClick = onToggleHide) {
                        Text(if (isHidden) "Unhide" else "Hide")
                    }
                    TextButton(
                        onClick = onMarkInstalled,
                        enabled = row.mapping?.lastSeenVersion != null
                            && row.mapping.lastSeenVersion != row.mapping.acknowledgedVersion
                    ) { Text("Mark installed") }
                    Button(onClick = { onSave(url, userStatus, personalRating, personalNotes, manualCorrectionNote) }) { Text("Save") }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(onClick = onClear) { Text("Clear URL") }
                        TextButton(
                            onClick = if (notOnF95) onClearNotOnF95 else onMarkNotOnF95
                        ) { Text(if (notOnF95) "In catalog after all" else "Not in catalog") }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onToggleHide) {
                            Text(if (isHidden) "Unhide" else "Hide")
                        }
                        TextButton(
                            onClick = onMarkInstalled,
                            enabled = row.mapping?.lastSeenVersion != null
                                && row.mapping.lastSeenVersion != row.mapping.acknowledgedVersion
                        ) { Text("Mark installed") }
                        Button(onClick = { onSave(url, userStatus, personalRating, personalNotes, manualCorrectionNote) }) { Text("Save") }
                    }
                }
            }
        },
    )
}

@Composable
private fun AmbiguousCatalogDialog(
    item: AmbiguousCatalogMatch,
    onPick: (CatalogGame) -> Unit,
    onNone: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose catalog match") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(item.row.installed.label, style = MaterialTheme.typography.titleSmall)
                Text(item.row.installed.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Multiple catalog titles matched (${item.via}). Pick the correct thread, or choose None.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (game in item.candidates) {
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(game) },
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(game.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium)
                                val sub = buildString {
                                    game.version?.takeIf { it.isNotBlank() }?.let { append(it) }
                                    game.creator?.takeIf { it.isNotBlank() }?.let {
                                        if (isNotEmpty()) append(" \u2022 ")
                                        append(it)
                                    }
                                    if (isNotEmpty()) append(" \u2022 ")
                                    append(game.source.sourceDisplayName).append(" id ").append(game.sourceId ?: game.thread_id.toString())
                                }
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNone) { Text("None / not in catalog") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("Skip") }
                TextButton(onClick = onDismiss) { Text("Close all") }
            }
        },
    )
}

@Composable
private fun UnmappedReviewDialog(
    items: List<AmbiguousCatalogMatch>,
    alreadyMatchedItems: List<AlreadyMatchedCatalogMatch>,
    onDismiss: () -> Unit,
    onDismissAlreadyMatched: () -> Unit,
    onKeepAlreadyMatched: () -> Unit,
    onPick: (AmbiguousCatalogMatch, CatalogGame) -> Unit,
    onNone: (AmbiguousCatalogMatch) -> Unit,
    onSearch: (AmbiguousCatalogMatch) -> Unit,
    onAddManualMatches: () -> Unit,
    onSelectClosestFuzzy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review unmapped games") },
        text = {
            if (items.isEmpty() && alreadyMatchedItems.isEmpty()) {
                Text(
                    "No unmapped games are currently listed. Use Add manually mapped matches to review manual mappings here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (alreadyMatchedItems.isNotEmpty()) {
                        item("already-matched-header") {
                            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Already matched (${alreadyMatchedItems.size})", fontWeight = FontWeight.Bold)
                                    Text(
                                        "These look like games you already mapped before. Keep all to apply the previous catalog choices and remove them from this review.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(onClick = onKeepAlreadyMatched) { Text("Keep all already matched") }
                                        TextButton(onClick = onDismissAlreadyMatched) { Text("Dismiss all") }
                                    }
                                }
                            }
                        }
                        items(alreadyMatchedItems, key = { "already-${it.item.row.installed.packageName}" }) { already ->
                            val item = already.item
                            val game = already.keptGame
                            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text(item.row.installed.label, fontWeight = FontWeight.Bold)
                                    Text(item.row.installed.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "Previously mapped to: ${game.title.ifBlank { "(untitled)" }} • ${game.source.sourceDisplayName} id ${game.sourceId ?: game.thread_id.toString()}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = { onPick(item, game) }) { Text("Keep") }
                                        TextButton(onClick = { onSearch(item) }) { Text("Search") }
                                    }
                                }
                            }
                        }
                        if (items.isNotEmpty()) {
                            item("unmatched-header") {
                                Text(
                                    "New / still unmatched (${items.size})",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    items(items, key = { it.row.installed.packageName }) { item ->
                        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(item.row.installed.label, fontWeight = FontWeight.Bold)
                                Text(item.row.installed.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.candidates.isEmpty()) {
                                    Text("No safe suggestion. Search manually or mark as not in catalog.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Suggestions (${item.via})", style = MaterialTheme.typography.bodySmall)
                                    item.candidates.take(4).forEach { game ->
                                        TextButton(onClick = { onPick(item, game) }) {
                                            Text("${game.title.ifBlank { "(untitled)" }} • ${game.source.sourceDisplayName} id ${game.sourceId ?: game.thread_id.toString()}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { onSearch(item) }) { Text("Search") }
                                    TextButton(onClick = { onNone(item) }) { Text("Not in catalog") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onAddManualMatches) { Text("Add manually mapped matches", softWrap = false) }
                TextButton(onClick = onSelectClosestFuzzy) { Text("Select closest fuzzy matches", softWrap = false) }
                TextButton(onClick = onDismiss) { Text("Close", softWrap = false) }
            }
        },
    )
}

private suspend fun checkOne(
    row: AppRow,
    scraper: F95Scraper,
    repo: MappingRepository,
) {
    val url = row.mapping?.f95Url ?: return
    val result = scraper.fetch(url).getOrNull() ?: return
    val seen = result.parsedVersion ?: row.mapping.lastSeenVersion
    repo.upsert(
        AppMapping(
            packageName = row.installed.packageName,
            f95Url = url,
            lastSeenVersion = seen,
            lastChecked = System.currentTimeMillis(),
            acknowledgedVersion = row.mapping.acknowledgedVersion,
            threadId = row.mapping.threadId ?: F95UrlParser.extractThreadId(url),
            notOnF95 = row.mapping.notOnF95,
            matchSource = row.mapping.matchSource,
        ).withPersonalFieldsFrom(row.mapping)
    )
}

private suspend fun checkAll(
    rows: List<AppRow>,
    scraper: F95Scraper,
    repo: MappingRepository,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
) {
    val total = rows.size
    var done = 0
    onProgress(0, total)
    rows.forEach { row ->
        val url = row.mapping?.f95Url ?: run { done++; onProgress(done, total); return@forEach }
        val result = scraper.fetch(url).getOrNull()
        if (result != null) {
            val seen = result.parsedVersion ?: row.mapping.lastSeenVersion
            // Do NOT auto-acknowledge — keep user's previous ack so default status
            // is driven by matchesInstalled() vs. APK versionName.
            repo.upsert(
                AppMapping(
                    packageName = row.installed.packageName,
                    f95Url = url,
                    lastSeenVersion = seen,
                    lastChecked = System.currentTimeMillis(),
                    acknowledgedVersion = row.mapping.acknowledgedVersion,
                    threadId = row.mapping.threadId ?: F95UrlParser.extractThreadId(url),
                    notOnF95 = row.mapping.notOnF95,
                    matchSource = row.mapping.matchSource,
                ).withPersonalFieldsFrom(row.mapping)
            )
        }
        done++
        onProgress(done, total)
        kotlinx.coroutines.delay(1000)
    }
}

/** Merge JoiPlay sources: prefer backup-imported entries (better titles + engine info),
 *  fall back to folder-scan entries for anything not in the backup. */
fun mergeJoiPlaySources(
    backup: List<InstalledApp>,
    folder: List<InstalledApp>,
): List<InstalledApp> {
    // Show backup-derived JoiPlay games (they have full metadata: engine, id, execFile).
    // Also surface folder-only games (newly installed via "Install game in JoiPlay…"
    // before the user re-exports the backup) so the user sees them right away. Launch
    // for folder-only games falls back to opening JoiPlay's main screen because we
    // don't know the engine type.
    val folderByKey = folder.mapNotNull { g ->
        val key = joiPlaySizeKey(g) ?: return@mapNotNull null
        key to g
    }.toMap()
    val byKey = backup.associateBy { joiPlaySizeKey(it) ?: it.storageFolderName ?: it.label }
        .mapValues { (key, app) ->
            val folderApp = folderByKey[key]
            if (folderApp != null && folderApp.totalSize > 0L) {
                app.copy(
                    apkSize = folderApp.apkSize,
                    dataSize = folderApp.dataSize,
                    cacheSize = folderApp.cacheSize,
                )
            } else {
                app
            }
        }
        .toMutableMap()
    for (g in folder) {
        val key = joiPlaySizeKey(g) ?: g.storageFolderName ?: g.label
        if (key !in byKey) byKey[key] = g
    }
    return byKey.values
        .filterNot { InstalledAppIgnoreRules.shouldIgnore(it) }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun FullSizeImageDialog(url: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}
