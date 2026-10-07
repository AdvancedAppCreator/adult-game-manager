package com.example.f95updater

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

enum class CatalogSortKey(val label: String) {
    Relevance("Relevance"),
    Title("Title"),
    Rating("Rating"),
    Updated("Last update"),
    Newest("Newest"),
    Views("Views"),
    Likes("Likes"),
}

/** Matches an unfinished `tag:<prefix>` token at the end of the search query
 *  (no trailing whitespace). Group 1 = the prefix, possibly empty. */
private val TAG_TOKEN_AT_END = Regex("""(?i)\btag:([\w-]*)$""")
private const val CATALOG_PAGE_SIZE = 100
private const val CATALOG_PAGE_PREFETCH_DISTANCE = 20

internal class CatalogScreenRetainedState {
    var databaseSignature: String? = null
    var totalCatalogCount: Int = 0
    var availableTagLabels: List<String> = emptyList()
    var filteredGroups: List<CatalogRowsGroup> = emptyList()
    var filteredCount: Int = 0
    var filterKey: CatalogFilterCacheKey? = null
    var preferences: CatalogPrefs.State? = null

    fun installDatabase(
        signature: String,
        totalCount: Int,
        tagLabels: List<String>,
    ) {
        databaseSignature = signature
        totalCatalogCount = totalCount
        availableTagLabels = tagLabels
        filteredGroups = emptyList()
        filteredCount = 0
        filterKey = null
    }

    fun clearPrepared() {
        databaseSignature = null
        totalCatalogCount = 0
        availableTagLabels = emptyList()
        filteredGroups = emptyList()
        filteredCount = 0
        filterKey = null
    }
}

internal data class CatalogFilterCacheKey(
    val query: String,
    val sortKey: CatalogSortKey,
    val sortDesc: Boolean,
    val statusFilter: Int?,
    val engineFilter: Int?,
    val categoryFilter: String?,
    val sourceFilter: String?,
    val platformFilter: String?,
    val minRating: Float,
    val installedOnly: Boolean,
    val notInstalledOnly: Boolean,
    val installedCatalogKeys: Set<CatalogInstallKey>,
    val installedCatalogUrls: Set<String>,
    val installedAgmGroupIds: Set<String>,
    val wishlistOnly: Boolean,
    val notWishlistOnly: Boolean,
    val wishlistGroupIds: Set<String>,
    val ignoredOnly: Boolean,
    val ignoredGroupIds: Set<String>,
    val canonicalTagIds: List<String> = emptyList(),
    val tagGroups: List<CatalogTagGroup> = emptyList(),
    val searchMode: CatalogSearchMode = CatalogSearchMode.Normal,
    val includeSynopsis: Boolean = false,
    val selectedTagTokens: List<String> = emptyList(),
    val minPopularity: Long = 0L,
    val dateField: CatalogDateField = CatalogDateField.Updated,
    val dateRange: DateRangeFilter = DateRangeFilter.Any,
)

/** Prefix IDs for status/category-style filters (resolved against labels.json). */
object KnownPrefixes {
    const val COMPLETED = 18
    const val ONHOLD    = 20
    const val ABANDONED = 22

    // Game engines / common types — based on labels.json scrape:
    val ENGINE_IDS = listOf(2 to "RPGM", 3 to "Unity", 7 to "Ren'Py", 13 to "VN",
                             4 to "HTML", 31 to "Unreal", 8 to "Flash", 14 to "Others")
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CatalogScreen(
    catalog: CatalogRepository,
    labels: CatalogLabelsV2?,
    retainedState: CatalogScreenRetainedState,
    mappings: Map<String, AppMapping>,
    installedPackageNames: Set<String>,
    taxonomy: CanonicalTagTaxonomy = CanonicalTagTaxonomy(),
    screenshotQuery: String? = null,
    navigationIdentity: CatalogNavigationIdentity? = null,
    navigationRequestId: Long = 0L,
    onOpenThread: (CatalogGame) -> Unit,
    onGoToInstalled: (String) -> Unit = {},
    onOpenDownloads: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val contentRevision by catalog.contentRevision.collectAsState()
    val compactWidth = LocalConfiguration.current.screenWidthDp < 420
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    val retainedPreferences = retainedState.preferences
    var databaseReady by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingStage by remember { mutableStateOf("Reading catalog files…") }
    var loadingDetail by remember { mutableStateOf("") }
    var loadingProgress by remember { mutableFloatStateOf(0.02f) }
    var lastSyncMs by remember { mutableStateOf(0L) }
    var syncing by remember { mutableStateOf(false) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    var catalogLoadError by remember { mutableStateOf<String?>(null) }
    var initialResultsReady by remember {
        mutableStateOf(retainedState.filteredGroups.isNotEmpty())
    }
    val snackHostState = remember { SnackbarHostState() }
    // Load prefs synchronously *before* declaring filter state so initial values
    // already reflect what the user had on last run. Until prefs load (first frame),
    // we use defaults; the LaunchedEffect below overwrites them.
    var prefsLoaded by remember { mutableStateOf(retainedPreferences != null) }
    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { snackHostState.showSnackbar(it); snackbarMsg = null }
    }
    LaunchedEffect(contentRevision, labels) {
        if (labels == null) {
            loading = true
            loadingStage = "Loading catalog labels…"
            loadingDetail = ""
            loadingProgress = 0.03f
            return@LaunchedEffect
        }
        try {
            val initialLoadStartedAt = System.currentTimeMillis()
            CatalogMemoryDiagnostics.log(phase = "catalog_screen_load_start")
            databaseReady = false
            loading = true
            loadingStage = "Preparing catalog database…"
            loadingDetail = ""
            loadingProgress = 0.15f
            initialResultsReady = false
            val progressJob = launch {
                catalog.catalogBuildProgress.collect { frac ->
                    if (frac < 1f) {
                        loadingStage = "Building catalog index…"
                        loadingDetail = ""
                        loadingProgress = 0.15f + frac * 0.8f
                    }
                }
            }
            val database = try {
                catalog.prepareCatalogDatabase()
            } finally {
                progressJob.cancel()
            }
            loadingProgress = 0.96f
            retainedState.installDatabase(
                database.signature,
                database.totalCount,
                database.tagLabels,
            )
            databaseReady = true
            lastSyncMs = catalog.lastSyncMs()
            catalogLoadError = null
            CatalogMemoryDiagnostics.log(
                phase = "catalog_rows_database_ready",
                startedAtMs = initialLoadStartedAt,
                detail =
                    "rows=${database.totalCount} bytes=${catalog.catalogRowsDatabaseFile.length()}",
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLog.e("Catalog", "Initial catalog load failed", e)
            catalogLoadError = "Could not load catalog: ${e.message}"
            snackbarMsg = catalogLoadError
        } finally {
            loading = false
        }
    }

    suspend fun doRefresh() {
        syncing = true
        try {
            when (val r = catalog.sync()) {
                is CatalogSyncResult.Updated -> {
                    loadingStage = "Reading updated catalog…"
                    loadingDetail = ""
                    loadingProgress = 0.05f
                    initialResultsReady = false
                    lastSyncMs = catalog.lastSyncMs()
                    catalogLoadError = null
                    snackbarMsg = "Catalog updated: ${r.gameCount} games"
                }
                CatalogSyncResult.NotModified -> {
                    lastSyncMs = catalog.lastSyncMs()
                    snackbarMsg = "Catalog already up-to-date"
                }
                is CatalogSyncResult.Error -> snackbarMsg = "Sync failed: ${r.message}"
            }
        } finally { syncing = false }
    }

    val initialSearchMode = retainedPreferences?.searchMode ?: CatalogSearchMode.Normal
    val initialQuery = retainedPreferences?.query.orEmpty()
    var query by remember { mutableStateOf(initialQuery) }
    var debouncedQuery by remember {
        mutableStateOf(initialQuery.takeIf { regexValidationError(it, initialSearchMode) == null }.orEmpty())
    }
    var sortKey by remember { mutableStateOf(retainedPreferences?.sortKey ?: CatalogSortKey.Relevance) }
    var sortDesc by remember { mutableStateOf(retainedPreferences?.sortDesc ?: true) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var statusFilter by remember { mutableStateOf(retainedPreferences?.statusFilter) }   // 18/20/22 or null
    var engineFilter by remember { mutableStateOf(retainedPreferences?.engineFilter) }   // engine prefix id or null
    var categoryFilter by remember { mutableStateOf(retainedPreferences?.categoryFilter) } // "games"/"mods"/...
    var sourceFilter by remember { mutableStateOf(retainedPreferences?.sourceFilter) }
    var platformFilter by remember { mutableStateOf(retainedPreferences?.platformFilter) }
    var minRating by remember { mutableStateOf(retainedPreferences?.minRating ?: 0f) }
    var minPopularity by remember { mutableStateOf(retainedPreferences?.minPopularity ?: 0L) }
    var dateField by remember { mutableStateOf(retainedPreferences?.dateField ?: CatalogDateField.Updated) }
    var dateRange by remember { mutableStateOf(retainedPreferences?.dateRange ?: DateRangeFilter.Any) }
    var installedOnly by remember { mutableStateOf(retainedPreferences?.installedOnly ?: false) }
    var notInstalledOnly by remember { mutableStateOf(retainedPreferences?.notInstalledOnly ?: false) }
    var wishlistOnly by remember { mutableStateOf(retainedPreferences?.wishlistOnly ?: false) }
    var notWishlistOnly by remember { mutableStateOf(retainedPreferences?.notWishlistOnly ?: false) }
    var ignoredOnly by remember { mutableStateOf(retainedPreferences?.ignoredOnly ?: false) }
    val wishlistGroupIds by CatalogWishlistStore.observe(context.applicationContext)
        .collectAsState(initial = emptySet())
    val ignoredGroupIds by CatalogIgnoreStore.observe(context.applicationContext)
        .collectAsState(initial = emptySet())
    val selection = remember { mutableStateListOf<String>() }
    val selectionMode = selection.isNotEmpty()
    var selectedCanonicalTags by remember {
        mutableStateOf(retainedPreferences?.canonicalTagIds?.toSet() ?: emptySet())
    }
    var selectedTagTokens by remember {
        mutableStateOf(retainedPreferences?.selectedTagTokens?.toSet() ?: emptySet())
    }
    var tagGroups by remember {
        mutableStateOf(retainedPreferences?.tagGroups ?: emptyList())
    }
    var searchMode by remember {
        mutableStateOf(initialSearchMode)
    }
    var includeSynopsis by remember {
        mutableStateOf(retainedPreferences?.includeSynopsis ?: false)
    }
    var advancedFiltersOpen by remember { mutableStateOf(false) }
    var translateTitles by remember { mutableStateOf(retainedPreferences?.translateTitles ?: false) }
    var translationTarget by remember { mutableStateOf(retainedPreferences?.translationTarget ?: "en") }
    var translationDialogOpen by remember { mutableStateOf(false) }
    var scheduledTranslationSettings by remember {
        mutableStateOf(retainedPreferences?.let { it.translateTitles to it.translationTarget })
    }

    LaunchedEffect(screenshotQuery) {
        if (screenshotQuery != null) query = screenshotQuery
    }

    val regexError = regexValidationError(query, searchMode)
    LaunchedEffect(query, searchMode) {
        delay(200)
        if (regexValidationError(query, searchMode) == null) {
            debouncedQuery = query
        }
    }

    // 1. Load persisted filter state on first composition.
    LaunchedEffect(Unit) {
        val s = retainedState.preferences ?: CatalogPrefs.load(context)
        retainedState.preferences = s
        query = s.query
        sortKey = s.sortKey
        sortDesc = s.sortDesc
        statusFilter = s.statusFilter
        engineFilter = s.engineFilter
        categoryFilter = s.categoryFilter
        sourceFilter = s.sourceFilter
        platformFilter = s.platformFilter
        minRating = s.minRating
        minPopularity = s.minPopularity
        dateField = s.dateField
        dateRange = s.dateRange
        installedOnly = s.installedOnly
        notInstalledOnly = s.notInstalledOnly
        wishlistOnly = s.wishlistOnly
        notWishlistOnly = s.notWishlistOnly
        ignoredOnly = s.ignoredOnly
        selectedCanonicalTags = s.canonicalTagIds.toSet()
        selectedTagTokens = s.selectedTagTokens.toSet()
        tagGroups = s.tagGroups
        includeSynopsis = s.includeSynopsis
        searchMode = s.searchMode
        translateTitles = s.translateTitles
        translationTarget = s.translationTarget
        prefsLoaded = true
    }
    // 2. Save filter state whenever any tracked value changes (only after first load
    //    completes — otherwise we'd overwrite the persisted state with defaults).
    LaunchedEffect(prefsLoaded, query, sortKey, sortDesc, statusFilter, engineFilter,
        categoryFilter, sourceFilter, platformFilter, minRating, installedOnly, notInstalledOnly,
        wishlistOnly, notWishlistOnly, ignoredOnly, minPopularity, dateField, dateRange,
        selectedCanonicalTags, selectedTagTokens, tagGroups, includeSynopsis, searchMode,
        translateTitles, translationTarget) {
        if (!prefsLoaded) return@LaunchedEffect
        val state = CatalogPrefs.State(
            query = query,
            sortKey = sortKey,
            sortDesc = sortDesc,
            statusFilter = statusFilter,
            engineFilter = engineFilter,
            categoryFilter = categoryFilter,
            sourceFilter = sourceFilter,
            platformFilter = platformFilter,
            minRating = minRating,
            minPopularity = minPopularity,
            dateField = dateField,
            dateRange = dateRange,
            installedOnly = installedOnly,
            notInstalledOnly = notInstalledOnly,
            wishlistOnly = wishlistOnly,
            notWishlistOnly = notWishlistOnly,
            ignoredOnly = ignoredOnly,
            canonicalTagIds = selectedCanonicalTags.toList(),
            selectedTagTokens = selectedTagTokens.toList(),
            tagGroups = tagGroups,
            includeSynopsis = includeSynopsis,
            searchMode = searchMode,
            translateTitles = translateTitles,
            translationTarget = translationTarget,
        )
        retainedState.preferences = state
        CatalogPrefs.save(context, state)
        val current = translateTitles to translationTarget
        val previous = scheduledTranslationSettings
        if (previous != current) {
            CatalogTranslationWork.configure(
                context = context,
                enabled = translateTitles,
                targetLanguage = translationTarget,
                resetCursor = previous != null,
            )
            scheduledTranslationSettings = current
        }
    }

    // Cache source-aware catalog identifiers linked to installed apps for quick filtering.
    // Derived from the LIVE scan: only mappings whose local game is currently
    // installed ([installedPackageNames]) mark a catalog entry as installed. Stale
    // mappings (uninstalled apps, deleted JoiPlay games) no longer light the badge.
    val installedIdentities = remember(mappings, installedPackageNames) {
        installedCatalogIdentities(mappings, installedPackageNames)
    }
    val installedCatalogKeys: Set<CatalogInstallKey> = installedIdentities.keys
    val installedCatalogUrls: Set<String> = installedIdentities.urls
    val installedAgmGroupIds: Set<String> = installedIdentities.agmGroupIds
    fun isInstalled(entry: SourceCatalogEntry): Boolean {
        return entry.agmGroupId?.let(installedAgmGroupIds::contains) == true ||
            catalogInstallKey(entry) in installedCatalogKeys ||
            (entry.canonicalUrl.isNotBlank() && entry.canonicalUrl in installedCatalogUrls)
    }

    var detailGroup by remember { mutableStateOf<CatalogRowsGroup?>(null) }
    var highlightedGroupId by remember { mutableStateOf<String?>(null) }
    var consumedNavigationRequestId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(databaseReady, navigationRequestId, navigationIdentity) {
        val identity = navigationIdentity ?: return@LaunchedEffect
        if (!databaseReady || navigationRequestId == 0L) return@LaunchedEffect
        if (navigationRequestId <= consumedNavigationRequestId) return@LaunchedEffect
        consumedNavigationRequestId = navigationRequestId
        val destination = catalog.catalogGroupForNavigation(identity)
        if (destination == null) {
            snackbarMsg = "The mapped catalog game is not present in the current catalog."
        } else {
            highlightedGroupId = destination.agmGroupId
            detailGroup = destination
        }
    }
    val currentFilterKey = CatalogFilterCacheKey(
        query = debouncedQuery,
        sortKey = sortKey,
        sortDesc = sortDesc,
        statusFilter = statusFilter,
        engineFilter = engineFilter,
        categoryFilter = categoryFilter,
        sourceFilter = sourceFilter,
        platformFilter = platformFilter,
        minRating = minRating,
        installedOnly = installedOnly,
        notInstalledOnly = notInstalledOnly,
        installedCatalogKeys = installedCatalogKeys,
        installedCatalogUrls = installedCatalogUrls,
        installedAgmGroupIds = installedAgmGroupIds,
        wishlistOnly = wishlistOnly,
        notWishlistOnly = notWishlistOnly,
        wishlistGroupIds = if (wishlistOnly || notWishlistOnly) wishlistGroupIds else emptySet(),
        ignoredOnly = ignoredOnly,
        ignoredGroupIds = ignoredGroupIds,
        canonicalTagIds = selectedCanonicalTags.sorted(),
        tagGroups = tagGroups,
        searchMode = searchMode,
        includeSynopsis = includeSynopsis,
        selectedTagTokens = selectedTagTokens.sorted(),
        minPopularity = minPopularity,
        dateField = dateField,
        dateRange = dateRange,
    )
    val listState = rememberLazyListState()
    var pagedGroups by remember { mutableStateOf(retainedState.filteredGroups) }
    var totalFilteredCount by remember { mutableIntStateOf(retainedState.filteredCount) }
    var rowsQuery by remember { mutableStateOf<CatalogRowsQuery?>(null) }
    var hasMorePages by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val availableCatalogTagLabels = retainedState.availableTagLabels
    val advancedFilterState = CatalogAdvancedFilterState(
        includeSynopsis = includeSynopsis,
        searchMode = searchMode,
        statusFilter = statusFilter,
        engineFilter = engineFilter,
        categoryFilter = categoryFilter,
        sourceFilter = sourceFilter,
        platformFilter = platformFilter,
        minRating = minRating,
        minPopularity = minPopularity,
        dateField = dateField,
        dateRange = dateRange,
        installedOnly = installedOnly,
        notInstalledOnly = notInstalledOnly,
        wishlistOnly = wishlistOnly,
        notWishlistOnly = notWishlistOnly,
        ignoredOnly = ignoredOnly,
        selectedTagTokens = selectedTagTokens,
        canonicalTagIds = selectedCanonicalTags,
        tagGroups = tagGroups,
    )
    val advancedFilterSummary = catalogAdvancedFilterSummary(
        advancedFilterState,
        taxonomy,
        availableCatalogTagLabels,
    )

    DisposableEffect(Unit) {
        onDispose {
            rowsQuery?.close()
            rowsQuery = null
        }
    }

    LaunchedEffect(databaseReady, currentFilterKey) {
        if (!databaseReady) {
            val previousQuery = rowsQuery
            rowsQuery = null
            withContext(Dispatchers.IO) { previousQuery?.close() }
            return@LaunchedEffect
        }
        val previousQuery = rowsQuery
        rowsQuery = null
        withContext(Dispatchers.IO) { previousQuery?.close() }
        loadingMore = false
        initialResultsReady = false
        val parsed = parseSearchQuery(debouncedQuery)
        val freeText = parsed.freeText.lowercase()
        val searchStartedAt = System.currentTimeMillis()
        CatalogMemoryDiagnostics.log(
            phase = "catalog_filter_search_start",
            detail = "rows=${retainedState.totalCatalogCount} queryLength=${freeText.length}",
        )
        try {
            val excludeTitlePredicate = CatalogSearchModes.anyTitlePredicate(parsed.excludedText, searchMode)
            val includeTitlePredicate =
                if (searchMode != CatalogSearchMode.Normal && freeText.isNotEmpty()) {
                    CatalogSearchModes.titlePredicate(parsed.freeText, searchMode)
                } else {
                    null
                }
            // One title_lower scan covers whole-word/regex include + any exclusion (all modes).
            val scan = if (includeTitlePredicate != null || excludeTitlePredicate != null) {
                val searchContext = kotlinx.coroutines.currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    CatalogRowsDatabaseStore.scanTitleKeys(
                        file = catalog.catalogRowsDatabaseFile,
                        include = includeTitlePredicate,
                        exclude = excludeTitlePredicate,
                        checkCancelled = { searchContext.ensureActive() },
                    )
                }
            } else {
                null to null
            }
            val matchingKeyRanks =
                if (searchMode == CatalogSearchMode.Normal && freeText.isNotEmpty()) {
                    catalog.searchCatalogEntryRanks(freeText)
                } else {
                    null
                }
            val matchingKeys = when {
                searchMode == CatalogSearchMode.Normal && freeText.isNotEmpty() ->
                    matchingKeyRanks.orEmpty().keys
                searchMode != CatalogSearchMode.Normal && freeText.isNotEmpty() ->
                    // scan.first is null only when the regex was invalid -> show no results.
                    scan.first ?: emptySet()
                else -> null
            }
            val synopsisGroupRanks =
                if (includeSynopsis && freeText.isNotEmpty() && searchMode != CatalogSearchMode.Regex) {
                    catalog.searchSynopsisAgmGroupRanks(parsed.freeText)
                } else {
                    null
                }
            val synopsisGroupIds = synopsisGroupRanks?.keys
            val excludedKeys = scan.second
            val dateCutoff = dateRange.cutoffIso(System.currentTimeMillis())
            val filter = CatalogRowsFilter(
                matchingKeys = matchingKeys,
                matchingGroupIds = synopsisGroupIds,
                matchingKeyRanks = matchingKeyRanks,
                matchingGroupRanks = synopsisGroupRanks,
                tagTokens = parsed.tags + selectedTagTokens,
                canonicalTagIds = selectedCanonicalTags.toList(),
                tagTokenGroups = tagGroups.map { it.tokens },
                statusFilter = statusFilter,
                engineFilter = engineFilter,
                categoryFilter = categoryFilter,
                sourceFilter = sourceFilter,
                platformFilter = platformFilter,
                minRating = minRating,
                minPopularity = minPopularity,
                updatedSinceIso = dateCutoff.takeIf { dateField == CatalogDateField.Updated },
                publishedSinceIso = dateCutoff.takeIf { dateField == CatalogDateField.Published },
                installedOnly = installedOnly,
                notInstalledOnly = notInstalledOnly,
                installedCatalogKeys = installedCatalogKeys,
                installedCatalogUrls = installedCatalogUrls,
                installedAgmGroupIds = installedAgmGroupIds,
                wishlistOnly = wishlistOnly,
                notWishlistOnly = notWishlistOnly,
                wishlistGroupIds = wishlistGroupIds,
                ignoredOnly = ignoredOnly,
                ignoredGroupIds = ignoredGroupIds,
                sortKey = sortKey,
                sortDesc = sortDesc,
                excludedKeys = excludedKeys,
                excludedTagTokens = parsed.excludedTags,
            )
            val newQuery = withContext(Dispatchers.IO) {
                val opened = CatalogRowsDatabaseStore.openQuery(
                    catalog.catalogRowsDatabaseFile,
                    filter,
                )
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    opened
                } catch (error: Throwable) {
                    opened.close()
                    throw error
                }
            }
            try {
                val retainedPage = retainedState.filteredGroups.takeIf {
                    retainedState.filterKey == currentFilterKey && it.isNotEmpty()
                }
                val page = if (retainedPage != null) {
                    CatalogRowsGroupPage(retainedState.filteredCount, retainedPage)
                } else {
                    withContext(Dispatchers.IO) {
                        newQuery.loadGroups(offset = 0, limit = CATALOG_PAGE_SIZE)
                    }
                }
                rowsQuery = newQuery
                pagedGroups = page.groups
                totalFilteredCount = page.totalCount
                hasMorePages = page.groups.size < page.totalCount
                retainedState.filteredGroups = page.groups
                retainedState.filteredCount = page.totalCount
                retainedState.filterKey = currentFilterKey
                loadingStage = "Catalog ready"
                loadingDetail = "${page.totalCount} games"
                loadingProgress = 1f
                initialResultsReady = true
                catalogLoadError = null
                // The list must be composed before scrollToItem can complete.
                if (retainedPage == null) listState.scrollToItem(0)
                CatalogMemoryDiagnostics.log(
                    phase = "catalog_filter_search_complete",
                    startedAtMs = searchStartedAt,
                    detail =
                        "matchingKeys=${matchingKeys?.size ?: -1} " +
                            "synopsisGroups=${synopsisGroupIds?.size ?: -1} " +
                            "page=${page.groups.size} matches=${page.totalCount}",
                )
            } catch (error: Throwable) {
                newQuery.close()
                throw error
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            AppLog.e("Catalog", "Catalog page query failed", error)
            catalogLoadError = "Could not query catalog: ${error.message}"
            snackbarMsg = catalogLoadError
            initialResultsReady = true
        }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            hasMorePages &&
                lastVisible >= pagedGroups.size - CATALOG_PAGE_PREFETCH_DISTANCE
        }
    }
    LaunchedEffect(shouldLoadMore, rowsQuery, pagedGroups.size) {
        val querySession = rowsQuery ?: return@LaunchedEffect
        if (!shouldLoadMore || loadingMore) return@LaunchedEffect
        loadingMore = true
        try {
            val next = withContext(Dispatchers.IO) {
                querySession.loadGroups(offset = pagedGroups.size, limit = CATALOG_PAGE_SIZE)
            }
            if (querySession !== rowsQuery) return@LaunchedEffect
            pagedGroups = pagedGroups + next.groups
            totalFilteredCount = next.totalCount
            hasMorePages = pagedGroups.size < next.totalCount && next.groups.isNotEmpty()
            retainedState.filteredGroups = pagedGroups
            retainedState.filteredCount = next.totalCount
        } finally {
            loadingMore = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Catalog",
                            fontSize = if (compactHeight) 20.sp else TextUnit.Unspecified,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("$totalFilteredCount of ${retainedState.totalCatalogCount}")
                                if (!compactWidth && !compactHeight && lastSyncMs > 0L) {
                                    append(" · Updated ")
                                    append(formatRelativeTime(lastSyncMs))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Default.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = { translationDialogOpen = true }) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = "Catalog translation",
                            tint = if (translateTitles) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { doRefresh() } },
                        enabled = !syncing,
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh catalog")
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            CatalogSortKey.values().forEach { k ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (k == sortKey) {
                                                Icon(
                                                    if (sortDesc) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                                    null, modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            } else Spacer(Modifier.width(22.dp))
                                            Text(k.label)
                                        }
                                    },
                                    onClick = {
                                        if (k == sortKey) sortDesc = !sortDesc
                                        else { sortKey = k; sortDesc = true }
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search title, tag:harem, -tag:ntr, -word") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
            // Tag autocomplete — appears only while the query ends with an unfinished
            // `tag:<prefix>` token. Up to 8 suggestions; clicking inserts the full
            // tag name in place of the partial prefix.
            val tagSuggestions: List<String> = remember(query, labels) {
                if (labels == null) return@remember emptyList()
                val match = TAG_TOKEN_AT_END.find(query) ?: return@remember emptyList()
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
                                val match = TAG_TOKEN_AT_END.find(query) ?: return@AssistChip
                                // Replace the matched "tag:<prefix>" suffix with the
                                // completed "tag:<full> ".
                                val before = query.substring(0, match.range.first)
                                query = before + "tag:" + catalogTagFilterToken(suggestion) + " "
                            },
                            label = { Text(suggestion, fontSize = 12.sp) },
                        )
                    }
                }
            }
            AdvancedFilterBar(
                summary = advancedFilterSummary,
                regexError = regexError,
                onOpen = { advancedFiltersOpen = true },
            )
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
                            scope.launch {
                                CatalogIgnoreStore.setIgnored(
                                    context.applicationContext,
                                    selection.toList(),
                                    ignored = true,
                                )
                                selection.clear()
                            }
                        }) { Text("Ignore") }
                        TextButton(onClick = {
                            scope.launch {
                                CatalogIgnoreStore.setIgnored(
                                    context.applicationContext,
                                    selection.toList(),
                                    ignored = false,
                                )
                                selection.clear()
                            }
                        }) { Text("Unignore") }
                    }
                }
            }
            HorizontalDivider()
            if (loading || (databaseReady && !initialResultsReady)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LinearProgressIndicator(
                        progress = { loadingProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        loadingStage,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (loadingDetail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            loadingDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(loadingProgress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (catalogLoadError != null) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        catalogLoadError.orEmpty(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (!databaseReady || retainedState.totalCatalogCount == 0) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        "No catalog data yet. Check your internet connection and reopen the app.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 96.dp),
                ) {
                    if (pagedGroups.isEmpty() && totalFilteredCount == 0) {
                        item(key = "catalog-no-matches") {
                            Box(
                                modifier = Modifier.fillParentMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No catalog entries match the current search and filters.",
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                    items(pagedGroups, key = { it.agmGroupId }) { grp ->
                        val display = remember(grp) {
                            CatalogGroupPresentation.of(grp.agmGroupId, grp.members)
                        }
                        CatalogGroupRowCard(
                            group = display,
                            labels = labels,
                            installed = display.members.any { isInstalled(it) },
                            wishlisted = grp.agmGroupId in wishlistGroupIds,
                            ignored = grp.agmGroupId in ignoredGroupIds,
                            selected = grp.agmGroupId in selection,
                            selectionMode = selectionMode,
                            highlighted = grp.agmGroupId == highlightedGroupId,
                            translateTitles = translateTitles,
                            translationTarget = translationTarget,
                            onClick = { detailGroup = grp },
                            onToggleSelect = {
                                if (grp.agmGroupId in selection) {
                                    selection.remove(grp.agmGroupId)
                                } else {
                                    selection.add(grp.agmGroupId)
                                }
                            },
                            onLongPress = {
                                if (grp.agmGroupId !in selection) selection.add(grp.agmGroupId)
                            },
                            onToggleWishlist = {
                                scope.launch {
                                    CatalogWishlistStore.toggle(context.applicationContext, grp.agmGroupId)
                                }
                            },
                            onToggleIgnored = {
                                scope.launch {
                                    CatalogIgnoreStore.toggle(context.applicationContext, grp.agmGroupId)
                                }
                            },
                            onOpen = { onOpenThread(sourceEntryToCatalogGame(display.representative)) },
                        )
                    }
                    if (loadingMore) {
                        item(key = "catalog-loading-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (advancedFiltersOpen) {
        CatalogAdvancedFilterDialog(
            state = advancedFilterState,
            query = query,
            availableTagLabels = availableCatalogTagLabels,
            taxonomy = taxonomy,
            onApply = { applied ->
                includeSynopsis = applied.includeSynopsis
                searchMode = applied.searchMode
                statusFilter = applied.statusFilter
                engineFilter = applied.engineFilter
                categoryFilter = applied.categoryFilter
                sourceFilter = applied.sourceFilter
                platformFilter = applied.platformFilter
                minRating = applied.minRating
                minPopularity = applied.minPopularity
                dateField = applied.dateField
                dateRange = applied.dateRange
                installedOnly = applied.installedOnly
                notInstalledOnly = applied.notInstalledOnly
                wishlistOnly = applied.wishlistOnly
                notWishlistOnly = applied.notWishlistOnly
                ignoredOnly = applied.ignoredOnly
                selectedTagTokens = applied.selectedTagTokens
                selectedCanonicalTags = applied.canonicalTagIds
                tagGroups = applied.tagGroups
                focusManager.clearFocus()
            },
            onDismiss = { advancedFiltersOpen = false },
        )
    }

    if (translationDialogOpen) {
        CatalogTranslationDialog(
            enabled = translateTitles,
            targetLanguage = translationTarget,
            onEnabledChange = { translateTitles = it },
            onTargetLanguageChange = { translationTarget = it },
            onDismiss = { translationDialogOpen = false },
        )
    }

    detailGroup?.let { grp ->
        val display = remember(grp) { CatalogGroupPresentation.of(grp.agmGroupId, grp.members) }
        val installedPackageName = remember(grp, mappings, installedPackageNames) {
            installedPackageForCatalogGroup(grp, mappings, installedPackageNames)
        }
        CatalogGroupDetailDialog(
            group = display,
            catalog = catalog,
            labels = labels,
            installed = installedPackageName != null,
            ignored = grp.agmGroupId in ignoredGroupIds,
            translateTitles = translateTitles,
            translationTarget = translationTarget,
            onDismiss = {
                detailGroup = null
                // After the dialog goes away the underlying screen's OutlinedTextField
                // sometimes captures focus and pops the keyboard. Clear focus explicitly.
                focusManager.clearFocus()
            },
            onOpenSource = { onOpenThread(sourceEntryToCatalogGame(it)) },
            onToggleIgnored = {
                scope.launch {
                    CatalogIgnoreStore.toggle(context.applicationContext, grp.agmGroupId)
                }
            },
            onGoToInstalled = installedPackageName?.let { packageName ->
                {
                    detailGroup = null
                    onGoToInstalled(packageName)
                }
            },
        )
    }
}
