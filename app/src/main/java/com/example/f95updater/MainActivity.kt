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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.Calendar

private const val MAX_NESTED_ARCHIVE_DEPTH = 1
// Bulk-install driver timing. Grace to let an item's async install flow become busy before we treat
// it as a no-op; debounce window the flow must stay idle to be considered settled.
private const val BULK_ENGAGE_GRACE_MS = 3000L
private const val BULK_SETTLE_DEBOUNCE_MS = 900L

private data class PendingPackageRefresh(
    val sequence: Int,
    val packageNames: Set<String>,
)

private data class GameDeleteUiState(
    val label: String,
    val removedEntries: Long = 0L,
)

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
        lifecycleScope.launch { WinlatorClient.runStartupProbe(applicationContext) }
        setContent {
            val themeMode by ThemePrefs.observe(applicationContext).collectAsState(initial = AppThemeMode.System)
            val cardColors by CardColorPrefs.observe(applicationContext)
                .collectAsState(initial = CardColorSettings())
            val systemDark = isSystemInDarkTheme()
            androidx.compose.runtime.CompositionLocalProvider(
                LocalCardColorSettings provides cardColors,
            ) {
                MaterialTheme(colorScheme = appColorScheme(themeMode, systemDark)) {
                    Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
                }
            }
        }
    }

}

// ---- Sort & filter ----
private suspend fun scanInstalledLibrarySnapshot(
    context: Context,
    reason: String,
): List<InstalledApp> = withContext(Dispatchers.IO) {
    val startedAt = android.os.SystemClock.elapsedRealtime()
    val androidStartedAt = android.os.SystemClock.elapsedRealtime()
    val androidApps = InstalledAppsScanner.scan(context)
    AppLog.i(
        "Scan",
        "$reason stage=android elapsedMs=${android.os.SystemClock.elapsedRealtime() - androidStartedAt} " +
            "count=${androidApps.size}",
    )
    val managedStartedAt = android.os.SystemClock.elapsedRealtime()
    val managedApps = ManagedGameStore(context.applicationContext).get().map(ManagedGame::toInstalledApp)
    AppLog.i(
        "Scan",
        "$reason stage=managed_store elapsedMs=${android.os.SystemClock.elapsedRealtime() - managedStartedAt} " +
            "count=${managedApps.size}",
    )
    (androidApps + managedApps)
        .sortedBy { it.label.lowercase() }
        .also {
            AppLog.i(
                "Scan",
                "$reason snapshot complete elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} " +
                    "total=${it.size}",
            )
        }
}

private suspend fun refreshInstalledLibraryWinlator(
    context: Context,
    current: List<InstalledApp>,
    reason: String,
): List<InstalledApp> = withContext(Dispatchers.IO) {
    val startedAt = android.os.SystemClock.elapsedRealtime()
    val managedApps = refreshManagedWinlatorBindings(context).map(ManagedGame::toInstalledApp)
    (current.filter { it.source == AppSource.Android } + managedApps)
        .sortedBy { it.label.lowercase() }
        .also {
            AppLog.i(
                "Scan",
                "$reason stage=winlator_refresh elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} " +
                    "managed=${managedApps.size} total=${it.size}",
            )
        }
}

private suspend fun scanInstalledLibrary(
    context: Context,
    reason: String = "rescan",
): List<InstalledApp> {
    val snapshot = scanInstalledLibrarySnapshot(context, reason)
    return refreshInstalledLibraryWinlator(context, snapshot, reason)
}

internal class InitialResumeScanGate {
    private var initialResumeObserved = false
    private var initialScanSucceeded: Boolean? = null

    fun onInitialScanFinished(succeeded: Boolean) {
        initialScanSucceeded = succeeded
    }

    fun shouldScan(): Boolean {
        if (!initialResumeObserved) {
            initialResumeObserved = true
            return initialScanSucceeded == false
        }
        return true
    }
}

internal fun matchesEngineFilter(app: InstalledApp, filter: AppSource): Boolean = when (filter) {
    AppSource.Android -> app.source == AppSource.Android
    AppSource.JoiPlay -> app.source == AppSource.JoiPlay ||
        app.managedRunnerBindings.any { it.kind == ManagedRunnerKind.JoiPlay && it.enabled }
    AppSource.Winlator -> app.source == AppSource.Winlator ||
        app.managedRunnerBindings.any { it.kind == ManagedRunnerKind.Winlator && it.enabled }
    AppSource.Kirikiroid -> app.source == AppSource.Kirikiroid ||
        app.managedRunnerBindings.any { it.kind == ManagedRunnerKind.Kirikiroid && it.enabled }
    AppSource.Managed -> app.source == AppSource.Managed
}

internal fun shouldClearLibrarySearchFocus(searchBounds: Rect?, tapPosition: Offset): Boolean =
    searchBounds?.contains(tapPosition) != true

internal enum class PermissionRationale {
    AllFilesConfig,
    AllFilesInstallGame,
    AllFilesCleanupReview,
    AllFilesRenPySaves,
    UsageAccess,
}

internal enum class SaveReportFilter(val label: String) {
    All("All"),
    Associated("Associated"),
    Unassociated("Unassociated"),
}

internal fun isWideEditorLayout(configuration: android.content.res.Configuration): Boolean {
    return configuration.screenWidthDp >= 700 || configuration.screenWidthDp > configuration.screenHeightDp
}

@Composable
internal fun ScrollableColumnWithScrollbar(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.dialogVerticalScroll(scrollState),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

private fun catalogMatchLogContext(
    app: InstalledApp,
    labels: List<String>,
    identities: List<CatalogIdentityCandidate> = emptyList(),
): String {
    val normalized = labels.map { CatalogRepository.normalizeTitle(it) }
        .filter { it.isNotBlank() }
        .distinct()
    return "app='${app.label}' pkg=${app.packageName} source=${app.source} " +
        "labels=${labels.joinToString(prefix = "[", postfix = "]") { "'$it'" }} " +
        "norms=${normalized.joinToString(prefix = "[", postfix = "]") { "'$it'" }} " +
        "identities=${identities.joinToString(prefix = "[", postfix = "]") {
            "'${it.kind.wireValue}:${it.value}'"
        }}"
}

internal fun browserDownloadFileName(
    request: BrowserDownloadRequest,
    preferredFileName: String? = null,
): String {
    val raw = BrowserDownloads.preferredFileName(
        preferredFileName,
        request.contentDisposition,
        request.pageTitle,
    )
        ?: android.webkit.URLUtil.guessFileName(request.url, null, request.mimeType)
    return BrowserDownloads.limitFileName(raw)
}

private fun downloadLogContext(record: DownloadRecord): String {
    val game = record.catalogGame
    val host = runCatching { Uri.parse(record.url).host }.getOrNull().orEmpty()
    val title = game?.title?.replace(Regex("""[\r\n]+"""), " ")?.take(120).orEmpty()
    return "id=${record.id} file='${record.fileName}' host='$host' " +
        "catalog=${game?.source.orEmpty()}:${game?.sourceId.orEmpty()} " +
        "group=${game?.agmGroupId.orEmpty()} title='$title'"
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
    if (app.source == AppSource.JoiPlay ||
        app.managedRunnerBindings.any { it.kind == ManagedRunnerKind.JoiPlay && it.enabled }
    ) {
        return true
    }
    if (labels.any { it != app.label && CatalogRepository.normalizeTitle(it).length in 3..8 }) return true
    val norm = CatalogRepository.normalizeTitle(app.label)
    if (norm.length !in 4..8 || app.label.any { it.isLowerCase() }) return false
    val lowerPkg = app.packageName.lowercase()
    val commonPrefixes = listOf("com.", "org.", "net.", "android.", "google.", "samsung.")
    return commonPrefixes.none { lowerPkg.startsWith(it) } && lowerPkg.startsWith(norm)
}

internal data class AmbiguousCatalogMatch(
    val row: AppRow,
    val candidates: List<CatalogGame>,
    val via: String,
)

internal data class AlreadyMatchedCatalogMatch(
    val item: AmbiguousCatalogMatch,
    val keptGame: CatalogGame,
)

/** A manually-mapped game whose automated re-match points at a DIFFERENT catalog entry. */
internal data class ManualOverrideMatch(
    val row: AppRow,
    val current: AppMapping,
    val auto: CatalogGame,
    val via: String,
)

internal enum class ScreenshotPanel(val title: String) {
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
    JoiPlaySettings("Non-Android game settings"),
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
    var navigationRequestId by remember { mutableLongStateOf(0L) }
    var catalogNavigationIdentity by remember { mutableStateOf<CatalogNavigationIdentity?>(null) }
    var installedNavigationPackage by remember { mutableStateOf<String?>(null) }
    var screenshotCatalogQuery by remember { mutableStateOf<String?>(null) }
    var installedShellReady by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rootView = LocalView.current
    val rootFocusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    val catalog = remember { CatalogRepository(context.applicationContext) }
    DisposableEffect(catalog) {
        onDispose { catalog.close() }
    }
    val catalogScreenState = remember { CatalogScreenRetainedState() }
    val groupRepo = remember { CatalogGroupRepository(context.applicationContext) }
    val groupTaxonomy by groupRepo.taxonomy.collectAsState()
    LaunchedEffect(Unit) { groupRepo.loadCached() }
    val repo = remember { MappingRepository(context.applicationContext) }
    var catalogLabels by remember { mutableStateOf<CatalogLabelsV2?>(null) }
    LaunchedEffect(Unit) {
        catalog.sourceCatalogIndex()
        catalogLabels = catalog.labels()
        try {
            catalog.prepareCatalogDatabase()
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            AppLog.e("Catalog", "Background catalog preparation failed", error)
        }
    }
    LaunchedEffect(Unit) {
        yield()
        installedShellReady = true
    }
    LaunchedEffect(tab) {
        rootFocusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val mappings by repo.mappings.collectAsState(initial = emptyMap())
    // Live installed package identities, lifted from InstalledScreen's scan so the
    // Catalog tab's "Installed" badge/filter reflects the current games list even
    // while the Installed tab is not composed.
    var installedPackageNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    val appConfig by AppConfigStore.observe(context.applicationContext).collectAsState()
    // Catalog 2.x: the canonical-tag taxonomy powers the faceted tag filters inside the Catalog tab.
    LaunchedEffect(appConfig.canonicalTagsUrl) {
        if (appConfig.canonicalTagsUrl.isNotBlank()) {
            runCatching { groupRepo.refreshTaxonomy(appConfig) }
                .onFailure { AppLog.e("Catalog2", "taxonomy refresh failed", it) }
        }
    }
    var screenshotCapturing by remember { mutableStateOf(false) }
    var rootSnackbarMsg by remember { mutableStateOf<String?>(null) }
    // Snackbar with an action button: (message, actionLabel, onAction).
    var rootSnackAction by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val rootSnackbarHostState = remember { SnackbarHostState() }

    // In-app browser: when the user enables "Open links inside AGM", outbound game/thread/download
    // links load in [InAppBrowser] instead of an external browser. Downloads started in the browser
    // are enqueued via the system DownloadManager (staged in the app dir), tracked in the persistent
    // [DownloadStore], and on completion moved into the user's configured folder. The completion
    // receiver lives here (app-UI scope) so a download survives the browser closing.
    val browserSettings by BrowserPrefs.observe(context.applicationContext)
        .collectAsState(initial = BrowserSettings())
    var inAppBrowserUrl by remember { mutableStateOf<String?>(null) }
    var inAppBrowserCatalogGame by remember { mutableStateOf<CatalogGame?>(null) }
    var inAppBrowserMinimized by remember { mutableStateOf(false) }
    // Bumped to ask the Installed screen to open the Download Manager (from a snackbar action).
    var openDownloadsTick by remember { mutableStateOf(0) }
    val downloadManager = remember {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
    }
    val observedDownloadStatuses = remember { mutableMapOf<Long, DownloadManagerSnapshot>() }
    val externalDownloadObservations =
        remember { mutableMapOf<Long, ExternalCandidateObservation>() }
    val terminalTransitionIds = remember { mutableSetOf<Long>() }
    val browserDownloadRecords by DownloadStore.records.collectAsState()

    suspend fun discardDownloadAttempt(record: DownloadRecord) {
        if (record.backend != DownloadBackend.AndroidDownloadManager) return
        withContext(Dispatchers.IO) {
            downloadManager.remove(record.id)
            record.stagedPath?.let { java.io.File(it).delete() }
            record.targetPath
                ?.let { java.io.File(it) }
                ?.takeIf { it.length() == 0L }
                ?.delete()
        }
        observedDownloadStatuses.remove(record.id)
    }

    suspend fun settleBrowserDownload(id: Long, snapshot: DownloadManagerSnapshot?) {
        if (!terminalTransitionIds.add(id)) return
        try {
            val appContext = context.applicationContext
            val record = DownloadStore.records.value.firstOrNull { it.id == id }
                ?: return
            if (record.backend != DownloadBackend.AndroidDownloadManager ||
                record.state != DownloadState.Running
            ) return

            suspend fun fail(
                reason: String,
                reasonCode: Int? = null,
            ) {
                discardDownloadAttempt(record)
                DownloadStore.update(appContext, id) {
                    if (it.state != DownloadState.Running) {
                        it
                    } else {
                        it.copy(
                            state = DownloadState.Failed,
                            stagedPath = null,
                            targetPath = null,
                            finalizedBytes = 0,
                            error = reason,
                        )
                    }
                }
                if (DownloadStore.records.value.firstOrNull { it.id == id }?.state !=
                    DownloadState.Failed
                ) return
                AppLog.w(
                    "BrowserDownload",
                    "FAILED ${downloadLogContext(record)} reason='$reason'" +
                        (reasonCode?.let { " code=$it" } ?: ""),
                )
                rootSnackbarMsg = "Download failed: $reason"
            }

            when (snapshot?.status) {
                null -> fail(
                    "No longer registered with Android Download Manager; retry to start again",
                )
                android.app.DownloadManager.STATUS_FAILED ->
                    fail(downloadManagerReasonLabel(snapshot.reason), snapshot.reason)
                android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                    observedDownloadStatuses.remove(id)
                    DownloadStore.update(appContext, id) {
                        if (it.state != DownloadState.Running) it
                        else it.copy(
                            state = DownloadState.Finalizing,
                            totalBytes = snapshot.totalBytes.takeIf { total -> total > 0L }
                                ?: snapshot.downloadedBytes,
                            finalizedBytes = 0,
                            error = null,
                        )
                    }
                    AppLog.i("BrowserDownload", "READY_TO_FINALIZE ${downloadLogContext(record)}")
                }
            }
        } finally {
            terminalTransitionIds.remove(id)
        }
    }

    LaunchedEffect(Unit) { DownloadStore.load(context.applicationContext) }
    val runningDownloadIds = browserDownloadRecords
        .filter {
            it.backend == DownloadBackend.AndroidDownloadManager &&
                it.state == DownloadState.Running
        }
        .map { it.id }
    LaunchedEffect(runningDownloadIds) {
        while (runningDownloadIds.isNotEmpty()) {
            val running = DownloadStore.records.value.filter {
                it.state == DownloadState.Running && it.id in runningDownloadIds
            }
            val snapshots = withContext(Dispatchers.IO) {
                queryDownloadManagerSnapshots(downloadManager, running.map { it.id })
            }
            for (record in running) {
                val current = snapshots[record.id]
                if (current == null) {
                    if (System.currentTimeMillis() - record.createdAt >= 5_000L) {
                        settleBrowserDownload(record.id, null)
                    }
                    continue
                }
                val previous = observedDownloadStatuses.put(record.id, current)
                if (previous?.status == current.status && previous.reason == current.reason) continue
                AppLog.i(
                    "BrowserDownload",
                    "STATUS ${downloadLogContext(record)} " +
                        "state='${downloadManagerStatusLabel(current.status, current.reason)}' " +
                        "bytes=${current.downloadedBytes}/${current.totalBytes}",
                )
                if (current.status == android.app.DownloadManager.STATUS_SUCCESSFUL ||
                    current.status == android.app.DownloadManager.STATUS_FAILED
                ) {
                    settleBrowserDownload(record.id, current)
                }
            }
            delay(2_000)
        }
    }

    val externalRunningIds = browserDownloadRecords
        .filter {
            it.backend == DownloadBackend.ExternalBrowser &&
                it.state == DownloadState.Running
        }
        .map { it.id }
    LaunchedEffect(externalRunningIds) {
        while (externalRunningIds.isNotEmpty()) {
            val records = DownloadStore.records.value.filter {
                it.backend == DownloadBackend.ExternalBrowser &&
                    it.state == DownloadState.Running &&
                    it.id in externalRunningIds
            }
            for (record in records) {
                val candidate = withContext(Dispatchers.IO) {
                    selectExternalDownloadCandidate(
                        record,
                        scanExternalDownloadFiles(record.externalSearchRoots),
                    )
                }
                val observation = updateExternalCandidateObservation(
                    externalDownloadObservations[record.id],
                    candidate,
                )
                if (observation == null) {
                    externalDownloadObservations.remove(record.id)
                    continue
                }
                externalDownloadObservations[record.id] = observation
                if (observation.confirmations < 2) continue
                val file = java.io.File(observation.path)
                DownloadStore.update(context.applicationContext, record.id) {
                    if (it.backend != DownloadBackend.ExternalBrowser ||
                        it.state != DownloadState.Running
                    ) it
                    else it.copy(
                        fileName = file.name,
                        state = DownloadState.Completed,
                        destPath = file.absolutePath,
                        totalBytes = observation.size,
                        finalizedBytes = observation.size,
                        error = null,
                    )
                }
                externalDownloadObservations.remove(record.id)
                AppLog.i(
                    "BrowserDownload",
                    "EXTERNAL_COMPLETED ${downloadLogContext(record.copy(fileName = file.name))} " +
                        "bytes=${observation.size} destination='${file.absolutePath}'",
                )
                rootSnackbarMsg = "Detected browser download ${file.name}"
            }
            delay(2_000)
        }
    }

    val finalizingDownload = browserDownloadRecords
        .filter {
            it.backend == DownloadBackend.AndroidDownloadManager &&
                it.state == DownloadState.Finalizing
        }
        .minByOrNull { it.createdAt }
    LaunchedEffect(finalizingDownload?.id) {
        val record = finalizingDownload ?: return@LaunchedEffect
        val appContext = context.applicationContext
        val staged = record.stagedPath?.let { java.io.File(it) }
        val expectedBytes = record.totalBytes.takeIf { it > 0L } ?: staged?.length() ?: -1L
        val existingTarget = record.targetPath?.let { java.io.File(it) }

        if (staged?.isFile != true) {
            if (existingTarget?.isFile == true &&
                existingTarget.length() > 0L &&
                (expectedBytes <= 0L || existingTarget.length() == expectedBytes)
            ) {
                DownloadStore.update(appContext, record.id) {
                    if (it.state != DownloadState.Finalizing) it
                    else it.copy(
                        state = DownloadState.Completed,
                        stagedPath = null,
                        destPath = existingTarget.absolutePath,
                        finalizedBytes = existingTarget.length(),
                        error = null,
                    )
                }
                withContext(Dispatchers.IO) { downloadManager.remove(record.id) }
                return@LaunchedEffect
            }
            DownloadStore.update(appContext, record.id) {
                if (it.state != DownloadState.Finalizing) it
                else it.copy(
                    state = DownloadState.FinalizationFailed,
                    error = "Downloaded staging file is missing",
                )
            }
            return@LaunchedEffect
        }

        try {
            val correctedName = BrowserDownloads.correctGenericDownloadName(staged, record.fileName)
            var lastPersisted = record.finalizedBytes
            suspend fun persistProgress(bytes: Long) {
                if (bytes == expectedBytes || bytes - lastPersisted >= 32L * 1024L * 1024L) {
                    lastPersisted = bytes
                    DownloadStore.update(appContext, record.id) {
                        if (it.state != DownloadState.Finalizing) it
                        else it.copy(finalizedBytes = bytes)
                    }
                }
            }
            val reservation = existingTarget?.let {
                BrowserDownloads.LegacyTargetReservation(it, verifiedExisting = false)
            } ?: withContext(Dispatchers.IO) {
                BrowserDownloads.reserveLegacyTarget(
                    effectiveDownloadFolder(browserSettings),
                    correctedName,
                    staged,
                    ::persistProgress,
                )
            }
            val target = reservation.file
            DownloadStore.update(appContext, record.id) {
                if (it.state != DownloadState.Finalizing) it
                else it.copy(fileName = correctedName, targetPath = target.absolutePath)
            }
            val placed = withContext(Dispatchers.IO) {
                BrowserDownloads.finalizeDownloadedFile(
                    staged,
                    target,
                    expectedBytes,
                    targetContentVerified = reservation.verifiedExisting,
                    onProgress = ::persistProgress,
                )
            }
            withContext(Dispatchers.IO) { downloadManager.remove(record.id) }
            DownloadStore.update(appContext, record.id) {
                if (it.state != DownloadState.Finalizing) it
                else it.copy(
                    fileName = correctedName,
                    state = DownloadState.Completed,
                    stagedPath = null,
                    targetPath = placed.absolutePath,
                    destPath = placed.absolutePath,
                    finalizedBytes = placed.length(),
                    error = null,
                )
            }
            AppLog.i(
                "BrowserDownload",
                "COMPLETED ${downloadLogContext(record.copy(fileName = correctedName))} " +
                    "bytes=${placed.length()} destination='${placed.absolutePath}'",
            )
            rootSnackbarMsg = "Downloaded $correctedName"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DownloadStore.update(appContext, record.id) {
                if (it.state != DownloadState.Finalizing) it
                else it.copy(
                    state = DownloadState.FinalizationFailed,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
            AppLog.w(
                "BrowserDownload",
                "FINALIZE_FAILED ${downloadLogContext(record)}",
                error,
            )
            rootSnackbarMsg = "Could not finalize ${record.fileName}: ${error.message}"
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == -1L) return
                scope.launch {
                    val record = DownloadStore.records.value.firstOrNull { it.id == id }
                    if (record?.backend != DownloadBackend.AndroidDownloadManager) return@launch
                    val snapshot = withContext(Dispatchers.IO) {
                        queryDownloadManagerSnapshots(downloadManager, listOf(id))[id]
                    }
                    settleBrowserDownload(id, snapshot)
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    suspend fun enqueueBrowserDownload(
        req: BrowserDownloadRequest,
        catalogGame: CatalogGame?,
        preferredFileName: String? = null,
    ): Result<DownloadRecord> = runCatching {
        if (!hasAllFilesAccess()) {
            error("All files access is required to save browser downloads")
        }
        val fileName = browserDownloadFileName(req, preferredFileName)
        val request = android.app.DownloadManager.Request(Uri.parse(req.url))
        val cookie = android.webkit.CookieManager.getInstance().getCookie(req.url)
        if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
        if (!req.userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", req.userAgent)
        if (!req.referrer.isNullOrBlank()) request.addRequestHeader("Referer", req.referrer)
        if (!req.mimeType.isNullOrBlank()) request.setMimeType(req.mimeType)
        request.setTitle(fileName)
        request.setNotificationVisibility(
            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
        )
        request.setAllowedOverMetered(true)
        request.setAllowedOverRoaming(true)

        val targetFolder = effectiveDownloadFolder(browserSettings)
        val targetFile = withContext(Dispatchers.IO) {
            BrowserDownloads.reserveTargetFile(targetFolder, fileName)
        }
        val stagedFile = java.io.File(
            targetFolder,
            ".agm-${java.util.UUID.randomUUID()}.part",
        )
        request.setDestinationUri(Uri.fromFile(stagedFile))

        val id = try {
            downloadManager.enqueue(request)
        } catch (error: Exception) {
            withContext(Dispatchers.IO) {
                stagedFile.delete()
                targetFile.delete()
            }
            throw error
        }
        val record = DownloadRecord(
            id = id,
            url = req.url,
            fileName = fileName,
            catalogGame = catalogGame,
            userAgent = req.userAgent,
            contentDisposition = req.contentDisposition,
            mimeType = req.mimeType,
            referrer = req.referrer,
            stagedPath = stagedFile.absolutePath,
            targetPath = targetFile.absolutePath,
            state = DownloadState.Running,
            createdAt = System.currentTimeMillis(),
            expectedBytes = req.contentLength,
        )
        AppLog.i(
            "BrowserDownload",
            "START ${downloadLogContext(record)} mime='${req.mimeType.orEmpty()}' " +
                "disposition='${req.contentDisposition.orEmpty().take(240)}'",
        )
        record
    }

    fun externalDownloadSearchRoots(): List<String> = listOf(
        effectiveDownloadFolder(browserSettings).absolutePath,
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        ).absolutePath,
    ).distinctBy { it.lowercase() }

    fun externalBrowserIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)

    fun canOpenInExternalBrowser(url: String): Boolean =
        context.packageManager.queryIntentActivities(
            externalBrowserIntent(url),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).any { it.activityInfo.packageName != context.packageName }

    fun launchExternalBrowserDownload(record: DownloadRecord) {
        context.startActivity(
            Intent.createChooser(
                externalBrowserIntent(record.url),
                "Download with browser",
            ),
        )
    }

    suspend fun createExternalBrowserDownload(
        req: BrowserDownloadRequest,
        catalogGame: CatalogGame?,
        fileName: String,
    ): DownloadRecord {
        if (!hasAllFilesAccess()) {
            error("All files access is required to detect browser downloads")
        }
        if (!canOpenInExternalBrowser(req.url)) {
            error("No external browser can open this download")
        }
        val now = System.currentTimeMillis()
        val roots = externalDownloadSearchRoots()
        return DownloadRecord(
            id = newExternalDownloadId(now, DownloadStore.records.value.map { it.id }),
            url = req.url,
            fileName = fileName,
            backend = DownloadBackend.ExternalBrowser,
            catalogGame = catalogGame,
            userAgent = req.userAgent,
            contentDisposition = req.contentDisposition,
            mimeType = req.mimeType,
            referrer = req.referrer,
            state = DownloadState.Running,
            createdAt = now,
            expectedBytes = req.contentLength,
            externalSearchRoots = roots,
            externalBaseline = withContext(Dispatchers.IO) {
                snapshotExternalDownloadFiles(roots)
            },
        )
    }

    suspend fun handOffExternalBrowserDownload(record: DownloadRecord) {
        DownloadStore.add(context.applicationContext, record)
        try {
            launchExternalBrowserDownload(record)
        } catch (error: Exception) {
            DownloadStore.update(context.applicationContext, record.id) {
                if (it.createdAt != record.createdAt) it
                else it.copy(
                    state = DownloadState.Failed,
                    error = "Could not open external browser: ${error.message}",
                )
            }
            throw error
        }
        AppLog.i("BrowserDownload", "EXTERNAL_START ${downloadLogContext(record)}")
    }

    val startBrowserDownload: (BrowserDownloadRequest) -> String? = startBrowserDownload@{ req ->
        val fileName = runCatching { browserDownloadFileName(req) }.getOrElse { error ->
            AppLog.w("BrowserDownload", "Could not determine filename for ${req.url}", error)
            rootSnackbarMsg = "Couldn't start download: ${error.message}"
            return@startBrowserDownload null
        }
        val catalogGame = inAppBrowserCatalogGame
        scope.launch {
            if (browserSettings.downloadBackend == BrowserDownloadBackend.ExternalBrowser) {
                try {
                    val record = createExternalBrowserDownload(req, catalogGame, fileName)
                    handOffExternalBrowserDownload(record)
                    rootSnackAction = Triple(
                        "Opened ${record.fileName} in external browser",
                        "View",
                    ) { openDownloadsTick++ }
                } catch (error: Exception) {
                    AppLog.w(
                        "BrowserDownload",
                        "External browser handoff failed for ${req.url}",
                        error,
                    )
                    rootSnackbarMsg = "Couldn't open browser download: ${error.message}"
                }
                return@launch
            }
            val result = enqueueBrowserDownload(req, catalogGame, fileName)
            val record = result.getOrElse { error ->
                AppLog.w("BrowserDownload", "Download enqueue failed for ${req.url}", error)
                rootSnackbarMsg = "Couldn't start download: ${error.message}"
                return@launch
            }
            try {
                DownloadStore.add(context.applicationContext, record)
                rootSnackAction = Triple("Downloading ${record.fileName}\u2026", "View") {
                    openDownloadsTick++
                }
            } catch (error: Exception) {
                discardDownloadAttempt(record)
                AppLog.w("BrowserDownload", "Could not persist ${downloadLogContext(record)}", error)
                rootSnackbarMsg = "Couldn't track download: ${error.message}"
            }
        }
        fileName
    }
    val cancelBrowserDownload: (DownloadRecord) -> Unit = { rec ->
        scope.launch {
            if (rec.backend == DownloadBackend.ExternalBrowser) {
                DownloadStore.update(context.applicationContext, rec.id) {
                    if (it.state != DownloadState.Running) it
                    else it.copy(state = DownloadState.Cancelled, error = null)
                }
                externalDownloadObservations.remove(rec.id)
                rootSnackbarMsg = "Stopped tracking ${rec.fileName}"
                return@launch
            }
            if (!terminalTransitionIds.add(rec.id)) {
                rootSnackbarMsg = "${rec.fileName} is finishing"
                return@launch
            }
            try {
                val current = DownloadStore.records.value.firstOrNull { it.id == rec.id }
                if (current?.state != DownloadState.Running) {
                    rootSnackbarMsg = "${rec.fileName} already completed"
                    return@launch
                }
                DownloadStore.update(context.applicationContext, rec.id) {
                    if (it.state != DownloadState.Running) it
                    else it.copy(
                        state = DownloadState.Cancelled,
                        stagedPath = null,
                        targetPath = null,
                        error = null,
                    )
                }
                discardDownloadAttempt(rec)
                if (DownloadStore.records.value.firstOrNull { it.id == rec.id }?.state ==
                    DownloadState.Cancelled
                ) {
                    AppLog.i("BrowserDownload", "CANCELLED ${downloadLogContext(rec)}")
                    rootSnackbarMsg = "Cancelled ${rec.fileName}"
                } else {
                    rootSnackbarMsg = "${rec.fileName} already completed"
                }
            } finally {
                terminalTransitionIds.remove(rec.id)
            }
        }
    }
    val retryBrowserDownload: (DownloadRecord) -> Unit = { rec ->
        scope.launch {
            if (rec.backend == DownloadBackend.ExternalBrowser) {
                try {
                    if (!canOpenInExternalBrowser(rec.url)) {
                        error("No external browser can open this download")
                    }
                    val now = System.currentTimeMillis()
                    val roots = externalDownloadSearchRoots()
                    val replacement = rec.copy(
                        state = DownloadState.Running,
                        createdAt = now,
                        destPath = null,
                        totalBytes = -1,
                        finalizedBytes = 0,
                        externalSearchRoots = roots,
                        externalBaseline = withContext(Dispatchers.IO) {
                            snapshotExternalDownloadFiles(roots)
                        },
                        error = null,
                    )
                    DownloadStore.update(context.applicationContext, rec.id) {
                        if (it.createdAt != rec.createdAt) it else replacement
                    }
                    externalDownloadObservations.remove(rec.id)
                    launchExternalBrowserDownload(replacement)
                    AppLog.i(
                        "BrowserDownload",
                        "EXTERNAL_RETRY ${downloadLogContext(replacement)}",
                    )
                    rootSnackbarMsg = "Reopened ${rec.fileName} in external browser"
                } catch (error: Exception) {
                    DownloadStore.update(context.applicationContext, rec.id) {
                        if (it.backend != DownloadBackend.ExternalBrowser ||
                            it.state != DownloadState.Running
                        ) it
                        else it.copy(state = DownloadState.Failed, error = error.message)
                    }
                    rootSnackbarMsg = "Couldn't reopen browser download: ${error.message}"
                }
                return@launch
            }
            if (rec.state == DownloadState.FinalizationFailed) {
                DownloadStore.update(context.applicationContext, rec.id) {
                    if (it.createdAt != rec.createdAt ||
                        it.state != DownloadState.FinalizationFailed
                    ) it
                    else it.copy(
                        state = DownloadState.Finalizing,
                        finalizedBytes = 0,
                        error = null,
                    )
                }
                rootSnackbarMsg = "Retrying finalization for ${rec.fileName}"
                return@launch
            }
            if (!terminalTransitionIds.add(rec.id)) {
                rootSnackbarMsg = "${rec.fileName} is finishing"
                return@launch
            }
            try {
                val current = DownloadStore.records.value.firstOrNull { it.id == rec.id }
                if (current == null ||
                    current.createdAt != rec.createdAt ||
                    current.state == DownloadState.Completed ||
                    current.state == DownloadState.Finalizing
                ) {
                    rootSnackbarMsg = "${rec.fileName} is already finishing"
                    return@launch
                }
                discardDownloadAttempt(rec)
                val result = enqueueBrowserDownload(rec.retryRequest(), rec.catalogGame, rec.fileName)
                val replacement = result.getOrNull()
                if (replacement != null) {
                    try {
                        DownloadStore.replace(context.applicationContext, rec.id, replacement)
                        observedDownloadStatuses.remove(rec.id)
                        AppLog.i(
                            "BrowserDownload",
                            "RESTART oldId=${rec.id} ${downloadLogContext(replacement)}",
                        )
                        rootSnackbarMsg = "Restarted ${replacement.fileName}"
                    } catch (error: Exception) {
                        discardDownloadAttempt(replacement)
                        AppLog.w(
                            "BrowserDownload",
                            "RESTART_PERSIST_FAILED ${downloadLogContext(rec)}",
                            error,
                        )
                        rootSnackbarMsg = "Couldn't track restarted download: ${error.message}"
                    }
                } else {
                    val error = result.exceptionOrNull()
                        ?: IllegalStateException("Download restart failed")
                    DownloadStore.update(context.applicationContext, rec.id) {
                        if (it.createdAt != rec.createdAt || it.state.isActive) it
                        else it.copy(
                            state = DownloadState.Failed,
                            stagedPath = null,
                            targetPath = null,
                            error = "Restart failed: ${error.message}",
                        )
                    }
                    AppLog.w(
                        "BrowserDownload",
                        "RESTART_FAILED ${downloadLogContext(rec)}",
                        error,
                    )
                    rootSnackbarMsg = "Couldn't restart ${rec.fileName}: ${error.message}"
                }
            } finally {
                terminalTransitionIds.remove(rec.id)
            }
        }
    }
    val openLink: (String) -> Unit = { url ->
        inAppBrowserCatalogGame = null
        if (browserSettings.openLinksInApp) {
            inAppBrowserUrl = url
            inAppBrowserMinimized = false
        }
        else openExternalUrl(context, url)
    }

    LaunchedEffect(rootSnackbarMsg) {
        rootSnackbarMsg?.let {
            rootSnackbarHostState.showSnackbar(it)
            rootSnackbarMsg = null
        }
    }
    LaunchedEffect(rootSnackAction) {
        rootSnackAction?.let { (msg, label, action) ->
            val res = rootSnackbarHostState.showSnackbar(
                msg, actionLabel = label, duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) action()
            rootSnackAction = null
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
            if (!installedShellReady && tab == Tab.Installed) {
                StartupPlaceholder()
            }
            if (installedShellReady) {
                Box(
                    modifier = if (tab == Tab.Installed) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.size(0.dp).clearAndSetSemantics { }
                    },
                ) {
                    InstalledScreen(
                        catalog = catalog,
                        sharedRepo = repo,
                        sharedLabels = catalogLabels,
                        onLabelsChange = { catalogLabels = it },
                        onScreenshotTabChange = { tab = it },
                        onScreenshotCatalogQuery = { screenshotCatalogQuery = it },
                        onInstalledPackagesChange = { installedPackageNames = it },
                        navigationPackageName = installedNavigationPackage,
                        navigationRequestId = navigationRequestId,
                        onNavigationConsumed = { installedNavigationPackage = null },
                        onNavigateToCatalog = { mapping ->
                            navigationRequestId++
                            installedNavigationPackage = null
                            catalogNavigationIdentity = confirmedCatalogNavigationIdentity(mapping)
                            tab = Tab.Catalog
                        },
                        openLink = openLink,
                        openDownloadsTick = openDownloadsTick,
                        onCancelDownload = cancelBrowserDownload,
                        onRetryDownload = retryBrowserDownload,
                        onOpenDownloadInCatalog = { game ->
                            val identity = catalogNavigationIdentity(game)
                            if (identity == null) {
                                rootSnackbarMsg = "This download has no catalog identity."
                            } else {
                                navigationRequestId++
                                installedNavigationPackage = null
                                catalogNavigationIdentity = identity
                                tab = Tab.Catalog
                            }
                        },
                        onRestoreBrowser = inAppBrowserUrl?.let {
                            { inAppBrowserMinimized = false }
                        },
                    )
                }
            }
            if (installedShellReady) {
                Box(
                    modifier = if (tab == Tab.Catalog) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.size(0.dp).clearAndSetSemantics { }
                    },
                ) {
                    CatalogScreen(
                        catalog = catalog,
                        labels = catalogLabels,
                        retainedState = catalogScreenState,
                        mappings = mappings,
                        installedPackageNames = installedPackageNames,
                        taxonomy = groupTaxonomy,
                        screenshotQuery = screenshotCatalogQuery,
                        navigationIdentity = catalogNavigationIdentity,
                        navigationRequestId = navigationRequestId,
                        onOpenThread = { game ->
                            if (browserSettings.openLinksInApp) {
                                inAppBrowserCatalogGame = game
                                inAppBrowserUrl = game.canonicalUrl
                                inAppBrowserMinimized = false
                            } else {
                                openExternalUrl(context, game.canonicalUrl)
                            }
                        },
                        onGoToInstalled = { packageName ->
                            navigationRequestId++
                            catalogNavigationIdentity = null
                            installedNavigationPackage = packageName
                            tab = Tab.Installed
                        },
                        onOpenDownloads = { openDownloadsTick++ },
                    )
                }
            }
            if (inAppBrowserMinimized) {
                inAppBrowserUrl?.let { browserUrl ->
                    MinimizedBrowserCard(
                        url = browserUrl,
                        onRestore = { inAppBrowserMinimized = false },
                        onClose = {
                            inAppBrowserUrl = null
                            inAppBrowserCatalogGame = null
                            inAppBrowserMinimized = false
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    )
                }
            }
        }
    }

    inAppBrowserUrl?.takeUnless { inAppBrowserMinimized }?.let { browserUrl ->
        val configuredPopupHosts = appConfig.effectiveBrowserPopupAllowlist.toSet()
        InAppBrowser(
            url = browserUrl,
            popupAllowlist = effectivePopupAllowlist(configuredPopupHosts, browserSettings).toList(),
            popupBlocklist = browserSettings.blockedPopupHosts.toList(),
            onStartDownload = { req -> startBrowserDownload(req) },
            onAllowPopupHost = { host ->
                scope.launch {
                    BrowserPrefs.allowPopupHost(
                        context.applicationContext,
                        host,
                        configuredPopupHosts,
                    )
                }
            },
            onBlockPopupHost = { host ->
                scope.launch { BrowserPrefs.blockPopupHost(context.applicationContext, host) }
            },
            onOpenExternally = { link ->
                openExternalUrl(context, link)
            },
            onPopupBlocked = { blockedUrl, count ->
                rootSnackbarMsg = "Popup blocked ($count): $blockedUrl"
            },
            onOpenCatalog = {
                inAppBrowserMinimized = true
                tab = Tab.Catalog
            },
            onOpenDownloads = {
                inAppBrowserMinimized = true
                openDownloadsTick++
            },
            onUrlChanged = { inAppBrowserUrl = it },
            onDismiss = {
                inAppBrowserUrl = null
                inAppBrowserCatalogGame = null
                inAppBrowserMinimized = false
            },
        )
    }
}

@Composable
private fun MinimizedBrowserCard(
    url: String,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(max = 340.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Language, contentDescription = null)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("Browser minimized", style = MaterialTheme.typography.titleSmall)
                Text(
                    Uri.parse(url).host ?: url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRestore) { Text("Return") }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close browser")
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

/**
 * Holds the state for the library cleanup/organization features in a single object so InstalledScreen
 * gains only one local variable instead of ~10. That matters: InstalledScreen is an enormous composable
 * and adding many locals pushed its generated method past a register-count threshold that triggers a
 * Kotlin/Compose codegen VerifyError at runtime. One holder keeps the register footprint flat.
 */
private class LibraryExtrasState {
    var bulkDeleteConfirm by mutableStateOf<List<AppRow>?>(null)
    var bulkTagTarget by mutableStateOf<List<AppRow>?>(null)
    var bulkStatusTarget by mutableStateOf<List<AppRow>?>(null)
    var storageDashboardOpen by mutableStateOf(false)
    var duplicatesOpen by mutableStateOf(false)
    var collectionsOpen by mutableStateOf(false)
    var stateFilter by mutableStateOf<GameState?>(null)
    var lastPlayed by mutableStateOf<Map<String, Long>>(emptyMap())
    var gameStates by mutableStateOf<Map<String, GameState>>(emptyMap())
}

/**
 * Non-inline @Composable wrapper. Wrapping a block of content in `DialogHost { … }` moves that
 * content into a separate lambda method (with full closure access to the caller's locals), which is
 * how the very large InstalledScreen composable keeps its own method under the JVM 64 KB limit.
 */
@Composable
private fun DialogHost(content: @Composable () -> Unit) {
    content()
}

private data class RefreshProgress(
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

/**
 * Consolidates InstalledScreen's function-scope dialog/UI state into one object. This keeps the
 * giant InstalledScreen composable's register footprint flat: each DialogHost scope now captures a
 * single [st] reference instead of ~150 individual state vars, which is what let the R8-minified
 * release build pass verification (the Compose register-coalescing VerifyError for oversized
 * composables). States whose initializers depend on locals (filters/context) stay in the composable.
 */
private class InstalledScreenState {
    var librarySearchBounds by mutableStateOf<Rect?>(null)
    var updateCheckResults by mutableStateOf<List<UpdateCheckResult>>(emptyList())
    var downloadProgress by mutableStateOf<UpdateDownloadProgress?>(null)
    var apps by mutableStateOf<List<InstalledApp>>(emptyList())
    var appsInitialLoading by mutableStateOf(true)
    var renPySaveAssociations by mutableStateOf<Map<String, List<RenPySaveAssociation>>>(emptyMap())
    var renPySaveLocations by mutableStateOf<List<RenPySaveLocation>>(emptyList())
    var renPyManualAssociations by mutableStateOf<Map<String, RenPySaveManualAssociation>>(emptyMap())
    var renPySaveLastScannedAt by mutableStateOf(0L)
    var renPySaveScanning by mutableStateOf(false)
    var renPySaveScanRequest by mutableStateOf(0)
    var renPySaveScanManualRequest by mutableStateOf(false)
    var rpgmSaveAssociations by mutableStateOf<Map<String, List<RpgmSaveLocation>>>(emptyMap())
    var rpgmSaveLocations by mutableStateOf<List<RpgmSaveLocation>>(emptyList())
    var rpgmManualAssociations by mutableStateOf<Map<String, RpgmSaveManualAssociation>>(emptyMap())
    var rpgmSaveLastScannedAt by mutableStateOf(0L)
    var rpgmSaveScanning by mutableStateOf(false)
    var rpgmSaveScanRequest by mutableStateOf(0)
    var rpgmSaveScanManualRequest by mutableStateOf(false)
    var joiPlaySizeInfo by mutableStateOf<Map<String, JoiPlayScanner.SizeInfo>>(emptyMap())
    var joiPlaySizeScanning by mutableStateOf(false)
    var joiPlaySizeScanProgress by mutableStateOf<JoiPlayScanner.SizeProgress?>(null)
    var joiPlaySizeScanningFolders by mutableStateOf<Set<String>>(emptySet())
    var joiPlaySizeScanRequest by mutableStateOf(0)
    var joiPlaySizeScanForce by mutableStateOf(false)
    var joiPlaySizeAutoRequestedKeys by mutableStateOf<Set<String>>(emptySet())
    var mappingOverrides by mutableStateOf<Map<String, AppMapping>>(emptyMap())
    var catalogById by mutableStateOf<Map<Int, CatalogGame>?>(null)
    var catalogReloadTick by mutableStateOf(0)
    var checking by mutableStateOf(false)
    var checkProgress by mutableStateOf<Pair<Int, Int>?>(null)
    var snackbarMsg by mutableStateOf<String?>(null)
    var autoBackupList by mutableStateOf<List<AutoBackupManager.BackupEntry>>(emptyList())
    var autoBackupDialogOpen by mutableStateOf(false)
    var autoBackupConfirmRestore by mutableStateOf<AutoBackupManager.BackupEntry?>(null)
    var supportDialogOpen by mutableStateOf(false)
    var topStatusOpen by mutableStateOf(false)
    var diagnosticsSummaryOpen by mutableStateOf(false)
    var savedPasswordsOpen by mutableStateOf(false)
    var matchResearchProgress by mutableStateOf<MatchResearchProgress?>(null)
    var permissionRationale by mutableStateOf<PermissionRationale?>(null)
    var installWarningOpen by mutableStateOf(false)
    var installWarningOpensBulk by mutableStateOf(false)
    var joiplaySettingsOpen by mutableStateOf(false)
    var installPickerOpen by mutableStateOf(false)
    var patchPickerOpen by mutableStateOf(false)
    var joiplayBackupFilePickerOpen by mutableStateOf(false)
    var backupScopedPickerOpen by mutableStateOf(false)
    var backupScopedRootUri by mutableStateOf<Uri?>(null)
    var importBackupPickerOpen by mutableStateOf(false)
    var importBackupAccessDisclosureOpen by mutableStateOf(false)
    var importBackupScopedRootUri by mutableStateOf<Uri?>(null)
    var exportBackupPickerOpen by mutableStateOf(false)
    var cleanupRootPickerOpen by mutableStateOf(false)
    var cleanupSavePickerOpen by mutableStateOf(false)
    var cleanupRootPath by mutableStateOf<String?>(null)
    var cleanupReport by mutableStateOf<CleanupReviewReport?>(null)
    var cleanupScanning by mutableStateOf(false)
    var cleanupProgress by mutableStateOf<CleanupReviewReporter.Progress?>(null)
    var cleanupOrphanRemoveTarget by mutableStateOf<CleanupGameEntry?>(null)
    var renPySaveAssociationPicker by mutableStateOf<RenPySaveLocation?>(null)
    var renPySaveEditorTarget by mutableStateOf<AppRow?>(null)
    var renPyAddFolderTarget by mutableStateOf<AppRow?>(null)
    var rpgmSaveAssociationPicker by mutableStateOf<RpgmSaveLocation?>(null)
    var rpgmSaveViewerTarget by mutableStateOf<AppRow?>(null)
    var rpgmAddFolderTarget by mutableStateOf<AppRow?>(null)
    var saveBackupBrowserOpen by mutableStateOf(false)
    var apkInstallConfirm by mutableStateOf<java.io.File?>(null)
    var pendingExtractedApkRoot by mutableStateOf<java.io.File?>(null)
    var pendingExtractedSourceArchive by mutableStateOf<java.io.File?>(null)
    var pendingExtractedArchiveTitle by mutableStateOf<String?>(null)
    var extractTarget by mutableStateOf<InstallRouting.Target?>(null)
    var pendingArchiveDestination by mutableStateOf<PendingArchiveDestination?>(null)
    var upgradePrompt by mutableStateOf<ManagedUpgradePrompt?>(null)
    var archiveAnalysisInProgress by mutableStateOf<java.io.File?>(null)
    var winlatorExecutableCandidate by mutableStateOf<WinlatorExecutableCandidate?>(null)
    var managedRunnerInspection by mutableStateOf<ManagedGameInspection?>(null)
    var managedInstallSourceArchive by mutableStateOf<java.io.File?>(null)
    var managedInstallOwnsFiles by mutableStateOf(false)
    var pendingInstallCatalogGame by mutableStateOf<CatalogGame?>(null)
    var managedEngineUpdateProgress by mutableStateOf<ManagedEngineDiscoveryProgress?>(null)
    var managedEngineBulkProgress by mutableStateOf<ManagedEngineDiscoveryProgress?>(null)
    var bucketExtract by mutableStateOf<InstallRouting.Bucket?>(null)
    var winlatorConfigureTarget by mutableStateOf<InstalledApp?>(null)
    var gameSettingsTarget by mutableStateOf<AppRow?>(null)
    var winlatorInstallerTarget by mutableStateOf<InstalledApp?>(null)
    var winlatorDeleteConfirm by mutableStateOf<AppRow?>(null)
    var winlatorIsolationConfirm by mutableStateOf<InstalledApp?>(null)
    var pendingWinlatorOperation by mutableStateOf<PendingWinlatorOperation?>(null)
    var pendingAutomaticWinlatorRecovery by mutableStateOf<WinlatorPortableRecoveryCandidate?>(null)
    var activeWinlatorRecoveryManagedGameId by mutableStateOf<String?>(null)
    var pendingWinlatorAutoRecommend by mutableStateOf<PendingWinlatorAutoRecommend?>(null)
    var winlatorSubmissionQueue by mutableStateOf<List<WinlatorConfigSubmission>>(emptyList())
    var winlatorQueueTick by mutableStateOf(0)
    var pendingWinlatorRetry by mutableStateOf<Pair<String, String>?>(null)
    var pendingWinlatorRefresh by mutableStateOf<PendingWinlatorRefresh?>(null)
    var bulkInstallPickerOpen by mutableStateOf(false)
    var batch by mutableStateOf<BatchInstallSession?>(null)
    var batchPreflight by mutableStateOf<BatchInstallPreflight?>(null)
    var userTags by mutableStateOf<Map<String, Set<String>>>(emptyMap())
    var editTagsFor by mutableStateOf<InstalledApp?>(null)
    var upgradeGuidanceDismissed by mutableStateOf(false)
    var pendingPackageRefresh by mutableStateOf<PendingPackageRefresh?>(null)
    var hasSavesOnlyFilter by mutableStateOf(false)
    var sortMenuOpen by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var subCatalogOpen by mutableStateOf(false)
    var subInstallGamesOpen by mutableStateOf(false)
    var patchRollbackDialogOpen by mutableStateOf(false)
    var subSaveToolsOpen by mutableStateOf(false)
    var subBackupOpen by mutableStateOf(false)
    var subMaintenanceOpen by mutableStateOf(false)
    var subViewOpen by mutableStateOf(false)
    var subLogsOpen by mutableStateOf(false)
    var settingsDialogOpen by mutableStateOf(false)
    var downloadsOpen by mutableStateOf(false)
    var downloadFolderPickerOpen by mutableStateOf(false)
    var catalogRefreshDialogOpen by mutableStateOf(false)
    var saveLocationsOpen by mutableStateOf(false)
    var overwriteManualMatches by mutableStateOf(false)
    var resetAcksOnRefresh by mutableStateOf(false)
    var screenshotWalkthroughRunning by mutableStateOf(false)
    var launchScreenshotRunning by mutableStateOf(false)
    var screenshotPanel by mutableStateOf<ScreenshotPanel?>(null)
    var apkPostInstall by mutableStateOf<ApkPostInstall?>(null)
    var apkInstalling by mutableStateOf(false)
    var apkInstallProgress by mutableStateOf<ApkInstallProgress?>(null)
    var refreshProgress by mutableStateOf<RefreshProgress?>(null)
    var refreshCancelled by mutableStateOf(false)
    var refreshCancelledNote by mutableStateOf(false)
    var ambiguousCatalogMatches by mutableStateOf<List<AmbiguousCatalogMatch>>(emptyList())
    var unmappedReviewMatches by mutableStateOf<List<AmbiguousCatalogMatch>>(emptyList())
    var alreadyMatchedReviewMatches by mutableStateOf<List<AlreadyMatchedCatalogMatch>>(emptyList())
    var unmappedReviewOpen by mutableStateOf(false)
    var unmappedReviewLoading by mutableStateOf(false)
    var unmatchedFoundPromptOpen by mutableStateOf(false)
    var manualOverrideMatches by mutableStateOf<List<ManualOverrideMatch>>(emptyList())
    var manualOverrideReviewOpen by mutableStateOf(false)
    var joiplayImportBusy by mutableStateOf(false)
    var joiplayImportError by mutableStateOf<String?>(null)
    var isFirstRunFlow by mutableStateOf(false)
    var firstRunRefreshPending by mutableStateOf(false)
    var firstRunHintOpen by mutableStateOf(false)
    var f95MigrationPromptOpen by mutableStateOf(false)
    var f95MigrationError by mutableStateOf<String?>(null)
    var joiplayBackupPickerFirstRun by mutableStateOf(false)
    var joiplayBackupAccessDisclosureFirstRun by mutableStateOf<Boolean?>(null)
    var joiPlayDeleteConfirm by mutableStateOf<AppRow?>(null)
    var joiPlayGrantAskFor by mutableStateOf<AppRow?>(null)
    var hasBackupOnlyFilter by mutableStateOf(false)
    var joiPlayBackupPkgs by mutableStateOf<Set<String>>(emptySet())
    var dialogApp by mutableStateOf<AppRow?>(null)
    var returnToUnmappedAfterDialog by mutableStateOf(false)
    var fullSizeImageUrl by mutableStateOf<String?>(null)
    var joiPlayDetecting by mutableStateOf<AppRow?>(null)
    var manualVersionTarget by mutableStateOf<AppRow?>(null)
    var manualDateTarget by mutableStateOf<Pair<AppRow, CatalogGame?>?>(null)
    var joiPlayBackupTarget by mutableStateOf<Pair<AppRow, JoiPlayBackupAction>?>(null)
    var aboutOpen by mutableStateOf(false)
    var joiPlayInstalled by mutableStateOf(false)
    var kirikiroidInstalled by mutableStateOf(false)
    var kirikiroidDeleteConfirm by mutableStateOf<AppRow?>(null)
    var joiPlayUpdateStatus by mutableStateOf<JoiPlayUpdateChecker.Status?>(null)
    var joiPlayUpdatesDialogOpen by mutableStateOf(false)
    var autoSyncStatus by mutableStateOf<String?>(null)
    var joiPlayDeleting by mutableStateOf<GameDeleteUiState?>(null)
    /** Per-plugin version control (installed engine plugins vs joiplay.net latest). */
    var joiPlayPluginReport by mutableStateOf<JoiPlayPluginChecker.Report?>(null)
    /** A launch that needs an engine plugin AGM can offer to install. */
    var pluginLaunchRequest by mutableStateOf<JoiPlayLaunchResult.PluginRequired?>(null)
    /** The active plugin install/update prompt (download + why), or null. */
    var pluginInstall by mutableStateOf<PluginInstallState?>(null)
    /** Byte progress of the active plugin download, or null when idle. */
    var pluginInstallProgress by mutableStateOf<Pair<Long, Long>?>(null)
}

/** A pending plugin install/update prompt: which manifest download, and a user-facing reason. */
data class PluginInstallState(
    val download: JoiPlayDownload,
    val reason: String,
)

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
    onInstalledPackagesChange: (Set<String>) -> Unit = {},
    navigationPackageName: String? = null,
    navigationRequestId: Long = 0L,
    onNavigationConsumed: () -> Unit = {},
    onNavigateToCatalog: (AppMapping) -> Unit = {},
    openLink: (String) -> Unit = {},
    openDownloadsTick: Int = 0,
    onCancelDownload: (DownloadRecord) -> Unit = {},
    onRetryDownload: (DownloadRecord) -> Unit = {},
    onOpenDownloadInCatalog: (CatalogGame) -> Unit = {},
    onRestoreBrowser: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val focusManager = LocalFocusManager.current
    val st = remember { InstalledScreenState() }
    val scope = rememberCoroutineScope()
    val winlatorRecoverySingleFlight = remember { WinlatorRecoverySingleFlight() }
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

    var updateCheckRequestId by remember { mutableIntStateOf(0) }
    val catalogLabels = sharedLabels
    val catalogTranslationSettingsFlow = remember(context.applicationContext) {
        CatalogPrefs.observeTranslationSettings(context.applicationContext)
    }
    val catalogTranslationSettings by catalogTranslationSettingsFlow
        .collectAsState(initial = CatalogPrefs.TranslationSettings())

    var hasUsage by remember { mutableStateOf(InstalledAppsScanner.hasUsageAccess(context)) }
    val mappings by repo.mappings.collectAsState(initial = emptyMap())
    val hidden by repo.hidden.collectAsState(initial = emptySet())
    val themeMode by ThemePrefs.observe(context.applicationContext).collectAsState(initial = AppThemeMode.System)
    val cardColors by CardColorPrefs.observe(context.applicationContext)
        .collectAsState(initial = CardColorSettings())
    val browserSettings by BrowserPrefs.observe(context.applicationContext).collectAsState(initial = BrowserSettings())
    val downloadRecords by DownloadStore.records.collectAsState()
    // Reactive app config — UI recomposes on Import / drop-in pickup / clear.
    val appConfig by AppConfigStore.observe(context.applicationContext).collectAsState()
    val catalogContentRevision by catalog.contentRevision.collectAsState()
    val catalogBuildProgress by catalog.catalogBuildProgress.collectAsState()
    // Only catalog rows referenced by installed mappings are retained in memory.
    LaunchedEffect(st.catalogReloadTick, catalogContentRevision, mappings) {
        val threadIds = mappings.values.mapNotNull(AppMapping::f95CatalogThreadId).toSet()
        try {
            st.catalogById = catalog.catalogGamesByF95ThreadIds(threadIds)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            AppLog.e("Catalog", "Could not load mapped catalog snapshots", error)
            st.catalogById = emptyMap()
        }
    }

    // ---- Persisted filter state ----
    val initialFilters = remember { FilterPrefs.load(context.applicationContext) }
    var showHidden by remember { mutableStateOf(initialFilters.showHidden) }

    // Reconcile persisted mappings against each live scan result: publish the
    // installed package set for the Catalog "Installed" badge, and delete stale
    // mappings whose game is gone (preserving any that carry user data). Guarded
    // against empty/partial scans so a denied permission can't wipe good data.
    LaunchedEffect(st.apps) {
        if (st.apps.isEmpty()) return@LaunchedEffect
        val installedPkgs = st.apps.mapTo(HashSet()) { it.packageName }
        val androidScanned = st.apps.any { it.source == AppSource.Android }
        val joiplayScanned = st.apps.any {
            it.source == AppSource.Managed || it.source == AppSource.JoiPlay
        }
        onInstalledPackagesChange(installedPkgs)
        val result = repo.pruneStale(installedPkgs, androidScanned, joiplayScanned)
        if (result.removed > 0) {
            AppLog.i(
                "Mapping",
                "Reconcile removed ${result.removed} stale mapping(s); retained ${result.retainedWithData} with user data",
            )
            st.snackbarMsg = "Removed ${result.removed} stale mapping" +
                if (result.removed == 1) "" else "s"
        }
    }
    // When the JoiPlay install warning is confirmed, open the bulk picker instead of the single one.
    // Pending APK install confirmation: source file + non-persistent "delete on success".
    var nestedArchiveDepth by remember { mutableIntStateOf(0) }
    // Holds a pending extract confirmation: source file + chosen destination root.
    var extractConfirm by remember {
        mutableStateOf<Pair<java.io.File, ArchiveExtractor.ExtractRoot>?>(null)
    }
    var installDestinationPicker by remember {
        mutableStateOf<PendingInstallDestinationSelection?>(null)
    }
    // Install-routing state for non-library buckets (Videos/Other).
    // After a Winlator game is created, auto-fetch its recommended config and apply it (config
    // surface). Holds (gameId, title); the effect below builds and enqueues the submission.
    // Ordered queue of Winlator submissions to apply back-to-back (e.g. a config round-trip then a
    // settings round-trip when "Review & apply" spans both surfaces). The head is launched, then
    // dropped and the next launched when its result returns; a failure clears the remainder.
    var winlatorRefreshSequence by remember { mutableIntStateOf(0) }
    // Selection-mode bulk action triggers plus the storage/collections feature state live in one
    // holder to keep InstalledScreen's local/register count flat (see LibraryExtrasState).
    val lib = remember { LibraryExtrasState() }
    remember {
        st.hasSavesOnlyFilter = initialFilters.hasSavesOnly
        st.hasBackupOnlyFilter = initialFilters.hasBackupOnly
        lib.stateFilter = initialFilters.gameState
        true
    }
    // True while a JoiPlay launch activity is in-flight (JoiPlay has no other busy signal). Used by
    // the bulk-install driver to know an item is still being handled.
    // Bulk (multi-file) install state.
    // User-defined per-game tags (packageName -> normalized tag set) + the game being edited.
    LaunchedEffect(Unit) {
        lib.gameStates = withContext(Dispatchers.IO) { GameStateStore.load(context.applicationContext) }
    }
    LaunchedEffect(Unit) {
        st.userTags = withContext(Dispatchers.IO) { UserTagsStore.load(context.applicationContext) }
    }
    val extractFlow = remember { JoiPlayExtractFlow(context.applicationContext, scope) }
    val upgradeFlow = remember { ManagedArchiveUpgradeFlow(context.applicationContext, scope) }
    val patchFlow = remember { PatchInstallFlow(context.applicationContext, scope) }
    LaunchedEffect(Unit) { patchFlow.refreshRollbackPoints() }
    remember { PasswordVault.init(context.applicationContext); true }
    LaunchedEffect(Unit) {
        st.upgradeGuidanceDismissed = JoiPlaySettingsStore.upgradeGuidanceDismissed(context.applicationContext)
    }
    // Last directory the user navigated to in the unified installer. Falls back
    // to external-storage root the first
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
            st.backupScopedRootUri = uri
            st.importBackupScopedRootUri = uri
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
    // Shared "hide the progress dialog, keep working in AGM" state for every archive operation.
    // Purely a UI concern: the coroutines below keep running and stay cancellable either way.
    var progressMinimize by remember { mutableStateOf(ProgressMinimizeState()) }
    val activeArchiveProgress: MinimizedProgress? = when {
        extractFlow.inProgress -> MinimizedProgress(
            kind = ProgressOperationKind.Extract,
            archiveName = extractFlow.archiveName ?: "archive",
            phase = extractFlow.phase,
            progress = extractFlow.progress,
            batchPosition = st.batch?.let { "${it.activePosition} of ${it.total}" },
            cancelEnabled = true,
        )
        upgradeFlow.inProgress -> MinimizedProgress(
            kind = ProgressOperationKind.Upgrade,
            archiveName = upgradeFlow.archiveName ?: "archive",
            phase = upgradeFlow.phase,
            progress = upgradeFlow.progress,
            batchPosition = st.batch?.let { "${it.activePosition} of ${it.total}" },
            cancelEnabled = upgradeFlow.cancellationAllowed,
        )
        patchFlow.inProgress -> MinimizedProgress(
            kind = ProgressOperationKind.Patch,
            archiveName = patchFlow.archiveName ?: "patch",
            phase = patchFlow.phase,
            progress = patchFlow.progress,
            batchPosition = null,
            cancelEnabled = patchFlow.cancellationAllowed,
        )
        else -> null
    }
    val activeProgressKey = activeArchiveProgress?.key
    val activeBatchRunId = st.batch?.takeUnless { it.isComplete }?.runId
    LaunchedEffect(activeProgressKey, activeBatchRunId) {
        progressMinimize = ProgressMinimize.onActiveOperation(
            progressMinimize,
            activeProgressKey,
            activeBatchRunId,
        )
    }
    val progressIsMinimized = progressMinimize.minimized && activeArchiveProgress != null
    fun minimizeProgress() {
        progressMinimize = ProgressMinimize.minimize(progressMinimize, activeProgressKey, activeBatchRunId)
        AppLog.i("Extract", "Progress minimized for $activeProgressKey batch=$activeBatchRunId")
    }
    fun cancelActiveProgress() {
        when (activeArchiveProgress?.kind) {
            ProgressOperationKind.Extract -> extractFlow.cancelInProgress()
            ProgressOperationKind.Upgrade -> upgradeFlow.cancelInProgress()
            ProgressOperationKind.Patch -> patchFlow.cancelInProgress()
            null -> Unit
        }
    }
    // The single source of truth for "AGM is installing on its own behalf right now".
    //
    // A bulk session outlives its run: the finished banner stays until the user closes it, and a
    // session can hold no active item at all. Only an automatic run that still owns an active item
    // may skip confirmations, pick destinations/runners by itself and convert dialogs into pauses.
    // Every unattended branch below asks these helpers, so a standalone install started while a
    // completed run's banner is on screen behaves exactly as it would with no banner at all.
    fun unattendedBatch(): BatchInstallSession? = st.batch?.takeIf { it.isUnattendedActive }
    /** Run id of the unattended run, for deferred work that must not act on a later run. */
    fun unattendedBatchRunId(): Long? = unattendedBatch()?.runId
    /** Pauses the unattended run with [message]. Returns false when no run is unattended. */
    fun pauseAutomaticBatch(message: String): Boolean {
        val batch = unattendedBatch() ?: return false
        st.batch = batch.pause(message)
        return true
    }
    /** Same, but only if the unattended run is still the one identified by [runId]. */
    fun pauseUnattendedRun(runId: Long?, message: String): Boolean {
        if (runId == null) return false
        val batch = st.batch?.takeIf { it.isUnattendedRun(runId) } ?: return false
        st.batch = batch.pause(message)
        return true
    }
    // AGM installs one thing at a time. Who owns the install pipeline is derived from the running
    // flows and the bulk run only — never from `progressMinimize`. That is the invariant that makes
    // minimizing a non-cancellable operation safe: hiding the dialog cannot widen what AGM accepts,
    // and every re-entry point below refuses identically whether the dialog is shown or minimized.
    fun installOwner(): InstallOperationOwner? = InstallOperationGuard.firstOwner(
        BulkRunGuard.ownerOf(st.batchPreflight, st.batch),
        InstallOperationGuard.ownerOf(activeArchiveProgress),
        upgradeFlow.activeOperation,
        patchFlow.activeOperation,
        extractFlow.activeOperation,
        // The APK installer and the archive analyser are install steps with no progress dialog of
        // their own; neither can be interrupted once handed to Android/the reader.
        when {
            st.apkInstalling -> InstallOperationOwner(
                kind = InstallOperationKind.Extract,
                detail = st.apkInstallConfirm?.name ?: "an app",
                cancellable = false,
            )
            st.archiveAnalysisInProgress != null -> InstallOperationOwner(
                kind = InstallOperationKind.Extract,
                detail = st.archiveAnalysisInProgress?.name,
                cancellable = false,
            )
            st.pendingWinlatorOperation != null -> InstallOperationOwner(
                kind = InstallOperationKind.Extract,
                detail = "a Winlator game",
                cancellable = false,
            )
            else -> null
        },
    )
    /** The message shown when [requested] cannot start right now, or null when it may. */
    fun installRefusal(requested: InstallOperationKind): String? {
        if (requested == InstallOperationKind.Bulk) {
            BulkRunGuard.refuse(st.batchPreflight, st.batch)?.let { return it }
        }
        return InstallOperationGuard.refuse(installOwner(), requested)
    }
    /** Gates a user-facing entry point. Returns true when the action was refused and reported. */
    fun refuseInstallEntry(requested: InstallOperationKind): Boolean {
        val reason = installRefusal(requested) ?: return false
        st.snackbarMsg = reason
        AppLog.w("Install", "Refused a $requested entry point: $reason")
        return true
    }
    /** Surfaces a flow's own refusal. Returns true when the start was refused. */
    fun reportInstallRefusal(outcome: InstallStartOutcome): Boolean {
        val reason = outcome.refusalOrNull ?: return false
        if (!pauseAutomaticBatch(reason)) st.snackbarMsg = reason
        AppLog.w("Install", "Install start refused: $reason")
        return true
    }
    fun joiPlayIsInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo("cyou.joiplay.joiplay", 0)
        true
    }.getOrDefault(false)
    // Resolve a configured bucket root (Videos/Other) to a writable directory, or null if unset/bad.
    suspend fun resolveBucketDir(bucket: InstallRouting.Bucket): java.io.File? {
        val kind = when (bucket) {
            InstallRouting.Bucket.Videos -> JoiPlaySettingsStore.InstallRoot.Videos
            InstallRouting.Bucket.Other -> JoiPlaySettingsStore.InstallRoot.Other
        }
        val uriStr = JoiPlaySettingsStore.installRootUri(context.applicationContext, kind) ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val dir = if (uri.scheme == "file") {
            uri.path?.let { java.io.File(it) }
        } else {
            SharedGamesRoot.resolveTreeUri(context.applicationContext, uri)
        }
        return dir?.takeIf { it.isDirectory && it.canWrite() }
    }
    // Extract a non-library bucket (Videos/Other) into its configured root folder.
    fun prepareBucketExtract(file: java.io.File, bucket: InstallRouting.Bucket) {
        scope.launch {
            val dir = resolveBucketDir(bucket)
            if (dir == null) {
                val message = when (bucket) {
                    InstallRouting.Bucket.Videos -> "Set a Videos folder in Non-Android game settings first."
                    InstallRouting.Bucket.Other -> "Set an Other folder in Non-Android game settings first."
                }
                if (!pauseAutomaticBatch(message)) st.snackbarMsg = message
                return@launch
            }
            st.bucketExtract = bucket
            st.extractTarget = null
            st.pendingExtractedSourceArchive = null
            st.pendingExtractedArchiveTitle = file.nameWithoutExtension.trim().ifBlank { null }
            val root = ArchiveExtractor.ExtractRoot.FileRoot(dir)
            if (unattendedBatch() != null) {
                st.pendingExtractedSourceArchive =
                    file.takeIf {
                        it.canWrite() && JoiPlaySettingsStore.deleteAfterInstall(context)
                    }
                reportInstallRefusal(extractFlow.start(Uri.fromFile(file), root))
            } else {
                extractConfirm = file to root
            }
        }
    }
    // Move an extracted game directory into [destParent], preserving its name (uniquified on
    // collision). Uses a fast rename on the same volume and falls back to copy+delete across volumes.
    suspend fun relocateDirInto(source: java.io.File, destParent: java.io.File): java.io.File? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!source.isDirectory) return@runCatching null
                if (!destParent.isDirectory && !destParent.mkdirs()) return@runCatching null
                var target = java.io.File(destParent, source.name)
                var suffix = 1
                while (target.exists()) {
                    target = java.io.File(destParent, "${source.name} ($suffix)")
                    suffix++
                }
                if (source.renameTo(target)) return@runCatching target
                source.copyRecursively(target, overwrite = false)
                source.deleteRecursively()
                target
            }.getOrNull()
        }
    // Move [source] into [destParent] under a chosen [desiredName] (uniquified on collision).
    // Fast same-volume rename, falling back to copy+delete across volumes.
    suspend fun relocateDirAs(
        source: java.io.File,
        destParent: java.io.File,
        desiredName: String,
    ): java.io.File? = withContext(Dispatchers.IO) {
        runCatching {
            if (!source.isDirectory) return@runCatching null
            if (!destParent.isDirectory && !destParent.mkdirs()) return@runCatching null
            val base = desiredName.trim().ifBlank { source.name }
            var target = java.io.File(destParent, base)
            var suffix = 1
            while (target.exists()) {
                target = java.io.File(destParent, "$base ($suffix)")
                suffix++
            }
            if (source.renameTo(target)) return@runCatching target
            source.copyRecursively(target, overwrite = false)
            source.deleteRecursively()
            target
        }.getOrNull()
    }
    // Strip characters illegal in folder names so an archive/game title is a safe directory name.
    fun sanitizeFolderName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
            .ifBlank { "Game" }
    // Find the actual game root inside a freshly extracted tree: the folder that directly contains
    // .xp3 data or a loose startup.tjs. Descends through single wrapper subfolders (an archive that
    // packs everything under one top-level directory) but never past real content.
    fun locateKirikiroidGameRoot(dir: java.io.File): java.io.File {
        var cursor = dir
        repeat(8) {
            val children = cursor.listFiles()?.toList() ?: return cursor
            val files = children.filter { it.isFile }
            val dirs = children.filter { it.isDirectory }
            val hasGame = files.any {
                val n = it.name.lowercase()
                n.endsWith(".xp3") || n == "startup.tjs"
            }
            if (hasGame) return cursor
            val meaningfulFiles = files.filterNot { it.name.startsWith(".") }
            if (dirs.size == 1 && meaningfulFiles.isEmpty()) cursor = dirs[0] else return cursor
        }
        return cursor
    }
    // Resolve the configured Kirikiroid install root to a writable directory (no shared-root
    // fallback: a distinct folder avoids the JoiPlay folder scanner also claiming these games).
    suspend fun resolveKirikiroidRootDir(): java.io.File? {
        val uriStr = JoiPlaySettingsStore.installRootUri(
            context.applicationContext,
            JoiPlaySettingsStore.InstallRoot.Kirikiroid,
        ) ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val dir = if (uri.scheme == "file") {
            uri.path?.let { java.io.File(it) }
        } else {
            SharedGamesRoot.resolveTreeUri(context.applicationContext, uri)
        }
        return dir?.takeIf { it.isDirectory && it.canWrite() }
    }
    // Verify Kirikiroid2 + its root are ready, then extract the archive into a staging folder under
    // the Kirikiroid root (finalized into a clean per-game folder once extraction completes).
    fun extractForKirikiroid(file: java.io.File) {
        scope.launch {
            if (!KirikiroidLauncher.isInstalled(context)) {
                st.snackbarMsg = "Kirikiroid2 isn't installed."
                return@launch
            }
            val rootDir = resolveKirikiroidRootDir()
            if (rootDir == null) {
                st.snackbarMsg = "Set a Kirikiroid folder in Non-Android game settings first."
                return@launch
            }
            val staging = java.io.File(rootDir, ".agm-krkr-stage-${java.util.UUID.randomUUID()}")
            if (!staging.mkdirs() && !staging.isDirectory) {
                st.snackbarMsg = "AGM couldn't create a staging folder in your Kirikiroid folder."
                return@launch
            }
            st.pendingExtractedSourceArchive = null
            st.pendingExtractedArchiveTitle = file.nameWithoutExtension.trim().ifBlank { null }
            st.extractTarget = InstallRouting.Target.Kirikiroid
            val root = ArchiveExtractor.ExtractRoot.FileRoot(staging)
            if (unattendedBatch() != null) {
                st.pendingExtractedSourceArchive =
                    file.takeIf {
                        it.canWrite() && JoiPlaySettingsStore.deleteAfterInstall(context)
                    }
                extractFlow.start(Uri.fromFile(file), root).let { outcome ->
                    if (reportInstallRefusal(outcome)) staging.deleteRecursively()
                }
            } else {
                extractConfirm = file to root
            }
        }
    }
    // Turn an extracted tree into one clean Kirikiroid game folder under the Kirikiroid root, then
    // refresh the library + size cache. Works for both the pre-extract path (staging already under
    // the root) and the post-extract path (content extracted elsewhere, relocated in here).
    suspend fun finalizeKirikiroidExtraction(
        root: ArchiveExtractor.ExtractRoot,
        sourceArchiveToDelete: java.io.File?,
        title: String?,
        keepInPlaceIfUnderRoot: Boolean = false,
    ) {
        val rootDir = resolveKirikiroidRootDir()
        val fileRoot = (root as? ArchiveExtractor.ExtractRoot.FileRoot)?.file
        if (rootDir == null || fileRoot == null) {
            ArchiveExtractor.deleteExtractedRoot(root)
            st.snackbarMsg = "Set a Kirikiroid folder in Non-Android game settings first."
            return
        }
        val gameDir = withContext(Dispatchers.IO) { locateKirikiroidGameRoot(fileRoot) }
        val desired = sanitizeFolderName(title ?: gameDir.name)
        // When the user adds an already-extracted game whose folder already lives directly
        // under the Kirikiroid root, relocating it would collide with itself and append a
        // " (1)" suffix on every re-add. Keep it in place instead.
        val alreadyInRoot = keepInPlaceIfUnderRoot && withContext(Dispatchers.IO) {
            runCatching { gameDir.parentFile?.canonicalFile == rootDir.canonicalFile }
                .getOrDefault(false)
        }
        val moved = if (alreadyInRoot) gameDir else relocateDirAs(gameDir, rootDir, desired)
        // Clean up the staging directory if it still exists separately from the moved game.
        // Never touch it when the game was kept in place (staging IS the live game folder).
        if (!alreadyInRoot && moved != fileRoot && fileRoot.exists()) {
            withContext(Dispatchers.IO) { runCatching { fileRoot.deleteRecursively() } }
        }
        sourceArchiveToDelete?.let { src ->
            withContext(Dispatchers.IO) { runCatching { if (src.exists()) src.delete() } }
        }
        if (moved == null) {
            st.snackbarMsg = "Extracted, but AGM couldn't place the game in your Kirikiroid folder."
            return
        }
        st.snackbarMsg = "Added $desired to Kirikiroid2."
        GameStorageSizeWork.enqueueImmediate(context.applicationContext)
        st.apps = scanInstalledLibrary(context)
    }
    fun apkExtractionCleanupDirectory(root: ArchiveExtractor.ExtractRoot): java.io.File? {
        val extracted = (root as? ArchiveExtractor.ExtractRoot.FileRoot)?.file ?: return null
        val tempBase = runCatching {
            java.io.File(context.cacheDir, "apk-archives").canonicalFile
        }.getOrNull()
        val parent = runCatching { extracted.parentFile?.canonicalFile }.getOrNull()
        return if (tempBase != null && parent?.parentFile == tempBase) parent else extracted
    }
    fun startOrConfirmExtraction(
        file: java.io.File,
        target: InstallRouting.Target,
        root: ArchiveExtractor.ExtractRoot,
    ) {
        st.extractTarget = target
        if (unattendedBatch() != null) {
            scope.launch {
                st.pendingExtractedSourceArchive =
                    file.takeIf {
                        it.canWrite() && JoiPlaySettingsStore.deleteAfterInstall(context)
                    }
                reportInstallRefusal(
                    extractFlow.start(
                        archiveUri = Uri.fromFile(file),
                        destRoot = root,
                        cleanupDestinationOnAbort = target == InstallRouting.Target.Android,
                    ),
                )
            }
        } else {
            extractConfirm = file to root
        }
    }
    fun prepareArchiveExtract(file: java.io.File, target: InstallRouting.Target) {
        st.pendingExtractedSourceArchive = null
        st.pendingExtractedArchiveTitle = file.nameWithoutExtension.trim().ifBlank { null }
        scope.launch {
            if (target == InstallRouting.Target.Android) {
                val tempRoot = java.io.File(
                    context.cacheDir,
                    "apk-archives/${java.util.UUID.randomUUID()}",
                )
                if (!tempRoot.mkdirs() && !tempRoot.isDirectory) {
                    st.snackbarMsg = "AGM couldn't create temporary storage for this APK archive."
                    return@launch
                }
                startOrConfirmExtraction(
                    file,
                    target,
                    ArchiveExtractor.ExtractRoot.FileRoot(tempRoot),
                )
                return@launch
            }
            // Per-type root takes priority over the shared games root (falls back below if unset/invalid).
            val perTypeRootUri = when (target) {
                InstallRouting.Target.Winlator ->
                    JoiPlaySettingsStore.installRootUri(context.applicationContext, JoiPlaySettingsStore.InstallRoot.Winlator)
                InstallRouting.Target.JoiPlay ->
                    JoiPlaySettingsStore.installRootUri(context.applicationContext, JoiPlaySettingsStore.InstallRoot.JoiPlay)
                else -> null
            }
            if (perTypeRootUri != null) {
                val dir = runCatching { Uri.parse(perTypeRootUri) }.getOrNull()?.let { u ->
                    if (u.scheme == "file") u.path?.let { java.io.File(it) }
                    else SharedGamesRoot.resolveTreeUri(context.applicationContext, u)
                }
                if (dir != null && dir.isDirectory && dir.canWrite()) {
                    startOrConfirmExtraction(
                        file,
                        target,
                        ArchiveExtractor.ExtractRoot.FileRoot(dir),
                    )
                    return@launch
                }
            }
            val sharedRoot = SharedGamesRoot.get(context.applicationContext)
            val hasPermission = sharedRoot?.let {
                if (it.uri.scheme == "file") {
                    val directory = it.absoluteDirectory
                    directory != null && directory.canRead() && directory.canWrite()
                } else {
                    hasPersistedReadPermission(context, it.uri) && hasPersistedWritePermission(context, it.uri)
                }
            } == true
            val root = if (hasPermission) {
                SharedGamesRoot.extractionRoot(
                    sharedRoot!!,
                    requireAbsolutePath =
                        target == InstallRouting.Target.Winlator ||
                            target == InstallRouting.Target.Managed ||
                            target == InstallRouting.Target.Auto,
                )
            } else null
            if (root != null) {
                startOrConfirmExtraction(file, target, root)
            } else {
                if (sharedRoot != null &&
                    (target == InstallRouting.Target.Winlator ||
                        target == InstallRouting.Target.Managed ||
                        target == InstallRouting.Target.Auto) &&
                    hasPermission
                ) {
                    st.snackbarMsg =
                        "Winlator needs a games folder that resolves to a normal path. Choose a folder in internal shared storage."
                }
                if (!pauseAutomaticBatch("No writable destination is configured for ${file.name}.")) {
                    st.pendingArchiveDestination = PendingArchiveDestination(file, target)
                }
            }
        }
    }
    // Verify Winlator is ready, then extract the archive into the Winlator root.
    fun extractForWinlator(file: java.io.File) {
        scope.launch {
            WinlatorClient.requiredV2Capabilities(context.applicationContext)
                .getOrElse {
                    st.snackbarMsg = "Winlator Secure isn't ready: ${it.message}"
                    return@launch
                }
            prepareArchiveExtract(file, InstallRouting.Target.Winlator)
        }
    }
    fun dispatchArchiveRoute(file: java.io.File, route: InstallRouting.ArchiveRoute) {
        when (route) {
            is InstallRouting.ArchiveRoute.Extract -> when (route.target) {
                InstallRouting.Target.Android -> prepareArchiveExtract(file, route.target)
                InstallRouting.Target.JoiPlay,
                InstallRouting.Target.Winlator,
                InstallRouting.Target.Kirikiroid,
                InstallRouting.Target.Managed ->
                    prepareArchiveExtract(file, InstallRouting.Target.Managed)
                InstallRouting.Target.Auto -> error("Auto target is only used after extraction.")
            }
            is InstallRouting.ArchiveRoute.ExtractNested ->
                prepareArchiveExtract(file, InstallRouting.Target.Auto)
            InstallRouting.ArchiveRoute.ChooseRunner,
            InstallRouting.ArchiveRoute.HtmlOnly ->
                prepareArchiveExtract(file, InstallRouting.Target.Managed)
            InstallRouting.ArchiveRoute.Videos ->
                prepareBucketExtract(file, InstallRouting.Bucket.Videos)
            InstallRouting.ArchiveRoute.Other ->
                prepareBucketExtract(file, InstallRouting.Bucket.Other)
            is InstallRouting.ArchiveRoute.Unsupported -> st.snackbarMsg = route.message
        }
    }
    fun prepareUnifiedArchive(file: java.io.File, allowUpgradePrompt: Boolean = true) {
        nestedArchiveDepth = 0
        st.archiveAnalysisInProgress = file
        scope.launch {
            try {
                val analysis = withContext(Dispatchers.IO) { ManagedArchiveInspector.analyze(file) }
                if (analysis.entryNames.isEmpty()) {
                    AppLog.i(
                        "Install",
                        "Archive entries unavailable before extraction; deferring classification for ${file.name}",
                    )
                    prepareArchiveExtract(file, InstallRouting.Target.Auto)
                    return@launch
                }
                val route = InstallRouting.routeArchive(analysis.entryNames)
                val decision = if (allowUpgradePrompt) {
                    ManagedArchiveUpgradeCoordinator.decide(route, analysis, st.apps)
                } else {
                    ManagedArchiveUpgradeCoordinator.Decision.InstallAsNew
                }
                AppLog.i(
                    "ManagedUpgrade",
                    "Routing ${file.name}: route=$route upgradeEligible=" +
                        "${InstallRouting.isManagedUpgradeEligible(route)} decision=$decision " +
                        "promptAllowed=$allowUpgradePrompt",
                )
                when (decision) {
                    is ManagedArchiveUpgradeCoordinator.Decision.Upgrade -> {
                        st.upgradePrompt = ManagedUpgradePrompt(file, decision.matches, route)
                    }
                    ManagedArchiveUpgradeCoordinator.Decision.InstallAsNew -> dispatchArchiveRoute(file, route)
                }
            } catch (t: Exception) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.w("Install", "Archive classification failed for ${file.absolutePath}", t)
                st.snackbarMsg = "Could not inspect this archive: ${t.message ?: "unknown error"}"
            } finally {
                st.archiveAnalysisInProgress = null
            }
        }
    }
    fun prepareWinlatorExecutable(
        file: java.io.File,
        sourceArchiveToDelete: java.io.File? = null,
        archiveTitle: String? = null,
    ) {
        scope.launch {
            WinlatorClient.requiredV2Capabilities(context.applicationContext)
                .getOrElse {
                    st.snackbarMsg = "Winlator Secure isn't ready: ${it.message}"
                    return@launch
                }
            val parentName = file.parentFile?.name?.trim()?.takeIf { it.isNotBlank() }
            val fileTitle = file.nameWithoutExtension.trim().ifBlank { "Windows game" }
            st.winlatorExecutableCandidate = WinlatorExecutableCandidate(
                file = file,
                archiveTitle = archiveTitle?.trim()?.takeIf { it.isNotBlank() },
                executableTitle = fileTitle,
                folderTitle = parentName,
                readmeTitle = readGameReadmeTitleNear(file.parentFile),
                sourceArchiveToDelete = sourceArchiveToDelete,
            )
        }
    }
    fun startCleanupReview(
        rootPath: String,
        installedApps: List<InstalledApp> = st.apps,
    ) {
        st.cleanupRootPath = rootPath
        st.cleanupScanning = true
        st.cleanupProgress = CleanupReviewReporter.Progress(stage = "Preparing cleanup review")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    CleanupReviewReporter.buildReport(
                        rootFolder = java.io.File(rootPath),
                        installedApps = installedApps,
                    ) { progress ->
                        rootView.post { st.cleanupProgress = progress }
                    }
                }
            }.onSuccess { report ->
                st.cleanupReport = report
                st.snackbarMsg =
                    "Found ${report.unassociatedFolders.size} unassociated folders and ${report.orphanGames.size} orphan games"
            }.onFailure { error ->
                AppLog.e("CleanupReview", "Scan failed", error)
                st.snackbarMsg = "Cleanup Review failed: ${error.message}"
            }
            st.cleanupScanning = false
            st.cleanupProgress = null
        }
    }
    // Multi-select: long-press a row to start, tap toggles, "Hide" / "Unhide" applies in bulk.
    val selection = remember { mutableStateListOf<String>() }
    val selectionMode = selection.isNotEmpty()

    // Counter bumped from the APK installer result callback to trigger a post-
    // install scan + catalog match. Done as a counter (not a Boolean) so multiple
    // installs back-to-back each re-run the effect.

    // Auto-flush any queued crashes on launch.
    LaunchedEffect(Unit) {
        if (catalogLabels == null) onLabelsChange(catalog.labels())
        // Only auto-flush if upload is configured. Otherwise crashes silently queue locally
        // until the user taps "Save logs to Documents".
        if (AppConfigStore.current(context.applicationContext).hasCrashUpload) {
            val (ok, fail) = CrashReporter.flush(context.applicationContext)
            if (ok > 0 || fail > 0) {
                st.snackbarMsg = "Crash reports: $ok uploaded, $fail pending"
            }
        }
    }

    var sortKey by remember { mutableStateOf(initialFilters.sortKey) }
    var sortDesc by remember { mutableStateOf(initialFilters.sortDesc) }
    val activeFilters = remember { mutableStateListOf<UpdateStatus>().apply { addAll(initialFilters.activeStatuses) } }
    var nameFilter by remember { mutableStateOf(initialFilters.nameFilter) }
    var appliedNameFilter by remember {
        mutableStateOf(
            initialFilters.nameFilter
                .takeIf { regexValidationError(it, initialFilters.searchMode) == null }
                .orEmpty(),
        )
    }
    var librarySearchMode by remember { mutableStateOf(initialFilters.searchMode) }
    var selectedUserTags by remember { mutableStateOf(initialFilters.selectedUserTags.toSet()) }
    var userStatusFilter by remember { mutableStateOf(initialFilters.userStatus) }
    var minPersonalRatingFilter by remember { mutableIntStateOf(initialFilters.minPersonalRating) }
    var storageFilter by remember { mutableStateOf(initialFilters.storageFilter) }
    var installedDateField by remember { mutableStateOf(initialFilters.dateField) }
    var installedDateRange by remember { mutableStateOf(initialFilters.dateRange) }
    var advancedFiltersOpen by remember { mutableStateOf(false) }
    var threadUpdatedAfterInstallFilter by remember { mutableStateOf(initialFilters.threadUpdatedAfterInstallOnly) }
    var libraryLayoutMode by remember { mutableStateOf(initialFilters.layoutMode) }
    LaunchedEffect(Unit) {
        st.overwriteManualMatches = MatchingPrefs.overwriteManualMatches(context.applicationContext)
        st.resetAcksOnRefresh = MatchingPrefs.resetAcksOnRefresh(context.applicationContext)
    }
    val expanded = remember { mutableStateListOf<String>() }
    val collapsedTreeFolders = remember { mutableStateListOf<String>() }
    val initialResumeScanGate = remember { InitialResumeScanGate() }

    val libraryRegexError = regexValidationError(nameFilter, librarySearchMode)
    LaunchedEffect(nameFilter, librarySearchMode) {
        delay(200)
        if (regexValidationError(nameFilter, librarySearchMode) == null) {
            appliedNameFilter = nameFilter
        }
    }

    // Initial scan on first composition; lifecycle observer below catches subsequent ON_RESUMEs.
    LaunchedEffect(Unit) {
        val initialStartedAt = android.os.SystemClock.elapsedRealtime()
        AppLog.i("Scan", "Initial scan: entering LaunchedEffect")
        // Take an auto-backup if we just upgraded. Runs once per launch, cheap if no upgrade.
        runCatching { AutoBackupManager.maybeBackupOnUpgrade(context.applicationContext, repo) }
            .onFailure { AppLog.w("AutoBackup", "Upgrade-backup hook threw (ignored)", it) }
        var reconciledWinlatorCreate: PendingWinlatorAutoRecommend? = null
        var upgradeRecovery: ManagedUpgradeRecoveryOutcome = ManagedUpgradeRecoveryOutcome.None
        var patchRecovery: List<PatchRecoveryOutcome> = emptyList()
        val migration = runCatching {
            val migration = withContext(Dispatchers.IO) {
                // Resolve an upgrade interrupted by process death before anything reads the
                // managed store, so the first library snapshot already shows the final folder.
                upgradeRecovery = runCatching { recoverPendingManagedUpgrade(context.applicationContext) }
                    .onFailure { AppLog.e("ManagedUpgrade", "Upgrade recovery threw", it) }
                    .getOrDefault(ManagedUpgradeRecoveryOutcome.None)
                // Finish or report any patch transaction that never reached a resolved state.
                patchRecovery = runCatching { recoverPendingPatchInstalls(context.applicationContext) }
                    .onFailure { AppLog.e("PatchInstall", "Patch recovery threw", it) }
                    .getOrDefault(emptyList())
                reconciledWinlatorCreate =
                    reconcilePendingManagedWinlatorCreate(context.applicationContext)
                val migrationStartedAt = android.os.SystemClock.elapsedRealtime()
                migrateLegacyManagedGames(context.applicationContext, repo).also {
                    AppLog.i(
                        "Scan",
                        "initial stage=managed_migration " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - migrationStartedAt} " +
                            "alreadyCompleted=${it.alreadyCompleted} deferred=${it.deferredWinlatorMigration}",
                    )
                }
            }
            if (!migration.alreadyCompleted) {
                AppLog.i(
                    "ManagedMigration",
                    "supplied=${migration.supplied} created=${migration.created} " +
                        "merged=${migration.merged} skipped=${migration.skippedMissing}",
                )
            }
            st.apps = scanInstalledLibrarySnapshot(context, "initial")
            st.appsInitialLoading = false
            initialResumeScanGate.onInitialScanFinished(true)
            AppLog.i(
                "Scan",
                "Initial scan VISIBLE: elapsedMs=${android.os.SystemClock.elapsedRealtime() - initialStartedAt} " +
                    "total=${st.apps.size}",
            )
            migration
        }.onFailure {
            initialResumeScanGate.onInitialScanFinished(false)
            AppLog.e("Scan", "Initial scan FATAL", it)
            CrashReporter.logCaught(context.applicationContext, "initial_scan", it)
            st.snackbarMsg = "Initial app scan failed: ${it.message}"
        }.also {
            st.appsInitialLoading = false
        }.getOrNull()
        if (reconciledWinlatorCreate != null) {
            st.pendingWinlatorAutoRecommend = reconciledWinlatorCreate
        }
        ManagedWinlatorPendingStore.load(context.applicationContext)
            ?.recoveryCandidate
            ?.let { st.pendingAutomaticWinlatorRecovery = it }
        when (val recovery = upgradeRecovery) {
            ManagedUpgradeRecoveryOutcome.None -> Unit
            is ManagedUpgradeRecoveryOutcome.Discarded -> st.snackbarMsg = recovery.reason
            is ManagedUpgradeRecoveryOutcome.Failed -> st.snackbarMsg = recovery.message
            is ManagedUpgradeRecoveryOutcome.Completed -> st.snackbarMsg =
                "Finished the interrupted upgrade of ${recovery.result.label}."
        }
        patchRecovery.forEach { outcome ->
            st.snackbarMsg = when (outcome) {
                is PatchRecoveryOutcome.RolledBack -> outcome.message
                is PatchRecoveryOutcome.Unresolved -> outcome.message
            }
        }
        if (patchRecovery.isNotEmpty()) patchFlow.refreshRollbackPoints()
        if (migration?.let { it.alreadyCompleted || it.deferredWinlatorMigration } == true) {
            runCatching {
                st.apps = refreshInstalledLibraryWinlator(context, st.apps, "initial_background")
            }.onFailure {
                AppLog.e("Scan", "Initial Winlator refresh failed", it)
                CrashReporter.logCaught(context.applicationContext, "initial_winlator_refresh", it)
            }
        }
        if (migration != null) {
            AppLog.i(
                "Scan",
                "Initial scan DONE: elapsedMs=${android.os.SystemClock.elapsedRealtime() - initialStartedAt} " +
                    "total=${st.apps.size}",
            )
        }
    }

    // Rescan on resume so newly installed/uninstalled apps appear.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pendingManagedCreate = st.pendingWinlatorOperation
                    ?.takeIf {
                        it.managedGameId != null &&
                            (it.kind == WinlatorOperationKind.CreatePortable ||
                                it.kind == WinlatorOperationKind.CreateInstaller)
                    }
                if (
                    pendingManagedCreate != null &&
                    st.apps.any { it.managedGameId == pendingManagedCreate.managedGameId }
                ) {
                    st.managedEngineUpdateProgress = null
                }
            }
            if (event == Lifecycle.Event.ON_RESUME && initialResumeScanGate.shouldScan()) {
                hasUsage = InstalledAppsScanner.hasUsageAccess(context)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            AppConfigStore.reload(context.applicationContext)
                        }
                    }
                        .onFailure { AppLog.w("AppConfig", "Resume config reload failed", it) }
                    runCatching {
                        st.apps = scanInstalledLibrarySnapshot(context, "resume")
                        st.apps = refreshInstalledLibraryWinlator(context, st.apps, "resume")
                    }.onFailure {
                        AppLog.e("Scan", "Resume scan failed", it)
                        CrashReporter.logCaught(context.applicationContext, "resume_scan", it)
                    }.also {
                        st.appsInitialLoading = false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val managedUpgradeWinlatorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> upgradeFlow.onWinlatorResult(result.resultCode, result.data) }

    LaunchedEffect(upgradeFlow.winlatorRequest) {
        val request = upgradeFlow.winlatorRequest ?: return@LaunchedEffect
        // Claiming is what makes the request ours to launch: a cancellation that got here first has
        // already withdrawn it, and launching it anyway would point Winlator at a deleted folder.
        val intent = upgradeFlow.claimWinlatorRequest(request.sequence) ?: return@LaunchedEffect
        runCatching { managedUpgradeWinlatorLauncher.launch(intent) }
            .onFailure { upgradeFlow.onWinlatorLaunchFailed(it) }
    }

    val winlatorLaunchResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = st.pendingWinlatorOperation
        st.pendingWinlatorOperation = null
        when (val parsed = WinlatorApi.parseResult(result.resultCode, result.data)) {
            is WinlatorApi.OperationResult.Success ->
                st.snackbarMsg = "Started ${pending?.title ?: "the game"} in Winlator."
            is WinlatorApi.OperationResult.Failure -> {
                if (parsed.code == "GAME_NOT_FOUND" && pending != null) {
                    val app = st.apps.firstOrNull { it.winlatorGameId == pending.gameId }
                    val managedGameId = app?.managedGameId
                    if (app != null && managedGameId != null) {
                        lifecycleOwner.lifecycleScope.launch {
                            persistMissingWinlatorRegistration(
                                context.applicationContext,
                                managedGameId,
                            )
                            st.apps = scanInstalledLibrarySnapshot(
                                context,
                                "winlator_launch_registration_missing",
                            )
                            when (val eligibility = portableWinlatorRecoveryCandidate(app)) {
                                is WinlatorPortableRecoveryEligibility.Eligible -> {
                                    AppLog.i(
                                        "WinlatorRecovery",
                                        "Launch reported missing registration; recovering game=${pending.gameId}",
                                    )
                                    st.pendingAutomaticWinlatorRecovery = eligibility.candidate
                                }
                                is WinlatorPortableRecoveryEligibility.Ineligible ->
                                    st.snackbarMsg = eligibility.message
                            }
                        }
                    } else {
                        st.snackbarMsg = "${pending.title}: ${parsed.code}: ${parsed.message}"
                    }
                } else {
                    val prefix = pending?.title?.let { "$it: " }.orEmpty()
                    st.snackbarMsg = "$prefix${parsed.code}: ${parsed.message}"
                }
            }
        }
    }

    fun launchWinlatorNow(
        gameId: String,
        title: String,
        managedGameId: String?,
        app: InstalledApp?,
    ): Boolean {
        st.pendingWinlatorOperation = PendingWinlatorOperation(
            kind = WinlatorOperationKind.Launch,
            gameId = gameId,
            title = title,
            managedGameId = managedGameId,
        )
        return runCatching {
            val intent = if (app != null) {
                winlatorLaunchIntentWithRtpFix(app, gameId)
            } else {
                WinlatorApi.launch(gameId)
            }
            winlatorLaunchResultLauncher.launch(intent)
            app?.let {
                LastPlayedStore.recordLaunch(context.applicationContext, it.packageName)
            }
            AppLog.i("Winlator", "Launch dispatched game=$gameId title='$title'")
            true
        }.getOrElse {
            st.pendingWinlatorOperation = null
            st.snackbarMsg = "Could not open Winlator: ${it.message}"
            false
        }
    }

    suspend fun finishWinlatorRecovery(
        candidate: WinlatorPortableRecoveryCandidate,
        operation: WinlatorApi.OperationResult,
    ): Boolean {
        var reconciliation = reconcileWinlatorPortableCreate(
            candidate.registration(),
            operation,
            AndroidWinlatorGameReader(context.applicationContext),
        )
        if (reconciliation is WinlatorCreateReconciliation.Missing) {
            for (attempt in 1 until 40) {
                kotlinx.coroutines.delay(500L)
                reconciliation = reconcileWinlatorPortableCreate(
                    candidate.registration(),
                    operation,
                    AndroidWinlatorGameReader(context.applicationContext),
                )
                if (reconciliation !is WinlatorCreateReconciliation.Missing) break
            }
        }
        when (reconciliation) {
            is WinlatorCreateReconciliation.Recovered -> {
                val recovered = reconciliation
                val schemaRead = WinlatorClient.getConfigSchema(context.applicationContext)
                val schema = when (schemaRead) {
                    is WinlatorClient.Read.Ok ->
                        runCatching { WinlatorConfigSchema.parse(schemaRead.payload) }.getOrNull()
                    else -> null
                }
                if (
                    candidate.desiredConfigJson != null &&
                    (
                        schema == null ||
                            recovered.game.configJson == null ||
                            recovered.game.configSha256 == null
                        )
                ) {
                    st.snackbarMsg = when (schemaRead) {
                        is WinlatorClient.Read.Err ->
                            "${candidate.title}: ${schemaRead.code}: ${schemaRead.message}"
                        is WinlatorClient.Read.Unavailable ->
                            "${candidate.title}: ${schemaRead.reason}"
                        else ->
                            "Winlator restored ${candidate.title}, but did not return the fresh " +
                                "configuration data required to restore its settings."
                    }
                    return false
                }
                val submission = schema?.let {
                    buildWinlatorRecoveryConfigSubmission(candidate, recovered.game, it)
                }
                if (submission != null) {
                    st.snackbarMsg = "Restoring Winlator settings for ${candidate.title}\u2026"
                    st.winlatorSubmissionQueue = listOf(submission)
                    st.winlatorQueueTick++
                } else {
                    persistRecoveredWinlatorRegistration(
                        context.applicationContext,
                        candidate,
                        recovered.game,
                    )
                    st.apps = scanInstalledLibrary(context, "winlator_recovery_reconciled")
                    AppLog.i(
                        "WinlatorRecovery",
                        "Registration reconciled with no config patch game=${candidate.gameId}",
                    )
                    return true
                }
            }
            is WinlatorCreateReconciliation.Missing -> {
                persistMissingWinlatorRegistration(
                    context.applicationContext,
                    candidate.managedGameId,
                )
                st.apps = scanInstalledLibrarySnapshot(context, "winlator_recovery_missing")
                val detail = reconciliation.operationCode?.let {
                    " ($it: ${reconciliation.operationMessage})"
                }.orEmpty()
                st.snackbarMsg =
                    "Winlator did not restore ${candidate.title}$detail. Its saved identity was kept."
            }
            is WinlatorCreateReconciliation.Conflict ->
                st.snackbarMsg =
                    "${candidate.title}: recovery stopped because ${reconciliation.reason}"
            is WinlatorCreateReconciliation.OperationRejected ->
                st.snackbarMsg =
                    "${candidate.title}: ${reconciliation.code}: ${reconciliation.message}"
            is WinlatorCreateReconciliation.ProviderError ->
                st.snackbarMsg =
                    "${candidate.title}: ${reconciliation.code}: ${reconciliation.message}"
            is WinlatorCreateReconciliation.Unavailable ->
                st.snackbarMsg = "${candidate.title}: ${reconciliation.reason}"
        }
        return false
    }

    fun recoveryContinuationPending(candidate: WinlatorPortableRecoveryCandidate): Boolean =
        st.winlatorSubmissionQueue.any {
            it.source == "registration-recovery" && it.gameId == candidate.gameId
        }

    fun releaseWinlatorRecovery(candidate: WinlatorPortableRecoveryCandidate) {
        winlatorRecoverySingleFlight.end(candidate.managedGameId)
        if (st.activeWinlatorRecoveryManagedGameId == candidate.managedGameId) {
            st.activeWinlatorRecoveryManagedGameId = null
        }
    }

    fun releaseActiveWinlatorRecovery() {
        st.activeWinlatorRecoveryManagedGameId?.let(winlatorRecoverySingleFlight::end)
        st.activeWinlatorRecoveryManagedGameId = null
    }

    fun launchRecoveredWinlatorGame(candidate: WinlatorPortableRecoveryCandidate) {
        val app = st.apps.firstOrNull { it.managedGameId == candidate.managedGameId }
        val launched = launchWinlatorNow(
            gameId = candidate.gameId,
            title = candidate.title,
            managedGameId = candidate.managedGameId,
            app = app,
        )
        if (launched) {
            ManagedWinlatorPendingStore.clear(context.applicationContext)
        }
        releaseWinlatorRecovery(candidate)
    }

    suspend fun verifyRecoveryConfigAndLaunch(submission: WinlatorConfigSubmission) {
        val candidate = ManagedWinlatorPendingStore.load(context.applicationContext)
            ?.takeIf {
                it.kind == WinlatorOperationKind.RecoverPortable &&
                    it.gameId == submission.gameId
            }
            ?.recoveryCandidate
        if (candidate == null) {
            st.snackbarMsg =
                "Winlator restored ${submission.title}, but AGM lost the recovery state before launch."
            releaseActiveWinlatorRecovery()
            return
        }
        when (val read = WinlatorClient.getGame(context.applicationContext, submission.gameId)) {
            is WinlatorClient.Read.Ok -> {
                val actual = runCatching {
                    WinlatorApi.ManagedGame.parse(JSONObject(read.payload))
                }.getOrNull()
                if (actual != null && isWinlatorRecoveryConfigApplied(submission, actual)) {
                    st.apps = refreshInstalledLibraryWinlator(
                        context,
                        st.apps,
                        "winlator_recovery_config_verified",
                    )
                    AppLog.i(
                        "WinlatorRecovery",
                        "Configuration verified; launching game=${submission.gameId}",
                    )
                    launchRecoveredWinlatorGame(candidate)
                } else {
                    st.snackbarMsg =
                        "Winlator restored ${submission.title}, but its saved settings could not be verified."
                    releaseActiveWinlatorRecovery()
                }
            }
            is WinlatorClient.Read.Err -> {
                st.snackbarMsg = "${submission.title}: ${read.code}: ${read.message}"
                releaseActiveWinlatorRecovery()
            }
            is WinlatorClient.Read.Unavailable -> {
                st.snackbarMsg = "${submission.title}: ${read.reason}"
                releaseActiveWinlatorRecovery()
            }
        }
    }

    val winlatorResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = st.pendingWinlatorOperation
            ?: ManagedWinlatorPendingStore.load(context.applicationContext)
        st.pendingWinlatorOperation = null
        val parsed = WinlatorApi.parseResult(result.resultCode, result.data)
        if (pending?.kind == WinlatorOperationKind.RecoverPortable &&
            pending.recoveryCandidate != null
        ) {
            val candidate = pending.recoveryCandidate
            lifecycleOwner.lifecycleScope.launch {
                try {
                    if (finishWinlatorRecovery(candidate, parsed)) {
                        launchRecoveredWinlatorGame(candidate)
                    }
                } catch (error: Exception) {
                    AppLog.e("WinlatorRecovery", "Recovery reconciliation failed", error)
                    st.snackbarMsg = "Could not finish Winlator recovery: ${error.message}"
                } finally {
                    if (!recoveryContinuationPending(candidate)) {
                        ManagedWinlatorPendingStore.clear(context.applicationContext)
                        releaseWinlatorRecovery(candidate)
                    }
                    st.managedEngineUpdateProgress = null
                }
            }
            return@rememberLauncherForActivityResult
        }
        when (parsed) {
            is WinlatorApi.OperationResult.Success -> {
                val title = pending?.title ?: "Winlator game"
                pending?.configChangeId?.let { changeId ->
                    scope.launch {
                        WinlatorManagementStore.finishChange(
                            context.applicationContext,
                            changeId,
                            success = true,
                        )
                    }
                }
                pending?.sourceArchiveToDelete?.let { source ->
                    runCatching { source.exists() && source.delete() }
                        .onSuccess { deleted ->
                            if (deleted) {
                                AppLog.i("Winlator", "Deleted source archive ${source.absolutePath}")
                            } else {
                                AppLog.w("Winlator", "Could not delete source archive ${source.absolutePath}")
                            }
                        }
                        .onFailure { AppLog.w("Winlator", "Could not delete source archive ${source.absolutePath}", it) }
                }
                st.snackbarMsg = when (pending?.kind) {
                    WinlatorOperationKind.CreatePortable,
                    WinlatorOperationKind.CreateInstaller -> "Added $title to Winlator."
                    WinlatorOperationKind.RecoverPortable -> "Restored $title in Winlator."
                    WinlatorOperationKind.Configure -> "Configured $title."
                    WinlatorOperationKind.RunInstaller -> "Installer started for $title."
                    WinlatorOperationKind.Launch -> "Started $title in Winlator."
                    WinlatorOperationKind.Delete -> when {
                        parsed.containerDeleted -> "Deleted $title and reclaimed its isolated Winlator container."
                        parsed.containerPreserved -> "Deleted $title; its shared Winlator container was preserved."
                        else -> "Deleted $title from Winlator."
                    }
                    WinlatorOperationKind.MoveToIsolated ->
                        "Moved $title to its own isolated Winlator container."
                    null -> "Winlator operation completed."
                }
                if (
                    pending?.kind == WinlatorOperationKind.Delete &&
                    pending.managedGameId != null &&
                    pending.deleteSharedFiles
                ) {
                    scope.launch {
                        val app = st.apps.firstOrNull { it.managedGameId == pending.managedGameId }
                        if (app == null) {
                            st.snackbarMsg = "Winlator was removed, but AGM could not find the managed game record."
                            return@launch
                        }
                        val deleted = runCatching {
                            deleteManagedGameFilesAndRecord(context.applicationContext, app)
                        }.onFailure {
                            AppLog.w("Delete", "Managed delete refused for ${app.label}", it)
                        }.getOrDefault(false)
                        if (!deleted) {
                            st.snackbarMsg = "Winlator was removed, but AGM could not delete ${app.label}'s files."
                            return@launch
                        }
                        st.apps = scanInstalledLibrary(context)
                        GameStorageSizeWork.enqueueImmediate(context.applicationContext)
                        st.snackbarMsg = "Deleted ${app.label} and all configured runner data."
                    }
                }
                if (
                    (pending?.kind == WinlatorOperationKind.CreatePortable ||
                        pending?.kind == WinlatorOperationKind.CreateInstaller) &&
                    pending.managedGameId != null
                ) {
                    val winlatorGameId = parsed.gameId ?: pending.gameId
                    scope.launch {
                        st.managedEngineUpdateProgress = ManagedEngineDiscoveryProgress(
                            current = 0,
                            total = 1,
                            gameTitle = pending.title,
                            stage = "Waiting for Winlator to finish setup",
                        )
                        try {
                            attachWinlatorBindingId(
                                context.applicationContext,
                                pending.managedGameId,
                                winlatorGameId,
                            )
                            var ready = false
                            for (attempt in 0 until 40) {
                                if (WinlatorClient.getGame(
                                        context.applicationContext,
                                        winlatorGameId,
                                    ) is WinlatorClient.Read.Ok
                                ) {
                                    ready = true
                                    break
                                }
                                kotlinx.coroutines.delay(500L)
                            }
                            st.apps = scanInstalledLibrary(context)
                            if (ready) {
                                st.pendingWinlatorAutoRecommend = PendingWinlatorAutoRecommend(
                                    gameId = winlatorGameId,
                                    title = pending.title,
                                    detectedLanguage = pending.detectedLanguage,
                                    launchAfterApply = pending.launchAfterCreate,
                                )
                            } else if (!ready) {
                                st.snackbarMsg =
                                    "Winlator accepted ${pending.title}; setup is still finishing."
                            }
                        } catch (error: Exception) {
                            AppLog.e("Winlator", "Could not finalize managed binding", error)
                            st.snackbarMsg = "Could not finish Winlator setup: ${error.message}"
                        } finally {
                            st.managedEngineUpdateProgress = null
                        }
                    }
                }
                val managedCreate =
                    (pending?.kind == WinlatorOperationKind.CreatePortable ||
                        pending?.kind == WinlatorOperationKind.CreateInstaller) &&
                        pending.managedGameId != null
                if (shouldRefreshAfterWinlatorOperation(pending?.kind) && !managedCreate) {
                    winlatorRefreshSequence++
                    st.pendingWinlatorRefresh = PendingWinlatorRefresh(
                        sequence = winlatorRefreshSequence,
                        gameId = parsed.gameId ?: pending?.gameId,
                        gameJson = parsed.gameJson,
                        matchCatalog = shouldMatchCatalogAfterWinlatorOperation(pending?.kind),
                        removeGame = pending?.kind == WinlatorOperationKind.Delete,
                    )
                }
                // Advance the multi-surface apply queue: drop the submission that just succeeded and
                // launch the next one (if any). Retry-after-apply only fires once the whole sequence
                // is done, so a config round-trip never launches the game before settings apply.
                val completedSubmission = st.winlatorSubmissionQueue.firstOrNull()
                val moreQueued = if (st.winlatorSubmissionQueue.isNotEmpty()) {
                    st.winlatorSubmissionQueue = st.winlatorSubmissionQueue.drop(1)
                    st.winlatorSubmissionQueue.isNotEmpty()
                } else false
                if (moreQueued) {
                    st.winlatorQueueTick++
                } else if (pending?.kind == WinlatorOperationKind.Configure) {
                    ManagedWinlatorPendingStore.load(context.applicationContext)
                        ?.takeIf {
                            it.gameId == pending.gameId &&
                                it.kind != WinlatorOperationKind.RecoverPortable
                        }
                        ?.let { ManagedWinlatorPendingStore.clear(context.applicationContext) }
                    if (pending.retryAfterApply) {
                        if (completedSubmission?.source == "registration-recovery") {
                            lifecycleOwner.lifecycleScope.launch {
                                verifyRecoveryConfigAndLaunch(completedSubmission)
                            }
                        } else {
                            st.pendingWinlatorRetry = pending.gameId to pending.title
                        }
                    }
                } else if (
                    pending?.kind == WinlatorOperationKind.CreatePortable ||
                    pending?.kind == WinlatorOperationKind.CreateInstaller
                ) {
                    if (!managedCreate) {
                        // Seed recommended settings automatically on a freshly created legacy game.
                        (parsed.gameId ?: pending.gameId)?.let { gid ->
                            st.pendingWinlatorAutoRecommend = PendingWinlatorAutoRecommend(
                                gameId = gid,
                                title = pending.title,
                            )
                        }
                    }
                }
            }
            is WinlatorApi.OperationResult.Failure -> {
                val failedSubmission = st.winlatorSubmissionQueue.firstOrNull()
                // A failed submission aborts the rest of the multi-surface apply sequence.
                st.winlatorSubmissionQueue = emptyList()
                if (failedSubmission?.source == "registration-recovery") {
                    releaseActiveWinlatorRecovery()
                }
                if (pending?.kind == WinlatorOperationKind.Configure) {
                    ManagedWinlatorPendingStore.load(context.applicationContext)
                        ?.takeIf {
                            it.gameId == pending.gameId &&
                                it.kind != WinlatorOperationKind.RecoverPortable
                        }
                        ?.let { ManagedWinlatorPendingStore.clear(context.applicationContext) }
                }
                if (
                    (pending?.kind == WinlatorOperationKind.CreatePortable ||
                        pending?.kind == WinlatorOperationKind.CreateInstaller) &&
                    pending.managedGameId != null
                ) {
                    scope.launch {
                        val rollback = pending.managedRunnerRollback
                        if (rollback != null) {
                            restoreManagedRunnerConfiguration(
                                context.applicationContext,
                                pending.managedGameId,
                                rollback,
                            )
                        } else {
                            disableUnconfiguredWinlatorBinding(
                                context.applicationContext,
                                pending.managedGameId,
                            )
                        }
                        ManagedWinlatorPendingStore.clear(context.applicationContext)
                        st.apps = scanInstalledLibrary(context)
                    }
                }
                pending?.configChangeId?.let { changeId ->
                    scope.launch {
                        WinlatorManagementStore.finishChange(
                            context.applicationContext,
                            changeId,
                            success = false,
                            errorCode = parsed.code,
                            errorMessage = parsed.message,
                        )
                    }
                }
                val prefix = pending?.title?.let { "$it: " }.orEmpty()
                st.snackbarMsg = if (parsed.code == "CONFIG_CONFLICT" || parsed.code == "SETTINGS_CONFLICT") {
                    st.winlatorConfigureTarget = parsed.gameJson
                        ?.let { runCatching { WinlatorApi.ManagedGame.parse(JSONObject(it)).toInstalledApp() }.getOrNull() }
                        ?: st.apps.firstOrNull { it.winlatorGameId == pending?.gameId }
                        ?: st.winlatorConfigureTarget
                    "$prefix settings changed in Winlator. Reopen and try again."
                } else {
                    "$prefix${parsed.code}: ${parsed.message}"
                }
                AppLog.w("Winlator", "Operation failed kind=${pending?.kind} code=${parsed.code}: ${parsed.message}")
                if (pending?.managedGameId != null) {
                    st.managedEngineUpdateProgress = null
                }
            }
        }
    }

    fun launchWinlatorWithPreflight(app: InstalledApp) {
        val gameId = app.winlatorGameId
        if (gameId == null) {
            st.snackbarMsg = "Winlator setup is required for ${app.label}."
            return
        }
        val flightKey = app.managedGameId ?: gameId
        if (!winlatorRecoverySingleFlight.begin(flightKey)) return
        lifecycleOwner.lifecycleScope.launch {
            var continueRecoveryFlight = false
            try {
                val eligibility = portableWinlatorRecoveryCandidate(app)
                val preflight = if (eligibility is WinlatorPortableRecoveryEligibility.Eligible) {
                    preflightWinlatorRegistration(
                        eligibility.candidate.registration(),
                        AndroidWinlatorGameReader(context.applicationContext),
                    )
                } else {
                    when (val read =
                        WinlatorClient.getGame(context.applicationContext, gameId)
                    ) {
                        is WinlatorClient.Read.Ok -> {
                            val game = runCatching {
                                WinlatorApi.ManagedGame.parse(JSONObject(read.payload))
                            }.getOrNull()
                            if (game == null) {
                                WinlatorRegistrationPreflight.ProviderError(
                                    "INVALID_GAME_JSON",
                                    "Winlator returned invalid managed-game data.",
                                )
                            } else {
                                WinlatorRegistrationPreflight.Registered(game)
                            }
                        }
                        is WinlatorClient.Read.Err ->
                            if (read.code == "GAME_NOT_FOUND") {
                                WinlatorRegistrationPreflight.Missing
                            } else {
                                WinlatorRegistrationPreflight.ProviderError(
                                    read.code,
                                    read.message,
                                )
                            }
                        is WinlatorClient.Read.Unavailable ->
                            WinlatorRegistrationPreflight.Unavailable(read.reason)
                    }
                }
                when (preflight) {
                    is WinlatorRegistrationPreflight.Registered -> {
                        if (
                            app.winlatorExecutablePath != null &&
                            eligibility is WinlatorPortableRecoveryEligibility.Ineligible
                        ) {
                            st.snackbarMsg = eligibility.message
                            return@launch
                        }
                        if (app.winlatorExecutablePath == null) {
                            val identity =
                                matchContainerOwnedWinlatorRegistration(app, preflight.game)
                            if (identity is WinlatorRegistrationIdentity.Conflict) {
                                st.snackbarMsg =
                                    "${app.label}: launch stopped because ${identity.reason}"
                                return@launch
                            }
                        }
                        if (
                            app.winlatorState == WINLATOR_REGISTRATION_MISSING &&
                            eligibility is WinlatorPortableRecoveryEligibility.Eligible
                        ) {
                            st.activeWinlatorRecoveryManagedGameId =
                                eligibility.candidate.managedGameId
                            ManagedWinlatorPendingStore.save(
                                context.applicationContext,
                                PendingWinlatorOperation(
                                    kind = WinlatorOperationKind.RecoverPortable,
                                    gameId = eligibility.candidate.gameId,
                                    title = eligibility.candidate.title,
                                    managedGameId = eligibility.candidate.managedGameId,
                                    recoveryCandidate = eligibility.candidate,
                                ),
                            )
                            val launchReady = finishWinlatorRecovery(
                                eligibility.candidate,
                                WinlatorApi.OperationResult.Success(
                                    gameId = gameId,
                                    containerId = preflight.game.containerId,
                                    gameJson = null,
                                    createReconciled = true,
                                    launchDeferred = false,
                                    containerDeleted = false,
                                    containerPreserved = false,
                                    containerReferenceCount =
                                        preflight.game.containerReferenceCount,
                                ),
                            )
                            if (launchReady) {
                                launchRecoveredWinlatorGame(eligibility.candidate)
                            } else {
                                continueRecoveryFlight =
                                    recoveryContinuationPending(eligibility.candidate)
                            }
                            if (!launchReady && !continueRecoveryFlight) {
                                releaseWinlatorRecovery(eligibility.candidate)
                            }
                            return@launch
                        }
                        launchWinlatorNow(gameId, app.label, app.managedGameId, app)
                    }
                    WinlatorRegistrationPreflight.Missing -> {
                        val managedGameId = app.managedGameId
                        if (managedGameId == null) {
                            st.snackbarMsg =
                                "Winlator no longer has ${app.label}; add it again from its files or installer."
                            return@launch
                        }
                        persistMissingWinlatorRegistration(
                            context.applicationContext,
                            managedGameId,
                        )
                        st.apps = scanInstalledLibrarySnapshot(
                            context,
                            "winlator_preflight_registration_missing",
                        )
                        when (eligibility) {
                            is WinlatorPortableRecoveryEligibility.Eligible ->
                                st.pendingAutomaticWinlatorRecovery = eligibility.candidate
                            is WinlatorPortableRecoveryEligibility.Ineligible ->
                                st.snackbarMsg = eligibility.message
                        }
                    }
                    is WinlatorRegistrationPreflight.Conflict ->
                        st.snackbarMsg =
                            "${app.label}: launch stopped because ${preflight.reason}"
                    is WinlatorRegistrationPreflight.ProviderError ->
                        st.snackbarMsg =
                            "${app.label}: ${preflight.code}: ${preflight.message}"
                    is WinlatorRegistrationPreflight.Unavailable ->
                        st.snackbarMsg = "${app.label}: ${preflight.reason}"
                }
            } finally {
                if (!continueRecoveryFlight) {
                    winlatorRecoverySingleFlight.end(flightKey)
                }
            }
        }
    }

    fun startWinlatorRecovery(candidate: WinlatorPortableRecoveryCandidate): Boolean {
        if (!winlatorRecoverySingleFlight.begin(candidate.managedGameId)) return false
        st.activeWinlatorRecoveryManagedGameId = candidate.managedGameId
        lifecycleOwner.lifecycleScope.launch {
            var activityStarted = false
            try {
                val currentApp = st.apps.firstOrNull {
                    it.managedGameId == candidate.managedGameId
                }
                val currentEligibility = currentApp?.let(::portableWinlatorRecoveryCandidate)
                if (currentEligibility !is WinlatorPortableRecoveryEligibility.Eligible) {
                    st.snackbarMsg = when (currentEligibility) {
                        is WinlatorPortableRecoveryEligibility.Ineligible ->
                            currentEligibility.message
                        else -> "The managed game is no longer available for Winlator recovery."
                    }
                    return@launch
                }
                val recoveryOperation = PendingWinlatorOperation(
                    kind = WinlatorOperationKind.RecoverPortable,
                    gameId = candidate.gameId,
                    title = candidate.title,
                    managedGameId = candidate.managedGameId,
                    recoveryCandidate = candidate,
                )
                ManagedWinlatorPendingStore.save(
                    context.applicationContext,
                    recoveryOperation,
                )
                when (
                    val preflight = preflightWinlatorRegistration(
                        candidate.registration(),
                        AndroidWinlatorGameReader(context.applicationContext),
                    )
                ) {
                    is WinlatorRegistrationPreflight.Registered -> {
                        if (finishWinlatorRecovery(
                            candidate,
                            WinlatorApi.OperationResult.Success(
                                gameId = candidate.gameId,
                                containerId = preflight.game.containerId,
                                gameJson = null,
                                createReconciled = true,
                                launchDeferred = false,
                                containerDeleted = false,
                                containerPreserved = false,
                                containerReferenceCount = preflight.game.containerReferenceCount,
                            ),
                        )) {
                            launchRecoveredWinlatorGame(candidate)
                        }
                    }
                    WinlatorRegistrationPreflight.Missing -> {
                        st.pendingWinlatorOperation = recoveryOperation
                        st.managedEngineUpdateProgress = ManagedEngineDiscoveryProgress(
                            current = 0,
                            total = 1,
                            gameTitle = candidate.title,
                            stage = "Restoring the Winlator registration",
                        )
                        runCatching {
                            winlatorResultLauncher.launch(candidate.registration().createIntent())
                            activityStarted = true
                        }.onFailure { error ->
                            st.pendingWinlatorOperation = null
                            if (finishWinlatorRecovery(
                                candidate,
                                WinlatorApi.OperationResult.Failure(
                                    "NO_RESULT",
                                    error.message ?: "Could not open Winlator recovery.",
                                ),
                            )) {
                                launchRecoveredWinlatorGame(candidate)
                            }
                        }
                    }
                    is WinlatorRegistrationPreflight.Conflict ->
                        st.snackbarMsg =
                            "${candidate.title}: recovery stopped because ${preflight.reason}"
                    is WinlatorRegistrationPreflight.ProviderError ->
                        st.snackbarMsg =
                            "${candidate.title}: ${preflight.code}: ${preflight.message}"
                    is WinlatorRegistrationPreflight.Unavailable ->
                        st.snackbarMsg = "${candidate.title}: ${preflight.reason}"
                }
            } catch (error: Exception) {
                AppLog.e("WinlatorRecovery", "Could not start recovery", error)
                st.snackbarMsg = "Could not start Winlator recovery: ${error.message}"
            } finally {
                if (!activityStarted) {
                    if (!recoveryContinuationPending(candidate)) {
                        ManagedWinlatorPendingStore.clear(context.applicationContext)
                        releaseWinlatorRecovery(candidate)
                    }
                    st.managedEngineUpdateProgress = null
                }
            }
        }
        return true
    }

    LaunchedEffect(st.pendingAutomaticWinlatorRecovery) {
        val candidate = st.pendingAutomaticWinlatorRecovery ?: return@LaunchedEffect
        while (!startWinlatorRecovery(candidate)) {
            kotlinx.coroutines.delay(50L)
        }
        if (st.pendingAutomaticWinlatorRecovery == candidate) {
            st.pendingAutomaticWinlatorRecovery = null
        }
        st.snackbarMsg = "Restoring ${candidate.title}'s Winlator runner\u2026"
    }

    LaunchedEffect(st.pendingWinlatorRetry) {
        val retry = st.pendingWinlatorRetry ?: return@LaunchedEffect
        st.pendingWinlatorRetry = null
        val retryApp = st.apps.firstOrNull { it.winlatorGameId == retry.first }
        launchWinlatorNow(
            gameId = retry.first,
            title = retry.second,
            managedGameId = retryApp?.managedGameId,
            app = retryApp,
        )
    }

    LaunchedEffect(
        st.pendingWinlatorAutoRecommend,
        st.pendingWinlatorOperation,
        st.winlatorSubmissionQueue.isNotEmpty(),
    ) {
        val target = st.pendingWinlatorAutoRecommend ?: return@LaunchedEffect
        if (st.winlatorSubmissionQueue.isNotEmpty() || st.pendingWinlatorOperation != null) return@LaunchedEffect
        val result = runCatching {
            WinlatorRecommendedConfig.buildAutoSubmissions(
                context.applicationContext,
                target.gameId,
                target.title,
                target.detectedLanguage,
            )
        }
        val submissions = result.getOrElse {
            AppLog.w("Winlator", "Auto recommended-config failed for ${target.title}", it)
            val message = "Could not initialize Winlator settings for ${target.title}: ${it.message}"
            if (!pauseAutomaticBatch(message)) st.snackbarMsg = message
            ManagedWinlatorPendingStore.load(context.applicationContext)
                ?.takeIf { it.gameId == target.gameId }
                ?.let { ManagedWinlatorPendingStore.clear(context.applicationContext) }
            st.pendingWinlatorAutoRecommend = null
            return@LaunchedEffect
        }
        if (submissions.isEmpty()) {
            ManagedWinlatorPendingStore.load(context.applicationContext)
                ?.takeIf { it.gameId == target.gameId }
                ?.let { ManagedWinlatorPendingStore.clear(context.applicationContext) }
            if (target.launchAfterApply) {
                st.pendingWinlatorRetry = target.gameId to target.title
            }
            st.pendingWinlatorAutoRecommend = null
            return@LaunchedEffect
        }
        val queuedSubmissions = if (target.launchAfterApply) {
            submissions.mapIndexed { index, submission ->
                submission.copy(retryAfterApply = index == submissions.lastIndex)
            }
        } else {
            submissions
        }
        queuedSubmissions.forEach { submission ->
            AppLog.i(
                "Winlator",
                "Auto config queued title='${target.title}' surface=${submission.surface} " +
                    "set=${submission.setJson}",
            )
        }
        st.pendingWinlatorAutoRecommend = null
        st.snackbarMsg = "Applying recommended settings for ${target.title}\u2026"
        st.winlatorSubmissionQueue = queuedSubmissions
        st.winlatorQueueTick++
    }

    LaunchedEffect(st.winlatorQueueTick) {
        if (st.winlatorQueueTick == 0) return@LaunchedEffect
        val submission = st.winlatorSubmissionQueue.firstOrNull() ?: return@LaunchedEffect
        if (submission.surface == WinlatorConfigSurface.Settings) {
            val settingsUpdate = WinlatorConfigJson.settingsUpdateJson(
                submission.baseConfigSha256,
                submission.setJson,
            )
            st.pendingWinlatorOperation = PendingWinlatorOperation(
                kind = WinlatorOperationKind.Configure,
                gameId = submission.gameId,
                title = submission.title,
                configChangeId = null,
                retryAfterApply = submission.retryAfterApply,
            )
            runCatching {
                winlatorResultLauncher.launch(
                    WinlatorApi.configureGameSettings(
                        gameId = submission.gameId,
                        settingsUpdateJson = settingsUpdate,
                    )
                )
            }.onFailure {
                st.pendingWinlatorOperation = null
                st.winlatorSubmissionQueue = emptyList()
                if (submission.source == "registration-recovery") {
                    releaseActiveWinlatorRecovery()
                }
                st.snackbarMsg = "Could not open Winlator: ${it.message}"
            }
            return@LaunchedEffect
        }
        val changeId = runCatching {
            WinlatorManagementStore.beginChange(context.applicationContext, submission)
        }.getOrElse {
            st.winlatorSubmissionQueue = emptyList()
            if (submission.source == "registration-recovery") {
                releaseActiveWinlatorRecovery()
            }
            st.snackbarMsg = "Could not record the Winlator settings change: ${it.message}"
            return@LaunchedEffect
        }
        val configUpdate = JSONObject(submission.setJson)
            .takeIf { it.length() > 0 }
            ?.let {
                WinlatorConfigJson.updateJson(
                    submission.baseConfigSha256,
                    submission.setJson,
                )
            }
        st.pendingWinlatorOperation = PendingWinlatorOperation(
            kind = WinlatorOperationKind.Configure,
            gameId = submission.gameId,
            title = submission.title,
            configChangeId = changeId,
            retryAfterApply = submission.retryAfterApply,
        )
        runCatching {
            winlatorResultLauncher.launch(
                WinlatorApi.configureGame(
                    gameId = submission.gameId,
                    executableDosPath = submission.executableDosPath,
                    configUpdateJson = configUpdate,
                )
            )
        }.onFailure {
            st.pendingWinlatorOperation = null
            st.winlatorSubmissionQueue = emptyList()
            WinlatorManagementStore.finishChange(
                context.applicationContext,
                changeId,
                success = false,
                errorCode = "ACTIVITY_LAUNCH_FAILED",
                errorMessage = it.message,
            )
            if (submission.source == "registration-recovery") {
                releaseActiveWinlatorRecovery()
            }
            st.snackbarMsg = "Could not open Winlator: ${it.message}"
        }
    }

    LaunchedEffect(Unit) {
        WinlatorEventBus.events.collect { event ->
            val report = event.reportId?.let { reportId ->
                when (val read = WinlatorClient.getDiagnostic(context.applicationContext, reportId)) {
                    is WinlatorClient.Read.Ok ->
                        runCatching { WinlatorDiagnosticReport.parse(read.payload) }
                            .onFailure { AppLog.w("Winlator", "Could not parse diagnostic $reportId", it) }
                            .getOrNull()
                            ?.also { parsed ->
                                runCatching {
                                    WinlatorManagementStore.cacheReports(
                                        context.applicationContext,
                                        listOf(parsed),
                                    )
                                }.onFailure { AppLog.w("Winlator", "Could not cache diagnostic $reportId", it) }
                            }
                    is WinlatorClient.Read.Err -> {
                        AppLog.w("Winlator", "Diagnostic $reportId failed ${read.code}: ${read.message}")
                        null
                    }
                    is WinlatorClient.Read.Unavailable -> {
                        AppLog.w("Winlator", "Diagnostic $reportId unavailable: ${read.reason}")
                        null
                    }
                }
            }
            val runtimeReached = event.runtimeReached ?: report?.runtimeReached
            val failureReason = report?.evidence?.lastOrNull { it.message.isNotBlank() }?.message
                ?: event.errorMessage?.takeIf { it.isNotBlank() }
                ?: report?.let { "${it.category.replace('_', ' ')} (${it.phase.replace('_', ' ')})" }
                ?: event.outcome?.replace('_', ' ')
            val message: String? = when (event.type) {
                "install_progress" -> buildString {
                    append("Winlator installer")
                    event.stage?.let { append(": ").append(it.replace('_', ' ')) }
                    event.percent?.let { append(" (").append(it).append("%)") }
                }
                "install_completed" -> "Winlator installation completed."
                "install_failed" ->
                    "Winlator installation failed: ${event.errorCode ?: "ERROR"}: ${event.errorMessage ?: "unknown error"}"
                "game_exited" -> winlatorGameExitedMessage(runtimeReached, failureReason)
                // Post-commit reconcile notification only (the authoritative outcome came back on the
                // synchronous configure result). Don't surface it, and never treat its success flag as
                // a failure; just refetch below to reflect the persisted values.
                "settings_changed" -> null
                else -> "Winlator event: ${event.type}"
            }
            if (message != null) st.snackbarMsg = message
            if (shouldRefreshAfterWinlatorEvent(event.type)) {
                winlatorRefreshSequence++
                st.pendingWinlatorRefresh = PendingWinlatorRefresh(
                    sequence = winlatorRefreshSequence,
                    gameId = event.gameId,
                )
            }
        }
    }

    val kirikiroidResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val err = KirikiroidLauncher.interpretResult(result.resultCode, result.data)
        if (err != null) {
            AppLog.w("KirikiroidResult", "Launch failed: rc=${result.resultCode} err=$err")
            st.snackbarMsg = err
        }
    }
    fun launchKirikiroid(app: InstalledApp) {
        val intent = KirikiroidLauncher.buildLaunchIntent(context, app)
        if (intent == null) {
            st.snackbarMsg = if (!KirikiroidLauncher.isInstalled(context)) {
                "Kirikiroid2 isn't installed."
            } else {
                "This game has no readable folder path for Kirikiroid2."
            }
            return
        }
        runCatching {
            kirikiroidResultLauncher.launch(intent)
            LastPlayedStore.recordLaunch(context.applicationContext, app.packageName)
        }.onFailure {
            st.snackbarMsg = "Could not open Kirikiroid2: ${it.message}"
        }
    }
    suspend fun associateCatalogDownload(
        packageName: String,
        game: CatalogGame,
        installedApp: InstalledApp? = null,
    ) {
        val existing = repo.get()[packageName]
        var mapping = AppMapping(
            packageName = packageName,
            f95Url = game.canonicalUrl,
            lastSeenVersion = game.version,
            lastChecked = System.currentTimeMillis(),
            acknowledgedVersion = game.version,
            threadId = game.f95ThreadIdOrNull,
            notOnF95 = false,
            matchSource = "browser-download",
        ).withPersonalFieldsFrom(existing).withCatalogSnapshot(game)
        if (installedApp != null) mapping = mapping.withLocalIdentityFrom(installedApp)
        repo.upsert(mapping)
        AppLog.i(
            "BrowserDownload",
            "Associated installed package=$packageName with ${game.source}:${game.sourceId} group=${game.agmGroupId}",
        )
    }
    fun beginManagedExistingGame(file: java.io.File) {
        scope.launch {
            val folder = file.parentFile?.takeIf { it.isDirectory }
            if (folder == null) {
                st.snackbarMsg = "The selected launch file has no readable game folder."
                return@launch
            }
            val inspection = runCatching {
                withContext(Dispatchers.IO) {
                    ManagedGameDiscovery.inspect(
                        root = folder,
                        selectedLaunchFile = file,
                        joiPlayAvailable = joiPlayIsInstalled(),
                        winlatorAvailable = WinlatorClient.isInstalled(context.applicationContext),
                        kirikiroidAvailable = KirikiroidLauncher.isInstalled(context),
                    )
                }
            }.getOrElse {
                st.snackbarMsg = "Could not validate this game: ${it.message}"
                return@launch
            }
            st.managedInstallOwnsFiles = false
            st.managedInstallSourceArchive = null
            st.managedRunnerInspection = inspection
        }
    }
    // Tracks what to clean up after the system installer returns: optional package
    // name to verify the install succeeded, and optional source-archive file to
    // delete (set when user checked "delete after install").
    fun queuePackageRefresh(packageName: String?) {
        val normalized = packageName?.trim().orEmpty()
        if (normalized.isBlank()) {
            AppLog.w("LibraryScan", "Package refresh requested without a package name")
            return
        }
        val current = st.pendingPackageRefresh
        st.pendingPackageRefresh = PendingPackageRefresh(
            sequence = (current?.sequence ?: 0) + 1,
            packageNames = current?.packageNames.orEmpty() + normalized,
        )
    }

    // PackageInstaller session callback — fires when install completes/fails
    androidx.compose.runtime.DisposableEffect(Unit) {
        ApkInstaller.onProgress = { progress ->
            st.apkInstallProgress = progress
        }
        ApkInstaller.onResult = { pkg, success, msg ->
            AppLog.i("ApkInstall", "Session callback: success=$success msg=$msg pkg=$pkg")
            st.apkInstalling = false
            st.apkInstallProgress = null
            val pending = st.apkPostInstall
            st.apkPostInstall = null
            if (pending != null) {
                pending.extractedRoot?.let { root ->
                    runCatching { root.deleteRecursively() }
                        .onSuccess { AppLog.i("ApkInstall", "Deleted extracted APK root ${root.absolutePath}") }
                        .onFailure { AppLog.w("ApkInstall", "Could not delete extracted APK root ${root.absolutePath}", it) }
                }
                if (success) {
                    pending.sourceArchive?.let { src ->
                        runCatching {
                            if (src.exists() && src.delete()) {
                                AppLog.i("ApkInstall", "Deleted source ${src.absolutePath}")
                                st.snackbarMsg = "Installed ✓ Source deleted."
                            } else {
                                st.snackbarMsg = "Installed ✓ (could not delete source)."
                            }
                        }
                    } ?: run { st.snackbarMsg = "Install complete ✓" }
                    val installedPackage = pkg ?: pending.pkgName
                    queuePackageRefresh(installedPackage)
                    if (installedPackage != null && pending.catalogGame != null) {
                        scope.launch {
                            runCatching {
                                associateCatalogDownload(installedPackage, pending.catalogGame)
                            }.onFailure {
                                AppLog.e("BrowserDownload", "Could not associate installed APK", it)
                                st.snackbarMsg = "Installed, but AGM couldn't link it to the catalog: ${it.message}"
                            }
                        }
                    }
                } else {
                    val error = msg.ifBlank { "Install cancelled or failed; source kept." }
                    if (!pauseAutomaticBatch(error)) st.snackbarMsg = error
                }
            }
        }
        onDispose {
            ApkInstaller.onResult = null
            ApkInstaller.onProgress = null
            ApkInstaller.unregisterReceiver(context)
        }
    }
    androidx.compose.runtime.DisposableEffect(context.applicationContext) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                AppLog.i(
                    "LibraryScan",
                    "Package change action=${intent.action} package=${intent.data?.schemeSpecificPart}",
                )
                queuePackageRefresh(intent.data?.schemeSpecificPart)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
        }
    }
    // Legacy launcher kept for URI-based fallback (extracted APK without File handle)
    val apkInstallResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val rc = result.resultCode
        AppLog.i("ApkInstall", "Legacy installer returned rc=$rc")
        val pending = st.apkPostInstall
        st.apkPostInstall = null
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
                                st.snackbarMsg = "Installed. Source archive deleted."
                            } else {
                                st.snackbarMsg = "Installed (could not delete source)."
                            }
                        }
                    } ?: run { st.snackbarMsg = "Install complete." }
                    queuePackageRefresh(pending.pkgName)
                    if (pending.pkgName != null && pending.catalogGame != null) {
                        runCatching {
                            associateCatalogDownload(pending.pkgName, pending.catalogGame)
                        }.onFailure {
                            AppLog.e("BrowserDownload", "Could not associate installed APK", it)
                            st.snackbarMsg = "Installed, but AGM couldn't link it to the catalog: ${it.message}"
                        }
                    }
                } else {
                    st.snackbarMsg = "Install cancelled or failed; source kept."
                }
            }
        }
    }
    fun launchApkSession(
        target: java.io.File,
        deleteSource: java.io.File?,
        extractedRoot: java.io.File?,
    ) {
        val pkgName = runCatching {
            context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.packageName
        }.getOrNull()
        val catalogGame = st.pendingInstallCatalogGame
        st.pendingInstallCatalogGame = null
        AppLog.i(
            "ApkInstall",
            "Launching session install: pkg=$pkgName file=${target.name} deleteSrc=${deleteSource != null}",
        )
        st.apkPostInstall = ApkPostInstall(pkgName, deleteSource, extractedRoot, catalogGame)
        st.apkInstalling = true
        st.apkInstallProgress = ApkInstallProgress(ApkInstallProgress.Phase.Reading, 0f)
        scope.launch {
            runCatching { ApkInstaller.installApk(context, target, pkgName) }
                .onFailure {
                    extractedRoot?.deleteRecursively()
                    st.apkPostInstall = null
                    st.apkInstalling = false
                    st.apkInstallProgress = null
                    val message = "Installer failed: ${it.message}"
                    if (!pauseAutomaticBatch(message)) st.snackbarMsg = message
                    AppLog.e("ApkInstall", "Session install failed", it)
                }
        }
    }

    // Route a single picked file to its platform-specific install flow (shared by the single-file
    // installer and the bulk-install driver). Returns the detected route so callers can react to
    // Unsupported without waiting.
    fun routePickedFile(
        file: java.io.File,
        catalogGame: CatalogGame? = null,
        allowUpgradePrompt: Boolean = true,
    ): InstallRouting.PickRoute {
        val linkedDownload = DownloadStore.records.value.firstOrNull { record ->
            val path = record.destPath ?: return@firstOrNull false
            runCatching {
                java.io.File(path).canonicalFile == file.canonicalFile
            }.getOrDefault(false)
        }
        st.pendingInstallCatalogGame = catalogGame ?: linkedDownload?.catalogGame
        st.pendingExtractedSourceArchive = null
        st.pendingExtractedArchiveTitle = null
        val route = InstallRouting.routePick(file.name)
        when (route) {
            InstallRouting.PickRoute.InspectArchive -> prepareUnifiedArchive(file, allowUpgradePrompt)
            InstallRouting.PickRoute.InstallApk -> {
                if (unattendedBatch() != null) {
                    st.apkInstalling = true
                    scope.launch {
                        val deleteSource =
                            if (JoiPlaySettingsStore.deleteAfterInstall(context) && file.canWrite()) file else null
                        launchApkSession(file, deleteSource, extractedRoot = null)
                    }
                } else {
                    st.apkInstallConfirm = file
                }
            }
            InstallRouting.PickRoute.ChooseRunner,
            InstallRouting.PickRoute.HtmlOnly,
            InstallRouting.PickRoute.LaunchJoiPlayFile -> beginManagedExistingGame(file)
            is InstallRouting.PickRoute.Unsupported -> st.snackbarMsg = route.message
        }
        return route
    }
    // Open the Download Manager when the browser's download snackbar "View" action fires.
    LaunchedEffect(openDownloadsTick) {
        if (openDownloadsTick > 0) st.downloadsOpen = true
    }

    // True while ANY install step (dialog, extraction, external activity, background analysis) is
    // in flight. The bulk driver watches this to know when an item has fully settled.
    fun installBusy(): Boolean =
        st.apkInstallConfirm != null ||
            st.apkInstalling ||
            st.apkPostInstall != null ||
            st.pendingWinlatorOperation != null ||
            st.winlatorExecutableCandidate != null ||
            st.winlatorInstallerTarget != null ||
            st.winlatorConfigureTarget != null ||
            st.archiveAnalysisInProgress != null ||
            extractConfirm != null ||
            installDestinationPicker != null ||
            st.pendingArchiveDestination != null ||
            st.upgradePrompt != null ||
            upgradeFlow.busy ||
            patchFlow.busy ||
            st.managedRunnerInspection != null ||
            st.bucketExtract != null ||
            extractFlow.inProgress ||
            extractFlow.extractedRoot != null ||
            extractFlow.passwordPromptFor != null ||
            extractFlow.errorMessage != null

    // Bulk-install preflight: classifies every picked file and resolves every "does this replace an
    // installed game?" question BEFORE the queue runs, so no destructive choice is guessed mid-run.
    suspend fun preflightArchive(
        file: java.io.File,
        base: BatchPreflightItem,
    ): BatchPreflightItem {
        val analysis = runCatching { withContext(Dispatchers.IO) { ManagedArchiveInspector.analyze(file) } }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLog.w("Install", "Preflight could not inspect ${file.name}", error)
                return base.copy(note = "Could not read this archive: ${error.message ?: "unknown error"}")
            }
        if (analysis.entryNames.isEmpty()) {
            return base.copy(note = "Entry list unreadable until extraction")
        }
        val route = InstallRouting.routeArchive(analysis.entryNames)
        if (route is InstallRouting.ArchiveRoute.Unsupported) {
            return base.copy(kind = BatchItemKind.Unsupported, note = route.message, installable = false)
        }
        val decision = ManagedArchiveUpgradeCoordinator.decide(route, analysis, st.apps)
        val targets = (decision as? ManagedArchiveUpgradeCoordinator.Decision.Upgrade)?.matches.orEmpty()
            .mapNotNull { candidate ->
                candidate.app.managedGameId?.let { id ->
                    BatchUpgradeTarget(
                        managedGameId = id,
                        label = candidate.app.label,
                        storagePath = candidate.app.storagePath.orEmpty(),
                        reason = candidate.reason,
                    )
                }
            }
        AppLog.i(
            "ManagedUpgrade",
            "Preflight ${file.name}: route=$route upgradeTargets=${targets.size}",
        )
        return base.copy(targets = targets)
    }

    fun startBulkPreflight(files: List<java.io.File>) {
        if (files.isEmpty()) {
            st.snackbarMsg = "No files selected."
            return
        }
        // One bulk run at a time. Without this a second preflight would replace the first one's
        // state while its scan coroutine and (worse) its queue driver were still running.
        if (refuseInstallEntry(InstallOperationKind.Bulk)) return
        // A finished run only leaves its banner behind; drop it so the new run owns the state.
        if (st.batch?.isComplete == true) st.batch = null
        val runId = System.nanoTime()
        st.batchPreflight = BatchInstallPreflight(runId = runId, total = files.size)
        AppLog.i("Install", "Bulk preflight started for ${files.size} file(s) run=$runId")
        scope.launch {
            for (file in files) {
                if (st.batchPreflight?.runId != runId) return@launch
                val base = BatchInstall.preflightItem(file.name, file.absolutePath, file.isFile)
                val item = if (base.installable && base.kind == BatchItemKind.Archive) {
                    preflightArchive(file, base)
                } else {
                    base
                }
                val current = st.batchPreflight?.takeIf { it.runId == runId } ?: return@launch
                st.batchPreflight = current.withItem(item)
            }
            val current = st.batchPreflight?.takeIf { it.runId == runId } ?: return@launch
            st.batchPreflight = current.finishScan()
            AppLog.i(
                "Install",
                "Bulk preflight scanned ${current.items.size} file(s); " +
                    "${current.decisionItems.size} need an update decision",
            )
        }
    }

    // Bulk-install driver: processes the queue one item at a time by starting the item's normal
    // install flow, then waiting for it to engage and settle before advancing. Decoupled from every
    // individual completion callback via the installBusy() signal, which tolerates the transient
    // busy=false gaps between the multi-coroutine extract -> install handoffs (via a debounce window).
    // Upgrade routing is never re-run here: the preflight already stored the user's decision.
    //
    // Every write back into st.batch is scoped by the run id. Two bulk runs can legitimately hold
    // the very same file paths, so matching on the path alone would let a driver left over from a
    // cancelled run settle — and advance — the replacement run's items.
    LaunchedEffect(st.batch?.runId, st.batch?.activeIndex, st.batch?.pausedError, st.batch != null) {
        val session = st.batch ?: return@LaunchedEffect
        if (session.pausedError != null) return@LaunchedEffect
        val item = session.current ?: return@LaunchedEffect
        val runId = session.runId
        fun advance(status: BatchItemStatus) {
            val latest = st.batch ?: return
            // run was cancelled, replaced by a new run, or already moved on
            if (!latest.ownsActiveItem(runId, item.path)) {
                AppLog.i("Install", "Bulk driver for run=$runId ignored a stale settle of ${item.name}")
                return
            }
            st.batch = latest.settleActive(status).startNext()
        }
        fun pauseRun(message: String) {
            val latest = st.batch ?: return
            if (!latest.ownsActiveItem(runId, item.path)) return
            st.batch = latest.pause(message)
        }
        val file = java.io.File(item.path)
        if (!file.isFile) {
            val message = "${item.name}: file no longer exists."
            if (session.isUnattendedActive) pauseRun(message)
            else {
                st.snackbarMsg = "Skipped $message"
                advance(BatchItemStatus.Failed)
            }
            return@LaunchedEffect
        }
        rememberPickerDir(file)
        when (val decision = item.decision) {
            is BatchInstallDecision.Upgrade -> {
                val target = st.apps.firstOrNull { it.managedGameId == decision.managedGameId }
                if (target == null) {
                    val message = "${item.name}: ${decision.targetLabel} is no longer in the library."
                    if (session.isUnattendedActive) pauseRun(message)
                    else {
                        st.snackbarMsg = message
                        advance(BatchItemStatus.Failed)
                    }
                    return@LaunchedEffect
                }
                AppLog.i(
                    "ManagedUpgrade",
                    "Bulk item ${item.name} upgrading '${target.label}' per preflight decision",
                )
                val outcome = upgradeFlow.start(file, target)
                outcome.refusalOrNull?.let { reason ->
                    val message = "${item.name}: $reason"
                    if (session.isUnattendedActive) pauseRun(message)
                    else {
                        st.snackbarMsg = message
                        advance(BatchItemStatus.Failed)
                    }
                    return@LaunchedEffect
                }
            }
            BatchInstallDecision.Skip -> {
                advance(BatchItemStatus.Skipped)
                return@LaunchedEffect
            }
            BatchInstallDecision.InstallAsNew, null -> {
                val route = routePickedFile(file, allowUpgradePrompt = false)
                if (route is InstallRouting.PickRoute.Unsupported) {
                    if (session.isUnattendedActive) pauseRun(route.message)
                    else advance(BatchItemStatus.Failed)
                    return@LaunchedEffect
                }
            }
        }
        val engaged = withTimeoutOrNull(BULK_ENGAGE_GRACE_MS) {
            snapshotFlow { installBusy() }.first { it }
        } != null
        if (!engaged) {
            if (session.isUnattendedActive) {
                pauseRun("${item.name} did not enter a valid install flow.")
            } else {
                advance(BatchItemStatus.Failed)
            }
            return@LaunchedEffect
        }
        while (true) {
            snapshotFlow { installBusy() }.first { !it }
            val resumed = withTimeoutOrNull(BULK_SETTLE_DEBOUNCE_MS) {
                snapshotFlow { installBusy() }.first { it }
            } != null
            if (!resumed) break
        }
        if (st.batch?.takeIf { it.runId == runId }?.pausedError != null) return@LaunchedEffect
        advance(BatchItemStatus.Done)
    }
    LaunchedEffect(st.batch?.isComplete == true) {
        val session = st.batch ?: return@LaunchedEffect
        if (session.isComplete) {
            st.snackbarMsg = buildString {
                append("Bulk install finished: ${session.doneCount} of ${session.total} processed")
                val skipped = session.failedCount + session.skippedCount
                if (skipped > 0) append(", $skipped skipped")
                append('.')
            }
        }
    }
    val refreshRunMutex = remember { kotlinx.coroutines.sync.Mutex() }
    var manualNoAutoResultCount by remember { mutableIntStateOf(0) }

    fun liveMappings(): Map<String, AppMapping> = mappings + st.mappingOverrides

    fun rememberMapping(mapping: AppMapping) {
        st.mappingOverrides = st.mappingOverrides + (mapping.packageName to mapping)
    }

    fun currentUnmappedRows(): List<AppRow> = st.apps.mapNotNull { app ->
        val m = mappings[app.packageName]
        if (m?.notOnF95 == true) return@mapNotNull null
        if (m?.f95Url.isNullOrBlank()) AppRow(app, m, UpdateStatus.NotMapped) else null
    }.sortedBy { it.installed.label.lowercase() }

    fun currentManualMatchedRows(): List<AppRow> = st.apps.mapNotNull { app ->
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
        val rows = currentManualMatchedRows()
        val byId = catalog.catalogGamesByF95ThreadIds(
            rows.mapNotNull { it.mapping?.f95CatalogThreadId() }.toSet(),
        )
        return rows.map { row ->
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
            scope.launch(Dispatchers.IO) {
                try {
                    repo.upsert(updated)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    AppLog.e("CatalogRefresh", "Failed to persist migrated local identity", error)
                }
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
    // Returns Pair(matched, unmatched), or null when another refresh owns the run or it fails.
    // Updates [refreshProgress] for the foreground dialog; honors [refreshCancelled].
    suspend fun refreshFromCatalog(targetRows: List<AppRow>): Pair<Int, Int>? {
        if (!refreshRunMutex.tryLock()) {
            AppLog.i("CatalogRefresh", "Ignored overlapping refresh request")
            return null
        }
        st.refreshCancelled = false
        st.refreshProgress = RefreshProgress(0, targetRows.size, 0, System.currentTimeMillis())
        return try {
            AppLog.i("CatalogRefresh", "Started for ${targetRows.size} rows")
            CatalogMemoryDiagnostics.log(
                phase = "catalog_refresh_start",
                detail = "rows=${targetRows.size}",
            )
            val knownMappings = liveMappings().values + targetRows.mapNotNull { it.mapping }
            val byId = catalog.catalogGamesByF95ThreadIds(
                knownMappings.mapNotNull(AppMapping::f95CatalogThreadId).toSet(),
            )
            AppLog.i("CatalogRefresh", "Loaded catalog: byId=${byId.size}")
            CatalogMemoryDiagnostics.log(
                phase = "catalog_refresh_catalog_ready",
                detail = "games=${byId.size}",
            )
            var matched = 0
            var unmatched = 0
            val ambiguous = mutableListOf<AmbiguousCatalogMatch>()
            val alreadyMatched = mutableListOf<AlreadyMatchedCatalogMatch>()
            val manualOverrides = mutableListOf<ManualOverrideMatch>()
            var manualNoAuto = 0
            val startMs = st.refreshProgress!!.startedAtMs
            for ((idx, row) in targetRows.withIndex()) {
                if (st.refreshCancelled) {
                    AppLog.i("CatalogRefresh", "Cancelled at ${idx}/${targetRows.size}")
                    break
                }
                val current = liveMappings()[row.installed.packageName] ?: row.mapping
                if (current?.notOnF95 == true) {
                    unmatched++
                    st.refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                    continue
                }
                if (InstalledAppIgnoreRules.shouldSkipCatalogMatching(
                        row.installed,
                        hasExistingMapping = !current?.f95Url.isNullOrBlank(),
                    )
                ) {
                    AppLog.i(
                        "CatalogRefresh",
                        "SKIP NON-GAME app='${row.installed.label}' " +
                            "pkg=${row.installed.packageName} source=${row.installed.source}",
                    )
                    st.refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                    continue
                }
                val isManualMapping = current?.matchSource?.startsWith("manual") == true
                val tidFromMapping = current?.threadId
                val tidFromUrl = F95UrlParser.extractThreadId(current?.f95Url)
                val nameCandidates = nameCandidatesFor(row.installed)
                val identityCandidates = identityCandidatesFor(row.installed)
                val labels = nameCandidates.map { it.text }.distinct()
                val matchContext = catalogMatchLogContext(row.installed, labels, identityCandidates)
                val allowAcronym = allowCatalogAcronymMatch(row.installed, labels)
                val norm = CatalogRepository.normalizeTitle(labels.firstOrNull() ?: row.installed.label)
                // Non-manual (already auto-matched) mappings keep the fast preserve path: a
                // refresh just re-checks their version. Manual mappings fall through so we can
                // compute the automated suggestion and categorize (confirm / differ / none).
                if (!isManualMapping && !current?.f95Url.isNullOrBlank() && !st.overwriteManualMatches) {
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
                    st.refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                    continue
                }
                var via = ""
                var candidate = if (!isManualMapping) tidFromMapping?.let { byId[it] }?.also { via = "tid-mapping" } else null
                if (candidate == null) {
                    candidate = if (!isManualMapping) tidFromUrl?.let { byId[it] }?.also { via = "tid-url" } else null
                }
                if (candidate != null && !isManualMapping) {
                    val c = candidate
                    val trusted = catalog.likelySameTitle(labels, c, allowAcronym)
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
                // Exactly one scored analysis over the shared text-search index — replaces the
                // old duplicate bestTitleMatch calls plus ambiguousTitleMatch fallback.
                var reviewCandidates: List<CatalogGame> = emptyList()
                var reviewVia = "none"
                if (candidate == null) {
                    val analysisStartedAt = System.currentTimeMillis()
                    if (idx == 0) {
                        CatalogMemoryDiagnostics.log(
                            phase = "catalog_refresh_first_analysis_start",
                            detail = "candidates=${nameCandidates.size}",
                        )
                    }
                    when (val analysis = catalog.scoredTitleAnalysis(nameCandidates, identityCandidates)) {
                        is ScoredCatalogTitleResult.Selected -> {
                            candidate = analysis.game
                            via = analysis.via
                        }
                        is ScoredCatalogTitleResult.Ambiguous -> {
                            reviewCandidates = analysis.candidates
                            reviewVia = analysis.via
                        }
                        ScoredCatalogTitleResult.NoMatch -> Unit
                    }
                    if (idx == 0) {
                        CatalogMemoryDiagnostics.log(
                            phase = "catalog_refresh_first_analysis_complete",
                            startedAtMs = analysisStartedAt,
                            detail = "reviewCandidates=${reviewCandidates.size} matched=${candidate != null}",
                        )
                    }
                }
                if (isManualMapping) {
                    val autoCandidate = candidate
                    val same = autoCandidate != null && (
                        (autoCandidate.f95ThreadIdOrNull != null &&
                            autoCandidate.f95ThreadIdOrNull == current?.threadId) ||
                        (autoCandidate.canonicalUrl.isNotBlank() &&
                            autoCandidate.canonicalUrl == current?.f95Url)
                    )
                    when {
                        autoCandidate != null && (same || st.overwriteManualMatches) -> {
                            // (a) automated result agrees with the manual choice — or the user
                            // asked to force overwrite — so mark it as automated.
                            matched++
                            AppLog.i(
                                "CatalogRefresh",
                                "MANUAL->AUTO $matchContext -> tid=${autoCandidate.thread_id} " +
                                    "same=$same via=$via",
                            )
                            repo.upsert(
                                AppMapping(
                                    packageName = row.installed.packageName,
                                    f95Url = autoCandidate.canonicalUrl,
                                    lastSeenVersion = autoCandidate.version,
                                    lastChecked = System.currentTimeMillis(),
                                    acknowledgedVersion = current?.acknowledgedVersion,
                                    threadId = autoCandidate.f95ThreadIdOrNull,
                                    matchSource = "catalog-auto:$via",
                                ).withPersonalFieldsFrom(current).withCatalogSnapshot(autoCandidate),
                            )
                        }
                        autoCandidate != null -> {
                            // (b) automated result differs — keep the manual mapping for now and
                            // queue it for the selective overwrite review.
                            manualOverrides += ManualOverrideMatch(row, current!!, autoCandidate, via)
                            matched++
                            AppLog.i(
                                "CatalogRefresh",
                                "MANUAL DIFFERS $matchContext current=tid${current?.threadId} " +
                                    "auto=tid${autoCandidate.thread_id} '${autoCandidate.title}'",
                            )
                            repo.upsert(current!!.copy(lastChecked = System.currentTimeMillis()))
                        }
                        else -> {
                            // (c) no automated result — keep the manual mapping.
                            manualNoAuto++
                            AppLog.i("CatalogRefresh", "MANUAL NO-AUTO $matchContext")
                            current?.let {
                                repo.upsert(it.copy(lastChecked = System.currentTimeMillis()))
                            }
                        }
                    }
                    st.refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                    if ((idx + 1) % 25 == 0) {
                        CatalogMemoryDiagnostics.log(
                            phase = "catalog_refresh_checkpoint",
                            detail = "completed=${idx + 1}/${targetRows.size} matched=$matched unmatched=$unmatched",
                        )
                    }
                    continue
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
                    if (reviewCandidates.isNotEmpty()) {
                        val item = AmbiguousCatalogMatch(
                            row = row,
                            candidates = reviewCandidates,
                            via = reviewVia,
                        )
                        val prior = previouslyMappedCandidate(item.candidates, targetRows.map { it.mapping })
                            ?: previouslyMappedByLocalIdentity(row, byId, targetRows.map { it.mapping })
                        if (prior != null) alreadyMatched += AlreadyMatchedCatalogMatch(item, prior)
                        else ambiguous += item
                        AppLog.i(
                            "CatalogRefresh",
                            "AMBIGUOUS $matchContext via=$reviewVia: " +
                                reviewCandidates.joinToString { "${it.thread_id} '${it.title}'" }
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
                st.refreshProgress = RefreshProgress(idx + 1, targetRows.size, matched, startMs)
                if ((idx + 1) % 25 == 0) {
                    CatalogMemoryDiagnostics.log(
                        phase = "catalog_refresh_checkpoint",
                        detail = "completed=${idx + 1}/${targetRows.size} matched=$matched unmatched=$unmatched",
                    )
                }
            }
            st.ambiguousCatalogMatches = emptyList()
            st.unmappedReviewMatches = ambiguous
            st.alreadyMatchedReviewMatches = alreadyMatched
            st.manualOverrideMatches = manualOverrides
            manualNoAutoResultCount = manualNoAuto
            st.manualOverrideReviewOpen = manualOverrides.isNotEmpty() && !st.refreshCancelled
            st.unmatchedFoundPromptOpen = (ambiguous.isNotEmpty() || alreadyMatched.isNotEmpty()) &&
                manualOverrides.isEmpty() && !st.refreshCancelled
            AppLog.i("CatalogRefresh", "Done: matched=$matched unmatched=$unmatched ambiguous=${ambiguous.size} alreadyMatched=${alreadyMatched.size} manualDiffer=${manualOverrides.size} manualNoAuto=$manualNoAuto cancelled=${st.refreshCancelled}")
            CatalogMemoryDiagnostics.log(
                phase = "catalog_refresh_complete",
                startedAtMs = startMs,
                detail = "matched=$matched unmatched=$unmatched",
            )
            matched to unmatched
        } catch (t: Throwable) {
            AppLog.e("CatalogRefresh", "Failed", t)
            CrashReporter.logCaught(context.applicationContext, "refresh_from_catalog", t)
            null
        } finally {
            val wasCancelled = st.refreshCancelled
            st.refreshProgress = null
            if (wasCancelled) st.refreshCancelledNote = true
            refreshRunMutex.unlock()
        }
    }

    LaunchedEffect(st.pendingWinlatorRefresh?.sequence) {
        val request = st.pendingWinlatorRefresh ?: return@LaunchedEffect
        runCatching {
            st.apps = scanInstalledLibrary(context)

            // Keep an open per-game config panel in sync with the freshly-persisted config so an
            // applied change (e.g. resolution) shows the new value immediately instead of the stale
            // pre-change value captured when the panel was opened.
            request.gameId?.let { gid ->
                if (st.winlatorConfigureTarget?.winlatorGameId == gid) {
                    st.apps.firstOrNull { it.winlatorGameId == gid }
                        ?.let { st.winlatorConfigureTarget = it }
                }
            }

            if (request.matchCatalog) {
                val target = request.gameId?.let { gameId ->
                    st.apps.firstOrNull { it.winlatorGameId == gameId }
                }
                if (target != null) {
                    val mapping = liveMappings()[target.packageName] ?: repo.get()[target.packageName]
                    refreshFromCatalog(listOf(AppRow(target, mapping, UpdateStatus.Unknown)))
                } else {
                    AppLog.w("Winlator", "New game ${request.gameId} was not available for targeted catalog matching")
                }
            }

            if (request.removeGame) {
                st.cleanupRootPath?.let { startCleanupReview(it, st.apps) }
            }
        }.onFailure {
            AppLog.w("Winlator", "Post-operation targeted refresh failed", it)
        }.also {
            if (st.pendingWinlatorRefresh?.sequence == request.sequence) {
                st.pendingWinlatorRefresh = null
            }
        }
    }

    // Package replacement emits removed/added/replaced broadcasts. Debounce them into one library
    // scan and catalog-match only packages that are actual games in the refreshed library.
    LaunchedEffect(st.pendingPackageRefresh) {
        val request = st.pendingPackageRefresh ?: return@LaunchedEffect
        delay(750)
        runCatching {
            st.apps = scanInstalledLibrary(context)
            val currentMappings = liveMappings()
            val freshRows = st.apps
                .filter { it.packageName in request.packageNames }
                .map { app ->
                    AppRow(app, currentMappings[app.packageName], UpdateStatus.Unknown)
                }
            AppLog.i(
                "LibraryScan",
                "Package refresh coalesced packages=${request.packageNames.sorted()} " +
                    "catalogTargets=${freshRows.map { it.installed.packageName }}",
            )
            if (freshRows.isNotEmpty()) {
                refreshFromCatalog(freshRows)
            }
        }.onFailure { AppLog.w("ApkInstall", "Post-install scan/match failed", it) }
        if (st.pendingPackageRefresh?.sequence == request.sequence) {
            st.pendingPackageRefresh = null
        }
    }
    // First-run state machine (declared here so the JoiPlay picker can reference it):
    //   isFirstRunFlow=true while the user is in the welcome -> JoiPlay-import sequence.
    //   firstRunRefreshPending fires the full catalog refresh once the sequence ends.

    fun askForJoiPlayBackupFolderAccess(firstRun: Boolean) {
        val existingRoot = st.backupScopedRootUri ?: st.importBackupScopedRootUri
        if (existingRoot != null) {
            st.backupScopedRootUri = existingRoot
            st.importBackupScopedRootUri = existingRoot
            st.joiplayBackupPickerFirstRun = firstRun
            st.backupScopedPickerOpen = true
        } else if (hasAllFilesAccess()) {
            st.joiplayBackupPickerFirstRun = firstRun
            st.joiplayBackupFilePickerOpen = true
        } else {
            st.joiplayBackupAccessDisclosureFirstRun = firstRun
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
        st.snackbarMsg = "Set installed version $cleaned for ${row.installed.label}"
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
        st.snackbarMsg = "Set installed date ${fmtDate(dateMs)} for ${row.installed.label}"
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
                if (s.managedGames > 0) parts.add("${s.managedGames} managed games")
                if (s.joiplayOverrides > 0) parts.add("${s.joiplayOverrides} version overrides")
                st.snackbarMsg = if (parts.isEmpty()) "Nothing imported" else "Imported " + parts.joinToString(", ")
                if (s.joiplayGames > 0 || s.managedGames > 0) {
                    st.apps = scanInstalledLibrary(context)
                }
                st.catalogReloadTick++
            }.onFailure {
                st.snackbarMsg = "Import failed: ${it.message}"
            }
        }
    }

    fun startJoiPlayBackupImport(uri: Uri, firstRun: Boolean) {
        st.joiplayImportBusy = true
        scope.launch {
            runCatching {
                importJoiPlayBackupIntoManagedGames(context.applicationContext, uri, repo)
            }.onSuccess { summary ->
                    AppLog.i(
                        "JoiPlay",
                        "Backup contained ${summary.backupGames}; imported=${summary.imported} " +
                            "skippedMissing=${summary.skippedMissing}",
                    )
                    st.apps = scanInstalledLibrary(context)
                    st.joiplayImportBusy = false

                    if (firstRun) {
                        st.firstRunRefreshPending = true
                        st.snackbarMsg =
                            "Imported ${summary.imported} existing games; matching all apps to catalog…"
                    } else {
                        val managedRows = st.apps.filter { it.source == AppSource.Managed }
                            .map { app ->
                                val m = liveMappings()[app.packageName] ?: repo.get()[app.packageName]
                                AppRow(app, m, UpdateStatus.Unknown)
                            }
                        st.snackbarMsg = "Imported ${summary.imported} games; matching to catalog…"
                        val matched = refreshFromCatalog(managedRows)?.first ?: 0
                        st.snackbarMsg = buildString {
                            append("Imported ${summary.imported} existing games, $matched matched to catalog")
                            if (summary.skippedMissing > 0) {
                                append("; skipped ${summary.skippedMissing} missing or invalid")
                            }
                        }
                    }
                }
                .onFailure { t ->
                    AppLog.e("JoiPlay", "Backup import failed", t)
                    st.joiplayImportBusy = false
                    val msg = t.message ?: "unknown error"
                    if (firstRun) {
                        st.joiplayImportError = msg
                        st.firstRunHintOpen = true
                    } else {
                        st.snackbarMsg = "JoiPlay import failed: $msg"
                    }
                }
        }
    }

    val joiplayBackupFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val firstRun = st.joiplayBackupPickerFirstRun
        if (uri == null) {
            st.joiplayBackupPickerFirstRun = false
            if (firstRun) st.firstRunHintOpen = true
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { AppLog.w("JoiPlay", "Could not persist backup folder permission", it) }
        st.backupScopedRootUri = uri
        st.importBackupScopedRootUri = uri
        scope.launch { JoiPlaySettingsStore.setBackupFolderUri(context.applicationContext, uri.toString()) }
        st.backupScopedPickerOpen = true
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
        st.backupScopedRootUri = uri
        st.importBackupScopedRootUri = uri
        scope.launch { JoiPlaySettingsStore.setBackupFolderUri(context.applicationContext, uri.toString()) }
        st.importBackupPickerOpen = true
    }

    // Declare delete-flow state early so the SAF picker (below) can reference it.

    val installGamesRootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val pending = st.pendingArchiveDestination
        if (uri == null || pending == null) {
            st.pendingArchiveDestination = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure {
                AppLog.w("Install", "Could not persist shared games-folder permission", it)
            }
            val document = DocumentFile.fromTreeUri(context.applicationContext, uri)
                ?.takeIf { it.isDirectory }
            val resolved = document?.let {
                SharedGamesRoot.Resolved(
                    uri = uri,
                    document = it,
                    absoluteDirectory = SharedGamesRoot.resolveTreeUri(context.applicationContext, uri),
                )
            }
            val root = resolved?.let {
                SharedGamesRoot.extractionRoot(
                    it,
                    requireAbsolutePath =
                        pending.target == InstallRouting.Target.Winlator ||
                            pending.target == InstallRouting.Target.Managed ||
                            pending.target == InstallRouting.Target.Auto,
                )
            }
            if (root == null) {
                st.snackbarMsg = if (
                    pending.target == InstallRouting.Target.Winlator ||
                    pending.target == InstallRouting.Target.Managed ||
                    pending.target == InstallRouting.Target.Auto
                ) {
                    "That folder doesn't resolve to a Winlator-accessible shared-storage path."
                } else {
                    "AGM couldn't write to that games folder."
                }
                return@launch
            }
            JoiPlayScanner.setRootUri(context.applicationContext, uri)
            st.pendingArchiveDestination = null
            st.extractTarget = pending.target
            extractConfirm = pending.archive to root
            AppLog.i("Install", "Shared games root set to $uri for target=${pending.target}")
        }
    }

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
                // If user was in the middle of trying to delete a JoiPlay game, continue that flow.
                val pendingDelete = st.joiPlayGrantAskFor
                st.joiPlayGrantAskFor = null
                if (pendingDelete != null) {
                    st.joiPlayDeleteConfirm = pendingDelete
                }
            }
        } else {
            st.joiPlayGrantAskFor = null
        }
    }

    fun requestJoiPlaySizeScan(force: Boolean) {
        if (st.joiPlaySizeScanning) {
            st.snackbarMsg = "Game storage scan already running"
            return
        }
        AppLog.i("GameSize", "Manual storage refresh requested force=$force")
        st.joiPlaySizeScanForce = force
        st.joiPlaySizeScanRequest++
    }

    fun requestRenPySaveScan() {
        if (st.renPySaveScanning) return
        if (!hasAllFilesAccess()) {
            st.permissionRationale = PermissionRationale.AllFilesRenPySaves
            return
        }
        st.renPySaveScanManualRequest = true
        st.renPySaveScanRequest++
    }

    fun updateRenPySaveLocations(locations: List<RenPySaveLocation>) {
        val sorted = locations.sortedWith(
            compareByDescending<RenPySaveLocation> { it.associatedPackageName != null }
                .thenByDescending { it.confidence }
                .thenBy { it.saveDirPath.lowercase() }
        )
        st.renPySaveLocations = sorted
        st.renPySaveAssociations = RenPySaveScanner.associationsByPackage(sorted)
    }

    fun persistRenPySaveState() {
        scope.launch {
            RenPySaveScanner.saveSnapshot(context.applicationContext, st.renPySaveLocations, st.renPySaveLastScannedAt)
            RenPySaveScanner.saveManualAssociations(context.applicationContext, st.renPyManualAssociations)
        }
    }

    fun manuallyAssociateRenPyLocation(location: RenPySaveLocation, app: InstalledApp) {
        val manual = RenPySaveManualAssociation(
            saveDirPath = location.saveDirPath,
            packageName = app.packageName,
            label = app.label,
        )
        st.renPyManualAssociations = st.renPyManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
        updateRenPySaveLocations(
            st.renPySaveLocations.map {
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
        st.renPySaveAssociationPicker = null
        st.snackbarMsg = "Associated saves with ${app.label}"
    }

    fun addRenPySaveFolderToGame(row: AppRow, folderPath: String) {
        scope.launch {
            val location = RenPySaveScanner.verifiedLocationForFolder(folderPath, row.installed)
            if (location == null) {
                st.snackbarMsg = "No verified Ren'Py .save files found in that folder"
                return@launch
            }
            val manual = RenPySaveManualAssociation(
                saveDirPath = location.saveDirPath,
                packageName = row.installed.packageName,
                label = row.installed.label,
            )
            st.renPyManualAssociations = st.renPyManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
            updateRenPySaveLocations(
                (st.renPySaveLocations.filterNot { it.saveDirPath == location.saveDirPath } + location)
            )
            persistRenPySaveState()
            st.snackbarMsg = "Added Ren'Py saves to ${row.installed.label}"
        }
    }

    fun updateRpgmSaveLocations(locations: List<RpgmSaveLocation>) {
        val sorted = locations.sortedWith(
            compareByDescending<RpgmSaveLocation> { it.associatedPackageName != null }
                .thenByDescending { it.confidence }
                .thenBy { it.saveDirPath.lowercase() }
        )
        st.rpgmSaveLocations = sorted
        st.rpgmSaveAssociations = RpgmSaveScanner.associationsByPackage(sorted)
    }

    fun persistRpgmSaveState() {
        scope.launch {
            RpgmSaveScanner.saveSnapshot(context.applicationContext, st.rpgmSaveLocations, st.rpgmSaveLastScannedAt)
            RpgmSaveScanner.saveManualAssociations(context.applicationContext, st.rpgmManualAssociations)
        }
    }

    fun requestRpgmSaveScan() {
        if (st.rpgmSaveScanning) return
        if (!hasAllFilesAccess()) {
            st.permissionRationale = PermissionRationale.AllFilesRenPySaves
            return
        }
        st.rpgmSaveScanManualRequest = true
        st.rpgmSaveScanRequest++
    }

    fun addRpgmSaveFolderToGame(row: AppRow, folderPath: String) {
        scope.launch {
            val location = RpgmSaveScanner.verifiedLocationForFolder(folderPath, row.installed)
            if (location == null) {
                st.snackbarMsg = "No verified RPGM saves found in that folder"
                return@launch
            }
            val manual = RpgmSaveManualAssociation(
                saveDirPath = location.saveDirPath,
                packageName = row.installed.packageName,
                label = row.installed.label,
            )
            st.rpgmManualAssociations = st.rpgmManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
            updateRpgmSaveLocations(st.rpgmSaveLocations.filterNot { it.saveDirPath == location.saveDirPath } + location)
            persistRpgmSaveState()
            st.snackbarMsg = "Added RPGM saves to ${row.installed.label}"
        }
    }

    fun manuallyAssociateRpgmLocation(location: RpgmSaveLocation, app: InstalledApp) {
        val manual = RpgmSaveManualAssociation(
            saveDirPath = location.saveDirPath,
            packageName = app.packageName,
            label = app.label,
        )
        st.rpgmManualAssociations = st.rpgmManualAssociations + (location.saveDirPath.replace('\\', '/') to manual)
        updateRpgmSaveLocations(
            st.rpgmSaveLocations.map {
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
        st.rpgmSaveAssociationPicker = null
        st.snackbarMsg = "Associated RPGM saves with ${app.label}"
    }

    fun clearRpgmManualAssociation(location: RpgmSaveLocation) {
        val key = location.saveDirPath.replace('\\', '/')
        st.rpgmManualAssociations = st.rpgmManualAssociations - key
        updateRpgmSaveLocations(
            st.rpgmSaveLocations.map {
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
        st.snackbarMsg = "Cleared manual RPGM save association"
    }

    fun clearRenPyManualAssociation(location: RenPySaveLocation) {
        val key = location.saveDirPath.replace('\\', '/')
        st.renPyManualAssociations = st.renPyManualAssociations - key
        updateRenPySaveLocations(
            st.renPySaveLocations.map {
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
        st.snackbarMsg = "Cleared manual save association"
    }

    LaunchedEffect(st.renPySaveScanRequest, st.apps) {
        if (st.renPySaveScanRequest == 0 || st.renPySaveScanning || st.apps.isEmpty()) return@LaunchedEffect
        val manualRequest = st.renPySaveScanManualRequest
        st.renPySaveScanManualRequest = false
        if (!manualRequest && !hasAllFilesAccess()) {
            AppLog.i("RenPySaves", "Skipping automatic save scan because All files access is not granted")
            return@LaunchedEffect
        }
        st.renPySaveScanning = true
        try {
            val manual = RenPySaveScanner.loadManualAssociations(context.applicationContext)
            st.renPyManualAssociations = manual
            val result = RenPySaveScanner.scan(st.apps)
            val locations = RenPySaveScanner.applyManualAssociations(result.locations, manual, st.apps)
            st.renPySaveAssociations = RenPySaveScanner.associationsByPackage(locations)
            st.renPySaveLocations = locations
            st.renPySaveLastScannedAt = result.lastScannedAt
            RenPySaveScanner.saveSnapshot(context.applicationContext, locations, result.lastScannedAt)
            val gameCount = st.renPySaveAssociations.size
            val folderCount = st.renPySaveLocations.size
            val unmatched = st.renPySaveLocations.count { it.associatedPackageName == null }
            if (manualRequest) {
                st.snackbarMsg = "Ren'Py saves: $folderCount folder${if (folderCount == 1) "" else "s"}, $gameCount associated game${if (gameCount == 1) "" else "s"}, $unmatched unmatched"
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (manualRequest) {
                AppLog.e("RenPySaves", "Manual scan failed", t)
                CrashReporter.logCaught(context.applicationContext, "renpy_save_scan", t)
                st.snackbarMsg = "Ren'Py save scan failed: ${t.message}"
            } else {
                AppLog.w("RenPySaves", "Automatic scan failed (not shown to user): ${t.message}", t)
            }
        } finally {
            st.renPySaveScanning = false
        }
    }

    LaunchedEffect(st.rpgmSaveScanRequest, st.apps) {
        if (st.rpgmSaveScanRequest == 0 || st.rpgmSaveScanning || st.apps.isEmpty()) return@LaunchedEffect
        val manualRequest = st.rpgmSaveScanManualRequest
        st.rpgmSaveScanManualRequest = false
        if (!manualRequest && !hasAllFilesAccess()) {
            AppLog.i("RpgmSaves", "Skipping automatic save scan because All files access is not granted")
            return@LaunchedEffect
        }
        st.rpgmSaveScanning = true
        try {
            val manual = RpgmSaveScanner.loadManualAssociations(context.applicationContext)
            st.rpgmManualAssociations = manual
            val result = RpgmSaveScanner.scan(st.apps)
            val locations = RpgmSaveScanner.applyManualAssociations(result.locations, manual, st.apps)
            st.rpgmSaveAssociations = RpgmSaveScanner.associationsByPackage(locations)
            st.rpgmSaveLocations = locations
            st.rpgmSaveLastScannedAt = result.lastScannedAt
            RpgmSaveScanner.saveSnapshot(context.applicationContext, locations, result.lastScannedAt)
            if (manualRequest) {
                val gameCount = st.rpgmSaveAssociations.size
                val unmatched = st.rpgmSaveLocations.count { it.associatedPackageName == null }
                st.snackbarMsg = "RPGM saves: ${st.rpgmSaveLocations.size} folder${if (st.rpgmSaveLocations.size == 1) "" else "s"}, $gameCount associated game${if (gameCount == 1) "" else "s"}, $unmatched unmatched"
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (manualRequest) {
                AppLog.e("RpgmSaves", "Manual scan failed", t)
                st.snackbarMsg = "RPGM save scan failed: ${t.message}"
            } else {
                AppLog.w("RpgmSaves", "Automatic scan failed (not shown to user): ${t.message}", t)
            }
        } finally {
            st.rpgmSaveScanning = false
        }
    }

    LaunchedEffect(Unit) {
        st.joiPlaySizeInfo = JoiPlayScanner.loadSizeInfo(context.applicationContext)
        GameStorageSizeWork.configurePeriodic(context.applicationContext)
        st.renPyManualAssociations = RenPySaveScanner.loadManualAssociations(context.applicationContext)
        val snapshot = RenPySaveScanner.loadSnapshot(context.applicationContext)
        st.renPySaveLocations = snapshot.locations
        st.renPySaveAssociations = RenPySaveScanner.associationsByPackage(snapshot.locations)
        st.renPySaveLastScannedAt = snapshot.lastScannedAt
        st.rpgmManualAssociations = RpgmSaveScanner.loadManualAssociations(context.applicationContext)
        val rpgmSnapshot = RpgmSaveScanner.loadSnapshot(context.applicationContext)
        st.rpgmSaveLocations = rpgmSnapshot.locations
        st.rpgmSaveAssociations = RpgmSaveScanner.associationsByPackage(rpgmSnapshot.locations)
        st.rpgmSaveLastScannedAt = rpgmSnapshot.lastScannedAt
    }

    // Auto-size any AGM-managed game that has no cached size yet. Re-arms whenever the
    // set of un-sized games changes (e.g. a new install), and is loop-safe: a folder that can't be
    // resolved keeps the same key set, so it won't retrigger until the library actually changes.
    LaunchedEffect(st.apps, st.joiPlaySizeInfo) {
        val unsized = st.apps.asSequence()
            .filter { it.source == AppSource.Managed || it.source == AppSource.JoiPlay }
            .mapNotNull { joiPlaySizeKey(it) }
            .filter { (st.joiPlaySizeInfo[it]?.lastScannedAt ?: 0L) <= 0L }
            .toSet()
        if (unsized.isEmpty() || unsized == st.joiPlaySizeAutoRequestedKeys || st.joiPlaySizeScanning) {
            return@LaunchedEffect
        }
        st.joiPlaySizeAutoRequestedKeys = unsized
        st.joiPlaySizeScanForce = false
        st.joiPlaySizeScanRequest++
    }

    LaunchedEffect(st.joiPlaySizeScanRequest) {
        if (st.joiPlaySizeScanRequest == 0 || st.joiPlaySizeScanning) return@LaunchedEffect
        val force = st.joiPlaySizeScanForce
        val targets = managedGameSizeTargets(st.apps)
        if (targets.isEmpty()) {
            AppLog.w("GameSize", "Storage refresh had no managed-game targets")
            st.snackbarMsg = "No managed game folders are available to scan."
            return@LaunchedEffect
        }
        val targetKeys = targets.map { it.key }.toSet()
        val before = JoiPlayScanner.loadSizeInfo(context.applicationContext)
        st.joiPlaySizeInfo = before
        val toScan = if (force) targetKeys else targetKeys.filter { before[it]?.lastScannedAt ?: 0L <= 0L }.toSet()
        if (toScan.isEmpty()) return@LaunchedEffect

        st.joiPlaySizeScanning = true
        st.joiPlaySizeScanningFolders = toScan
        st.joiPlaySizeScanProgress = null
        st.snackbarMsg = if (force) "Refreshing game storage sizes…" else "Scanning game storage sizes…"
        AppLog.i("GameSize", "Starting managed size scan force=$force folders=${toScan.size}")
        runCatching {
            val updated = JoiPlayScanner.computeTargetSizes(
                context.applicationContext,
                targets = targets.filter { it.key in toScan },
                recompute = force,
            ) { progress ->
                st.joiPlaySizeScanProgress = progress
            }
            st.joiPlaySizeInfo = updated
            st.apps = scanInstalledLibrary(context)
            val scannedCount = toScan.count { updated[it]?.lastScannedAt ?: 0L > 0L }
            st.snackbarMsg = "Game storage scan complete ($scannedCount games)"
            AppLog.i("GameSize", "Managed size scan complete scanned=$scannedCount totalCache=${updated.size}")
        }.onFailure { t ->
            AppLog.e("GameSize", "Managed size scan failed", t)
            CrashReporter.logCaught(context.applicationContext, "managed_game_size_scan", t)
            st.snackbarMsg = "Game storage scan failed: ${t.message}"
        }
        st.joiPlaySizeScanning = false
        st.joiPlaySizeScanningFolders = emptySet()
        st.joiPlaySizeScanProgress = null
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
        manualOnlyFilter, threadUpdatedAfterInstallFilter, libraryLayoutMode, activeFilters.toList(),
        librarySearchMode, selectedUserTags, userStatusFilter, minPersonalRatingFilter,
        lib.stateFilter, st.hasSavesOnlyFilter, st.hasBackupOnlyFilter, storageFilter,
        installedDateField, installedDateRange) {
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
                searchMode = librarySearchMode,
                userStatus = userStatusFilter,
                minPersonalRating = minPersonalRatingFilter,
                gameState = lib.stateFilter,
                selectedUserTags = selectedUserTags.toList(),
                hasSavesOnly = st.hasSavesOnlyFilter,
                hasBackupOnly = st.hasBackupOnlyFilter,
                storageFilter = storageFilter,
                dateField = installedDateField,
                dateRange = installedDateRange,
            )
        )
    }

    val visibleApps: List<InstalledApp> = remember(st.apps, hidden, showHidden) {
        if (showHidden) st.apps.filter { it.packageName in hidden }
        else st.apps.filter { it.packageName !in hidden }
    }

    // JoiPlay games that currently have an AGM upgrade backup (.bak-* folder). Used for the
    // card badge and the "Has backup" filter. findBackup is cheap (one directory listing).
    LaunchedEffect(st.apps) {
        val jp = st.apps.filter {
            it.source == AppSource.Managed || it.source == AppSource.JoiPlay
        }
        st.joiPlayBackupPkgs = if (jp.isEmpty()) emptySet()
        else jp.mapNotNull { app ->
            if (runCatching { JoiPlayBackupManager.findBackup(app) }.getOrNull() != null) app.packageName else null
        }.toSet()
    }

    val rows: List<AppRow> = remember(visibleApps, mappings, sortKey, sortDesc,
        activeFilters.toList(), appliedNameFilter, librarySearchMode, sourceFilter, manualOnlyFilter,
        userStatusFilter, minPersonalRatingFilter, selectedUserTags, storageFilter,
        installedDateField, installedDateRange, navigationPackageName,
        st.apps, st.hasSavesOnlyFilter,
        st.hasBackupOnlyFilter, st.joiPlayBackupPkgs,
        threadUpdatedAfterInstallFilter, st.userTags,
        lib.stateFilter, lib.gameStates,
        st.renPySaveAssociations, st.rpgmSaveAssociations, st.catalogById, catalogLabels, st.joiPlaySizeInfo) {
        val selectedSource = sourceFilter
        val bySource = if (selectedSource == null) {
            visibleApps
        } else {
            visibleApps.filter { app -> matchesEngineFilter(app, selectedSource) }
        }

        // Parse "tag:xxx" and free-text from the search box.
        val parsed = parseSearchQuery(appliedNameFilter)
        val byQuery = bySource.filter { app ->
            val mapping = mappings[app.packageName]
            val game = mappedCatalogGame(mapping, st.catalogById)
            val matchesText = matchesLibrarySearchText(
                app,
                mapping,
                game?.title,
                parsed.freeText,
                librarySearchMode,
            )
            if (!matchesText) return@filter false
            // `-word` free-text exclusion: drop games whose searchable text contains an excluded word.
            if (parsed.excludedText.any {
                    matchesLibrarySearchText(app, mapping, game?.title, it, librarySearchMode)
                }
            ) {
                return@filter false
            }

            val catalogTagNames = if (game != null && catalogLabels != null) {
                val sl = catalogLabels.forSource(game.source)
                game.tags.mapNotNull { sl?.tags?.get(it.toString())?.lowercase() } +
                    game.prefixes.mapNotNull { sl?.prefixes?.get(it.toString())?.lowercase() }
            } else emptyList()
            // Catalog tags + the user's own tags participate in tag:/-tag: filtering.
            val allTagNames = catalogTagNames + st.userTags[app.packageName].orEmpty()
            if (parsed.excludedTags.any { tq -> allTagNames.any { it.contains(tq) } }) return@filter false
            if (parsed.tags.isNotEmpty() && !parsed.tags.all { tq -> allTagNames.any { it.contains(tq) } }) {
                return@filter false
            }
            if (selectedUserTags.isNotEmpty() &&
                !st.userTags[app.packageName].orEmpty().containsAll(selectedUserTags)
            ) {
                return@filter false
            }
            true
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
            else filteredByManual.filter { threadUpdatedAfterInstall(it, st.catalogById) }
        val filteredBySaves = if (!st.hasSavesOnlyFilter) filteredByThreadUpdated
            else filteredByThreadUpdated.filter {
                st.renPySaveAssociations[it.installed.packageName].orEmpty().isNotEmpty() ||
                    st.rpgmSaveAssociations[it.installed.packageName].orEmpty().isNotEmpty()
            }
        val filteredByBackup = if (!st.hasBackupOnlyFilter) filteredBySaves
            else filteredBySaves.filter { it.installed.packageName in st.joiPlayBackupPkgs }
        val filteredByUserStatus = userStatusFilter?.let { wanted ->
            filteredByBackup.filter { it.mapping?.userStatus == wanted }
        } ?: filteredByBackup
        val filteredByRating = if (minPersonalRatingFilter <= 0) filteredByUserStatus
            else filteredByUserStatus.filter { (it.mapping?.personalRating ?: 0) >= minPersonalRatingFilter }
        val filteredByStorage = if (storageFilter == StorageFilter.Any) filteredByRating
            else filteredByRating.filter { row ->
                effectiveInstalledSize(
                    row.installed,
                    joiPlaySizeKey(row.installed)?.let { st.joiPlaySizeInfo[it] },
                ) >= storageFilter.minBytes
            }
        val dateCutoff = installedDateRange.cutoffEpochMs(System.currentTimeMillis())
        val filteredByDate = if (dateCutoff == null) filteredByStorage
            else filteredByStorage.filter {
                installedDateValue(it.installed, it.mapping, installedDateField) >= dateCutoff
            }
        val filtered = if (lib.stateFilter == null) filteredByDate
            else filteredByDate.filter { (lib.gameStates[it.installed.packageName] ?: GameState.None) == lib.stateFilter }
        val sorted = when (sortKey) {
            SortKey.Name -> filtered.sortedBy { row ->
                libraryNameSortKey(
                    row.installed,
                    row.mapping,
                    mappedCatalogGame(row.mapping, st.catalogById)?.title,
                )
            }
            SortKey.Installed -> filtered.sortedBy { effectiveInstalledDate(it.installed, it.mapping) }
            SortKey.AppUpdated -> filtered.sortedBy { appUpdatedAt(it.installed) }
            SortKey.LastUsed -> filtered.sortedBy { it.installed.lastUsedTime }
            SortKey.ThreadUpdated -> filtered.sortedBy { row ->
                mappedCatalogGame(row.mapping, st.catalogById)?.ts ?: 0L
            }
            SortKey.Size -> filtered.sortedBy { row ->
                effectiveInstalledSize(row.installed, joiPlaySizeKey(row.installed)?.let { st.joiPlaySizeInfo[it] })
            }
            SortKey.AppSize -> filtered.sortedBy { it.installed.apkSize }
            SortKey.DataSize -> filtered.sortedBy { it.installed.dataSize }
            SortKey.CacheSize -> filtered.sortedBy { it.installed.cacheSize }
            SortKey.Status -> filtered.sortedWith(
                compareBy<AppRow> { statusOrder(it.status) }.thenBy {
                    libraryNameSortKey(
                        it.installed,
                        it.mapping,
                        mappedCatalogGame(it.mapping, st.catalogById)?.title,
                    )
                }
            )
        }
        val ordered = if (sortDesc) sorted.reversed() else sorted
        val navigationApp = navigationPackageName?.let { packageName ->
            st.apps.firstOrNull { it.packageName == packageName }
        }
        val navigationRow = navigationApp?.let {
            val mapping = mappings[navigationApp.packageName]
            val status = when {
                mapping == null || mapping.f95Url.isNullOrBlank() -> UpdateStatus.NotMapped
                mapping.lastSeenVersion == null -> UpdateStatus.Unknown
                mapping.acknowledgedVersion != null &&
                    mapping.acknowledgedVersion == mapping.lastSeenVersion -> UpdateStatus.UpToDate
                VersionCompare.matchesInstalled(
                    mapping.lastSeenVersion,
                    effectiveInstalledVersion(navigationApp, mapping),
                ) -> UpdateStatus.UpToDate
                else -> UpdateStatus.UpdateAvailable
            }
            AppRow(navigationApp, mapping, status)
        }
        revealInstalledNavigationDestination(ordered, navigationRow)
    }

    LaunchedEffect(navigationRequestId, navigationPackageName, st.apps, st.appsInitialLoading) {
        val packageName = navigationPackageName ?: return@LaunchedEffect
        if (navigationRequestId == 0L) return@LaunchedEffect
        val destination = rows.firstOrNull { it.installed.packageName == packageName }
        if (destination != null) {
            if (packageName !in expanded) expanded.add(packageName)
            st.dialogApp = destination
            onNavigationConsumed()
        } else if (!st.appsInitialLoading) {
            onNavigationConsumed()
        }
    }

    val joiPlayApps = remember(st.apps) {
        st.apps.filter { it.source == AppSource.Managed || it.source == AppSource.JoiPlay }
    }
    val joiPlayTotalSize = remember(joiPlayApps, st.joiPlaySizeInfo) {
        effectiveInstalledTotalSize(joiPlayApps, st.joiPlaySizeInfo)
    }
    val joiPlayLastSizeScan = remember(joiPlayApps, st.joiPlaySizeInfo) {
        joiPlayApps.mapNotNull { app -> joiPlaySizeKey(app)?.let { st.joiPlaySizeInfo[it]?.lastScannedAt } }
            .filter { it > 0L }
            .maxOrNull() ?: 0L
    }
    val joiPlayStorageSummary = remember(joiPlayApps, joiPlayTotalSize, st.joiPlaySizeScanning, joiPlayLastSizeScan) {
        if (joiPlayApps.isEmpty()) null
        else buildString {
            append("Managed ${joiPlayApps.size}, ${fmtSize(joiPlayTotalSize)}")
            if (st.joiPlaySizeScanning) append(" • scanning")
            else if (joiPlayLastSizeScan > 0L) append(" • scanned ${fmtDate(joiPlayLastSizeScan)}")
        }
    }
    val renPySaveSummary = remember(st.renPySaveLocations, st.renPySaveAssociations, st.renPySaveScanning, st.renPySaveLastScannedAt) {
        val folderCount = st.renPySaveLocations.size
        if (st.renPySaveScanning) "Ren'Py saves scanning"
        else if (folderCount > 0) buildString {
            append("Ren'Py saves $folderCount / ${st.renPySaveAssociations.size} games")
            if (st.renPySaveLastScannedAt > 0L) append(" • scanned ${fmtDate(st.renPySaveLastScannedAt)}")
        }
        else null
    }
    val rpgmSaveSummary = remember(st.rpgmSaveLocations, st.rpgmSaveAssociations, st.rpgmSaveScanning, st.rpgmSaveLastScannedAt) {
        val folderCount = st.rpgmSaveLocations.size
        if (st.rpgmSaveScanning) "RPGM saves scanning"
        else if (folderCount > 0) buildString {
            append("RPGM saves $folderCount / ${st.rpgmSaveAssociations.size} games")
            if (st.rpgmSaveLastScannedAt > 0L) append(" • scanned ${fmtDate(st.rpgmSaveLastScannedAt)}")
        }
        else null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(st.snackbarMsg) {
        st.snackbarMsg?.let { snackbarHostState.showSnackbar(it); st.snackbarMsg = null }
    }

    var joiPlayVersionDialog by remember {
        mutableStateOf<Triple<AppRow, List<VersionCandidate>, (() -> Unit)>?>(null)
    }

    fun closeScreenshotOverlays() {
        st.sortMenuOpen = false
        st.menuOpen = false
        st.subCatalogOpen = false
        st.subInstallGamesOpen = false
        st.subSaveToolsOpen = false
        st.subBackupOpen = false
        st.subMaintenanceOpen = false
        st.subViewOpen = false
        st.subLogsOpen = false
        st.aboutOpen = false
        st.supportDialogOpen = false
        st.installWarningOpen = false
        st.joiplaySettingsOpen = false
        st.installPickerOpen = false
        st.backupScopedPickerOpen = false
        st.importBackupPickerOpen = false
        st.importBackupAccessDisclosureOpen = false
        st.exportBackupPickerOpen = false
        st.autoBackupDialogOpen = false
        st.apkInstallConfirm = null
        extractConfirm = null
        st.joiPlayDeleteConfirm = null
        st.screenshotPanel = null
    }

    fun startScreenshotWalkthrough() {
        if (st.screenshotWalkthroughRunning) return
        st.screenshotWalkthroughRunning = true
        st.snackbarMsg = null
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

                st.screenshotPanel = ScreenshotPanel.SortMenu
                snap("02-sort-menu")

                st.screenshotPanel = ScreenshotPanel.MainMenu
                snap("03-main-menu")

                st.screenshotPanel = ScreenshotPanel.CatalogMenu
                snap("04-catalog-submenu")

                st.screenshotPanel = ScreenshotPanel.JoiPlayMenu
                snap("05-joiplay-submenu")

                st.screenshotPanel = ScreenshotPanel.BackupMenu
                snap("06-backup-submenu")

                st.screenshotPanel = ScreenshotPanel.DiagnosticsMenu
                snap("07-help-diagnostics-submenu")

                st.screenshotPanel = ScreenshotPanel.About
                snap("08-about-dialog")

                if (
                    appConfig.donationUrl.isNotBlank() ||
                    appConfig.stripeDonationUrl.isNotBlank() ||
                    appConfig.contactEmail.isNotBlank()
                ) {
                    st.screenshotPanel = ScreenshotPanel.Support
                    snap("09-support-dialog")
                }

                st.screenshotPanel = ScreenshotPanel.JoiPlaySettings
                snap("10-joiplay-settings")

                st.screenshotPanel = ScreenshotPanel.JoiPlayWarning
                snap("11-joiplay-install-warning")

                st.screenshotPanel = ScreenshotPanel.JoiPlayPicker
                snap("12-joiplay-install-picker")

                st.screenshotPanel = ScreenshotPanel.ApkPicker
                snap("13-apk-install-picker")

                st.screenshotPanel = ScreenshotPanel.ApkConfirm
                snap("14-apk-install-confirm")

                st.screenshotPanel = ScreenshotPanel.ExtractConfirm
                snap("15-archive-extract-confirm")

                st.screenshotPanel = ScreenshotPanel.JoiPlayDelete
                snap("16-joiplay-delete-confirm")

                st.screenshotPanel = ScreenshotPanel.CatalogMain
                snap("17-catalog-main")

                st.screenshotPanel = ScreenshotPanel.CatalogTagFilter
                snap("18-catalog-tag-filter")

                st.screenshotPanel = null

                val count = ScreenshotDiagnostics.files(appContext).size
                st.snackbarMsg = "Saved $count screenshots to Documents/AdultGameManager/screenshots"
            }.onFailure {
                AppLog.e("Screenshots", "Walkthrough capture failed", it)
                st.snackbarMsg = "Screenshot capture failed: ${it.message}"
            }
            closeScreenshotOverlays()
            st.screenshotWalkthroughRunning = false
        }
    }

    fun startLaunchScreenshotSet() {
        if (st.launchScreenshotRunning) return
        st.launchScreenshotRunning = true
        st.snackbarMsg = null
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
                st.screenshotPanel = ScreenshotPanel.LaunchLibrary
                snap("agm-01-library")

                onScreenshotTabChange(Tab.Catalog)
                onScreenshotCatalogQuery(null)
                st.screenshotPanel = ScreenshotPanel.LaunchCatalogFilters
                snap("agm-02-catalog-source-platform-filters")

                st.screenshotPanel = ScreenshotPanel.LaunchAdvancedFilters
                snap("agm-03-catalog-advanced-filters")

                st.screenshotPanel = ScreenshotPanel.LaunchGameDetails
                snap("agm-04-game-details")

                onScreenshotTabChange(Tab.Installed)
                st.screenshotPanel = ScreenshotPanel.LaunchReviewUnmapped
                snap("agm-05-review-unmapped")

                st.screenshotPanel = ScreenshotPanel.LaunchF95Import
                snap("agm-06-f95-updater-import")

                st.screenshotPanel = null
                val count = ScreenshotDiagnostics.files(appContext).size
                st.snackbarMsg = "Saved $count launch screenshots to Documents/AdultGameManager/screenshots"
            }.onFailure {
                AppLog.e("Screenshots", "Launch screenshot capture failed", it)
                st.snackbarMsg = "Launch screenshots failed: ${it.message}"
            }
            closeScreenshotOverlays()
            st.launchScreenshotRunning = false
        }
    }

    // Detect JoiPlay package presence (used by first-run hint and other heuristics).
    LaunchedEffect(Unit) {
        st.joiPlayInstalled = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("cyou.joiplay.joiplay", 0)
            true
        }.getOrDefault(false)
        st.kirikiroidInstalled = KirikiroidLauncher.isInstalled(context)
    }

    // First-run hint: shown the first time the app launches.
    LaunchedEffect(Unit) {
        val firstRunMarker = java.io.File(context.applicationContext.filesDir, ".firstrun_done")
        if (!firstRunMarker.exists()) {
            st.isFirstRunFlow = true
            if (F95MigrationImport.isF95UpdaterInstalled(context.applicationContext)) {
                st.f95MigrationPromptOpen = true
            } else {
                st.firstRunHintOpen = true
            }
            runCatching { firstRunMarker.createNewFile() }
        }
    }

    // After the user dismisses the welcome flow (and any subsequent JoiPlay import),
    // run the full catalog refresh once. Watches both `apps` and the pending flag so
    // the JoiPlay-imported games are included if applicable.
    // Builds the row set from `apps` directly (bypasses any user filters / search box).
    LaunchedEffect(st.firstRunRefreshPending, st.apps.size) {
        if (st.firstRunRefreshPending && st.apps.isNotEmpty()) {
            // Clear flags first, then dispatch the actual work to `scope` so the long-running
            // refresh isn't tied to this LaunchedEffect's key — otherwise setting
            // firstRunRefreshPending=false re-keys this effect and cancels its own coroutine
            // partway through (observed as LeftCompositionCancellationException in refreshFromCatalog).
            st.firstRunRefreshPending = false
            st.isFirstRunFlow = false
            val snapshot = st.apps.toList()
            val mappingsSnapshot = mappings.toMap()
            // Show the progress dialog immediately so there's no UI gap between the
            // JoiPlay-import dialog closing and refreshFromCatalog actually starting
            // (which can include a catalog download on first install).
            st.refreshCancelled = false
            st.refreshProgress = RefreshProgress(0, snapshot.size, 0, System.currentTimeMillis())
            scope.launch {
                val catalogReady = try {
                    catalog.prepareCatalogDatabase().totalCount > 0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    AppLog.e("FirstRun", "Catalog preparation failed before first-run match", error)
                    false
                }
                if (!catalogReady) {
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
                val matched = refreshFromCatalog(allRows)?.first ?: 0
                st.snackbarMsg = "First-run match: $matched matched"
            }
        }
    }

    // Auto-sync on startup: catalog refresh, labels refresh, app-update check.
    // Runs in background so the UI doesn't block. The first-run "refresh all apps from catalog"
    // is deferred until AFTER the welcome dialog flow completes (see firstRunRefreshPending).
    LaunchedEffect(Unit) {
        st.autoSyncStatus = "Syncing catalog…"
        runCatching {
            when (val r = catalog.sync()) {
                is CatalogSyncResult.Updated -> {
                    AppLog.i("Startup", "Catalog auto-sync: ${r.gameCount} games (${r.sizeBytes} B)")
                    onLabelsChange(catalog.labels())
                    st.catalogReloadTick = st.catalogReloadTick + 1
                    Unit
                }
                CatalogSyncResult.NotModified -> {
                    AppLog.i("Startup", "Catalog auto-sync: 304 not modified")
                    if (st.catalogById.isNullOrEmpty()) {
                        st.catalogReloadTick = st.catalogReloadTick + 1
                    }
                    Unit
                }
                is CatalogSyncResult.Error -> AppLog.w("Startup", "Catalog auto-sync failed: ${r.message}")
            }
        }.onFailure { AppLog.w("Startup", "Catalog auto-sync threw", it) }

        st.autoSyncStatus = "Checking for app updates…"
        val requestId = updateCheckRequestId
        runCatching {
            val results = appUpdater.checkAll(context)
            val actionable = results.filter { result ->
                when (result) {
                    is UpdateCheckResult.Available -> true
                    is UpdateCheckResult.NotInstalled -> UpdatePromptPrefs.shouldOfferOptionalInstall(
                        context,
                        result.target,
                        result.info.versionCode,
                    )
                    is UpdateCheckResult.UpToDate,
                    is UpdateCheckResult.Error -> false
                }
            }
            if (updateCheckRequestId == requestId) {
                st.updateCheckResults = actionable
            }
            results.forEach { AppLog.i("Startup", "App update check: $it") }
        }.onFailure { AppLog.w("Startup", "App update check threw", it) }
        runCatching {
            val status = JoiPlayUpdateChecker.check(
                context,
                AppConfigStore.current(context).joiPlayDownloadsUrl,
            )
            st.joiPlayUpdateStatus = status
            st.joiPlayPluginReport = JoiPlayPluginChecker.report(context, status.downloads)
        }.onFailure { AppLog.w("Startup", "JoiPlay update check threw", it) }
        st.autoSyncStatus = null
    }

    val topStatusText = st.checkProgress?.let { (cur, total) -> "Checking $cur / $total" }
        ?: st.autoSyncStatus
        ?: listOfNotNull(
            "${rows.size} of ${visibleApps.size} apps",
            joiPlayStorageSummary,
            renPySaveSummary,
            rpgmSaveSummary,
        ).joinToString(" • ")
    val installedAdvancedFilterState = InstalledAdvancedFilterState(
        searchMode = librarySearchMode,
        activeStatuses = activeFilters.toSet(),
        sourceFilter = sourceFilter,
        manualOnly = manualOnlyFilter,
        threadUpdatedAfterInstallOnly = threadUpdatedAfterInstallFilter,
        userStatus = userStatusFilter,
        minPersonalRating = minPersonalRatingFilter,
        gameState = lib.stateFilter,
        selectedUserTags = selectedUserTags,
        hasSavesOnly = st.hasSavesOnlyFilter,
        hasBackupOnly = st.hasBackupOnlyFilter,
        storageFilter = storageFilter,
        dateField = installedDateField,
        dateRange = installedDateRange,
    )
    val installedFilterSummary = installedAdvancedFilterSummary(installedAdvancedFilterState)
    val availableUserTags = remember(st.userTags) {
        st.userTags.values.flatten().distinct().sorted()
    }
    val hasBmc = appConfig.donationUrl.isNotBlank()
    val hasStripe = appConfig.stripeDonationUrl.isNotBlank()
    val hasContact = appConfig.contactEmail.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    InstalledTopBarTitle(
                        showHidden = showHidden,
                        rowCount = rows.size,
                        compactWidth = compactWidth,
                        compactHeight = compactHeight,
                        onShowStatus = { st.topStatusOpen = true },
                    )
                },
                actions = {
                    // Donate (☕) — shown if any donation URL is configured.
                    if (!compactWidth && !compactHeight && (hasBmc || hasStripe || hasContact)) {
                        IconButton(onClick = {
                            if (hasContact || (hasBmc && hasStripe)) {
                                st.supportDialogOpen = true
                            } else {
                                val url = if (hasStripe) appConfig.stripeDonationUrl else appConfig.donationUrl
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { st.snackbarMsg = "Could not open browser: ${it.message}" }
                            }
                        }) {
                            // U+2615 hot beverage; falls back fine on every Android emoji font.
                            Text("\u2615", fontSize = 20.sp)
                        }
                    }
                    IconButton(onClick = {
                        if (st.checking) return@IconButton
                        val mapped = rows.filter { !it.mapping?.f95Url.isNullOrBlank() }
                        if (mapped.isEmpty()) {
                            st.snackbarMsg = "No mapped apps to check. Add a URL or import mappings first."
                            return@IconButton
                        }
                        st.checking = true
                        st.checkProgress = 0 to mapped.size
                        scope.launch {
                            try {
                                checkAll(mapped, scraper, repo) { done, total ->
                                    st.checkProgress = done to total
                                }
                            } finally {
                                st.checking = false
                                val total = st.checkProgress?.second ?: 0
                                st.checkProgress = null
                                st.snackbarMsg = "Check complete ($total apps)"
                            }
                        }
                    }) {
                        if (st.checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Check now")
                    }
                    InstalledSortMenu(
                        sortMenuOpen = st.sortMenuOpen,
                        onSortMenuOpenChange = { st.sortMenuOpen = it },
                        availableSortKeys = availableSortKeys,
                        sortKey = sortKey,
                        sortDesc = sortDesc,
                        onSelectSort = { k ->
                            if (k == sortKey) sortDesc = !sortDesc
                            else { sortKey = k; sortDesc = false }
                            st.sortMenuOpen = false
                        },
                    )
                    Box {
                        IconButton(onClick = { st.menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = st.menuOpen, onDismissRequest = {
                            st.menuOpen = false
                            st.subCatalogOpen = false; st.subInstallGamesOpen = false
                            st.subSaveToolsOpen = false; st.subBackupOpen = false
                            st.subMaintenanceOpen = false; st.subViewOpen = false; st.subLogsOpen = false
                        }) {
                            // --- TOP LEVEL
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { st.menuOpen = false; st.settingsDialogOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Check for app updates") },
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, null) },
                                onClick = {
                                    st.menuOpen = false
                                    updateCheckRequestId += 1
                                    val requestId = updateCheckRequestId
                                    scope.launch {
                                        st.snackbarMsg = "Checking for app updates…"
                                        val results = appUpdater.checkAll(context)
                                        val status = JoiPlayUpdateChecker.check(
                                            context,
                                            AppConfigStore.current(context).joiPlayDownloadsUrl,
                                        )
                                        st.joiPlayUpdateStatus = status
                                        st.joiPlayPluginReport = JoiPlayPluginChecker.report(context, status.downloads)
                                        if (updateCheckRequestId == requestId) {
                                            st.updateCheckResults = results
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Downloads\u2026") },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { st.menuOpen = false; st.downloadsOpen = true }
                            )

                            HorizontalDivider()
                            InstalledViewSubmenu(
                                subViewOpen = st.subViewOpen,
                                onToggle = { st.subViewOpen = !st.subViewOpen },
                                layoutMode = libraryLayoutMode,
                                showHidden = showHidden,
                                hiddenCount = hidden.size,
                                onCollections = {
                                    st.menuOpen = false; st.subViewOpen = false
                                    lib.collectionsOpen = true
                                },
                                onToggleViewType = {
                                    libraryLayoutMode = libraryLayoutMode.next()
                                    st.menuOpen = false; st.subViewOpen = false
                                },
                                onToggleShowHidden = {
                                    st.menuOpen = false; st.subViewOpen = false
                                    showHidden = !showHidden
                                },
                            )

                            // --- SUBMENU: Save tools
                            InstalledSaveToolsSubmenu(
                                subSaveToolsOpen = st.subSaveToolsOpen,
                                onToggle = { st.subSaveToolsOpen = !st.subSaveToolsOpen },
                                saveScanning = st.renPySaveScanning || st.rpgmSaveScanning,
                                hasAnyLocations = st.renPySaveLocations.isNotEmpty() || st.rpgmSaveLocations.isNotEmpty(),
                                onScanSaves = {
                                    st.menuOpen = false; st.subSaveToolsOpen = false
                                    requestRenPySaveScan()
                                    requestRpgmSaveScan()
                                },
                                onShowLocations = {
                                    st.menuOpen = false; st.subSaveToolsOpen = false
                                    st.saveLocationsOpen = true
                                },
                                onBrowseBackups = {
                                    st.menuOpen = false; st.subSaveToolsOpen = false
                                    st.saveBackupBrowserOpen = true
                                },
                            )

                            // --- SUBMENU: Catalog
                            InstalledCatalogSubmenu(
                                subCatalogOpen = st.subCatalogOpen,
                                onToggle = { st.subCatalogOpen = !st.subCatalogOpen },
                                onRefreshFromCatalog = {
                                    st.menuOpen = false; st.subCatalogOpen = false
                                    st.catalogRefreshDialogOpen = true
                                },
                                onReviewUnmapped = {
                                    st.menuOpen = false; st.subCatalogOpen = false
                                    st.unmappedReviewLoading = true
                                    scope.launch {
                                        try {
                                            val items = buildCurrentUnmappedReviewItems()
                                            val already = items.mapNotNull { item ->
                                                previouslyMappedCandidate(item.candidates)?.let { AlreadyMatchedCatalogMatch(item, it) }
                                            }
                                            st.alreadyMatchedReviewMatches = already
                                            st.unmappedReviewMatches = items.filterNot { item ->
                                                already.any { it.item.row.installed.packageName == item.row.installed.packageName }
                                            }
                                            st.unmappedReviewOpen = true
                                        } finally {
                                            st.unmappedReviewLoading = false
                                        }
                                    }
                                },
                                onAutoHideNonGames = {
                                    st.menuOpen = false; st.subCatalogOpen = false
                                    scope.launch {
                                        var hidden = 0
                                        // Scan all apps, not just visible rows, so the filter
                                        // doesn't accidentally exclude candidates.
                                        for (app in st.apps) {
                                            val mapping = mappings[app.packageName]
                                            if (app.source == AppSource.Android &&
                                                mapping?.threadId == null &&
                                                NonGamesDetector.isLikelyNonGame(context, app.packageName)
                                            ) {
                                                repo.hide(app.packageName); hidden++
                                            }
                                        }
                                        st.snackbarMsg = "Auto-hid $hidden likely non-games"
                                    }
                                },
                            )

                            // --- SUBMENU: Install / Games
                            InstalledInstallGamesSubmenu(
                                subInstallGamesOpen = st.subInstallGamesOpen,
                                onToggle = { st.subInstallGamesOpen = !st.subInstallGamesOpen },
                                onAddInstallGame = {
                                    st.menuOpen = false; st.subInstallGamesOpen = false
                                    if (refuseInstallEntry(InstallOperationKind.Extract)) {
                                        // Reported; one install at a time.
                                    } else if (!hasAllFilesAccess()) {
                                        st.permissionRationale = PermissionRationale.AllFilesInstallGame
                                    } else {
                                        scope.launch {
                                            val dismissed = JoiPlaySettingsStore.installWarningDismissed(context.applicationContext)
                                            if (dismissed) {
                                                st.installPickerOpen = true
                                            } else st.installWarningOpen = true
                                        }
                                    }
                                },
                                onBulkInstallGames = {
                                    st.menuOpen = false; st.subInstallGamesOpen = false
                                    if (refuseInstallEntry(InstallOperationKind.Bulk)) {
                                        // Reported; one bulk run at a time.
                                    } else if (!hasAllFilesAccess()) {
                                        st.permissionRationale = PermissionRationale.AllFilesInstallGame
                                    } else {
                                        scope.launch {
                                            val dismissed = JoiPlaySettingsStore.installWarningDismissed(context.applicationContext)
                                            if (dismissed) {
                                                st.bulkInstallPickerOpen = true
                                            } else {
                                                st.installWarningOpensBulk = true
                                                st.installWarningOpen = true
                                            }
                                        }
                                    }
                                },
                                onInstallPatch = {
                                    st.menuOpen = false; st.subInstallGamesOpen = false
                                    if (refuseInstallEntry(InstallOperationKind.Patch)) {
                                        // Reported; one install at a time.
                                    } else if (!hasAllFilesAccess()) {
                                        st.permissionRationale = PermissionRationale.AllFilesInstallGame
                                    } else {
                                        st.patchPickerOpen = true
                                    }
                                },
                                onManageInstalledPatches = {
                                    st.menuOpen = false; st.subInstallGamesOpen = false
                                    // Rolling a patch back rewrites the very game files another
                                    // install may be writing, so the dialog is gated too.
                                    if (!refuseInstallEntry(InstallOperationKind.Patch)) {
                                        patchFlow.refreshRollbackPoints()
                                        st.patchRollbackDialogOpen = true
                                    }
                                },
                                installedPatchCount = patchFlow.usableRollbackPoints.size,
                            )

                            // --- SUBMENU: Backup
                            InstalledBackupSubmenu(
                                subBackupOpen = st.subBackupOpen,
                                onToggle = { st.subBackupOpen = !st.subBackupOpen },
                                onExportBackup = {
                                    st.menuOpen = false; st.subBackupOpen = false
                                    st.exportBackupPickerOpen = true
                                },
                                onImportBackup = {
                                    st.menuOpen = false; st.subBackupOpen = false
                                    val existingRoot = st.importBackupScopedRootUri ?: st.backupScopedRootUri
                                    if (existingRoot != null) {
                                        st.importBackupScopedRootUri = existingRoot
                                        st.importBackupPickerOpen = true
                                    } else {
                                        st.importBackupAccessDisclosureOpen = true
                                    }
                                },
                                onImportJoiPlayBackup = {
                                    st.menuOpen = false; st.subBackupOpen = false
                                    askForJoiPlayBackupFolderAccess(firstRun = false)
                                },
                                onRestoreAutoBackup = {
                                    st.menuOpen = false; st.subBackupOpen = false
                                    scope.launch {
                                        st.autoBackupList = AutoBackupManager.list(context.applicationContext)
                                        st.autoBackupDialogOpen = true
                                    }
                                },
                            )

                            // --- SUBMENU: Maintenance
                            InstalledMaintenanceSubmenu(
                                subMaintenanceOpen = st.subMaintenanceOpen,
                                onToggle = { st.subMaintenanceOpen = !st.subMaintenanceOpen },
                                gameSizeScanning = st.joiPlaySizeScanning,
                                cleanupScanning = st.cleanupScanning,
                                onCleanupReview = {
                                    st.menuOpen = false; st.subMaintenanceOpen = false
                                    if (hasAllFilesAccess()) {
                                        st.cleanupRootPickerOpen = true
                                    } else {
                                        st.permissionRationale = PermissionRationale.AllFilesCleanupReview
                                    }
                                },
                                onFindDuplicates = {
                                    st.menuOpen = false; st.subMaintenanceOpen = false
                                    lib.duplicatesOpen = true
                                },
                                onStorageDashboard = {
                                    st.menuOpen = false; st.subMaintenanceOpen = false
                                    scope.launch {
                                        lib.lastPlayed = LastPlayedStore.load(context.applicationContext)
                                        lib.storageDashboardOpen = true
                                    }
                                },
                                onRefreshStorageSizes = {
                                    st.menuOpen = false; st.subMaintenanceOpen = false
                                    requestJoiPlaySizeScan(force = true)
                                },
                            )

                            HorizontalDivider()

                            if (hasBmc || hasStripe || hasContact) {
                                DropdownMenuItem(
                                    text = { Text("Support / donate") },
                                    leadingIcon = { Text("\u2615", fontSize = 20.sp) },
                                    onClick = {
                                        st.menuOpen = false
                                        if (hasContact || (hasBmc && hasStripe)) {
                                            st.supportDialogOpen = true
                                        } else {
                                            val url = if (hasStripe) appConfig.stripeDonationUrl else appConfig.donationUrl
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }.onFailure { st.snackbarMsg = "Could not open browser: ${it.message}" }
                                        }
                                    },
                                )
                            }

                            // --- SUBMENU: Help (browser docs + about + diagnostics + support)
                            InstalledHelpSubmenu(
                                subLogsOpen = st.subLogsOpen,
                                onToggle = { st.subLogsOpen = !st.subLogsOpen },
                                hasBmc = hasBmc,
                                hasStripe = hasStripe,
                                hasContact = hasContact,
                                hasCrashUpload = appConfig.hasCrashUpload,
                                diagnosticsEnabled = appConfig.diagnosticsEnabled,
                                matchResearchInProgress = st.matchResearchProgress != null,
                                launchScreenshotRunning = st.launchScreenshotRunning,
                                screenshotWalkthroughRunning = st.screenshotWalkthroughRunning,
                                onDocumentation = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW,
                                                Uri.parse(AppConfig.DEFAULT_HELP_URL))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure { st.snackbarMsg = "Could not open browser: ${it.message}" }
                                },
                                onAbout = { st.menuOpen = false; st.subLogsOpen = false; st.aboutOpen = true },
                                onCopyDiagnostics = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    st.diagnosticsSummaryOpen = true
                                },
                                onSaveLocalDiagnostics = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    scope.launch {
                                        AppLog.i("User", "Save local diagnostics requested")
                                        val logResult = AppLog.saveLocally(context.applicationContext)
                                        val (crashCount, crashResult) = CrashReporter.saveAllLocally(context.applicationContext)
                                        st.snackbarMsg = if (crashCount > 0)
                                            "Saved logs and $crashCount reports to $crashResult"
                                        else "Saved logs to $logResult"
                                    }
                                },
                                onSavedPasswords = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    st.savedPasswordsOpen = true
                                },
                                onUploadCrashLogs = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    scope.launch {
                                        val (ok, fail) = CrashReporter.flush(context.applicationContext)
                                        st.snackbarMsg = if (ok == 0 && fail == 0) "No crash logs to send"
                                        else "Crash logs: $ok sent, $fail still queued"
                                    }
                                },
                                onUploadAppLogs = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    scope.launch {
                                        AppLog.i("User", "Manual log upload requested")
                                        val (ok, fail) = AppLog.upload(context.applicationContext)
                                        st.snackbarMsg = "Logs: $ok sent, $fail failed"
                                    }
                                },
                                onSupport = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    if (hasContact || (hasBmc && hasStripe)) {
                                        st.supportDialogOpen = true
                                    } else {
                                        val url = if (hasStripe) appConfig.stripeDonationUrl else appConfig.donationUrl
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }.onFailure { st.snackbarMsg = "Could not open browser: ${it.message}" }
                                    }
                                },
                                onUploadMatchResearch = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    scope.launch {
                                        AppLog.i("MatchResearch", "Snapshot upload requested")
                                        st.matchResearchProgress = MatchResearchProgress("Starting")
                                        val result = runCatching {
                                            MatchResearchCollector.collectSaveUpload(
                                                context = context.applicationContext,
                                                repo = repo,
                                                catalog = catalog,
                                            ) { progress ->
                                                st.matchResearchProgress = progress
                                            }
                                        }
                                        st.matchResearchProgress = null
                                        result.onSuccess { upload ->
                                            st.snackbarMsg = if (upload.uploaded) {
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
                                            st.snackbarMsg = "Match snapshot failed: ${it.message}"
                                        }
                                    }
                                },
                                onCaptureLaunchScreenshots = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    startLaunchScreenshotSet()
                                },
                                onCaptureWalkthroughScreenshots = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    startScreenshotWalkthrough()
                                },
                                onSaveLogsToDocuments = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    scope.launch {
                                        AppLog.i("User", "Save logs locally requested")
                                        val logResult = AppLog.saveLocally(context.applicationContext)
                                        val (crashCount, crashResult) = CrashReporter.saveAllLocally(context.applicationContext)
                                        st.snackbarMsg = if (crashCount > 0)
                                            "Saved logs and $crashCount crashes to $crashResult"
                                        else "Saved logs to $logResult"
                                    }
                                },
                                onToggleVerbose = {
                                    st.menuOpen = false; st.subLogsOpen = false
                                    AppLog.verbose = !AppLog.verbose
                                    AppLog.i("User", "Verbose logging set to ${AppLog.verbose}")
                                    st.snackbarMsg = if (AppLog.verbose) "Verbose logging ON" else "Verbose logging OFF"
                                },
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(st.librarySearchBounds) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        if (shouldClearLibrarySearchFocus(st.librarySearchBounds, down.position)) {
                            focusManager.clearFocus(force = true)
                        }
                    }
                },
        ) {
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
            InstalledSearchHeader(
                nameFilter = nameFilter,
                onNameFilterChange = { nameFilter = it },
                onImeDone = { focusManager.clearFocus(force = true) },
                onSearchBoundsChange = { st.librarySearchBounds = it },
                tagSuggestions = tagSuggestions,
                onTagSuggestionClick = onTag@{ suggestion ->
                    val match = TAG_TOKEN_AT_END_APPS.find(nameFilter) ?: return@onTag
                    val before = nameFilter.substring(0, match.range.first)
                    nameFilter = before + "tag:" + suggestion + " "
                },
            )
            AdvancedFilterBar(
                summary = installedFilterSummary,
                regexError = libraryRegexError,
                onOpen = { advancedFiltersOpen = true },
            )
            HorizontalDivider()
            InstalledScanStatusBanners(
                apkInstalling = st.apkInstalling,
                gameSizeScanning = st.joiPlaySizeScanning,
                gameSizeScanProgress = st.joiPlaySizeScanProgress,
            )
            st.joiPlayUpdateStatus?.let { jp ->
                JoiPlayUpdateBanner(status = jp, onOpen = { st.joiPlayUpdatesDialogOpen = true })
            }
            InstalledSelectionAndUsageBar(
                selectionMode = selectionMode,
                selectionCount = selection.size,
                rowCount = rows.size,
                showHidden = showHidden,
                hasUsage = hasUsage,
                onCancelSelection = { selection.clear() },
                onToggleSelectAll = {
                    val all = rows.map { it.installed.packageName }
                    if (selection.size == all.size) selection.clear()
                    else { selection.clear(); selection.addAll(all) }
                },
                onHideSelected = {
                    val pkgs = selection.toList()
                    scope.launch {
                        for (p in pkgs) repo.hide(p)
                        st.snackbarMsg = "Hid ${pkgs.size} app${if (pkgs.size == 1) "" else "s"}"
                        selection.clear()
                    }
                },
                onUnhideSelected = {
                    val pkgs = selection.toList()
                    scope.launch {
                        for (p in pkgs) repo.unhide(p)
                        st.snackbarMsg = "Unhid ${pkgs.size} app${if (pkgs.size == 1) "" else "s"}"
                        selection.clear()
                    }
                },
                onTagSelected = {
                    val sel = selection.toSet()
                    val chosen = rows.filter { it.installed.packageName in sel }
                    if (chosen.isNotEmpty()) lib.bulkTagTarget = chosen
                },
                onStatusSelected = {
                    val sel = selection.toSet()
                    val chosen = rows.filter { it.installed.packageName in sel }
                    if (chosen.isNotEmpty()) lib.bulkStatusTarget = chosen
                },
                onDeleteSelected = {
                    val sel = selection.toSet()
                    val chosen = rows.filter { it.installed.packageName in sel }
                    if (chosen.isNotEmpty()) lib.bulkDeleteConfirm = chosen
                },
            )
            // Shared per-row actions (previously duplicated across the grid + list card callsites).
            fun launchWinlatorApp(app: InstalledApp) {
                fun openManagedWinlatorSetup(): Boolean {
                    if (app.source != AppSource.Managed || app.managedGameId == null) return false
                    val pendingCreate = ManagedWinlatorPendingStore.load(context.applicationContext)
                    if (pendingCreate?.managedGameId == app.managedGameId) {
                        st.snackbarMsg = "Winlator setup is still finishing for ${app.label}."
                        scope.launch {
                            val reconciled =
                                reconcilePendingManagedWinlatorCreate(context.applicationContext)
                            if (reconciled != null) {
                                st.apps = scanInstalledLibrary(context)
                                st.pendingWinlatorAutoRecommend = reconciled
                                st.snackbarMsg = "Winlator setup completed for ${app.label}."
                            }
                        }
                        return true
                    }
                    val executablePath = app.winlatorExecutablePath ?: return false
                    val executable = java.io.File(executablePath)
                    if (!executable.isFile) return false
                    st.managedEngineUpdateProgress = ManagedEngineDiscoveryProgress(
                        current = 0,
                        total = 1,
                        gameTitle = app.label,
                        stage = "Creating the Winlator runner",
                    )
                    scope.launch {
                        runCatching {
                            WinlatorClient.requiredSharedContainerCapabilities(
                                context.applicationContext,
                            ).getOrThrow()
                            val winlatorGameId = java.util.UUID.randomUUID().toString()
                            val metadata = JSONObject()
                                .put("source", "agm-managed-launch")
                                .put("sourcePath", executable.absolutePath)
                                .put("titleSource", "agm")
                                .put("importedAt", System.currentTimeMillis())
                                .toString()
                            val operation = PendingWinlatorOperation(
                                kind = WinlatorOperationKind.CreatePortable,
                                gameId = winlatorGameId,
                                title = app.label,
                                managedGameId = app.managedGameId,
                                managedRunnerRollback = ManagedRunnerRollback(
                                    defaultRunner = requireNotNull(app.managedDefaultRunner),
                                    bindings = app.managedRunnerBindings,
                                ),
                                launchAfterCreate = true,
                                detectedLanguage = preferredGameLanguage(app.label, null),
                            )
                            st.pendingWinlatorOperation = operation
                            ManagedWinlatorPendingStore.save(context.applicationContext, operation)
                            winlatorResultLauncher.launch(
                                WinlatorApi.createPortable(
                                    gameId = winlatorGameId,
                                    title = app.label,
                                    gamePath = requireNotNull(executable.parentFile).absolutePath,
                                    executablePath = executable.absolutePath,
                                    metadata = metadata,
                                    containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault,
                                ),
                            )
                        }.onFailure {
                            st.pendingWinlatorOperation = null
                            ManagedWinlatorPendingStore.clear(context.applicationContext)
                            st.snackbarMsg = "Could not set up Winlator: ${it.message}"
                        }
                        st.managedEngineUpdateProgress = null
                    }
                    return true
                }
                when (app.winlatorState) {
                    "installing" -> st.snackbarMsg = "${app.label} is still installing in Winlator."
                    "setup_required" -> {
                        if (!openManagedWinlatorSetup()) st.winlatorConfigureTarget = app
                    }
                    else -> launchWinlatorWithPreflight(app)
                }
            }
            fun launchManaged(app: InstalledApp, runner: ManagedRunnerKind) {
                val binding = app.managedRunnerBindings.firstOrNull {
                    it.kind == runner && it.enabled && it.compatible
                }
                if (binding == null) {
                    st.snackbarMsg = "${runner.displayName()} is not enabled for ${app.label}."
                    return
                }
                when (runner) {
                    ManagedRunnerKind.JoiPlay -> handleJoiPlayLaunch(
                        context,
                        app,
                        onMessage = { st.snackbarMsg = it },
                        onPluginRequired = { st.pluginLaunchRequest = it },
                    )
                    ManagedRunnerKind.Winlator -> launchWinlatorApp(app)
                    ManagedRunnerKind.Kirikiroid -> launchKirikiroid(app)
                }
            }
            fun launchRow(row: AppRow) {
                val pkg = row.installed.packageName
                when (row.installed.source) {
                    AppSource.Managed -> {
                        val runner = row.installed.managedDefaultRunner
                        if (runner == null) st.snackbarMsg = "This game has no default execution engine."
                        else launchManaged(row.installed, runner)
                    }
                    AppSource.JoiPlay -> handleJoiPlayLaunch(
                        context, row.installed,
                        onMessage = { st.snackbarMsg = it },
                        onPluginRequired = { st.pluginLaunchRequest = it },
                    )
                    AppSource.Winlator -> launchWinlatorApp(row.installed)
                    AppSource.Kirikiroid -> launchKirikiroid(row.installed)
                    AppSource.Android -> {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) context.startActivity(launchIntent)
                        else st.snackbarMsg = "No launcher activity for ${row.installed.label}"
                    }
                }
            }
            fun refreshRow(row: AppRow) {
                val pkg = row.installed.packageName
                scope.launch {
                    if (
                        row.installed.source == AppSource.JoiPlay ||
                        row.installed.managedRunnerBindings.any { it.kind == ManagedRunnerKind.JoiPlay }
                    ) {
                        st.joiPlayDetecting = row
                        val candidates = runCatching {
                            JoiPlayVersionDetector.detect(context.applicationContext, row.installed)
                        }.getOrElse {
                            AppLog.w("Detect", "version detect failed", it)
                            emptyList()
                        }
                        st.joiPlayDetecting = null
                        val distinct = candidates.map { it.version }.distinct()
                        suspend fun applyAndCheckSource(chosen: String?) {
                            if (chosen != null) {
                                setManualInstalledVersion(row, chosen)
                                st.apps = scanInstalledLibrary(context)
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
                            st.snackbarMsg = if (chosen != null)
                                "Set version $chosen for ${row.installed.label}"
                            else "Refreshed ${row.installed.label}"
                        }
                        when {
                            candidates.isEmpty() -> {
                                st.snackbarMsg = "No version detected. Use Set installed version to enter it manually."
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
                        st.snackbarMsg = "Refreshed ${row.installed.label}"
                    } else {
                        st.snackbarMsg = "Searching catalog for ${row.installed.label}…"
                        val found = searcher.findF95Thread(row.installed.label)
                        if (found.isNullOrBlank()) {
                            st.snackbarMsg = "Unable to fetch URL for ${row.installed.label}"
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
                            st.snackbarMsg = "Found URL & refreshed ${row.installed.label}"
                        }
                    }
                }
            }
            fun openGameSource(row: AppRow) {
                val target = row.mapping?.f95Url
                    ?: "https://www.google.com/search?q=" + Uri.encode("\"${row.installed.label}\" f95zone")
                openLink(target)
            }
            val forceTreeExpanded = remember(nameFilter) {
                parseSearchQuery(nameFilter).let { parsed ->
                    parsed.freeText.isNotBlank() ||
                        parsed.tags.isNotEmpty() ||
                        parsed.excludedText.isNotEmpty() ||
                        parsed.excludedTags.isNotEmpty()
                }
            }
            val listEntries = remember(
                rows,
                libraryLayoutMode,
                collapsedTreeFolders.toList(),
                forceTreeExpanded,
            ) {
                if (libraryLayoutMode == LibraryLayoutMode.FolderTree) {
                    buildLibraryFolderTree(
                        rows = rows,
                        collapsedFolders = collapsedTreeFolders.toSet(),
                        forceExpanded = forceTreeExpanded,
                    )
                } else {
                    rows.map { LibraryListEntry.Game(it, depth = 0) }
                }
            }
            if (st.appsInitialLoading) {
                InstalledLoadingPlaceholder()
            } else if (libraryLayoutMode == LibraryLayoutMode.Cards && rows.isNotEmpty()) {
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
                        val catalogGame: CatalogGame? = remember(st.catalogById, row.mapping) {
                            mappedCatalogGame(row.mapping, st.catalogById)
                        }
                        val translatedCatalogTitle = rememberCatalogTranslatedTitle(
                            source = catalogGame?.source.orEmpty(),
                            sourceId = catalogGame?.sourceId
                                ?: catalogGame?.thread_id?.takeIf { it > 0 }?.toString(),
                            title = catalogGame?.title.orEmpty(),
                            enabled = catalogTranslationSettings.enabled,
                            targetLanguage = catalogTranslationSettings.targetLanguage,
                        )
                        val catalogImages = remember(catalogGame) { catalogImageUrls(catalogGame) }
                        NiceGameCard(
                            row = row,
                            hasBackup = row.installed.packageName in st.joiPlayBackupPkgs,
                            joiPlaySizeInfo = joiPlaySizeKey(row.installed)?.let { st.joiPlaySizeInfo[it] },
                            isJoiPlaySizeScanning = joiPlaySizeKey(row.installed) in st.joiPlaySizeScanningFolders,
                            renPySaves = st.renPySaveAssociations[row.installed.packageName].orEmpty(),
                            rpgmSaves = st.rpgmSaveAssociations[row.installed.packageName].orEmpty(),
                            expanded = isExpanded,
                            catalogGame = catalogGame,
                            catalogDisplayTitle = translatedCatalogTitle ?: catalogGame?.title,
                            catalogThumbnail = catalogImages.thumbnailUrl,
                            catalogCover = catalogImages.coverUrl,
                            catalogLabels = catalogLabels,
                            selected = pkg in selection || pkg == navigationPackageName,
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
                            onShowCover = { st.fullSizeImageUrl = it },
                            onSnack = { st.snackbarMsg = it },
                            onOpenRenPySaves = { st.renPySaveEditorTarget = row },
                            onAddRenPySaveFolder = { st.renPyAddFolderTarget = row },
                            onOpenRpgmSaves = { st.rpgmSaveViewerTarget = row },
                            onAddRpgmSaveFolder = { st.rpgmAddFolderTarget = row },
                            onRunWinlatorInstaller = { st.winlatorInstallerTarget = row.installed },
                            onLaunch = { launchRow(row) },
                            onLaunchRunner = { launchManaged(row.installed, it) },
                            onRefreshOne = { refreshRow(row) },
                            onOpenSettings = { st.gameSettingsTarget = row },
                            onOpenSource = { openGameSource(row) },
                            onGoToCatalog = row.mapping
                                ?.takeIf { confirmedCatalogNavigationIdentity(it) != null }
                                ?.let { mapping -> { onNavigateToCatalog(mapping) } },
                        )
                    }
                }
            } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 96.dp)
            ) {
                if (rows.isEmpty() && st.apps.isNotEmpty()) {
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
                            body = if (st.joiPlayInstalled) {
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
                items(listEntries, key = { it.key }) { entry ->
                    if (entry is LibraryListEntry.Folder) {
                        LibraryFolderHeader(entry) {
                            val folderKey = entry.key.removePrefix("folder:")
                            if (folderKey in collapsedTreeFolders) {
                                collapsedTreeFolders.remove(folderKey)
                            } else {
                                collapsedTreeFolders.add(folderKey)
                            }
                        }
                        return@items
                    }
                    val gameEntry = entry as LibraryListEntry.Game
                    val row = gameEntry.row
                    val pkg = row.installed.packageName
                    val isExpanded = pkg in expanded
                    // Resolve catalog match synchronously from the pre-loaded byId map (no per-row suspend).
                    val catalogGame: CatalogGame? = remember(st.catalogById, row.mapping) {
                        mappedCatalogGame(row.mapping, st.catalogById)
                    }
                    val translatedCatalogTitle = rememberCatalogTranslatedTitle(
                        source = catalogGame?.source.orEmpty(),
                        sourceId = catalogGame?.sourceId
                            ?: catalogGame?.thread_id?.takeIf { it > 0 }?.toString(),
                        title = catalogGame?.title.orEmpty(),
                        enabled = catalogTranslationSettings.enabled,
                        targetLanguage = catalogTranslationSettings.targetLanguage,
                    )
                    val catalogImages = remember(catalogGame) { catalogImageUrls(catalogGame) }
                    Box(
                        modifier = Modifier.padding(
                            start = if (libraryLayoutMode == LibraryLayoutMode.FolderTree) {
                                (gameEntry.depth * 14).dp
                            } else {
                                0.dp
                            },
                        ),
                    ) {
                        AppRowCard(
                            row = row,
                            hasBackup = row.installed.packageName in st.joiPlayBackupPkgs,
                            joiPlaySizeInfo = joiPlaySizeKey(row.installed)?.let { st.joiPlaySizeInfo[it] },
                            isJoiPlaySizeScanning = joiPlaySizeKey(row.installed) in st.joiPlaySizeScanningFolders,
                            renPySaves = st.renPySaveAssociations[row.installed.packageName].orEmpty(),
                            rpgmSaves = st.rpgmSaveAssociations[row.installed.packageName].orEmpty(),
                            expanded = isExpanded,
                            catalogGame = catalogGame,
                            catalogDisplayTitle = translatedCatalogTitle ?: catalogGame?.title,
                            catalogThumbnail = catalogImages.thumbnailUrl,
                            catalogCover = catalogImages.coverUrl,
                            catalogLabels = catalogLabels,
                            selected = pkg in selection || pkg == navigationPackageName,
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
                            onShowCover = { st.fullSizeImageUrl = it },
                            onSnack = { st.snackbarMsg = it },
                            onOpenRenPySaves = { st.renPySaveEditorTarget = row },
                            onAddRenPySaveFolder = { st.renPyAddFolderTarget = row },
                            onOpenRpgmSaves = { st.rpgmSaveViewerTarget = row },
                            onAddRpgmSaveFolder = { st.rpgmAddFolderTarget = row },
                            onRunWinlatorInstaller = { st.winlatorInstallerTarget = row.installed },
                            onLaunch = { launchRow(row) },
                            onLaunchRunner = { launchManaged(row.installed, it) },
                            onRefreshOne = { refreshRow(row) },
                            onOpenSettings = { st.gameSettingsTarget = row },
                            onOpenSource = { openGameSource(row) },
                            onGoToCatalog = row.mapping
                                ?.takeIf { confirmedCatalogNavigationIdentity(it) != null }
                                ?.let { mapping -> { onNavigateToCatalog(mapping) } },
                        )
                    }
                }
            }
            }
        }
    }

    if (advancedFiltersOpen) {
        InstalledAdvancedFilterDialog(
            state = installedAdvancedFilterState,
            query = nameFilter,
            availableUserTags = availableUserTags,
            onApply = { applied ->
                librarySearchMode = applied.searchMode
                activeFilters.clear()
                activeFilters.addAll(applied.activeStatuses)
                sourceFilter = applied.sourceFilter
                manualOnlyFilter = applied.manualOnly
                threadUpdatedAfterInstallFilter = applied.threadUpdatedAfterInstallOnly
                userStatusFilter = applied.userStatus
                minPersonalRatingFilter = applied.minPersonalRating
                lib.stateFilter = applied.gameState
                selectedUserTags = applied.selectedUserTags
                st.hasSavesOnlyFilter = applied.hasSavesOnly
                st.hasBackupOnlyFilter = applied.hasBackupOnly
                storageFilter = applied.storageFilter
                installedDateField = applied.dateField
                installedDateRange = applied.dateRange
            },
            onDismiss = { advancedFiltersOpen = false },
        )
    }

    DialogHost {
    st.joiPlayBackupTarget?.let { (row, action) ->
        val app = row.installed
        var info by remember(app.packageName, action) {
            mutableStateOf<JoiPlayBackupManager.BackupInfo?>(null)
        }
        var loading by remember(app.packageName, action) { mutableStateOf(true) }
        var working by remember(app.packageName, action) { mutableStateOf(false) }
        LaunchedEffect(app.packageName, action) {
            info = runCatching { JoiPlayBackupManager.findBackup(app) }.getOrNull()
            loading = false
        }
        var sizeBytes by remember(app.packageName, action) { mutableStateOf<Long?>(null) }
        LaunchedEffect(info) {
            val i = info
            sizeBytes = if (i != null) runCatching { JoiPlayBackupManager.totalSize(i) }.getOrNull() else null
        }
        val isRevert = action == JoiPlayBackupAction.Revert
        AlertDialog(
            onDismissRequest = { if (!working) st.joiPlayBackupTarget = null },
            title = { Text(if (isRevert) "Revert to backup?" else "Delete backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    when {
                        loading -> Text("Checking for backup…", style = MaterialTheme.typography.bodySmall)
                        info == null -> Text(
                            "No backup folder was found for this game.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        isRevert -> {
                            if (info!!.revertible) {
                                Text(
                                    "This deletes the CURRENT game folder and restores the most recent " +
                                        "backup (${info!!.newest.name}). Use this if the updated game won't launch.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    "This game was upgraded into a new folder, so the leftover " +
                                        "${info!!.newest.name} folder can only be deleted. To go back, " +
                                        "install the previous archive again.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            info!!.sourceArchive?.let {
                                Text(
                                    "Current version installed from: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            val n = info!!.count
                            val sizeStr = sizeBytes?.let { fmtSize(it) } ?: "calculating size…"
                            Text(
                                "This permanently deletes $n backup folder${if (n == 1) "" else "s"} " +
                                    "($sizeStr) for this game. Do this only after confirming the update works.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (working) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                if (isRevert) "Restoring backup…" else "Deleting backup…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !loading && !working && info != null &&
                        (!isRevert || info!!.revertible),
                    onClick = {
                        working = true
                        scope.launch {
                            val result = if (isRevert) {
                                JoiPlayBackupManager.revertToBackup(app)
                            } else {
                                JoiPlayBackupManager.deleteBackups(app)
                            }
                            when (result) {
                                is JoiPlayBackupManager.ActionResult.Success -> {
                                    st.snackbarMsg = result.message
                                    runCatching {
                                        st.joiPlaySizeInfo = JoiPlayScanner.loadSizeInfo(context.applicationContext)
                                        st.apps = scanInstalledLibrary(context)
                                    }.onFailure { AppLog.w("JoiPlayBackup", "Rescan after backup action failed", it) }
                                }
                                is JoiPlayBackupManager.ActionResult.Failure -> st.snackbarMsg = result.message
                                JoiPlayBackupManager.ActionResult.NoBackup ->
                                    st.snackbarMsg = "No backup found for ${app.label}."
                            }
                            working = false
                            st.joiPlayBackupTarget = null
                        }
                    },
                ) { Text(if (isRevert) "Revert" else "Delete") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { st.joiPlayBackupTarget = null }) { Text("Cancel") }
            },
        )
    }

    }
    DialogHost {
    st.dialogApp?.let { row ->
        val isHidden = row.installed.packageName in hidden
        val associatedCatalogGame = mappedCatalogGame(row.mapping, st.catalogById)
        fun afterDialogMapping(packageName: String) {
            if (st.returnToUnmappedAfterDialog) {
                st.unmappedReviewMatches = st.unmappedReviewMatches.filterNot { it.row.installed.packageName == packageName }
                st.unmappedReviewOpen = true
                st.returnToUnmappedAfterDialog = false
            }
        }
        EditMappingDialog(
            row = row,
            associatedCatalogTitle = associatedCatalogGame?.title,
            isHidden = isHidden,
            catalog = catalog,
            searcher = searcher,
            onDismiss = {
                st.dialogApp = null
                if (st.returnToUnmappedAfterDialog) {
                    st.unmappedReviewOpen = true
                    st.returnToUnmappedAfterDialog = false
                }
            },
            onSave = { newUrl, displayNameOverride, userStatus, personalRating, personalNotes, correctionNote ->
                scope.launch {
                    val existing = row.mapping ?: AppMapping(packageName = row.installed.packageName)
                    val normalizedUrl = newUrl.trim()
                    val associationChanged = normalizedUrl != existing.f95Url.orEmpty().trim()
                    val tid = F95UrlParser.extractThreadId(normalizedUrl)
                    val associationBase = if (associationChanged) {
                        existing.withoutCatalogAssociation()
                    } else {
                        existing
                    }
                    val mapping = associationBase.copy(
                        displayNameOverride = displayNameOverride,
                        f95Url = normalizedUrl.ifBlank { null },
                        lastSeenVersion = if (associationChanged) null else existing.lastSeenVersion,
                        lastChecked = if (associationChanged) 0L else existing.lastChecked,
                        acknowledgedVersion = if (associationChanged) null else existing.acknowledgedVersion,
                        threadId = if (associationChanged) tid else tid ?: existing.threadId,
                        notOnF95 = if (normalizedUrl.isNotBlank()) false else existing.notOnF95,
                        matchSource = when {
                            associationChanged && normalizedUrl.isNotBlank() -> "manual"
                            associationChanged -> null
                            else -> existing.matchSource
                        },
                        userStatus = userStatus,
                        personalRating = personalRating,
                        personalNotes = personalNotes.trim(),
                        manualCorrectionNote = correctionNote.trim(),
                    ).withLocalIdentityFrom(row.installed)
                    AppLog.i("ManualMapping", "SAVE ${catalogMatchLogContext(row.installed, catalogMatchLabels(row.installed))} url=${mapping.f95Url} thread=${mapping.threadId} identity=${mapping.manualLocalIdentity}")
                    rememberMapping(mapping)
                    repo.upsert(mapping)
                    st.dialogApp = null
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
                    st.snackbarMsg = "Mapped to '${game.title}'"
                    st.dialogApp = null
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
                    st.snackbarMsg = "Mapped to external source: ${external.title}"
                    st.dialogApp = null
                    afterDialogMapping(row.installed.packageName)
                }
            },
            onMarkNotOnF95 = {
                scope.launch {
                    val mapping = (row.mapping ?: AppMapping(packageName = row.installed.packageName))
                        .withoutCatalogAssociation(notOnF95 = true, matchSource = "manual")
                    repo.upsert(mapping)
                    st.dialogApp = null
                    afterDialogMapping(row.installed.packageName)
                }
            },
            onClearNotOnF95 = {
                scope.launch {
                    val m = row.mapping ?: return@launch
                    repo.upsert(m.copy(notOnF95 = false))
                    st.dialogApp = null
                }
            },
            onClear = {
                scope.launch {
                    val m = row.mapping
                    if (m?.hasPersonalFields() == true) {
                        repo.upsert(m.withoutCatalogAssociation())
                    } else {
                        repo.remove(row.installed.packageName)
                    }
                    st.dialogApp = null
                    st.returnToUnmappedAfterDialog = false
                }
            },
            onMarkInstalled = {
                scope.launch {
                    val m = row.mapping ?: return@launch
                    repo.upsert(m.copy(acknowledgedVersion = m.lastSeenVersion))
                    st.dialogApp = null
                }
            },
            onToggleHide = {
                scope.launch {
                    if (isHidden) repo.unhide(row.installed.packageName)
                    else repo.hide(row.installed.packageName)
                    st.dialogApp = null
                    st.returnToUnmappedAfterDialog = false
                }
            }
        )
    }

    st.ambiguousCatalogMatches.firstOrNull()?.let { ambiguous ->
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
                    st.snackbarMsg = "Mapped ${row.installed.label} to '${game.title}'"
                    st.ambiguousCatalogMatches = st.ambiguousCatalogMatches.drop(1)
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
                    st.snackbarMsg = "Marked ${row.installed.label} as not in catalog"
                    st.ambiguousCatalogMatches = st.ambiguousCatalogMatches.drop(1)
                }
            },
            onSkip = { st.ambiguousCatalogMatches = st.ambiguousCatalogMatches.drop(1) },
            onDismiss = { st.ambiguousCatalogMatches = emptyList() },
        )
    }

    }
    DialogHost {
    if (st.unmappedReviewOpen) {
        UnmappedReviewDialog(
            items = st.unmappedReviewMatches,
            alreadyMatchedItems = st.alreadyMatchedReviewMatches,
            onDismiss = { st.unmappedReviewOpen = false },
            onDismissAlreadyMatched = {
                st.alreadyMatchedReviewMatches = emptyList()
                if (st.unmappedReviewMatches.isEmpty()) st.unmappedReviewOpen = false
                st.snackbarMsg = "Dismissed already matched games from this review"
            },
            onKeepAlreadyMatched = {
                scope.launch {
                    val kept = st.alreadyMatchedReviewMatches
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
                    st.alreadyMatchedReviewMatches = emptyList()
                    if (st.unmappedReviewMatches.isEmpty()) st.unmappedReviewOpen = false
                    st.snackbarMsg = "Kept ${kept.size} already matched game${if (kept.size == 1) "" else "s"}"
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
                    st.unmappedReviewMatches = st.unmappedReviewMatches.filterNot { it.row.installed.packageName == row.installed.packageName }
                    st.alreadyMatchedReviewMatches = st.alreadyMatchedReviewMatches.filterNot { it.item.row.installed.packageName == row.installed.packageName }
                    st.snackbarMsg = "Mapped ${row.installed.label} to '${game.title}'"
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
                    st.unmappedReviewMatches = st.unmappedReviewMatches.filterNot { it.row.installed.packageName == row.installed.packageName }
                    st.alreadyMatchedReviewMatches = st.alreadyMatchedReviewMatches.filterNot { it.item.row.installed.packageName == row.installed.packageName }
                    st.snackbarMsg = "Marked ${row.installed.label} as not in catalog"
                }
            },
            onSearch = { item ->
                st.dialogApp = item.row
                st.returnToUnmappedAfterDialog = true
                st.unmappedReviewOpen = false
            },
            onAddManualMatches = {
                st.unmappedReviewLoading = true
                scope.launch {
                    try {
                        val existingPackages = st.unmappedReviewMatches.map { it.row.installed.packageName }.toSet()
                        val manualItems = buildManualMatchedReviewItems()
                            .filterNot { it.row.installed.packageName in existingPackages }
                        if (manualItems.isEmpty()) {
                            st.snackbarMsg = "No additional manually matched games."
                        } else {
                            st.unmappedReviewMatches = (st.unmappedReviewMatches + manualItems)
                                .sortedBy { it.row.installed.label.lowercase() }
                            st.snackbarMsg = "Added ${manualItems.size} manually matched games."
                        }
                    } finally {
                        st.unmappedReviewLoading = false
                    }
                }
            },
        )
    }

    if (st.unmappedReviewLoading) {
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
                    Text("Finding current unmapped games and deterministic suggestions...")
                }
            },
            confirmButton = { },
        )
    }

    if (st.manualOverrideReviewOpen) {
        ManualOverrideReviewDialog(
            items = st.manualOverrideMatches,
            noAutoResultCount = manualNoAutoResultCount,
            onDismiss = { st.manualOverrideReviewOpen = false },
            onKeepAll = {
                st.manualOverrideReviewOpen = false
                st.snackbarMsg = "Kept all manual mappings"
                st.unmatchedFoundPromptOpen =
                    st.unmappedReviewMatches.isNotEmpty() || st.alreadyMatchedReviewMatches.isNotEmpty()
            },
            onOverwrite = { chosen ->
                scope.launch {
                    chosen.forEach { item ->
                        val game = item.auto
                        repo.upsert(
                            AppMapping(
                                packageName = item.row.installed.packageName,
                                f95Url = game.canonicalUrl,
                                lastSeenVersion = game.version,
                                lastChecked = System.currentTimeMillis(),
                                acknowledgedVersion = item.current.acknowledgedVersion,
                                threadId = game.f95ThreadIdOrNull,
                                matchSource = "catalog-auto:manual-overwritten",
                            ).withPersonalFieldsFrom(item.current).withCatalogSnapshot(game),
                        )
                        AppLog.i(
                            "CatalogRefresh",
                            "MANUAL OVERWRITE ${item.row.installed.packageName} -> " +
                                "tid=${game.thread_id} '${game.title}'",
                        )
                    }
                    st.manualOverrideMatches = emptyList()
                    st.manualOverrideReviewOpen = false
                    st.snackbarMsg = "Overwrote ${chosen.size} manual mapping" +
                        "${if (chosen.size == 1) "" else "s"} with automated matches"
                    st.unmatchedFoundPromptOpen =
                        st.unmappedReviewMatches.isNotEmpty() || st.alreadyMatchedReviewMatches.isNotEmpty()
                }
            },
        )
    }

    if (st.unmatchedFoundPromptOpen) {
        AlertDialog(
            onDismissRequest = { st.unmatchedFoundPromptOpen = false },
            title = { Text("Unmatched games found") },
            text = {
                Text(
                    buildString {
                        if (st.alreadyMatchedReviewMatches.isNotEmpty()) {
                            append("${st.alreadyMatchedReviewMatches.size} entries match games you already mapped before. ")
                        }
                        if (st.unmappedReviewMatches.isNotEmpty()) {
                            append("${st.unmappedReviewMatches.size} entries still need review. ")
                        }
                        append("Open the review window to keep already matched games or handle new/unmatched ones?")
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    st.unmatchedFoundPromptOpen = false
                    st.unmappedReviewOpen = true
                }) { Text("Review now") }
            },
            dismissButton = {
                TextButton(onClick = { st.unmatchedFoundPromptOpen = false }) { Text("Later") }
            },
        )
    }

    st.fullSizeImageUrl?.let { url ->
        FullSizeImageDialog(url = url, onDismiss = { st.fullSizeImageUrl = null })
    }

    }
    DialogHost {
    if (st.settingsDialogOpen) {
        AppSettingsDialog(
            themeMode = themeMode,
            onSelectTheme = { mode ->
                scope.launch { ThemePrefs.set(context.applicationContext, mode) }
            },
            cardColors = cardColors,
            onSetCardColors = { colors ->
                scope.launch { CardColorPrefs.set(context.applicationContext, colors) }
            },
            hasAllFiles = hasAllFilesAccess(),
            onToggleAllFiles = { st.permissionRationale = PermissionRationale.AllFilesConfig },
            hasUsage = hasUsage,
            onToggleUsage = { st.permissionRationale = PermissionRationale.UsageAccess },
            openLinksInApp = browserSettings.openLinksInApp,
            onToggleOpenLinksInApp = {
                scope.launch {
                    BrowserPrefs.setOpenLinksInApp(context.applicationContext, !browserSettings.openLinksInApp)
                }
            },
            downloadBackend = browserSettings.downloadBackend,
            onSelectDownloadBackend = { backend ->
                scope.launch {
                    BrowserPrefs.setDownloadBackend(context.applicationContext, backend)
                }
            },
            downloadFolderLabel = effectiveDownloadFolder(browserSettings).absolutePath,
            onPickDownloadFolder = {
                if (hasAllFilesAccess()) st.downloadFolderPickerOpen = true
                else st.permissionRationale = PermissionRationale.AllFilesConfig
            },
            popupAllowedHosts = effectivePopupAllowlist(
                appConfig.effectiveBrowserPopupAllowlist,
                browserSettings,
            ).sorted(),
            popupBlockedHosts = browserSettings.blockedPopupHosts.sorted(),
            onAllowPopupHost = { host ->
                scope.launch {
                    BrowserPrefs.allowPopupHost(
                        context.applicationContext,
                        host,
                        appConfig.effectiveBrowserPopupAllowlist.toSet(),
                    )
                }
            },
            onBlockPopupHost = { host ->
                scope.launch { BrowserPrefs.blockPopupHost(context.applicationContext, host) }
            },
            onRemoveAllowedPopupHost = { host ->
                scope.launch {
                    BrowserPrefs.removeAllowedPopupHost(
                        context.applicationContext,
                        host,
                        appConfig.effectiveBrowserPopupAllowlist.toSet(),
                    )
                }
            },
            onRemoveBlockedPopupHost = { host ->
                scope.launch { BrowserPrefs.removeBlockedPopupHost(context.applicationContext, host) }
            },
            onOpenNonAndroidSettings = {
                st.settingsDialogOpen = false
                st.joiplaySettingsOpen = true
            },
            onFindAllEngines = {
                if (st.managedEngineBulkProgress == null) {
                    scope.launch {
                        try {
                            st.managedEngineBulkProgress = ManagedEngineDiscoveryProgress(
                                current = 0,
                                total = ManagedGameStore(context.applicationContext).get().size,
                                gameTitle = "",
                                stage = "Preparing",
                            )
                            val summary = ManagedGameDiscovery.discoverAll(
                                context.applicationContext,
                            ) { progress ->
                                st.managedEngineBulkProgress = progress
                            }
                            st.apps = scanInstalledLibrary(context)
                            st.snackbarMsg =
                                "Scanned ${summary.scanned} games; updated ${summary.updated}" +
                                    if (summary.failed > 0) "; ${summary.failed} failed" else ""
                        } catch (error: Exception) {
                            AppLog.e("EngineDiscovery", "Bulk discovery failed", error)
                            st.snackbarMsg = "Engine discovery failed: ${error.message}"
                        } finally {
                            st.managedEngineBulkProgress = null
                        }
                    }
                }
            },
            engineDiscoveryRunning = st.managedEngineBulkProgress != null,
            onDismiss = { st.settingsDialogOpen = false },
        )
    }
    st.managedEngineBulkProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Finding supported engines") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total <= 0) 0f
                            else progress.current.toFloat() / progress.total.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${progress.current} of ${progress.total}")
                    if (progress.gameTitle.isNotBlank()) {
                        Text(progress.gameTitle, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        progress.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
    }
    if (st.downloadsOpen) {
        DownloadManagerDialog(
            records = downloadRecords,
            onCancel = onCancelDownload,
            onRetry = onRetryDownload,
            onInstall = { rec ->
                val file = rec.destPath?.let { java.io.File(it) }
                if (file != null && file.isFile) {
                    st.downloadsOpen = false
                    rememberPickerDir(file)
                    routePickedFile(file, rec.catalogGame)
                } else {
                    st.snackbarMsg = "File no longer exists"
                }
            },
            onDeleteFile = { rec ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        rec.destPath?.let { runCatching { java.io.File(it).delete() } }
                    }
                    DownloadStore.remove(context.applicationContext, rec.id)
                    st.snackbarMsg = "Deleted ${rec.fileName}"
                }
            },
            onClearEntry = { rec ->
                scope.launch { DownloadStore.remove(context.applicationContext, rec.id) }
            },
            onOpenInCatalog = { game ->
                st.downloadsOpen = false
                onOpenDownloadInCatalog(game)
            },
            onRemoveMissing = {
                scope.launch {
                    val removed = DownloadStore.removeMissingFiles(context.applicationContext)
                    st.snackbarMsg = if (removed == 1) {
                        "Removed 1 missing download"
                    } else {
                        "Removed $removed missing downloads"
                    }
                }
            },
            onClearFinished = {
                scope.launch { DownloadStore.clearFinished(context.applicationContext) }
            },
            onReturnToBrowser = onRestoreBrowser?.let { restore ->
                {
                    st.downloadsOpen = false
                    restore()
                }
            },
            onDismiss = { st.downloadsOpen = false },
        )
    }
    if (st.downloadFolderPickerOpen) {
        FolderPickerDialog(
            initialPath = effectiveDownloadFolder(browserSettings).absolutePath,
            onCancel = { st.downloadFolderPickerOpen = false },
            onPick = { path ->
                st.downloadFolderPickerOpen = false
                scope.launch {
                    BrowserPrefs.setDownloadFolderPath(context.applicationContext, path)
                    st.snackbarMsg = "Browser downloads folder set"
                }
            },
        )
    }
    if (st.catalogRefreshDialogOpen) {
        CatalogRefreshDialog(
            overwriteManual = st.overwriteManualMatches,
            onToggleOverwrite = {
                st.overwriteManualMatches = !st.overwriteManualMatches
                scope.launch {
                    MatchingPrefs.setOverwriteManualMatches(context.applicationContext, st.overwriteManualMatches)
                }
            },
            resetAcks = st.resetAcksOnRefresh,
            onToggleResetAcks = {
                st.resetAcksOnRefresh = !st.resetAcksOnRefresh
                scope.launch {
                    MatchingPrefs.setResetAcksOnRefresh(context.applicationContext, st.resetAcksOnRefresh)
                }
            },
            onRefresh = {
                st.catalogRefreshDialogOpen = false
                scope.launch {
                    if (st.resetAcksOnRefresh) {
                        repo.resetAllAcknowledgements()
                    }
                    st.snackbarMsg = "Matching against catalog…"
                    val allRows = st.apps.map { app ->
                        val m = mappings[app.packageName]
                        AppRow(app, m, UpdateStatus.Unknown)
                    }
                    refreshFromCatalog(allRows)?.let { (m, u) ->
                        st.snackbarMsg = "Catalog refresh: $m / ${m + u} matched"
                    }
                }
            },
            onCancel = { st.catalogRefreshDialogOpen = false },
        )
    }
    if (st.aboutOpen) {
        AboutDialog(
            onDismiss = { st.aboutOpen = false },
            onOpenHelp = {
                runCatching {
                    openExternalUrl(context, AppConfig.DEFAULT_HELP_URL)
                }.onFailure { st.snackbarMsg = "Could not open help: ${it.message}" }
            },
            onShareApp = {
                runCatching {
                    shareAdultGameManager(context, appConfig.effectiveSupportThreadUrl)
                }.onFailure { st.snackbarMsg = "Could not open share sheet: ${it.message}" }
            },
            onReportIssue = {
                runCatching {
                    openExternalUrl(context, appConfig.issueReportUrl)
                }.onFailure { st.snackbarMsg = "Could not open issue page: ${it.message}" }
            },
            onOpenSupport = {
                runCatching {
                    openExternalUrl(context, appConfig.effectiveSupportThreadUrl)
                }.onFailure { st.snackbarMsg = "Could not open browser: ${it.message}" }
            },
        )
    }

    if (st.topStatusOpen) {
        AlertDialog(
            onDismissRequest = { st.topStatusOpen = false },
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
                TextButton(onClick = { st.topStatusOpen = false }) { Text("OK") }
            },
        )
    }

    if (st.diagnosticsSummaryOpen) {
        val clipboard = LocalClipboardManager.current
        val summary = remember(st.apps, rows, hasUsage, appConfig) {
            buildDiagnosticsSummary(
                context = context,
                appCount = st.apps.size,
                visibleCount = rows.size,
                hasUsage = hasUsage,
                hasAllFiles = hasAllFilesAccess(),
                config = appConfig,
            )
        }
        AlertDialog(
            onDismissRequest = { st.diagnosticsSummaryOpen = false },
            title = { Text("Diagnostics summary") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .dialogVerticalScroll(),
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
                    st.snackbarMsg = "Diagnostics summary copied"
                    st.diagnosticsSummaryOpen = false
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { st.diagnosticsSummaryOpen = false }) { Text("Close") } },
        )
    }

    // Foreground "Refresh from catalog" progress dialog with ETA + cancel.
    st.refreshProgress?.let { p ->
        AlertDialog(
            onDismissRequest = { /* not dismissible by tap-outside */ },
            title = { Text("Refreshing from catalog") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val buildingIndex = p.current == 0 && catalogBuildProgress < 1f
                    val ratio = when {
                        buildingIndex -> 0.03f + catalogBuildProgress * 0.9f
                        p.total > 0 -> p.current.toFloat() / p.total
                        else -> 0f
                    }
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (buildingIndex) {
                        Text(
                            "Building catalog index\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${(catalogBuildProgress * 100).toInt()}%  \u2022  one-time after a catalog update",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
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
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { st.refreshCancelled = true },
                    enabled = !st.refreshCancelled,
                ) { Text(if (st.refreshCancelled) "Cancelling\u2026" else "Cancel") }
            }
        )
    }

    if (st.refreshCancelledNote) {
        AlertDialog(
            onDismissRequest = { st.refreshCancelledNote = false },
            title = { Text("Refresh cancelled") },
            text = {
                Text(
                    "The catalog refresh was cancelled. You can run it again any time from " +
                            "Menu \u2192 Refresh from catalog.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { st.refreshCancelledNote = false }) { Text("OK") }
            }
        )
    }

    if (st.f95MigrationPromptOpen) {
        AlertDialog(
            onDismissRequest = { /* keep first-run flow explicit */ },
            title = { Text(if (st.f95MigrationError == null) "Import from F95 Updater?" else "Import failed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "F95 Updater is installed on this device. Adult Game Manager can import your mappings, hidden games, JoiPlay backup data, version overrides, and personal tracking fields.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    st.f95MigrationError?.let {
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
                            st.f95MigrationError = null
                            st.f95MigrationPromptOpen = false
                            st.firstRunHintOpen = true
                            st.snackbarMsg = "Imported ${summary.mappings} mappings and ${summary.joiplayGames} JoiPlay games from F95 Updater"
                        }.onFailure {
                            AppLog.e("Migration", "F95 Updater import failed", it)
                            st.f95MigrationError = it.message ?: "Could not import from F95 Updater."
                        }
                    }
                }) { Text(if (st.f95MigrationError == null) "Import" else "Try again") }
            },
            dismissButton = {
                TextButton(onClick = {
                    st.f95MigrationError = null
                    st.f95MigrationPromptOpen = false
                    st.firstRunHintOpen = true
                }) { Text("Skip") }
            },
        )
    }

    }
    DialogHost {
    if (st.firstRunHintOpen) {
        AlertDialog(
            onDismissRequest = { /* not dismissible by tapping outside during first-run */ },
            title = { Text(if (st.joiplayImportError != null) "Couldn't read that file" else "Welcome to Adult Game Manager!") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st.joiplayImportError != null) {
                        Text(
                            "That file isn't a valid JoiPlay backup (.joiback).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Details: ${st.joiplayImportError}",
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
                            "Track adult game updates across installed APKs and AGM-managed games with multiple execution engines.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (st.joiPlayInstalled) {
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
                            "You can browse the catalog without extra permissions. APK installs, managed-game extraction, folder tools, and last-used sorting ask only when you use those features.",
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
                if (st.joiPlayInstalled) {
                    TextButton(onClick = {
                        st.joiplayImportError = null
                        st.firstRunHintOpen = false
                        askForJoiPlayBackupFolderAccess(firstRun = true)
                    }) { Text(if (st.joiplayImportError != null) "Pick another file" else "Import JoiPlay backup") }
                } else {
                    TextButton(onClick = {
                        st.firstRunHintOpen = false
                        st.firstRunRefreshPending = true
                    }) { Text("Continue") }
                }
            },
            dismissButton = if (st.joiPlayInstalled) {
                {
                    TextButton(onClick = {
                        st.joiplayImportError = null
                        st.firstRunHintOpen = false
                        st.firstRunRefreshPending = true
                    }) { Text("Skip") }
                }
            } else null,
        )
    }

    st.joiplayBackupAccessDisclosureFirstRun?.let { firstRun ->
        AlertDialog(
            onDismissRequest = {
                st.joiplayBackupAccessDisclosureFirstRun = null
                if (firstRun) st.firstRunHintOpen = true
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
                    st.joiplayBackupAccessDisclosureFirstRun = null
                    st.joiplayBackupPickerFirstRun = firstRun
                    joiplayBackupFolderPicker.launch(null)
                }) { Text("Open folder picker") }
            },
            dismissButton = {
                TextButton(onClick = {
                    st.joiplayBackupAccessDisclosureFirstRun = null
                    if (firstRun) st.firstRunHintOpen = true
                }) { Text("Cancel") }
            },
        )
    }

    if (st.importBackupAccessDisclosureOpen) {
        AlertDialog(
            onDismissRequest = { st.importBackupAccessDisclosureOpen = false },
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
                    st.importBackupAccessDisclosureOpen = false
                    mappingBackupFolderPicker.launch(null)
                }) { Text("Open folder picker") }
            },
            dismissButton = {
                TextButton(onClick = { st.importBackupAccessDisclosureOpen = false }) { Text("Cancel") }
            },
        )
    }

    // Blocking dialog shown while a JoiPlay backup is being imported.
    if (st.joiplayImportBusy) {
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

    }
    DialogHost {
    st.matchResearchProgress?.let { progress ->
        MatchResearchProgressDialog(progress = progress)
    }

    // Progress dialog shown while running JoiPlay version detection.
    st.joiPlayDetecting?.let { row ->
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
                                    st.apps = scanInstalledLibrary(context)
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

    st.manualVersionTarget?.let { row ->
        val catalogVersion = row.mapping?.lastSeenVersion?.trim()?.ifBlank { null }
        var versionText by remember(row.installed.packageName, row.mapping?.manualInstalledVersion) {
            mutableStateOf(
                row.mapping?.manualInstalledVersion
                    ?.takeIf { hasActiveManualInstalledVersion(row.installed, row.mapping) }
                    ?: effectiveInstalledVersion(row.installed, row.mapping)
            )
        }
        AlertDialog(
            onDismissRequest = { st.manualVersionTarget = null },
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
                            st.manualVersionTarget = null
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
                                st.snackbarMsg = "Cleared installed-version override for ${row.installed.label}"
                            }
                            st.manualVersionTarget = null
                        }
                    ) { Text("Clear") }
                    TextButton(onClick = { st.manualVersionTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    st.manualDateTarget?.let { target ->
        val row = target.first
        val catalogGame = target.second
        val catalogDate = catalogInstalledDateCandidate(catalogGame)
        val activeManual = hasActiveManualInstalledDate(row.installed, row.mapping)
        val shownDate = effectiveInstalledDate(row.installed, row.mapping)
        AlertDialog(
            onDismissRequest = { st.manualDateTarget = null },
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
                                st.manualDateTarget = null
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
                                    st.manualDateTarget = null
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
                                st.snackbarMsg = "Cleared installed-date override for ${row.installed.label}"
                            }
                            st.manualDateTarget = null
                        }
                    ) { Text("Clear") }
                    TextButton(onClick = { st.manualDateTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    // Auto-prompt for SAF when deleting a JoiPlay game for the first time.
    st.joiPlayGrantAskFor?.let { row ->
        AlertDialog(
            onDismissRequest = { st.joiPlayGrantAskFor = null },
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
                TextButton(onClick = { st.joiPlayGrantAskFor = null }) { Text("Cancel") }
            }
        )
    }


    if (st.joiplayBackupFilePickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Pick a JoiPlay backup",
            allowedExtensions = setOf("joiback"),
            onCancel = {
                val firstRun = st.joiplayBackupPickerFirstRun
                st.joiplayBackupPickerFirstRun = false
                st.joiplayBackupFilePickerOpen = false
                if (firstRun) st.firstRunHintOpen = true
            },
            onPick = { file ->
                val firstRun = st.joiplayBackupPickerFirstRun
                st.joiplayBackupPickerFirstRun = false
                st.joiplayBackupFilePickerOpen = false
                rememberPickerDir(file)
                startJoiPlayBackupImport(Uri.fromFile(file), firstRun)
            },
        )
    }
    if (st.backupScopedPickerOpen) {
        val rootUri = st.backupScopedRootUri
        if (rootUri != null) {
            ScopedFilePickerDialog(
                rootUri = rootUri,
                title = "Pick a JoiPlay backup",
                allowedExtensions = setOf("joiback"),
                onCancel = {
                    val firstRun = st.joiplayBackupPickerFirstRun
                    st.joiplayBackupPickerFirstRun = false
                    st.backupScopedPickerOpen = false
                    if (firstRun) st.firstRunHintOpen = true
                },
                onPick = { uri, _ ->
                    val firstRun = st.joiplayBackupPickerFirstRun
                    st.joiplayBackupPickerFirstRun = false
                    st.backupScopedPickerOpen = false
                    startJoiPlayBackupImport(uri, firstRun)
                },
            )
        }
    }
    if (st.exportBackupPickerOpen) {
        FolderPickerDialog(
            initialPath = pickerInitialPath,
            onCancel = { st.exportBackupPickerOpen = false },
            onPick = { folderPath ->
                st.exportBackupPickerOpen = false
                pickerInitialPath = folderPath
                scope.launch {
                    JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, folderPath)
                    runCatching {
                        val outFile = java.io.File(folderPath, "adult-game-manager-backup.json")
                        outFile.writeText(repo.exportJson())
                        st.snackbarMsg = "Exported mappings to ${outFile.absolutePath}"
                    }.onFailure {
                        st.snackbarMsg = "Export failed: ${it.message}"
                    }
                }
            },
        )
    }
    if (st.cleanupRootPickerOpen) {
        FolderPickerDialog(
            initialPath = pickerInitialPath,
            onCancel = { st.cleanupRootPickerOpen = false },
            onPick = { folderPath ->
                st.cleanupRootPickerOpen = false
                pickerInitialPath = folderPath
                AppLog.i("CleanupReview", "Selected root='$folderPath' apps=${st.apps.size}")
                scope.launch { JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, folderPath) }
                startCleanupReview(folderPath)
            },
        )
    }
    if (st.cleanupSavePickerOpen) {
        FolderPickerDialog(
            initialPath = pickerInitialPath,
            onCancel = { st.cleanupSavePickerOpen = false },
            onPick = { folderPath ->
                st.cleanupSavePickerOpen = false
                pickerInitialPath = folderPath
                val report = st.cleanupReport
                if (report == null) {
                    st.snackbarMsg = "No report to save."
                    return@FolderPickerDialog
                }
                scope.launch {
                    JoiPlaySettingsStore.setLastFilePickerDir(context.applicationContext, folderPath)
                    runCatching {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
                        val outFile = java.io.File(folderPath, "agm-cleanup-review-$stamp.txt")
                        outFile.writeText(report.asText())
                        outFile.absolutePath
                    }.onSuccess { path ->
                        st.snackbarMsg = "Saved report to $path"
                    }.onFailure { t ->
                        st.snackbarMsg = "Save failed: ${t.message}"
                    }
                }
            },
        )
    }
    if (st.cleanupScanning) {
        CleanupReviewProgressDialog(progress = st.cleanupProgress)
    }
    st.cleanupReport?.let { report ->
        val clipboard = LocalClipboardManager.current
        CleanupReviewDialog(
            report = report,
            onDismiss = { st.cleanupReport = null },
            onCopy = {
                clipboard.setText(AnnotatedString(report.asText()))
                st.snackbarMsg = "Copied report to clipboard"
            },
            onSave = { st.cleanupSavePickerOpen = true },
            onOpenFolder = { path ->
                val result = FolderOpener.open(context, path)
                AppLog.i(
                    "OpenFolder",
                    "${if (result.ok) "ok" else "fail"}: ${result.message} (cleanupPath=$path)",
                )
                st.snackbarMsg = result.message
            },
            onRemoveOrphan = { st.cleanupOrphanRemoveTarget = it },
        )
    }
    st.cleanupOrphanRemoveTarget?.let { game ->
        AlertDialog(
            onDismissRequest = { st.cleanupOrphanRemoveTarget = null },
            title = { Text("Remove orphan game from list?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(game.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        game.effectiveFolderPath ?: game.storagePath.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when (game.source) {
                            AppSource.Managed ->
                                "AGM will remove this missing managed-game record. No game or Winlator container files will be touched."
                            AppSource.JoiPlay ->
                                "The missing folder will not be touched. AGM will remove this stale JoiPlay record from its list."
                            AppSource.Winlator ->
                                "AGM will ask Winlator to delete this stale managed-game record. A private isolated container may also be reclaimed; shared containers are preserved."
                            AppSource.Kirikiroid ->
                                "The missing folder will not be touched. AGM will remove this stale Kirikiroid record from its list."
                            AppSource.Android -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    st.cleanupOrphanRemoveTarget = null
                    when (game.source) {
                        AppSource.Managed -> scope.launch {
                            val id = game.packageName.removePrefix(MANAGED_GAME_PACKAGE_PREFIX)
                            runCatching { ManagedGameStore(context.applicationContext).delete(id) }
                                .onSuccess { deleted ->
                                    if (deleted) {
                                        st.apps = scanInstalledLibrary(context)
                                        st.cleanupRootPath?.let { startCleanupReview(it, st.apps) }
                                        st.snackbarMsg = "Removed ${game.title} from the list"
                                    } else {
                                        st.snackbarMsg = "Managed game record was already absent."
                                    }
                                }
                                .onFailure { error ->
                                    AppLog.w("Cleanup", "Managed game record was not removed", error)
                                    st.snackbarMsg = error.message
                                        ?: "AGM could not remove ${game.title} from the list."
                                }
                        }
                        AppSource.JoiPlay -> scope.launch {
                            runCatching {
                                game.joiPlayGameId?.let {
                                    JoiPlayBackupReader.markDeleted(context.applicationContext, it)
                                }
                                scanInstalledLibrary(context)
                            }.onSuccess { refreshed ->
                                st.apps = refreshed
                                st.cleanupRootPath?.let { startCleanupReview(it, refreshed) }
                                st.snackbarMsg = "Removed ${game.title} from the list"
                            }.onFailure {
                                AppLog.e("CleanupReview", "Could not remove JoiPlay orphan ${game.packageName}", it)
                                st.snackbarMsg = "Could not remove ${game.title}: ${it.message}"
                            }
                        }
                        AppSource.Winlator -> {
                            val gameId = game.winlatorGameId
                            if (gameId.isNullOrBlank()) {
                                st.snackbarMsg = "This orphan has no Winlator game ID to remove."
                            } else {
                                st.pendingWinlatorOperation = PendingWinlatorOperation(
                                    kind = WinlatorOperationKind.Delete,
                                    gameId = gameId,
                                    title = game.title,
                                )
                                runCatching { winlatorResultLauncher.launch(WinlatorApi.delete(gameId)) }
                                    .onFailure {
                                        st.pendingWinlatorOperation = null
                                        st.snackbarMsg = "Could not open Winlator: ${it.message}"
                                    }
                            }
                        }
                        AppSource.Kirikiroid -> scope.launch {
                            runCatching { scanInstalledLibrary(context) }
                                .onSuccess { refreshed ->
                                    st.apps = refreshed
                                    st.cleanupRootPath?.let { startCleanupReview(it, refreshed) }
                                    st.snackbarMsg = "Removed ${game.title} from the list"
                                }
                                .onFailure {
                                    st.snackbarMsg = "Could not remove ${game.title}: ${it.message}"
                                }
                        }
                        AppSource.Android -> Unit
                    }
                }) { Text("Remove from list") }
            },
            dismissButton = {
                TextButton(onClick = { st.cleanupOrphanRemoveTarget = null }) { Text("Cancel") }
            },
        )
    }
    if (st.saveLocationsOpen) {
        val clipboard = LocalClipboardManager.current
        SaveLocationsTabbedDialog(
            renPyLocations = st.renPySaveLocations,
            rpgmLocations = st.rpgmSaveLocations,
            renPyLastScannedAt = st.renPySaveLastScannedAt,
            rpgmLastScannedAt = st.rpgmSaveLastScannedAt,
            renPyActionsEnabled = !st.renPySaveScanning,
            rpgmActionsEnabled = !st.rpgmSaveScanning,
            onDismiss = { st.saveLocationsOpen = false },
            onAssociateRenPy = { st.renPySaveAssociationPicker = it },
            onClearRenPy = { clearRenPyManualAssociation(it) },
            onAssociateRpgm = { st.rpgmSaveAssociationPicker = it },
            onClearRpgm = { clearRpgmManualAssociation(it) },
            onCopyRenPy = {
                clipboard.setText(AnnotatedString(st.renPySaveLocations.asRenPySaveReportText(st.renPySaveLastScannedAt)))
                st.snackbarMsg = "Copied Ren'Py save locations"
            },
        )
    }
    st.renPySaveAssociationPicker?.let { location ->
        RenPySaveAssociationPickerDialog(
            location = location,
            apps = st.apps,
            onDismiss = { st.renPySaveAssociationPicker = null },
            onAssociate = { app -> manuallyAssociateRenPyLocation(location, app) },
        )
    }
    st.renPySaveEditorTarget?.let { row ->
        val locations = st.renPySaveLocations.filter { it.associatedPackageName == row.installed.packageName }
        RenPySaveEditorDialog(
            app = row.installed,
            locations = locations,
            onDismiss = { st.renPySaveEditorTarget = null },
        )
    }
    st.renPyAddFolderTarget?.let { row ->
        FolderPickerDialog(
            initialPath = row.installed.storagePath ?: android.os.Environment.getExternalStorageDirectory().absolutePath,
            onCancel = { st.renPyAddFolderTarget = null },
            onPick = { folder ->
                st.renPyAddFolderTarget = null
                addRenPySaveFolderToGame(row, folder)
            },
        )
    }
    st.rpgmSaveAssociationPicker?.let { location ->
        RpgmSaveAssociationPickerDialog(
            location = location,
            apps = st.apps,
            onDismiss = { st.rpgmSaveAssociationPicker = null },
            onAssociate = { app -> manuallyAssociateRpgmLocation(location, app) },
        )
    }
    st.rpgmSaveViewerTarget?.let { row ->
        RpgmSaveViewerDialog(
            app = row.installed,
            locations = st.rpgmSaveLocations.filter { it.associatedPackageName == row.installed.packageName },
            onDismiss = { st.rpgmSaveViewerTarget = null },
        )
    }
    if (st.saveBackupBrowserOpen) {
        SaveBackupBrowserDialog(
            renPyLocations = st.renPySaveLocations,
            rpgmLocations = st.rpgmSaveLocations,
            onDismiss = { st.saveBackupBrowserOpen = false },
        )
    }
    st.rpgmAddFolderTarget?.let { row ->
        FolderPickerDialog(
            initialPath = row.installed.storagePath ?: android.os.Environment.getExternalStorageDirectory().absolutePath,
            onCancel = { st.rpgmAddFolderTarget = null },
            onPick = { folder ->
                st.rpgmAddFolderTarget = null
                addRpgmSaveFolderToGame(row, folder)
            },
        )
    }
    if (st.importBackupPickerOpen) {
        val rootUri = st.importBackupScopedRootUri ?: st.backupScopedRootUri
        if (rootUri != null) {
            ScopedFilePickerDialog(
                rootUri = rootUri,
                title = "Pick a backup to import",
                allowedExtensions = setOf("json"),
                onCancel = { st.importBackupPickerOpen = false },
                onPick = { uri, _ ->
                    st.importBackupPickerOpen = false
                    startMappingBackupImport(uri)
                },
            )
        } else {
            st.importBackupPickerOpen = false
            st.importBackupAccessDisclosureOpen = true
        }
    }
    if (st.installWarningOpen) {
        JoiPlayInstallWarningDialog(
            onDismiss = { st.installWarningOpen = false },
            onContinue = { dontShow ->
                st.installWarningOpen = false
                val openBulk = st.installWarningOpensBulk
                st.installWarningOpensBulk = false
                scope.launch {
                    if (dontShow) JoiPlaySettingsStore.setInstallWarningDismissed(context.applicationContext, true)
                    if (openBulk) st.bulkInstallPickerOpen = true else st.installPickerOpen = true
                }
            },
        )
    }
    if (st.installPickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Pick a game file or archive",
            allowedExtensions = InstallRouting.pickerExtensions,
            onCancel = { st.installPickerOpen = false },
            onPick = { file ->
                st.installPickerOpen = false
                rememberPickerDir(file)
                routePickedFile(file)
            },
        )
    }
    if (st.patchPickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Pick a patch archive",
            allowedExtensions = InstallRouting.patchArchiveExtensions,
            onCancel = { st.patchPickerOpen = false },
            onPick = { file ->
                st.patchPickerOpen = false
                rememberPickerDir(file)
                AppLog.i("PatchInstall", "Patch archive picked: ${file.name}")
                reportInstallRefusal(patchFlow.start(Uri.fromFile(file)))
            },
        )
    }
    if (st.bulkInstallPickerOpen) {
        FilePickerDialog(
            initialPath = pickerInitialPath,
            title = "Select games to install",
            allowedExtensions = InstallRouting.pickerExtensions,
            multiSelect = true,
            onCancel = { st.bulkInstallPickerOpen = false },
            onPickMany = { files ->
                st.bulkInstallPickerOpen = false
                startBulkPreflight(files)
            },
        )
    }
    st.batchPreflight?.let { preflight ->
        LaunchedEffect(preflight.runId, preflight.isResolved) {
            if (preflight.isResolved) {
                // Only the run that is still on screen may promote itself into the queue.
                if (st.batchPreflight?.runId != preflight.runId) return@LaunchedEffect
                val session = preflight.toSession()
                AppLog.i(
                    "Install",
                    "Bulk preflight resolved: ${session.items.count { it.status == BatchItemStatus.Queued }} " +
                        "queued, ${session.skippedCount} skipped, automatic=${session.automatic}",
                )
                st.batchPreflight = null
                st.batch = session.startNext()
            }
        }
        val decision = preflight.currentDecision
        if (!preflight.modeChosen || preflight.scanning) {
            BatchPreflightDialog(
                preflight = preflight,
                onChooseAutomatic = { st.batchPreflight = preflight.chooseMode(automatic = true) },
                onChooseReview = { st.batchPreflight = preflight.chooseMode(automatic = false) },
                onCancel = { st.batchPreflight = null },
            )
        } else if (decision != null) {
            BatchUpgradeDecisionDialog(
                item = decision,
                position = preflight.decidedCount + 1,
                total = preflight.decisionItems.size,
                onUpgrade = { target ->
                    AppLog.i(
                        "ManagedUpgrade",
                        "Preflight decision for ${decision.name}: upgrade '${target.label}'",
                    )
                    st.batchPreflight = preflight.decide(
                        decision.path,
                        BatchInstallDecision.Upgrade(target.managedGameId, target.label),
                    )
                },
                onInstallAsNew = {
                    AppLog.i("ManagedUpgrade", "Preflight decision for ${decision.name}: install as new")
                    st.batchPreflight =
                        preflight.decide(decision.path, BatchInstallDecision.InstallAsNew)
                },
                onSkip = {
                    AppLog.i("ManagedUpgrade", "Preflight decision for ${decision.name}: skip")
                    st.batchPreflight = preflight.decide(decision.path, BatchInstallDecision.Skip)
                },
                onSkipAll = {
                    AppLog.i(
                        "ManagedUpgrade",
                        "Preflight decisions skipped for ${preflight.pendingDecisions.size} remaining file(s)",
                    )
                    st.batchPreflight = preflight.skipRemaining()
                },
            )
        }
    }
    activeArchiveProgress?.takeIf { progressMinimize.minimized }?.let { status ->
        Box(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            MinimizedProgressCard(
                status = status,
                onRestore = { progressMinimize = ProgressMinimize.restore(progressMinimize) },
                onCancel = { cancelActiveProgress() },
            )
        }
    }
    st.batch?.let { session ->
        BatchInstallBanner(
            session = session,
            onCancelRemaining = { st.batch = session.cancelRemaining() },
            onClose = { st.batch = null },
        )
        session.pausedError?.let { error ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Bulk install paused") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { st.batch = session.continueAfterError() }) {
                        Text("Skip and continue")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { st.batch = session.stopAfterError() }) {
                        Text("Stop remaining")
                    }
                },
            )
        }
    }
    InstalledLibraryDialogsHost(
        editTagsFor = st.editTagsFor,
        onSetEditTags = { st.editTagsFor = it },
        bulkTagTarget = lib.bulkTagTarget,
        bulkDeleteConfirm = lib.bulkDeleteConfirm,
        userTags = st.userTags,
        joiPlaySizeInfo = st.joiPlaySizeInfo,
        onSetBulkTagTarget = { lib.bulkTagTarget = it },
        onSetBulkDeleteConfirm = { lib.bulkDeleteConfirm = it },
        onUserTagsChanged = { st.userTags = it },
        onRescan = { scope.launch { st.apps = scanInstalledLibrary(context) } },
        onSnack = { st.snackbarMsg = it },
        onSelectionClear = { selection.clear() },
        storageDashboardOpen = lib.storageDashboardOpen,
        apps = st.apps,
        lastPlayed = lib.lastPlayed,
        onDismissDashboard = { lib.storageDashboardOpen = false },
        onOpenLeftoverReview = {
            if (hasAllFilesAccess()) st.cleanupRootPickerOpen = true
            else st.permissionRationale = PermissionRationale.AllFilesCleanupReview
        },
        duplicatesOpen = lib.duplicatesOpen,
        libraryRows = rows,
        onDismissDuplicates = { lib.duplicatesOpen = false },
        gameStates = lib.gameStates,
        onGameStatesChanged = { lib.gameStates = it },
        bulkStatusTarget = lib.bulkStatusTarget,
        onSetBulkStatusTarget = { lib.bulkStatusTarget = it },
        collectionsOpen = lib.collectionsOpen,
        onDismissCollections = { lib.collectionsOpen = false },
        onPickState = { st -> lib.collectionsOpen = false; lib.stateFilter = st },
        onPickTag = { tag ->
            lib.collectionsOpen = false
            selectedUserTags = setOf(tag)
        },
        joiPlayDeleteConfirm = st.joiPlayDeleteConfirm,
        joiPlayDeleting = st.joiPlayDeleting,
        onSetJoiPlayDeleteConfirm = { st.joiPlayDeleteConfirm = it },
        onSetJoiPlayDeleting = { st.joiPlayDeleting = it },
        onAppsChanged = { st.apps = it },
        autoBackupOpen = st.autoBackupDialogOpen,
        autoBackupList = st.autoBackupList,
        autoBackupConfirmRestore = st.autoBackupConfirmRestore,
        repo = repo,
        onSetAutoBackupOpen = { st.autoBackupDialogOpen = it },
        onSetAutoBackupList = { st.autoBackupList = it },
        onSetAutoBackupConfirmRestore = { st.autoBackupConfirmRestore = it },
    )
    }
    DialogHost {
    st.winlatorInstallerTarget?.let { target ->
        FilePickerDialog(
            initialPath = target.storagePath
                ?.takeIf { java.io.File(it).isDirectory }
                ?: pickerInitialPath,
            title = "Pick installer for ${target.label}",
            allowedExtensions = setOf("exe"),
            onCancel = { st.winlatorInstallerTarget = null },
            onPick = { file ->
                st.winlatorInstallerTarget = null
                rememberPickerDir(file)
                scope.launch {
                    if (!file.isFile || !file.isAbsolute) {
                        st.snackbarMsg = "The selected Winlator installer is no longer accessible."
                        return@launch
                    }
                    val gameId = target.winlatorGameId
                    if (gameId == null) {
                        st.snackbarMsg = "Winlator game id is missing."
                        return@launch
                    }
                    val capabilities = WinlatorClient.requiredV2Capabilities(context.applicationContext)
                        .getOrElse {
                            st.snackbarMsg = "Winlator Secure isn't ready: ${it.message}"
                            return@launch
                        }
                    st.pendingWinlatorOperation = PendingWinlatorOperation(
                        WinlatorOperationKind.RunInstaller,
                        gameId,
                        target.label,
                    )
                    runCatching {
                        winlatorResultLauncher.launch(
                            WinlatorApi.runInstaller(
                                gameId = gameId,
                                installerPath = file.absolutePath,
                                async = capabilities.asyncInstallers && capabilities.installerProgress,
                            )
                        )
                    }.onFailure {
                        st.pendingWinlatorOperation = null
                        st.snackbarMsg = "Could not open Winlator: ${it.message}"
                    }
                }
            },
        )
    }
    st.archiveAnalysisInProgress?.let { file ->
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
                        Text("Detecting Android, Winlator, or JoiPlay content...")
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
    st.apkInstallProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(progress.phase.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val fraction = progress.fraction
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${(fraction * 100).toInt()}%")
                    }
                    if (progress.phase == ApkInstallProgress.Phase.AwaitingConfirmation) {
                        Text("Confirm the installation in Android's installer.")
                    }
                }
            },
            confirmButton = { },
        )
    }
    st.upgradePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { st.upgradePrompt = null },
            title = { Text("Update an installed game?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "AGM matched this archive to an installed managed game. Updating extracts it " +
                            "into a brand new folder, copies your saves into it, then re-points AGM and " +
                            "every engine at it. The old folder is kept and renamed so you can delete it " +
                            "once the update works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    prompt.matches.forEach { candidate ->
                        val app = candidate.app
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppLog.i(
                                        "ManagedUpgrade",
                                        "User selected upgrade target '${app.label}' for archive " +
                                            "${prompt.archive.name} evidence=${candidate.evidence}"
                                    )
                                    st.upgradePrompt = null
                                    reportInstallRefusal(upgradeFlow.start(prompt.archive, app))
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
                                Text(
                                    candidate.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val archive = prompt.archive
                    val route = prompt.installAsNewRoute
                    st.upgradePrompt = null
                    dispatchArchiveRoute(archive, route)
                }) { Text("Install as new") }
            },
            dismissButton = {
                TextButton(onClick = { st.upgradePrompt = null }) { Text("Cancel") }
            },
        )
    }
    st.apkInstallConfirm?.let { apk ->
        val canDeleteSource = remember(apk) { runCatching { apk.canWrite() }.getOrDefault(false) }
        var deleteAfter by remember(apk) { mutableStateOf(false) }
        LaunchedEffect(apk) {
            deleteAfter = JoiPlaySettingsStore.deleteAfterInstall(context)
        }
        AlertDialog(
            onDismissRequest = {
                st.apkInstallConfirm = null
                st.pendingExtractedApkRoot?.deleteRecursively()
                st.pendingExtractedApkRoot = null
                st.pendingExtractedSourceArchive = null
                st.pendingExtractedArchiveTitle = null
            },
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
                    val deleteSrc = st.pendingExtractedSourceArchive
                        ?: if (canDeleteSource && deleteAfter) target else null
                    val extractedRoot = st.pendingExtractedApkRoot
                    st.pendingExtractedApkRoot = null
                    st.pendingExtractedSourceArchive = null
                    st.pendingExtractedArchiveTitle = null
                    st.apkInstallConfirm = null
                    launchApkSession(target, deleteSrc, extractedRoot)
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = {
                    st.apkInstallConfirm = null
                    st.pendingExtractedApkRoot?.deleteRecursively()
                    st.pendingExtractedApkRoot = null
                    st.pendingExtractedSourceArchive = null
                    st.pendingExtractedArchiveTitle = null
                }) { Text("Cancel") }
            },
        )
    }
    }
    DialogHost {
    st.winlatorExecutableCandidate?.let { candidate ->
        var titleSource by remember(candidate) {
            mutableStateOf(preferredWinlatorTitleSource(candidate))
        }
        var customTitle by remember(candidate) { mutableStateOf("") }
        var installerMode by remember(candidate) { mutableStateOf(false) }
        var executableDosPath by remember(candidate) { mutableStateOf("") }
        var containerPolicy by remember(candidate) {
            mutableStateOf(WinlatorApi.ContainerPolicy.SharedDefault)
        }
        val selectedTitle = selectedWinlatorTitle(candidate, titleSource, customTitle)

        suspend fun rollBackWinlatorSelection(): Boolean {
            val managedId = candidate.managedGameId ?: return false
            val rollback = candidate.managedRunnerRollback
            val restored = if (rollback != null) {
                restoreManagedRunnerConfiguration(
                    context.applicationContext,
                    managedId,
                    rollback,
                )
            } else {
                disableUnconfiguredWinlatorBinding(context.applicationContext, managedId)
            }
            st.apps = scanInstalledLibrary(context)
            return restored?.runnerBindings
                ?.filterIsInstance<ManagedRunnerBinding.Winlator>()
                ?.singleOrNull()
                ?.enabled == false
        }

        fun cancelWinlatorSetup() {
            st.winlatorExecutableCandidate = null
            candidate.managedGameId?.let {
                scope.launch {
                    val disabled = rollBackWinlatorSelection()
                    st.snackbarMsg = if (disabled) {
                        "Winlator setup cancelled; the previous engine choices were restored."
                    } else {
                        "Winlator setup cancelled; setup is still required before launch."
                    }
                }
            }
        }

        fun submitWinlatorGame() {
            if (selectedTitle.isBlank()) return
            val source = candidate.file
            val chosenTitle = selectedTitle
            val chosenInstallerMode = installerMode
            val chosenExecutableDosPath = executableDosPath.trim().takeIf { it.isNotBlank() }
            val chosenContainerPolicy = containerPolicy
            val chosenTitleSource = titleSource
            st.winlatorExecutableCandidate = null
            scope.launch {
                if (!source.isFile || !source.isAbsolute) {
                    st.snackbarMsg = "The selected Winlator executable is no longer accessible."
                    return@launch
                }
                val capabilitiesResult =
                    if (chosenContainerPolicy == WinlatorApi.ContainerPolicy.SharedDefault) {
                        WinlatorClient.requiredSharedContainerCapabilities(context.applicationContext)
                    } else {
                        WinlatorClient.requiredV2Capabilities(context.applicationContext)
                    }
                capabilitiesResult.getOrElse {
                    rollBackWinlatorSelection()
                    st.snackbarMsg = "Winlator Secure isn't ready: ${it.message}"
                    return@launch
                }
                val gameId = java.util.UUID.randomUUID().toString()
                val metadata = JSONObject()
                    .put("source", "agm-unified-installer")
                    .put("sourcePath", source.absolutePath)
                    .put("sourceArchiveName", candidate.archiveTitle ?: JSONObject.NULL)
                    .put("titleSource", chosenTitleSource.name.lowercase())
                    .put("importedAt", System.currentTimeMillis())
                    .toString()
                val operation = PendingWinlatorOperation(
                    kind = if (chosenInstallerMode) {
                        WinlatorOperationKind.CreateInstaller
                    } else {
                        WinlatorOperationKind.CreatePortable
                    },
                    gameId = gameId,
                    title = chosenTitle,
                    sourceArchiveToDelete = candidate.sourceArchiveToDelete,
                    managedGameId = candidate.managedGameId,
                    managedRunnerRollback = candidate.managedRunnerRollback,
                )
                val intent = if (chosenInstallerMode) {
                    WinlatorApi.createInstaller(
                        gameId = gameId,
                        title = chosenTitle,
                        installerPath = source.absolutePath,
                        executableDosPath = chosenExecutableDosPath,
                        metadata = metadata,
                        // Winlator requires async installers to launch during creation.
                        // Creation stays synchronous and never auto-launches from this Add flow.
                        async = false,
                        containerPolicy = chosenContainerPolicy,
                    )
                } else {
                    val parent = source.parentFile?.takeIf { it.isDirectory }
                    if (parent == null) {
                        st.snackbarMsg = "The portable executable has no accessible game folder."
                        return@launch
                    }
                    WinlatorApi.createPortable(
                        gameId = gameId,
                        title = chosenTitle,
                        gamePath = parent.absolutePath,
                        executablePath = source.absolutePath,
                        metadata = metadata,
                        containerPolicy = chosenContainerPolicy,
                    )
                }
                st.pendingWinlatorOperation = operation
                if (operation.managedGameId != null) {
                    ManagedWinlatorPendingStore.save(context.applicationContext, operation)
                }
                runCatching { winlatorResultLauncher.launch(intent) }
                    .onFailure {
                        candidate.managedGameId?.let { managedId ->
                            scope.launch {
                                rollBackWinlatorSelection()
                            }
                        }
                        st.pendingWinlatorOperation = null
                        ManagedWinlatorPendingStore.clear(context.applicationContext)
                        st.snackbarMsg = "Could not open Winlator: ${it.message}"
                    }
            }
        }

        AlertDialog(
            onDismissRequest = ::cancelWinlatorSetup,
            title = { Text("Add to Winlator") },
            text = {
                Column(
                    modifier = Modifier.dialogVerticalScroll(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        candidate.file.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Game name", style = MaterialTheme.typography.labelLarge)
                    listOfNotNull(
                        candidate.archiveTitle?.let { WinlatorTitleSource.Archive to it },
                        WinlatorTitleSource.Executable to candidate.executableTitle,
                        candidate.folderTitle?.let { WinlatorTitleSource.Folder to it },
                        candidate.readmeTitle?.let { WinlatorTitleSource.Readme to it },
                        WinlatorTitleSource.Other to "Enter a different name",
                    ).forEach { (source, value) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { titleSource = source },
                        ) {
                            RadioButton(
                                selected = titleSource == source,
                                onClick = { titleSource = source },
                            )
                            Column {
                                Text(source.label)
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (titleSource == WinlatorTitleSource.Other) {
                        OutlinedTextField(
                            value = customTitle,
                            onValueChange = { customTitle = it },
                            label = { Text("Custom game name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { installerMode = false },
                    ) {
                        RadioButton(selected = !installerMode, onClick = { installerMode = false })
                        Column {
                            Text("Portable game")
                            Text(
                                "Run this executable directly from shared storage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { installerMode = true },
                    ) {
                        RadioButton(selected = installerMode, onClick = { installerMode = true })
                        Column {
                            Text("Windows installer")
                            Text(
                                "Run this setup program inside Winlator.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "Winlator container",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault
                        },
                    ) {
                        RadioButton(
                            selected = containerPolicy == WinlatorApi.ContainerPolicy.SharedDefault,
                            onClick = { containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault },
                        )
                        Column {
                            Text("Shared default")
                            Text(
                                "Uses AGM's shared Wine container to save about 328 MiB per additional game.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            containerPolicy = WinlatorApi.ContainerPolicy.Isolated
                        },
                    ) {
                        RadioButton(
                            selected = containerPolicy == WinlatorApi.ContainerPolicy.Isolated,
                            onClick = { containerPolicy = WinlatorApi.ContainerPolicy.Isolated },
                        )
                        Column {
                            Text("Isolated")
                            Text(
                                "Creates a separate Wine container for games that need incompatible settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (installerMode) {
                        OutlinedTextField(
                            value = executableDosPath,
                            onValueChange = { executableDosPath = it },
                            label = { Text("Installed EXE path (optional)") },
                            placeholder = { Text("C:\\Program Files\\Game\\game.exe") },
                            supportingText = {
                                Text("Leave blank if unknown; AGM will ask for it before the first launch.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedTitle.isNotBlank(),
                    onClick = { submitWinlatorGame() },
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = ::cancelWinlatorSetup) { Text("Cancel") }
            },
        )
    }
    }
    DialogHost {
    st.gameSettingsTarget?.let { row ->
        val pkg = row.installed.packageName
        GameSettingsDialog(
            row = row,
            renPySaves = st.renPySaveAssociations[pkg].orEmpty(),
            rpgmSaves = st.rpgmSaveAssociations[pkg].orEmpty(),
            onDismiss = { st.gameSettingsTarget = null },
            onEditMapping = { st.dialogApp = row },
            onSetInstalledVersion = { st.manualVersionTarget = row },
            onSetInstalledDate = { st.manualDateTarget = row to mappedCatalogGame(row.mapping, st.catalogById) },
            onEditTags = { st.editTagsFor = row.installed },
            onOpenRenPySaves = { st.renPySaveEditorTarget = row },
            onAddRenPySaveFolder = { st.renPyAddFolderTarget = row },
            onOpenRpgmSaves = { st.rpgmSaveViewerTarget = row },
            onAddRpgmSaveFolder = { st.rpgmAddFolderTarget = row },
            onRevertBackup = { st.joiPlayBackupTarget = row to JoiPlayBackupAction.Revert },
            onDeleteBackup = { st.joiPlayBackupTarget = row to JoiPlayBackupAction.Delete },
            onWinlatorSubmit = { submissions ->
                if (submissions.isNotEmpty() && st.winlatorSubmissionQueue.isEmpty()) {
                    st.winlatorSubmissionQueue = submissions
                    st.winlatorQueueTick++
                }
            },
            onMoveWinlatorToIsolated = { st.winlatorIsolationConfirm = row.installed },
            onUpdateRunners = { bindings, enabled, defaultRunner ->
                st.managedEngineUpdateProgress = ManagedEngineDiscoveryProgress(
                    current = 0,
                    total = 1,
                    gameTitle = row.installed.label,
                    stage = "Applying engine choices",
                )
                scope.launch {
                    runCatching {
                        val id = requireNotNull(row.installed.managedGameId) {
                            "Managed-game id is missing."
                        }
                        val store = ManagedGameStore(context.applicationContext)
                        val game = requireNotNull(store.find(id))
                        val updated = store.update(
                            game.copy(
                                defaultRunner = defaultRunner,
                                runnerBindings = bindings.map { binding ->
                                    binding.withState(
                                        enabled = binding.kind in enabled,
                                        compatible = binding.compatible,
                                    )
                                },
                            ),
                        )
                        st.managedEngineUpdateProgress =
                            st.managedEngineUpdateProgress?.copy(
                                current = 1,
                                stage = "Refreshing the library",
                            )
                        st.apps = scanInstalledLibrary(context)
                        st.gameSettingsTarget = st.apps
                            .firstOrNull { it.managedGameId == id }
                            ?.let { refreshed ->
                                AppRow(refreshed, liveMappings()[refreshed.packageName], row.status)
                            }
                        val winlator = updated.runnerBindings
                            .filterIsInstance<ManagedRunnerBinding.Winlator>()
                            .singleOrNull()
                        if (
                            ManagedRunnerKind.Winlator in enabled &&
                            winlator?.managedId == null &&
                            !winlator?.executablePath.isNullOrBlank() &&
                            WinlatorClient.isInstalled(context.applicationContext)
                        ) {
                            st.managedEngineUpdateProgress =
                                st.managedEngineUpdateProgress?.copy(
                                    stage = "Creating the Winlator runner",
                                )
                            WinlatorClient.requiredSharedContainerCapabilities(
                                context.applicationContext,
                            ).getOrThrow()
                            val executable = java.io.File(requireNotNull(winlator?.executablePath))
                            require(executable.isFile) {
                                "The detected Winlator executable is no longer available."
                            }
                            val winlatorGameId = java.util.UUID.randomUUID().toString()
                            val metadata = JSONObject()
                                .put("source", "agm-managed-engine-settings")
                                .put("sourcePath", executable.absolutePath)
                                .put("titleSource", "agm")
                                .put("importedAt", System.currentTimeMillis())
                                .toString()
                            val operation = PendingWinlatorOperation(
                                kind = WinlatorOperationKind.CreatePortable,
                                gameId = winlatorGameId,
                                title = updated.label,
                                managedGameId = updated.id,
                                managedRunnerRollback = ManagedRunnerRollback(
                                    defaultRunner = game.defaultRunner,
                                    bindings = game.runnerBindings,
                                ),
                            )
                            st.pendingWinlatorOperation = operation
                            ManagedWinlatorPendingStore.save(context.applicationContext, operation)
                            st.gameSettingsTarget = null
                            winlatorResultLauncher.launch(
                                WinlatorApi.createPortable(
                                    gameId = winlatorGameId,
                                    title = updated.label,
                                    gamePath = requireNotNull(executable.parentFile).absolutePath,
                                    executablePath = executable.absolutePath,
                                    metadata = metadata,
                                    containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault,
                                ),
                            )
                        } else {
                            st.snackbarMsg = "Updated execution engines for ${updated.label}."
                        }
                    }.onFailure {
                        val pending = st.pendingWinlatorOperation
                        st.pendingWinlatorOperation = null
                        ManagedWinlatorPendingStore.clear(context.applicationContext)
                        if (
                            pending?.managedGameId != null &&
                            pending.managedRunnerRollback != null
                        ) {
                            scope.launch {
                                restoreManagedRunnerConfiguration(
                                    context.applicationContext,
                                    pending.managedGameId,
                                    pending.managedRunnerRollback,
                                )
                                st.apps = scanInstalledLibrary(context)
                            }
                        }
                        AppLog.e("EngineSettings", "Could not update ${row.installed.label}", it)
                        st.snackbarMsg = "Could not update engines: ${it.message}"
                    }
                    st.managedEngineUpdateProgress = null
                }
            },
            engineOperationBusy = st.managedEngineUpdateProgress != null,
            onRemoveFromList = {
                st.gameSettingsTarget = null
                scope.launch { repo.hide(pkg) }
                st.snackbarMsg = "Removed ${row.installed.label} from list"
            },
            onDelete = {
                st.gameSettingsTarget = null
                when (row.installed.source) {
                    AppSource.Managed -> {
                        if (row.installed.winlatorGameId != null) {
                            st.winlatorDeleteConfirm = row
                        } else {
                            st.joiPlayDeleteConfirm = row
                        }
                    }
                    AppSource.JoiPlay -> scope.launch {
                        val hasUri = JoiPlayScanner.getRootUri(context.applicationContext) != null
                        if (hasUri) st.joiPlayDeleteConfirm = row else st.joiPlayGrantAskFor = row
                    }
                    AppSource.Winlator -> st.winlatorDeleteConfirm = row
                    AppSource.Kirikiroid -> st.kirikiroidDeleteConfirm = row
                    AppSource.Android -> runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { st.snackbarMsg = "Uninstall failed: ${it.message}" }
                }
            },
        )
    }
    st.managedEngineUpdateProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Updating execution engines") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(progress.gameTitle, fontWeight = FontWeight.SemiBold)
                    Text(
                        progress.stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Please wait. Engine controls are disabled until this completes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
        )
    }
    st.winlatorConfigureTarget?.let { app ->
        WinlatorManagementDialog(
            app = app,
            onDismiss = { st.winlatorConfigureTarget = null },
            onSubmit = { submissions ->
                // Ignore a re-submit while a sequence is still draining so we never clobber an
                // in-flight queue. Apply every changed surface back-to-back via the queue driver.
                if (submissions.isNotEmpty() && st.winlatorSubmissionQueue.isEmpty()) {
                    st.winlatorSubmissionQueue = submissions
                    st.winlatorQueueTick++
                }
            },
        )
    }
    st.winlatorIsolationConfirm?.let { app ->
        AlertDialog(
            onDismissRequest = { st.winlatorIsolationConfirm = null },
            title = { Text("Move to isolated container?") },
            text = {
                Text(
                    "Winlator will clone the complete shared Wine container for ${app.label}. " +
                        "This can require substantial internal storage. Other games remain in the shared container."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val gameId = app.winlatorGameId ?: return@TextButton
                    st.winlatorIsolationConfirm = null
                    scope.launch {
                        val capabilities = WinlatorClient.requiredSharedContainerCapabilities(
                            context.applicationContext
                        ).getOrElse {
                            st.snackbarMsg = "Winlator Secure isn't ready: ${it.message}"
                            return@launch
                        }
                        if (!capabilities.moveGameToIsolated) {
                            st.snackbarMsg = "Winlator does not advertise move-to-isolated support."
                            return@launch
                        }
                        st.pendingWinlatorOperation = PendingWinlatorOperation(
                            WinlatorOperationKind.MoveToIsolated,
                            gameId,
                            app.label,
                        )
                        runCatching {
                            winlatorResultLauncher.launch(WinlatorApi.moveToIsolated(gameId))
                        }.onFailure {
                            st.pendingWinlatorOperation = null
                            st.snackbarMsg = "Could not open Winlator: ${it.message}"
                        }
                    }
                }) { Text("Clone and isolate") }
            },
            dismissButton = {
                TextButton(onClick = { st.winlatorIsolationConfirm = null }) { Text("Cancel") }
            },
        )
    }
    st.winlatorDeleteConfirm?.let { row ->
        AlertDialog(
            onDismissRequest = { st.winlatorDeleteConfirm = null },
            title = {
                Text(if (row.installed.source == AppSource.Managed) "Delete managed game?" else "Delete Winlator game?")
            },
            text = {
                Text(
                    if (row.installed.source == AppSource.Managed) {
                        "This removes every configured runner, asks Winlator to remove its managed binding, " +
                            "and permanently deletes ${row.installed.label}'s shared game folder. This cannot be undone."
                    } else if (row.installed.winlatorContainerShared) {
                        "This deletes only ${row.installed.label}'s managed-game record. " +
                            "The shared Winlator container and shared game or installer files are preserved."
                    } else {
                        "This deletes ${row.installed.label}'s managed-game record and reclaims its isolated " +
                            "Winlator container when no other record references it. Shared game and installer files are not deleted."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val app = row.installed
                    val gameId = app.winlatorGameId ?: return@TextButton
                    st.winlatorDeleteConfirm = null
                    if (
                        app.source == AppSource.Managed &&
                        !WinlatorClient.isInstalled(context.applicationContext)
                    ) {
                        scope.launch {
                            val outcome = runCatching {
                                deleteManagedGameFilesAndRecord(context.applicationContext, app)
                            }.onFailure {
                                AppLog.w("Delete", "Managed delete refused for ${app.label}", it)
                            }
                            st.snackbarMsg = if (outcome.getOrDefault(false)) {
                                st.apps = scanInstalledLibrary(context)
                                "Deleted ${app.label}."
                            } else {
                                outcome.exceptionOrNull()?.message
                                    ?: "Could not delete ${app.label}'s files."
                            }
                        }
                        return@TextButton
                    }
                    st.pendingWinlatorOperation = PendingWinlatorOperation(
                        WinlatorOperationKind.Delete,
                        gameId,
                        app.label,
                        managedGameId = app.managedGameId,
                        deleteSharedFiles = app.source == AppSource.Managed,
                    )
                    runCatching { winlatorResultLauncher.launch(WinlatorApi.delete(gameId)) }
                        .onFailure {
                            st.pendingWinlatorOperation = null
                            st.snackbarMsg = "Could not open Winlator: ${it.message}"
                        }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { st.winlatorDeleteConfirm = null }) { Text("Cancel") }
            },
        )
    }
    st.kirikiroidDeleteConfirm?.let { row ->
        AlertDialog(
            onDismissRequest = { st.kirikiroidDeleteConfirm = null },
            title = { Text("Delete Kirikiroid game?") },
            text = {
                Text(
                    "This permanently deletes ${row.installed.label}'s extracted game folder from storage. " +
                        "This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val app = row.installed
                    st.kirikiroidDeleteConfirm = null
                    val path = app.storagePath
                    if (path.isNullOrBlank()) {
                        st.snackbarMsg = "This game has no folder path to delete."
                        return@TextButton
                    }
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = java.io.File(path)
                                if (dir.isDirectory) dir.deleteRecursively() else false
                            }.getOrDefault(false)
                        }
                        if (deleted) {
                            GameStorageSizeWork.enqueueImmediate(context.applicationContext)
                            st.apps = scanInstalledLibrary(context)
                            st.snackbarMsg = "Deleted ${app.label}"
                        } else {
                            st.snackbarMsg = "Could not delete ${app.label}'s folder."
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { st.kirikiroidDeleteConfirm = null }) { Text("Cancel") }
            },
        )
    }
    if (st.joiplaySettingsOpen) {
        JoiPlaySettingsDialog(
            onDismiss = { st.joiplaySettingsOpen = false },
            onSourceChange = {}, onDestChange = {},
        )
    }
    if (st.joiPlayUpdatesDialogOpen) {
        JoiPlayDownloadsDialog(
            status = st.joiPlayUpdateStatus
                ?: JoiPlayUpdateChecker.Status(installedVersion = null, core = null, downloads = emptyList()),
            onOpenUrl = { url -> openLink(url) },
            onDismiss = { st.joiPlayUpdatesDialogOpen = false },
        )
    }
    }
    DialogHost {
    installDestinationPicker?.let { pending ->
        FolderPickerDialog(
            initialPath = pending.initialPath,
            onCancel = {
                st.extractTarget = pending.target
                extractConfirm = pending.archive to pending.currentRoot
                installDestinationPicker = null
            },
            onPick = { absolutePath ->
                val directory = java.io.File(absolutePath)
                val problem = when {
                    !directory.isDirectory -> "That destination folder no longer exists."
                    !directory.canRead() -> "AGM can't read that destination folder."
                    !directory.canWrite() -> "AGM can't write to that destination folder."
                    else -> null
                }
                if (problem != null) {
                    st.extractTarget = pending.target
                    extractConfirm = pending.archive to pending.currentRoot
                    installDestinationPicker = null
                    st.snackbarMsg = problem
                } else {
                    st.extractTarget = pending.target
                    extractConfirm = pending.archive to ArchiveExtractor.ExtractRoot.FileRoot(directory)
                    installDestinationPicker = null
                    AppLog.i(
                        "Install",
                        "Per-install destination set to ${directory.absolutePath} for target=${pending.target}",
                    )
                }
            },
        )
    }
    st.pendingArchiveDestination?.let { pending ->
        AlertDialog(
            onDismissRequest = { st.pendingArchiveDestination = null },
            title = { Text("Choose the shared games folder") },
            text = {
                Text(
                    "AGM uses the default destination from Non-Android game settings for extracted JoiPlay and Winlator games. " +
                        if (
                            pending.target == InstallRouting.Target.Winlator ||
                            pending.target == InstallRouting.Target.Managed ||
                            pending.target == InstallRouting.Target.Auto
                        ) {
                            "Choose a folder in internal shared storage so Winlator can access its normal filesystem path."
                        } else {
                            "AGM will extract this game into a new subfolder there."
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = { installGamesRootPicker.launch(null) }) {
                    Text("Choose folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { st.pendingArchiveDestination = null }) { Text("Cancel") }
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
            title = {
                Text(
                    when {
                        st.bucketExtract == InstallRouting.Bucket.Videos -> "Extract to Videos folder?"
                        st.bucketExtract == InstallRouting.Bucket.Other -> "Extract to Other folder?"
                        st.extractTarget == InstallRouting.Target.Android -> "Extract Android package?"
                        st.extractTarget == InstallRouting.Target.Winlator -> "Extract Windows game?"
                        st.extractTarget == InstallRouting.Target.JoiPlay -> "Extract JoiPlay game?"
                        st.extractTarget == InstallRouting.Target.Managed -> "Extract managed game?"
                        st.extractTarget == InstallRouting.Target.Auto -> "Extract and detect game type?"
                        else -> "Extract archive?"
                    }
                )
            },
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
                        if (st.extractTarget != InstallRouting.Target.Android) {
                            TextButton(
                                onClick = {
                                    val initialPath = when (root) {
                                        is ArchiveExtractor.ExtractRoot.FileRoot -> root.file.absolutePath
                                        is ArchiveExtractor.ExtractRoot.Saf ->
                                            SharedGamesRoot.resolveTreeUri(context.applicationContext, root.doc.uri)
                                                ?.absolutePath
                                                ?: android.os.Environment.getExternalStorageDirectory().absolutePath
                                    }
                                    installDestinationPicker = PendingInstallDestinationSelection(
                                        archive = file,
                                        target = st.extractTarget ?: InstallRouting.Target.Auto,
                                        currentRoot = root,
                                        initialPath = initialPath,
                                    )
                                    extractConfirm = null
                                },
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Choose a different folder\u2026")
                            }
                        }
                    }
                    Text(
                        if (st.extractTarget == InstallRouting.Target.Android) {
                            "AGM uses temporary storage while locating the Android package."
                        } else {
                            "A subfolder will be created here for this game. Changing it affects only this install."
                        },
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
                    st.pendingExtractedSourceArchive = src
                    extractConfirm = null
                    reportInstallRefusal(
                        extractFlow.start(
                            Uri.fromFile(file),
                            root,
                            cleanupDestinationOnAbort = st.extractTarget == InstallRouting.Target.Android,
                        ),
                    )
                }) { Text("Extract") }
            },
            dismissButton = {
                TextButton(onClick = { extractConfirm = null }) { Text("Cancel") }
            },
        )
    }

    }
    DialogHost {
    if (st.savedPasswordsOpen) {
        SavedPasswordsDialog(onClose = { st.savedPasswordsOpen = false })
    }
    st.managedRunnerInspection?.let { inspection ->
        val confirmManaged: (Set<ManagedRunnerKind>, ManagedRunnerKind) -> Unit =
            { enabled, defaultRunner ->
                val progressTitle = st.pendingExtractedArchiveTitle ?: inspection.title
                st.managedEngineUpdateProgress = ManagedEngineDiscoveryProgress(
                    current = 0,
                    total = 1,
                    gameTitle = progressTitle,
                    stage = "Preparing the managed game",
                )
                st.managedRunnerInspection = null
                scope.launch {
                    var awaitingWinlatorResult = false
                    var persistedGame: ManagedGame? = null
                    try {
                        kotlinx.coroutines.yield()
                        val detectedLanguage = if (ManagedRunnerKind.Winlator in enabled) {
                            st.managedEngineUpdateProgress =
                                st.managedEngineUpdateProgress?.copy(stage = "Detecting the game language")
                            runCatching { GameLanguageDetector.detect(inspection.root) }
                                .onFailure {
                                    AppLog.w(
                                        "GameLanguage",
                                        "Language detection failed for ${inspection.root.absolutePath}",
                                        it,
                                    )
                                }
                                .getOrNull()
                        } else {
                            null
                        }
                        val preferredLanguage = preferredGameLanguage(
                            inspection.title,
                            detectedLanguage?.languageTag,
                        )
                        st.managedEngineUpdateProgress =
                            st.managedEngineUpdateProgress?.copy(stage = "Adding the game to AGM")
                        val game = ManagedGameStore(context.applicationContext).upsert(
                            ManagedGameDiscovery.draft(
                                inspection = inspection,
                                enabledRunners = enabled,
                                defaultRunner = defaultRunner,
                            ),
                        )
                        persistedGame = game
                        st.managedInstallSourceArchive?.let { source ->
                            runCatching { if (source.exists()) source.delete() }
                                .onFailure {
                                    AppLog.w(
                                        "ManagedInstall",
                                        "Could not delete source ${source.absolutePath}",
                                        it,
                                    )
                                }
                        }
                        st.managedInstallSourceArchive = null
                        st.managedInstallOwnsFiles = false
                        st.apps = (st.apps.filterNot { it.managedGameId == game.id } + game.toInstalledApp())
                            .sortedBy { it.label.lowercase() }
                        st.pendingInstallCatalogGame?.let { catalogGame ->
                            associateCatalogDownload(game.packageName, catalogGame, game.toInstalledApp())
                            st.pendingInstallCatalogGame = null
                        }
                        GameStorageSizeWork.enqueueImmediate(context.applicationContext)

                        val winlator = game.runnerBindings
                            .filterIsInstance<ManagedRunnerBinding.Winlator>()
                            .singleOrNull()
                        if (
                            winlator?.enabled == true &&
                            winlator.managedId == null &&
                            !winlator.executablePath.isNullOrBlank() &&
                            WinlatorClient.isInstalled(context.applicationContext)
                        ) {
                            st.managedEngineUpdateProgress =
                                st.managedEngineUpdateProgress?.copy(stage = "Opening Winlator setup")
                            val executable = java.io.File(requireNotNull(winlator.executablePath))
                            WinlatorClient.requiredSharedContainerCapabilities(
                                context.applicationContext,
                            ).getOrThrow()
                            val winlatorGameId = java.util.UUID.randomUUID().toString()
                            val metadata = JSONObject()
                                .put("source", "agm-managed-install")
                                .put("sourcePath", executable.absolutePath)
                                .put("titleSource", "agm")
                                .put("importedAt", System.currentTimeMillis())
                                .apply {
                                    preferredLanguage?.let { put("detectedLanguage", it) }
                                    detectedLanguage?.let {
                                        put("detectedLanguageConfidence", it.confidence.toDouble())
                                    }
                                }
                                .toString()
                            val operation = PendingWinlatorOperation(
                                kind = WinlatorOperationKind.CreatePortable,
                                gameId = winlatorGameId,
                                title = game.label,
                                managedGameId = game.id,
                                detectedLanguage = preferredLanguage,
                            )
                            st.pendingWinlatorOperation = operation
                            ManagedWinlatorPendingStore.save(context.applicationContext, operation)
                            winlatorResultLauncher.launch(
                                WinlatorApi.createPortable(
                                    gameId = winlatorGameId,
                                    title = game.label,
                                    gamePath = requireNotNull(executable.parentFile).absolutePath,
                                    executablePath = executable.absolutePath,
                                    metadata = metadata,
                                    containerPolicy = WinlatorApi.ContainerPolicy.SharedDefault,
                                ),
                            )
                            awaitingWinlatorResult = true
                            st.snackbarMsg = "Added ${game.label}; creating its Winlator runner."
                        } else {
                            st.snackbarMsg = "Added ${game.label} to AGM."
                        }
                    } catch (error: Exception) {
                        val game = persistedGame
                        if (st.pendingWinlatorOperation != null) {
                            st.pendingWinlatorOperation = null
                            ManagedWinlatorPendingStore.clear(context.applicationContext)
                        }
                        game?.id?.let { managedId ->
                            disableUnconfiguredWinlatorBinding(
                                context.applicationContext,
                                managedId,
                            )?.let { repaired ->
                                st.apps = (
                                    st.apps.filterNot { it.managedGameId == repaired.id } +
                                        repaired.toInstalledApp()
                                    ).sortedBy { it.label.lowercase() }
                            }
                        }
                        val message = if (game == null) {
                            "Could not add managed game: ${error.message}"
                        } else if (st.pendingInstallCatalogGame != null) {
                            "Added ${game.label}, but AGM couldn't link it to the catalog: ${error.message}"
                        } else {
                            "Added ${game.label}, but Winlator setup failed: ${error.message}"
                        }
                        if (!pauseAutomaticBatch(message)) st.snackbarMsg = message
                    } finally {
                        if (!awaitingWinlatorResult) {
                            st.managedEngineUpdateProgress = null
                        }
                    }
                }
            }
        val runnerBatchRunId = unattendedBatchRunId()
        if (runnerBatchRunId != null) {
            LaunchedEffect(inspection) {
                val compatible = inspection.candidates
                    .filter { it.compatible && it.available && it.binding != null }
                    .mapTo(linkedSetOf()) { it.kind }
                val defaultRunner = inspection.recommendedRunner.takeIf { it in compatible }
                    ?: compatible.singleOrNull()
                if (defaultRunner == null) {
                    pauseUnattendedRun(
                        runnerBatchRunId,
                        "AGM could not choose a verified available runner for ${inspection.title}.",
                    )
                } else {
                    confirmManaged(compatible, defaultRunner)
                }
            }
        } else {
            ManagedRunnerSelectionDialog(
                title = st.pendingExtractedArchiveTitle ?: inspection.title,
                inspection = inspection,
                onConfirm = confirmManaged,
                onDismiss = {
                    st.managedRunnerInspection = null
                    st.managedInstallSourceArchive = null
                    st.pendingInstallCatalogGame = null
                    val deleteFiles = st.managedInstallOwnsFiles
                    st.managedInstallOwnsFiles = false
                    if (deleteFiles) {
                        scope.launch(Dispatchers.IO) { inspection.root.deleteRecursively() }
                    }
                },
            )
        }
    }
    }
    DialogHost {
    if (extractFlow.inProgress && !progressIsMinimized) {
        ExtractProgressDialog(
            archiveName = extractFlow.archiveName ?: "archive",
            phase = extractFlow.phase,
            progress = extractFlow.progress,
            subStatus = extractFlow.autoTryStatus,
            onCancel = { extractFlow.cancelInProgress() },
            onMinimize = { minimizeProgress() },
        )
    }
    extractFlow.passwordPromptFor?.let {
        PasswordPromptDialog(
            archiveName = extractFlow.archiveName ?: "archive",
            onCancel = { extractFlow.cancelInProgress() },
            onSubmit = { extractFlow.submitPassword(it) },
            message = when {
                extractFlow.savedPasswordsFailed ->
                    "No saved password worked for '${extractFlow.archiveName ?: "this archive"}'. Enter a password to try."
                extractFlow.lastPasswordWrong -> "That password didn't work. Try another."
                else -> null
            },
        )
    }
    extractFlow.errorMessage?.let { msg ->
        val extractErrorBatchRunId = unattendedBatchRunId()
        if (extractErrorBatchRunId != null) {
            LaunchedEffect(msg) {
                st.pendingExtractedSourceArchive = null
                st.pendingExtractedArchiveTitle = null
                extractFlow.acknowledgeError()
                if (!pauseUnattendedRun(extractErrorBatchRunId, msg)) st.snackbarMsg = msg
            }
        } else AlertDialog(
            onDismissRequest = {
                st.pendingExtractedSourceArchive = null
                st.pendingExtractedArchiveTitle = null
                extractFlow.acknowledgeError()
            },
            title = { Text("Extraction failed") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    st.pendingExtractedSourceArchive = null
                    st.pendingExtractedArchiveTitle = null
                    extractFlow.acknowledgeError()
                }) { Text("OK") }
            },
        )
    }
    extractFlow.extractedRoot?.let { root ->
        st.bucketExtract?.let { bucket ->
            LaunchedEffect(root) {
                st.pendingExtractedSourceArchive?.let { src -> runCatching { if (src.exists()) src.delete() } }
                val label = when (bucket) {
                    InstallRouting.Bucket.Videos -> "Videos"
                    InstallRouting.Bucket.Other -> "Other"
                }
                st.snackbarMsg = "Extracted to your $label folder (not added to the library)."
                st.pendingExtractedSourceArchive = null
                st.pendingExtractedArchiveTitle = null
                st.bucketExtract = null
                st.extractTarget = null
                extractFlow.acknowledgeResult()
            }
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Finishing up") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        Text("Saving files to your folder…")
                    }
                },
                confirmButton = { },
            )
            return@let
        }
        val target = st.extractTarget ?: InstallRouting.Target.JoiPlay
        if (target == InstallRouting.Target.Auto) {
            LaunchedEffect(root) {
                val entries = runCatching { ExtractedContentInspector.listFiles(root) }
                    .getOrElse {
                        ArchiveExtractor.deleteExtractedRoot(root)
                        if (
                            target != InstallRouting.Target.Managed &&
                            target != InstallRouting.Target.JoiPlay
                        ) {
                            extractFlow.acknowledgeResult()
                            st.extractTarget = null
                        }
                        nestedArchiveDepth = 0
                        st.pendingExtractedSourceArchive = null
                        st.pendingExtractedArchiveTitle = null
                        st.snackbarMsg = "Could not inspect extracted files: ${it.message}"
                        return@LaunchedEffect
                    }
                when (val route = InstallRouting.routeArchive(entries)) {
                    is InstallRouting.ArchiveRoute.Extract -> {
                        nestedArchiveDepth = 0
                        st.extractTarget = if (route.target == InstallRouting.Target.Android) {
                            InstallRouting.Target.Android
                        } else {
                            InstallRouting.Target.Managed
                        }
                    }
                    is InstallRouting.ArchiveRoute.ExtractNested -> {
                        val fileRoot = root as? ArchiveExtractor.ExtractRoot.FileRoot
                        val nestedArchive = ExtractedContentInspector.resolveFile(root, route.relativePath)
                        val destination = fileRoot?.file?.parentFile
                        val tooDeep = nestedArchiveDepth >= MAX_NESTED_ARCHIVE_DEPTH
                        if (tooDeep ||
                            nestedArchive == null ||
                            destination == null
                        ) {
                            ArchiveExtractor.deleteExtractedRoot(root)
                            extractFlow.acknowledgeResult()
                            st.extractTarget = null
                            nestedArchiveDepth = 0
                            st.pendingExtractedSourceArchive = null
                            st.pendingExtractedArchiveTitle = null
                            st.snackbarMsg =
                                if (tooDeep) {
                                    "This archive contains too many nested archive layers."
                                } else {
                                    "AGM couldn't safely open the nested archive."
                                }
                            return@LaunchedEffect
                        }
                        nestedArchiveDepth++
                        extractFlow.acknowledgeResult()
                        reportInstallRefusal(
                            extractFlow.start(
                                archiveUri = Uri.fromFile(nestedArchive),
                                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination),
                                intermediateRootToDeleteAfterCopy = root,
                            ),
                        )
                    }
                    is InstallRouting.ArchiveRoute.Unsupported -> {
                        ArchiveExtractor.deleteExtractedRoot(root)
                        extractFlow.acknowledgeResult()
                        st.extractTarget = null
                        nestedArchiveDepth = 0
                        st.pendingExtractedSourceArchive = null
                        st.pendingExtractedArchiveTitle = null
                        st.snackbarMsg = route.message
                    }
                    InstallRouting.ArchiveRoute.ChooseRunner -> {
                        nestedArchiveDepth = 0
                        st.extractTarget = InstallRouting.Target.Managed
                    }
                    InstallRouting.ArchiveRoute.HtmlOnly -> {
                        nestedArchiveDepth = 0
                        st.extractTarget = InstallRouting.Target.Managed
                    }
                    InstallRouting.ArchiveRoute.Videos, InstallRouting.ArchiveRoute.Other -> {
                        val bucket =
                            if (route is InstallRouting.ArchiveRoute.Videos) InstallRouting.Bucket.Videos
                            else InstallRouting.Bucket.Other
                        val label = if (bucket == InstallRouting.Bucket.Videos) "Videos" else "Other"
                        nestedArchiveDepth = 0
                        val bucketDir = resolveBucketDir(bucket)
                        val fileRoot = (root as? ArchiveExtractor.ExtractRoot.FileRoot)?.file
                        if (bucketDir == null) {
                            ArchiveExtractor.deleteExtractedRoot(root)
                            extractFlow.acknowledgeResult()
                            st.extractTarget = null
                            st.pendingExtractedSourceArchive = null
                            st.pendingExtractedArchiveTitle = null
                            st.snackbarMsg = "Set a $label folder in Non-Android game settings first."
                            return@LaunchedEffect
                        }
                        // Relocate the extracted content into the configured bucket root, then
                        // clean up the source. Not added to the library.
                        val moved = if (fileRoot != null) relocateDirInto(fileRoot, bucketDir) else null
                        st.pendingExtractedSourceArchive?.let { src -> runCatching { if (src.exists()) src.delete() } }
                        st.extractTarget = null
                        st.pendingExtractedSourceArchive = null
                        st.pendingExtractedArchiveTitle = null
                        extractFlow.acknowledgeResult()
                        st.snackbarMsg = if (moved != null) {
                            "Saved to your $label folder (not added to the library)."
                        } else {
                            "Extracted, but AGM couldn't move it into your $label folder."
                        }
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Detecting extracted game") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        Text("Checking the extracted files for Android, Winlator, or JoiPlay content…")
                    }
                },
                confirmButton = { },
            )
            return@let
        }
        if (target == InstallRouting.Target.Kirikiroid) {
            LaunchedEffect(root) {
                finalizeKirikiroidExtraction(
                    root = root,
                    sourceArchiveToDelete = st.pendingExtractedSourceArchive,
                    title = st.pendingExtractedArchiveTitle ?: extractFlow.archiveName,
                )
                nestedArchiveDepth = 0
                st.pendingExtractedSourceArchive = null
                st.pendingExtractedArchiveTitle = null
                st.extractTarget = null
                extractFlow.acknowledgeResult()
            }
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Adding to Kirikiroid2") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        Text("Saving files to your Kirikiroid folder…")
                    }
                },
                confirmButton = { },
            )
            return@let
        }
        val apkAutoBatchRunId = unattendedBatchRunId()
        if (target == InstallRouting.Target.Android && apkAutoBatchRunId != null) {
            LaunchedEffect(root) {
                val apkPaths = runCatching {
                    ExtractedContentInspector.listFiles(root)
                        .filter { it.substringAfterLast('.', "").equals("apk", ignoreCase = true) }
                }.getOrElse {
                    pauseUnattendedRun(
                        apkAutoBatchRunId,
                        "Could not inspect extracted APK files: ${it.message}",
                    )
                    return@LaunchedEffect
                }
                val apk = apkPaths.singleOrNull()?.let {
                    ExtractedContentInspector.resolveFile(root, it)
                }
                if (apk == null) {
                    pauseUnattendedRun(
                        apkAutoBatchRunId,
                        "Expected exactly one APK in the archive, but found ${apkPaths.size}.",
                    )
                    return@LaunchedEffect
                }
                val sourceArchive = st.pendingExtractedSourceArchive
                val cleanupRoot = apkExtractionCleanupDirectory(root)
                st.pendingExtractedSourceArchive = null
                st.pendingExtractedArchiveTitle = null
                extractFlow.acknowledgeResult()
                st.extractTarget = null
                launchApkSession(apk, sourceArchive, cleanupRoot)
            }
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Preparing APK") },
                text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
                confirmButton = { },
            )
            return@let
        }
        ExtractedFileBrowser(
            rootName = extractFlow.archiveName ?: "Extracted game",
            root = root,
            mode = when (target) {
                InstallRouting.Target.Android -> ExtractTargetMode.ApkInstall
                InstallRouting.Target.Winlator -> ExtractTargetMode.Winlator
                InstallRouting.Target.JoiPlay,
                InstallRouting.Target.Managed -> ExtractTargetMode.Managed
                InstallRouting.Target.Kirikiroid -> error("Kirikiroid target finalizes without the file browser.")
                InstallRouting.Target.Auto -> error("Auto target must be resolved before browsing.")
            },
            onCancel = {
                if (target == InstallRouting.Target.Android) {
                    apkExtractionCleanupDirectory(root)?.deleteRecursively()
                }
                st.pendingExtractedSourceArchive = null
                st.pendingExtractedArchiveTitle = null
                extractFlow.acknowledgeResult()
                st.extractTarget = null
            },
            onPick = { uri ->
                val selectedName = uri.path?.substringAfterLast('/') ?: uri.lastPathSegment.orEmpty()
                val selectedExt = selectedName.substringAfterLast('.', "").lowercase()
                val validSelection = when (target) {
                    InstallRouting.Target.Android -> selectedExt == "apk"
                    InstallRouting.Target.Winlator -> selectedExt == "exe"
                    InstallRouting.Target.JoiPlay,
                    InstallRouting.Target.Managed ->
                        InstallRouting.isManagedLaunchCandidate(selectedName)
                    InstallRouting.Target.Kirikiroid -> false
                    InstallRouting.Target.Auto -> false
                }
                if (!validSelection) {
                    st.snackbarMsg = when (target) {
                        InstallRouting.Target.Android -> "Select an APK file."
                        InstallRouting.Target.Winlator -> "Select a Windows .exe file."
                        InstallRouting.Target.JoiPlay,
                        InstallRouting.Target.Managed -> "Select the primary launch file for this game."
                        InstallRouting.Target.Kirikiroid -> "Kirikiroid games are added automatically."
                        InstallRouting.Target.Auto -> "AGM is still detecting the extracted game type."
                    }
                    return@ExtractedFileBrowser
                }
                extractFlow.acknowledgeResult()
                st.extractTarget = null
                when (target) {
                    InstallRouting.Target.Android -> {
                        val path = uri.path
                        if (uri.scheme == "file" && path != null) {
                            st.pendingExtractedApkRoot = apkExtractionCleanupDirectory(root)
                            st.apkInstallConfirm = java.io.File(path)
                        } else {
                            scope.launch {
                                val intent = ApkInstaller.buildIntentForUri(context, uri)
                                if (intent == null) {
                                    st.snackbarMsg = "Can't install from that URI."
                                } else {
                                    val catalogGame = st.pendingInstallCatalogGame
                                    st.pendingInstallCatalogGame = null
                                    st.apkPostInstall = ApkPostInstall(null, null, catalogGame = catalogGame)
                                    runCatching { apkInstallResultLauncher.launch(intent) }
                                        .onFailure {
                                            st.apkPostInstall = null
                                            st.snackbarMsg = "Installer failed: ${it.message}"
                                        }
                                }
                            }
                        }
                    }
                    InstallRouting.Target.Winlator -> {
                        val path = uri.path
                        if (uri.scheme != "file" || path.isNullOrBlank()) {
                            st.pendingExtractedSourceArchive = null
                            st.pendingExtractedArchiveTitle = null
                            st.snackbarMsg = "Winlator requires a normal shared-storage file path."
                        } else {
                            val sourceArchive = st.pendingExtractedSourceArchive
                            val archiveTitle = st.pendingExtractedArchiveTitle
                            st.pendingExtractedSourceArchive = null
                            st.pendingExtractedArchiveTitle = null
                            prepareWinlatorExecutable(
                                file = java.io.File(path),
                                sourceArchiveToDelete = sourceArchive,
                                archiveTitle = archiveTitle,
                            )
                        }
                    }
                    InstallRouting.Target.JoiPlay,
                    InstallRouting.Target.Managed -> {
                        val path = uri.path
                        val fileRoot = root as? ArchiveExtractor.ExtractRoot.FileRoot
                        if (uri.scheme != "file" || path.isNullOrBlank() || fileRoot == null) {
                            st.snackbarMsg = "Managed games require a normal shared-storage path."
                            return@ExtractedFileBrowser
                        }
                        scope.launch {
                            val inspection = runCatching {
                                withContext(Dispatchers.IO) {
                                    ManagedGameDiscovery.inspect(
                                        root = fileRoot.file,
                                        selectedLaunchFile = java.io.File(path),
                                        joiPlayAvailable = joiPlayIsInstalled(),
                                        winlatorAvailable = WinlatorClient.isInstalled(context.applicationContext),
                                        kirikiroidAvailable = KirikiroidLauncher.isInstalled(context),
                                    )
                                }
                            }.getOrElse {
                                st.snackbarMsg = "Could not validate this game: ${it.message}"
                                return@launch
                            }
                            st.managedRunnerInspection = inspection
                            st.managedInstallSourceArchive = st.pendingExtractedSourceArchive
                            st.managedInstallOwnsFiles = true
                            st.pendingExtractedSourceArchive = null
                            st.pendingExtractedArchiveTitle = null
                            extractFlow.acknowledgeResult()
                            st.extractTarget = null
                        }
                    }
                    InstallRouting.Target.Kirikiroid -> Unit
                    InstallRouting.Target.Auto -> Unit
                }
            },
        )
    }
    }
    DialogHost {
    if (upgradeFlow.inProgress && !progressIsMinimized) {
        ExtractProgressDialog(
            archiveName = upgradeFlow.archiveName ?: "archive",
            phase = upgradeFlow.phase,
            progress = upgradeFlow.progress,
            subStatus = upgradeFlow.autoTryStatus,
            onCancel = { upgradeFlow.cancelInProgress() },
            cancelEnabled = upgradeFlow.cancellationAllowed,
            onMinimize = { minimizeProgress() },
        )
    }
    upgradeFlow.passwordPromptFor?.let {
        PasswordPromptDialog(
            archiveName = upgradeFlow.archiveName ?: "archive",
            onCancel = { upgradeFlow.cancelPasswordPrompt() },
            onSubmit = { upgradeFlow.submitPassword(it) },
            message = when {
                upgradeFlow.savedPasswordsFailed ->
                    "No saved password worked for '${upgradeFlow.archiveName ?: "this archive"}'. Enter a password to try."
                upgradeFlow.lastPasswordWrong -> "That password didn't work. Try another."
                else -> null
            },
        )
    }
    // A failed upgrade during an automatic bulk run is handled like a failed extraction and like a
    // cancellation: acknowledged for the flow and turned into a pause carrying the exact error. A
    // modal here would either stall an unattended run or — once dismissed — let the driver settle
    // the item as Done, reporting an upgrade that never happened.
    upgradeFlow.errorMessage?.let { msg ->
        val upgradeErrorBatchRunId = unattendedBatchRunId()
        if (upgradeErrorBatchRunId != null) {
            LaunchedEffect(msg) {
                val item = st.batch?.current?.name
                upgradeFlow.acknowledgeError()
                val paused = ManagedUpgradeFailure.bulkMessage(item, msg)
                if (!pauseUnattendedRun(upgradeErrorBatchRunId, paused)) st.snackbarMsg = msg
            }
        } else AlertDialog(
            onDismissRequest = { upgradeFlow.acknowledgeError() },
            title = { Text("Upgrade failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { upgradeFlow.acknowledgeError() }) { Text("OK") } },
        )
    }
    // A cancelled update is neither a success nor a failure: nothing was changed. A bulk run must
    // stop on it rather than march on, because the user asked AGM to stop.
    upgradeFlow.cancellationNotice?.let { msg ->
        val upgradeCancelBatchRunId = unattendedBatchRunId()
        if (upgradeCancelBatchRunId != null) {
            LaunchedEffect(msg) {
                val item = st.batch?.current?.name
                upgradeFlow.acknowledgeCancellation()
                val paused = ManagedUpgradeCancellation.bulkMessage(item, msg)
                if (!pauseUnattendedRun(upgradeCancelBatchRunId, paused)) st.snackbarMsg = msg
            }
        } else AlertDialog(
            onDismissRequest = { upgradeFlow.acknowledgeCancellation() },
            title = { Text("Update cancelled") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { upgradeFlow.acknowledgeCancellation() }) { Text("OK") }
            },
        )
    }
    upgradeFlow.result?.let { result ->
        var libraryRefreshed by remember(result) { mutableStateOf(false) }
        LaunchedEffect(result) {
            st.apps = scanInstalledLibrary(context)
            GameStorageSizeWork.enqueueImmediate(context.applicationContext)
            libraryRefreshed = true
            if (st.upgradeGuidanceDismissed) {
                val cleanup = if (result.oldFolderRenamed) {
                    "Old folder renamed for deletion: ${result.oldFolder}"
                } else {
                    "Old folder could NOT be renamed and is still at: ${result.oldFolder}"
                }
                st.snackbarMsg = "Upgraded ${result.label}. $cleanup"
                upgradeFlow.acknowledgeResult()
            }
        }
        val refreshed = st.apps.firstOrNull { it.managedGameId == result.managedGameId }
        val cleanupLine = if (result.oldFolderRenamed) {
            "Old folder renamed for deletion: ${result.oldFolder}"
        } else {
            "Old folder could NOT be renamed and is still at: ${result.oldFolder}"
        }
        val archiveLine = result.sourceArchiveName?.let {
            ManagedUpgradeSourceArchive.summaryLine(result.sourceArchiveOutcome, it)
        }
        val launchUpgraded: () -> Unit = launch@{
            val app = refreshed
            if (app == null) {
                st.snackbarMsg = "AGM is still refreshing ${result.label}; launch it from the library."
                return@launch
            }
            when (app.managedDefaultRunner ?: ManagedRunnerKind.JoiPlay) {
                ManagedRunnerKind.JoiPlay -> handleJoiPlayLaunch(
                    context,
                    app,
                    onMessage = { st.snackbarMsg = it },
                    onPluginRequired = { st.pluginLaunchRequest = it },
                )
                ManagedRunnerKind.Kirikiroid -> launchKirikiroid(app)
                ManagedRunnerKind.Winlator -> {
                    if (app.winlatorGameId == null) {
                        st.winlatorConfigureTarget = app
                    } else {
                        launchWinlatorWithPreflight(app)
                    }
                }
            }
        }
        if (st.upgradeGuidanceDismissed) {
            Unit
        } else {
            var dontShowAgain by remember(result) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = {
                    if (libraryRefreshed) upgradeFlow.acknowledgeResult()
                },
                title = { Text("Upgrade complete") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${result.label} now runs from its new folder. Launch it to confirm the update, " +
                                "then delete the old folder.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("New: ${result.newFolder}", style = MaterialTheme.typography.bodySmall)
                        Text(cleanupLine, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Save data copied: ${result.saveItemsCopied}" +
                                if (result.saveItemsCopied == 0) " (no saves were found)." else ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        archiveLine?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        result.warnings.forEach { warning ->
                            Text(
                                warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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
                    TextButton(
                        enabled = libraryRefreshed,
                        onClick = {
                            if (dontShowAgain) {
                                st.upgradeGuidanceDismissed = true
                                scope.launch {
                                    JoiPlaySettingsStore.setUpgradeGuidanceDismissed(
                                        context.applicationContext,
                                        true,
                                    )
                                }
                            }
                            launchUpgraded()
                            upgradeFlow.acknowledgeResult()
                        },
                    ) { Text("Launch") }
                },
                dismissButton = {
                    TextButton(
                        enabled = libraryRefreshed,
                        onClick = {
                            if (dontShowAgain) {
                                st.upgradeGuidanceDismissed = true
                                scope.launch {
                                    JoiPlaySettingsStore.setUpgradeGuidanceDismissed(
                                        context.applicationContext,
                                        true,
                                    )
                                }
                            }
                            upgradeFlow.acknowledgeResult()
                        },
                    ) { Text("Done") }
                },
            )
        }
    }

    }
    DialogHost {
    if (patchFlow.inProgress && !progressIsMinimized) {
        ExtractProgressDialog(
            archiveName = patchFlow.archiveName ?: "patch",
            phase = patchFlow.phase,
            progress = patchFlow.progress,
            subStatus = patchFlow.autoTryStatus,
            onCancel = { patchFlow.cancelInProgress() },
            cancelEnabled = patchFlow.cancellationAllowed,
            onMinimize = { minimizeProgress() },
        )
    }
    patchFlow.passwordPromptFor?.let {
        PasswordPromptDialog(
            archiveName = patchFlow.archiveName ?: "patch",
            onCancel = { patchFlow.cancelPasswordPrompt() },
            onSubmit = { patchFlow.submitPassword(it) },
            message = when {
                patchFlow.savedPasswordsFailed ->
                    "No saved password worked for '${patchFlow.archiveName ?: "this patch"}'. Enter a password to try."
                patchFlow.lastPasswordWrong -> "That password didn't work. Try another."
                else -> null
            },
        )
    }
    patchFlow.preview?.let { preview ->
        PatchInstallPreviewDialog(
            preview = preview,
            acknowledged = patchFlow.overrideAcknowledged,
            onAcknowledgedChange = { patchFlow.acknowledgeOverride(it) },
            onInstall = { reportInstallRefusal(patchFlow.confirmInstall()) },
            onCancel = { patchFlow.cancelPreview() },
        )
    }
    patchFlow.refusal?.let { report ->
        PatchRefusalDialog(report = report, onDismiss = { patchFlow.acknowledgeRefusal() })
    }
    patchFlow.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { patchFlow.acknowledgeError() },
            title = { Text("Patch install failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { patchFlow.acknowledgeError() }) { Text("OK") } },
        )
    }
    patchFlow.result?.let { installed ->
        LaunchedEffect(installed) {
            st.apps = scanInstalledLibrary(context)
            GameStorageSizeWork.enqueueImmediate(context.applicationContext)
        }
        val record = patchFlow.usableRollbackPoints.firstOrNull { it.transactionId == installed.transactionId }
        PatchInstallResultDialog(
            result = installed,
            onRollback = {
                patchFlow.acknowledgeResult()
                if (record != null) reportInstallRefusal(patchFlow.rollback(record)) else st.patchRollbackDialogOpen = true
            },
            onDone = { patchFlow.acknowledgeResult() },
        )
    }
    patchFlow.rollbackOutcome?.let { outcome ->
        LaunchedEffect(outcome) { st.apps = scanInstalledLibrary(context) }
        PatchRollbackOutcomeDialog(outcome = outcome) { patchFlow.acknowledgeRollback() }
    }
    if (st.patchRollbackDialogOpen) {
        PatchRollbackPointsDialog(
            records = patchFlow.rollbackPoints,
            onRollback = {
                st.patchRollbackDialogOpen = false
                reportInstallRefusal(patchFlow.rollback(it))
            },
            onDiscard = { patchFlow.discardRollbackPoint(it) },
            onRetryRollback = {
                st.patchRollbackDialogOpen = false
                reportInstallRefusal(patchFlow.retryRollback(it))
            },
            onForget = { patchFlow.forgetRecord(it) },
            onClose = { st.patchRollbackDialogOpen = false },
        )
    }

    }
    DialogHost {
    if (st.supportDialogOpen) {
        AlertDialog(
            onDismissRequest = { st.supportDialogOpen = false },
            title = { Text("Support the project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Thanks for supporting the project. Choose a donation option or contact the developer directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (hasStripe) {
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().clickable {
                                st.supportDialogOpen = false
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(appConfig.stripeDonationUrl)),
                                    )
                                }.onFailure { st.snackbarMsg = "Could not open browser: ${it.message}" }
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("\uD83D\uDCB3", fontSize = 22.sp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Credit card / Apple Pay / Google Pay",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Direct card / wallet link",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (hasBmc) {
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().clickable {
                                st.supportDialogOpen = false
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(appConfig.donationUrl)),
                                    )
                                }.onFailure { st.snackbarMsg = "Could not open browser: ${it.message}" }
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
                                    Text(
                                        "Optional external support page",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (hasContact) {
                        Surface(
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().clickable {
                                st.supportDialogOpen = false
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_SENDTO,
                                            Uri.fromParts("mailto", appConfig.contactEmail, null),
                                        ),
                                    )
                                }.onFailure { st.snackbarMsg = "Could not open email app: ${it.message}" }
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("\u2709", fontSize = 22.sp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Email", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        appConfig.contactEmail,
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
                TextButton(onClick = { st.supportDialogOpen = false }) { Text("Close") }
            },
        )
    }
    st.permissionRationale?.let { rationale ->
        PermissionRationaleDialog(
            rationale = rationale,
            onDismiss = { st.permissionRationale = null },
            onOpenSettings = {
                st.permissionRationale = null
                when (rationale) {
                    PermissionRationale.AllFilesConfig,
                    PermissionRationale.AllFilesInstallGame,
                    PermissionRationale.AllFilesCleanupReview,
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

    st.screenshotPanel?.let {
        ScreenshotDemoOverlay(panel = it)
    }

    val startPluginInstall: (List<JoiPlayDownload>) -> Unit = { toInstall ->
        scope.launch {
            for (dl in toInstall) {
                st.pluginInstallProgress = 0L to 0L
                val outcome = JoiPlayPluginInstaller.downloadAndInstall(context, dl) { done, total ->
                    st.pluginInstallProgress = done to total
                }
                when (outcome) {
                    is JoiPlayPluginInstaller.Outcome.InstallerLaunched ->
                        st.snackbarMsg = "Installing ${dl.title}\u2026"
                    is JoiPlayPluginInstaller.Outcome.FellBack -> {
                        st.snackbarMsg = "Opening official download for ${dl.title}"
                        openLink(outcome.link)
                    }
                }
            }
            st.pluginInstallProgress = null
            val downloads = st.joiPlayUpdateStatus?.downloads.orEmpty()
            st.joiPlayPluginReport = JoiPlayPluginChecker.report(context, downloads)
        }
    }

    LaunchedEffect(st.pluginLaunchRequest) {
        val req = st.pluginLaunchRequest ?: return@LaunchedEffect
        val downloads = st.joiPlayUpdateStatus?.downloads?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                JoiPlayUpdateChecker.fetch(AppConfigStore.current(context).joiPlayDownloadsUrl)
            }.getOrDefault(emptyList())
        val recommended = JoiPlayPluginChecker.recommend(req.engineType, req.detectedVersion, downloads)
        st.pluginLaunchRequest = null
        if (recommended != null) {
            st.pluginInstall = PluginInstallState(
                recommended,
                "${req.displayName} games need the ${recommended.title} runtime to run without JoiPlay core.",
            )
        } else {
            st.snackbarMsg = "Couldn't find a ${req.displayName} plugin on joiplay.net."
        }
    }

    st.pluginInstall?.let { pi ->
        PluginInstallDialog(
            state = pi,
            progress = st.pluginInstallProgress,
            onInstall = { startPluginInstall(listOf(pi.download)) },
            onOpenLink = { url -> openLink(url) },
            onDismiss = { if (st.pluginInstallProgress == null) st.pluginInstall = null },
        )
    }


    if (st.updateCheckResults.isNotEmpty()) {
        AppUpdatesDialog(
            results = st.updateCheckResults,
            downloadProgress = st.downloadProgress,
            joiPlayStatus = st.joiPlayUpdateStatus,
            pluginReport = st.joiPlayPluginReport,
            onInstallPlugin = { dl -> startPluginInstall(listOf(dl)) },
            onOpenJoiPlayUrl = { url -> openLink(url) },
            onDismiss = {
                st.updateCheckResults
                    .filterIsInstance<UpdateCheckResult.NotInstalled>()
                    .forEach {
                        UpdatePromptPrefs.dismissOptionalInstall(
                            context,
                            it.target,
                            it.info.versionCode,
                        )
                    }
                st.updateCheckResults = emptyList()
                st.downloadProgress = null
            },
            onDownloadAndInstall = { result, info ->
                scope.launch {
                    st.downloadProgress = UpdateDownloadProgress(
                        target = result.target,
                        downloaded = 0L,
                        total = info.size.takeIf { it > 0 } ?: 1L,
                    )
                    runCatching {
                        val apk = appUpdater.download(context, result.target, info) { d, t ->
                            st.downloadProgress = UpdateDownloadProgress(
                                target = result.target,
                                downloaded = d,
                                total = if (t > 0) t else d,
                            )
                        }
                        appUpdater.install(context, apk)
                    }.onSuccess {
                        st.updateCheckResults = st.updateCheckResults.filterNot { it.target == result.target }
                        st.downloadProgress = null
                    }.onFailure {
                        st.snackbarMsg = "${result.target.displayName} update failed: ${it.message}"
                        st.downloadProgress = null
                    }
                }
            }
        )
    }
    }
}

private fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean {
    return context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isReadPermission
    }
}

private fun hasPersistedWritePermission(context: Context, uri: Uri): Boolean {
    return context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
    }
}

private fun hasFolderReadAccess(context: Context, uri: Uri): Boolean =
    if (uri.scheme == "file") {
        uri.path?.let { java.io.File(it) }?.let { it.isDirectory && it.canRead() } == true
    } else {
        hasPersistedReadPermission(context, uri)
    }

private enum class JoiPlayBackupAction { Revert, Delete }

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

/**
 * Runs [JoiPlayLauncher.launch] and routes the structured result: hard errors to [onMessage],
 * and a missing plugin-tier runtime to [onPluginRequired] (so the UI can offer to install it).
 */
private fun handleJoiPlayLaunch(
    context: android.content.Context,
    app: InstalledApp,
    onMessage: (String) -> Unit,
    onPluginRequired: (JoiPlayLaunchResult.PluginRequired) -> Unit,
) {
    when (
        val result = runCatching { JoiPlayLauncher.launch(context, app) }
            .getOrElse { JoiPlayLaunchResult.Failed(it.message ?: "unknown error") }
    ) {
        JoiPlayLaunchResult.Success -> Unit
        is JoiPlayLaunchResult.Failed -> {
            AppLog.w("Launch", "JoiPlay launch failed: ${result.message}")
            onMessage(result.message)
        }
        is JoiPlayLaunchResult.PluginRequired -> {
            AppLog.i("Launch", "JoiPlay game needs a runtime plugin for engine ${result.engineType}")
            onPluginRequired(result)
        }
    }
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
            .withCatalogAssociationFrom(row.mapping)
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
                    .withCatalogAssociationFrom(row.mapping)
            )
        }
        done++
        onProgress(done, total)
        kotlinx.coroutines.delay(1000)
    }
}


/**
 * Builds the Winlator LAUNCH_GAME intent, first applying a best-effort RPG Maker RGSS RTP fix to
 * the game's folder so self-contained VX Ace/VX/XP games don't abort on a missing shared RTP.
 * The fix is gated to self-contained RGSS games and is a no-op otherwise; it never blocks launch.
 */
/** Legacy single-game JoiPlay delete confirm + progress dialogs. Kept as a top-level composable so
 *  InstalledScreen stays within the JVM 64 KB method limit; still calls the file-private scan. */
@Composable
private fun JoiPlaySingleDeleteDialogs(
    deleteConfirm: AppRow?,
    deleting: GameDeleteUiState?,
    onSetConfirm: (AppRow?) -> Unit,
    onSetDeleting: (GameDeleteUiState?) -> Unit,
    onAppsChanged: (List<InstalledApp>) -> Unit,
    onSnack: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    deleteConfirm?.let { row ->
        AlertDialog(
            onDismissRequest = { onSetConfirm(null) },
            title = {
                Text(if (row.installed.source == AppSource.Managed) "Delete managed game?" else "Delete JoiPlay game?")
            },
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
                    if (row.installed.source != AppSource.Managed) {
                        Text(
                            "If you are unsure, cancel and make a backup in JoiPlay first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = row.installed.storageFolderName
                    if (name.isNullOrBlank()) {
                        onSnack("No folder name to delete")
                        onSetConfirm(null)
                    } else {
                        onSetConfirm(null)
                        onSetDeleting(GameDeleteUiState(row.installed.label))
                        scope.launch {
                            val ok = if (row.installed.source == AppSource.Managed) {
                                runCatching {
                                    deleteManagedGameFilesAndRecord(
                                        context.applicationContext,
                                        row.installed,
                                    ) { progress ->
                                        onSetDeleting(
                                            GameDeleteUiState(
                                                label = row.installed.label,
                                                removedEntries = progress.removedEntries,
                                            ),
                                        )
                                    }
                                }.onFailure {
                                    AppLog.w("Delete", "Managed delete refused for ${row.installed.label}", it)
                                    onSnack(it.message ?: "AGM refused to delete ${row.installed.label}.")
                                }.getOrDefault(false)
                            } else {
                                JoiPlayScanner.deleteFolder(
                                    context.applicationContext,
                                    name,
                                    storagePath = row.installed.storagePath,
                                )
                            }
                            if (ok) {
                                if (row.installed.source != AppSource.Managed) {
                                    row.installed.joiPlayGameId?.let {
                                        JoiPlayBackupReader.markDeleted(context.applicationContext, it)
                                    }
                                }
                                onAppsChanged(scanInstalledLibrary(context))
                                GameStorageSizeWork.enqueueImmediate(context.applicationContext)
                                onSnack("Deleted ${row.installed.label}")
                            } else {
                                onSnack("Delete failed — grant the folder that holds this game (with write access)")
                            }
                            onSetDeleting(null)
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { onSetConfirm(null) }) { Text("Cancel") }
            }
        )
    }

    deleting?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* not dismissible while in-flight */ },
            title = { Text("Deleting game") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(progress.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (progress.removedEntries == 0L) {
                                "Preparing deletion…"
                            } else {
                                "Removed ${progress.removedEntries} files and folders…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** Auto-backups list dialog, extracted from InstalledScreen to keep that composable within the JVM
 *  64 KB method limit. */
@Composable
private fun AutoBackupsDialog(
    open: Boolean,
    list: List<AutoBackupManager.BackupEntry>,
    repo: MappingRepository,
    onDismiss: () -> Unit,
    onListChanged: (List<AutoBackupManager.BackupEntry>) -> Unit,
    onConfirmRestore: (AutoBackupManager.BackupEntry) -> Unit,
    onSnack: (String) -> Unit,
) {
    if (!open) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-backups") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .dialogVerticalScroll(),
            ) {
                if (list.isEmpty()) {
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
                    for (e in list) {
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
                                TextButton(onClick = { onConfirmRestore(e) }) { Text("Restore") }
                                IconButton(onClick = {
                                    scope.launch {
                                        AutoBackupManager.delete(e)
                                        onListChanged(AutoBackupManager.list(context.applicationContext))
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
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    val path = AutoBackupManager.writeBackup(
                        context.applicationContext, repo, label = "manual"
                    )
                    onListChanged(AutoBackupManager.list(context.applicationContext))
                    onSnack("Backup saved: ${java.io.File(path).name}")
                }
            }) { Text("Snapshot now") }
        },
    )
}

/**
 * Single host for the library's action/cleanup dialogs (bulk actions, storage dashboard, JoiPlay
 * single-delete, auto-backups, restore-confirm). Rendering these from one call keeps the giant
 * InstalledScreen composable within the JVM 64 KB method limit.
 */
@Composable
private fun InstalledLibraryDialogsHost(
    editTagsFor: InstalledApp?,
    onSetEditTags: (InstalledApp?) -> Unit,
    bulkTagTarget: List<AppRow>?,
    bulkDeleteConfirm: List<AppRow>?,
    userTags: Map<String, Set<String>>,
    joiPlaySizeInfo: Map<String, JoiPlayScanner.SizeInfo>,
    onSetBulkTagTarget: (List<AppRow>?) -> Unit,
    onSetBulkDeleteConfirm: (List<AppRow>?) -> Unit,
    onUserTagsChanged: (Map<String, Set<String>>) -> Unit,
    onRescan: () -> Unit,
    onSnack: (String) -> Unit,
    onSelectionClear: () -> Unit,
    storageDashboardOpen: Boolean,
    apps: List<InstalledApp>,
    lastPlayed: Map<String, Long>,
    onDismissDashboard: () -> Unit,
    onOpenLeftoverReview: () -> Unit,
    duplicatesOpen: Boolean,
    libraryRows: List<AppRow>,
    onDismissDuplicates: () -> Unit,
    gameStates: Map<String, GameState>,
    onGameStatesChanged: (Map<String, GameState>) -> Unit,
    bulkStatusTarget: List<AppRow>?,
    onSetBulkStatusTarget: (List<AppRow>?) -> Unit,
    collectionsOpen: Boolean,
    onDismissCollections: () -> Unit,
    onPickState: (GameState) -> Unit,
    onPickTag: (String) -> Unit,
    joiPlayDeleteConfirm: AppRow?,
    joiPlayDeleting: GameDeleteUiState?,
    onSetJoiPlayDeleteConfirm: (AppRow?) -> Unit,
    onSetJoiPlayDeleting: (GameDeleteUiState?) -> Unit,
    onAppsChanged: (List<InstalledApp>) -> Unit,
    autoBackupOpen: Boolean,
    autoBackupList: List<AutoBackupManager.BackupEntry>,
    autoBackupConfirmRestore: AutoBackupManager.BackupEntry?,
    repo: MappingRepository,
    onSetAutoBackupOpen: (Boolean) -> Unit,
    onSetAutoBackupList: (List<AutoBackupManager.BackupEntry>) -> Unit,
    onSetAutoBackupConfirmRestore: (AutoBackupManager.BackupEntry?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    editTagsFor?.let { app ->
        EditUserTagsDialog(
            gameLabel = app.label,
            currentTags = userTags[app.packageName].orEmpty(),
            allExistingTags = UserTagsStore.allTags(userTags),
            onDismiss = { onSetEditTags(null) },
            onSave = { newTags ->
                val updated = userTags.toMutableMap()
                if (newTags.isEmpty()) updated.remove(app.packageName) else updated[app.packageName] = newTags
                onUserTagsChanged(updated)
                onSetEditTags(null)
                scope.launch(Dispatchers.IO) { UserTagsStore.save(context.applicationContext, updated) }
                onSnack(if (newTags.isEmpty()) "Tags cleared for ${app.label}" else "Saved ${newTags.size} tag(s)")
            },
        )
    }

    BulkActionsHost(
        bulkTagTarget = bulkTagTarget,
        bulkDeleteConfirm = bulkDeleteConfirm,
        userTags = userTags,
        joiPlaySizeInfo = joiPlaySizeInfo,
        onClearTagTarget = { onSetBulkTagTarget(null) },
        onClearDeleteConfirm = { onSetBulkDeleteConfirm(null) },
        onUserTagsChanged = onUserTagsChanged,
        onRescan = onRescan,
        onSnack = onSnack,
        onSelectionClear = onSelectionClear,
    )
    if (storageDashboardOpen) {
        StorageDashboardDialog(
            apps = apps,
            joiPlaySizeInfo = joiPlaySizeInfo,
            lastPlayed = lastPlayed,
            onRequestDelete = { toDelete ->
                onDismissDashboard()
                if (toDelete.isNotEmpty()) {
                    onSetBulkDeleteConfirm(toDelete.map { AppRow(it, null, UpdateStatus.Unknown) })
                }
            },
            onOpenLeftoverReview = {
                onDismissDashboard()
                onOpenLeftoverReview()
            },
            onRescan = onRescan,
            onSnack = onSnack,
            onDismiss = onDismissDashboard,
        )
    }
    if (duplicatesOpen) {
        DuplicatesDialog(
            rows = libraryRows,
            joiPlaySizeInfo = joiPlaySizeInfo,
            onRequestDelete = { toDelete ->
                onDismissDuplicates()
                if (toDelete.isNotEmpty()) {
                    onSetBulkDeleteConfirm(toDelete.map { AppRow(it, null, UpdateStatus.Unknown) })
                }
            },
            onDismiss = onDismissDuplicates,
        )
    }
    bulkStatusTarget?.let { targets ->
        GameStatePickerDialog(
            count = targets.size,
            onDismiss = { onSetBulkStatusTarget(null) },
            onApply = { st ->
                val pkgs = targets.map { it.installed.packageName }
                val updated = GameStateStore.applied(gameStates, pkgs, st)
                onGameStatesChanged(updated)
                onSetBulkStatusTarget(null)
                scope.launch(Dispatchers.IO) { GameStateStore.save(context.applicationContext, updated) }
                onSnack(
                    if (st == GameState.None) "Cleared status on ${pkgs.size} game${if (pkgs.size == 1) "" else "s"}"
                    else "Set ${st.label} on ${pkgs.size} game${if (pkgs.size == 1) "" else "s"}"
                )
                onSelectionClear()
            },
        )
    }
    if (collectionsOpen) {
        CollectionsDialog(
            stateCounts = GameStateStore.counts(gameStates),
            tagCounts = userTags.values.flatten().groupingBy { it }.eachCount()
                .toList().sortedByDescending { it.second },
            onPickState = onPickState,
            onPickTag = onPickTag,
            onDismiss = onDismissCollections,
        )
    }
    JoiPlaySingleDeleteDialogs(
        deleteConfirm = joiPlayDeleteConfirm,
        deleting = joiPlayDeleting,
        onSetConfirm = onSetJoiPlayDeleteConfirm,
        onSetDeleting = onSetJoiPlayDeleting,
        onAppsChanged = onAppsChanged,
        onSnack = onSnack,
    )
    AutoBackupsDialog(
        open = autoBackupOpen,
        list = autoBackupList,
        repo = repo,
        onDismiss = { onSetAutoBackupOpen(false) },
        onListChanged = onSetAutoBackupList,
        onConfirmRestore = onSetAutoBackupConfirmRestore,
        onSnack = onSnack,
    )
    autoBackupConfirmRestore?.let { entry ->
        AlertDialog(
            onDismissRequest = { onSetAutoBackupConfirmRestore(null) },
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
                    onSetAutoBackupConfirmRestore(null)
                    onSetAutoBackupOpen(false)
                    scope.launch {
                        val summary = runCatching {
                            AutoBackupManager.restore(context.applicationContext, repo, e)
                        }.getOrElse {
                            onSnack("Restore failed: ${it.message}")
                            return@launch
                        }
                        onAppsChanged(scanInstalledLibrary(context))
                        onSnack(
                            "Restored: ${summary.mappings} mappings, ${summary.hidden} hidden, " +
                                "${summary.joiplayGames} JoiPlay games"
                        )
                    }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { onSetAutoBackupConfirmRestore(null) }) { Text("Cancel") }
            },
        )
    }
}

private fun winlatorLaunchIntentWithRtpFix(app: InstalledApp, gameId: String): android.content.Intent {
    runCatching { RpgmRtpFix.applyIfNeeded(app.storagePath) }
        .onFailure { AppLog.w("RpgmRtp", "Pre-launch RTP fix failed for ${app.label}: ${it.message}", it) }
    return WinlatorApi.launch(gameId)
}
