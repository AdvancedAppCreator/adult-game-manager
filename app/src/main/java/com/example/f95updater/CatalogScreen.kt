package com.example.f95updater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch

enum class CatalogSortKey(val label: String) {
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

/** Prefix IDs for status/category-style filters (resolved against labels.json). */
object KnownPrefixes {
    const val COMPLETED = 18
    const val ONHOLD    = 20
    const val ABANDONED = 22

    // Game engines / common types — based on labels.json scrape:
    val ENGINE_IDS = listOf(2 to "RPGM", 3 to "Unity", 7 to "Ren'Py", 13 to "VN",
                             4 to "HTML", 31 to "Unreal", 8 to "Flash", 14 to "Others")
}

private val PLATFORM_FILTERS = listOf("Android", "Windows", "Mac", "Linux")

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    catalog: CatalogRepository,
    labels: CatalogLabels?,
    mappings: Map<String, AppMapping>,
    screenshotQuery: String? = null,
    onOpenThread: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val compactWidth = LocalConfiguration.current.screenWidthDp < 420
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    var allEntries by remember { mutableStateOf<List<SourceCatalogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var lastSyncMs by remember { mutableStateOf(0L) }
    var syncing by remember { mutableStateOf(false) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    val snackHostState = remember { SnackbarHostState() }
    // Load prefs synchronously *before* declaring filter state so initial values
    // already reflect what the user had on last run. Until prefs load (first frame),
    // we use defaults; the LaunchedEffect below overwrites them.
    var prefsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { snackHostState.showSnackbar(it); snackbarMsg = null }
    }
    LaunchedEffect(Unit) {
        allEntries = catalog.allSourceEntries().ifEmpty { sourceEntriesFromLegacyGames(catalog.allGames()) }
        lastSyncMs = catalog.lastSyncMs()
        loading = false
    }

    suspend fun doRefresh() {
        syncing = true
        try {
            when (val r = catalog.sync()) {
                is CatalogSyncResult.Updated -> {
                    allEntries = catalog.allSourceEntries().ifEmpty { sourceEntriesFromLegacyGames(catalog.allGames()) }
                    lastSyncMs = catalog.lastSyncMs()
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

    var query by remember { mutableStateOf("") }
    var sortKey by remember { mutableStateOf(CatalogSortKey.Rating) }
    var sortDesc by remember { mutableStateOf(true) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var statusFilter by remember { mutableStateOf<Int?>(null) }   // 18/20/22 or null
    var engineFilter by remember { mutableStateOf<Int?>(null) }   // engine prefix id or null
    var categoryFilter by remember { mutableStateOf<String?>(null) } // "games"/"mods"/...
    var sourceFilter by remember { mutableStateOf<CatalogSource?>(null) }
    var platformFilter by remember { mutableStateOf<String?>(null) }
    var minRating by remember { mutableStateOf(0f) }
    var installedOnly by remember { mutableStateOf(false) }
    var notInstalledOnly by remember { mutableStateOf(false) }

    LaunchedEffect(screenshotQuery) {
        if (screenshotQuery != null) query = screenshotQuery
    }

    // 1. Load persisted filter state on first composition.
    LaunchedEffect(Unit) {
        val s = CatalogPrefs.load(context)
        query = s.query
        sortKey = s.sortKey
        sortDesc = s.sortDesc
        statusFilter = s.statusFilter
        engineFilter = s.engineFilter
        categoryFilter = s.categoryFilter
        sourceFilter = s.sourceFilter
        platformFilter = s.platformFilter
        minRating = s.minRating
        installedOnly = s.installedOnly
        notInstalledOnly = s.notInstalledOnly
        prefsLoaded = true
    }
    // 2. Save filter state whenever any tracked value changes (only after first load
    //    completes — otherwise we'd overwrite the persisted state with defaults).
    LaunchedEffect(prefsLoaded, query, sortKey, sortDesc, statusFilter, engineFilter,
        categoryFilter, sourceFilter, platformFilter, minRating, installedOnly, notInstalledOnly) {
        if (!prefsLoaded) return@LaunchedEffect
        CatalogPrefs.save(context, CatalogPrefs.State(
            query, sortKey, sortDesc, statusFilter, engineFilter,
            categoryFilter, sourceFilter, platformFilter, minRating, installedOnly, notInstalledOnly,
        ))
    }

    // Cache the set of thread_ids that are linked to an installed app for quick filtering.
    val installedThreadIds: Set<Int> = remember(mappings) {
        mappings.values.mapNotNull {
            it.threadId ?: F95UrlParser.extractThreadId(it.f95Url)
        }.toSet()
    }

    var detailGame by remember { mutableStateOf<SourceCatalogEntry?>(null) }

    val filteredAndSorted: List<SourceCatalogEntry> = remember(
        allEntries, query, sortKey, sortDesc,
        statusFilter, engineFilter, categoryFilter, sourceFilter, platformFilter, minRating,
        installedOnly, notInstalledOnly, installedThreadIds, labels,
    ) {
        if (allEntries.isEmpty()) return@remember emptyList()
        val parsed = parseSearchQuery(query)
        val freeText = parsed.freeText.lowercase()
        var seq = allEntries.asSequence()
        if (freeText.isNotEmpty()) {
            seq = seq.filter { entry ->
                entry.title.lowercase().contains(freeText) ||
                    (entry.developer?.lowercase()?.contains(freeText) == true) ||
                    entry.source.displayName.lowercase().contains(freeText)
            }
        }
        if (parsed.tags.isNotEmpty()) {
            seq = seq.filter { entry ->
                val tagNames = displayTags(entry, labels).map { it.lowercase() }
                if (tagNames.isEmpty()) false
                else parsed.tags.all { tq -> tagNames.any { it.contains(tq) } }
            }
        }
        statusFilter?.let { sf -> seq = seq.filter { sourceTagIds(it).contains(sf) } }
        engineFilter?.let { ef -> seq = seq.filter { sourceTagIds(it).contains(ef) } }
        categoryFilter?.let { cf -> seq = seq.filter { it.tags.any { tag -> tag.equals(cf, ignoreCase = true) } } }
        sourceFilter?.let { sf -> seq = seq.filter { it.source == sf } }
        platformFilter?.let { pf -> seq = seq.filter { entry -> entry.platforms.any { platformMatches(it, pf) } } }
        if (minRating > 0f) seq = seq.filter { (it.rating ?: 0.0) >= minRating }
        if (installedOnly) seq = seq.filter { f95ThreadIdOrNull(it)?.let { id -> id in installedThreadIds } == true }
        if (notInstalledOnly) seq = seq.filter { f95ThreadIdOrNull(it)?.let { id -> id in installedThreadIds } != true }

        val sorted = when (sortKey) {
            CatalogSortKey.Title -> seq.sortedBy { it.title.lowercase() }
            CatalogSortKey.Rating -> seq.sortedWith(
                compareBy({ it.rating ?: 0.0 }, { it.popularity ?: 0.0 })
            )
            CatalogSortKey.Updated -> seq.sortedBy { it.modifiedAt ?: it.publishedAt ?: "" }
            CatalogSortKey.Newest -> seq.sortedBy { it.publishedAt ?: it.modifiedAt ?: "" }
            CatalogSortKey.Views -> seq.sortedBy { it.popularity ?: 0.0 }
            CatalogSortKey.Likes -> seq.sortedBy { it.popularity ?: 0.0 }
        }.toList()
        if (sortDesc) sorted.reversed() else sorted
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
                                append("${filteredAndSorted.size} of ${allEntries.size}")
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
                placeholder = { Text("Search title/dev + tag:harem tag:incest") },
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
                (labels.tags.values.asSequence() + labels.prefixes.values.asSequence())
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
                                query = before + "tag:" + suggestion + " "
                            },
                            label = { Text(suggestion, fontSize = 12.sp) },
                        )
                    }
                }
            }
            CatalogFilters(
                statusFilter = statusFilter, onStatusChange = { statusFilter = it },
                engineFilter = engineFilter, onEngineChange = { engineFilter = it },
                categoryFilter = categoryFilter, onCategoryChange = { categoryFilter = it },
                sourceFilter = sourceFilter, onSourceChange = { sourceFilter = it },
                platformFilter = platformFilter, onPlatformChange = { platformFilter = it },
                minRating = minRating, onMinRatingChange = { minRating = it },
                installedOnly = installedOnly, onInstalledOnlyChange = { v -> installedOnly = v; if (v) notInstalledOnly = false },
                notInstalledOnly = notInstalledOnly, onNotInstalledOnlyChange = { v -> notInstalledOnly = v; if (v) installedOnly = false },
            )
            HorizontalDivider()
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading catalog…", style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (allEntries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        "No catalog data yet. Check your internet connection and reopen the app.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 96.dp),
                ) {
                    items(filteredAndSorted, key = { "${it.source.name}:${it.sourceId}" }) { g ->
                        CatalogRowCard(
                            entry = g,
                            labels = labels,
                            installed = f95ThreadIdOrNull(g)?.let { it in installedThreadIds } == true,
                            onClick = { detailGame = g },
                            onOpen = { onOpenThread(g.canonicalUrl) },
                        )
                    }
                }
            }
        }
    }

    detailGame?.let { g ->
        CatalogDetailDialog(
            game = g,
            labels = labels,
            installed = f95ThreadIdOrNull(g)?.let { it in installedThreadIds } == true,
            onDismiss = {
                detailGame = null
                // After the dialog goes away the underlying screen's OutlinedTextField
                // sometimes captures focus and pops the keyboard. Clear focus explicitly.
                focusManager.clearFocus()
            },
            onOpenThread = { onOpenThread(g.canonicalUrl) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CatalogFilters(
    statusFilter: Int?, onStatusChange: (Int?) -> Unit,
    engineFilter: Int?, onEngineChange: (Int?) -> Unit,
    categoryFilter: String?, onCategoryChange: (String?) -> Unit,
    sourceFilter: CatalogSource?, onSourceChange: (CatalogSource?) -> Unit,
    platformFilter: String?, onPlatformChange: (String?) -> Unit,
    minRating: Float, onMinRatingChange: (Float) -> Unit,
    installedOnly: Boolean, onInstalledOnlyChange: (Boolean) -> Unit,
    notInstalledOnly: Boolean, onNotInstalledOnlyChange: (Boolean) -> Unit,
) {
    var filterSheetOpen by remember { mutableStateOf(false) }
    val activeAdvancedCount =
        (if (statusFilter != null) 1 else 0) +
            (if (engineFilter != null) 1 else 0) +
            (if (categoryFilter != null) 1 else 0) +
            (if (minRating > 0f) 1 else 0) +
            (if (installedOnly) 1 else 0) +
            (if (notInstalledOnly) 1 else 0)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (source in CatalogSource.values()) {
                FilterChip(
                    selected = sourceFilter == source,
                    onClick = { onSourceChange(if (sourceFilter == source) null else source) },
                    label = { Text(source.displayName, fontSize = 11.sp) },
                )
            }
            for (platform in PLATFORM_FILTERS) {
                FilterChip(
                    selected = platformFilter == platform,
                    onClick = { onPlatformChange(if (platformFilter == platform) null else platform) },
                    label = { Text(platformDisplayName(platform), fontSize = 11.sp) },
                )
            }
            FilterChip(
                selected = activeAdvancedCount > 0,
                onClick = { filterSheetOpen = true },
                leadingIcon = { Icon(Icons.Default.FilterList, null, modifier = Modifier.size(16.dp)) },
                label = {
                    Text(
                        if (activeAdvancedCount > 0) "Filters ($activeAdvancedCount)" else "Filters",
                        fontSize = 11.sp,
                    )
                },
            )
        }
        if (activeAdvancedCount > 0) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                statusFilter?.let { id ->
                    ActiveFilterChip(statusLabel(id), onClear = { onStatusChange(null) })
                }
                if (installedOnly) ActiveFilterChip("Installed", onClear = { onInstalledOnlyChange(false) })
                if (notInstalledOnly) ActiveFilterChip("Not installed", onClear = { onNotInstalledOnlyChange(false) })
                engineFilter?.let { id ->
                    ActiveFilterChip(KnownPrefixes.ENGINE_IDS.firstOrNull { it.first == id }?.second ?: "Engine", onClear = { onEngineChange(null) })
                }
                categoryFilter?.let { category ->
                    ActiveFilterChip(category.replaceFirstChar { it.uppercase() }, onClear = { onCategoryChange(null) })
                }
                if (minRating > 0f) ActiveFilterChip("≥ $minRating ★", onClear = { onMinRatingChange(0f) })
            }
        }
    }

    if (filterSheetOpen) {
        ModalBottomSheet(onDismissRequest = { filterSheetOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Catalog filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                FilterSection("Source") {
                    CatalogSource.values().forEach { source ->
                        FilterChip(
                            selected = sourceFilter == source,
                            onClick = { onSourceChange(if (sourceFilter == source) null else source) },
                            label = { Text(source.displayName) },
                        )
                    }
                }
                FilterSection("Platform") {
                    PLATFORM_FILTERS.forEach { platform ->
                        FilterChip(
                            selected = platformFilter == platform,
                            onClick = { onPlatformChange(if (platformFilter == platform) null else platform) },
                            label = { Text(platformDisplayName(platform)) },
                        )
                    }
                }
                FilterSection("Library") {
                    FilterChip(
                        selected = installedOnly,
                        onClick = { onInstalledOnlyChange(!installedOnly) },
                        label = { Text("Installed") },
                    )
                    FilterChip(
                        selected = notInstalledOnly,
                        onClick = { onNotInstalledOnlyChange(!notInstalledOnly) },
                        label = { Text("Not installed") },
                    )
                }
                FilterSection("Status") {
                    StatusFilterChip(KnownPrefixes.COMPLETED, statusFilter, onStatusChange)
                    StatusFilterChip(KnownPrefixes.ONHOLD, statusFilter, onStatusChange)
                    StatusFilterChip(KnownPrefixes.ABANDONED, statusFilter, onStatusChange)
                }
                FilterSection("Engine") {
                    for ((id, name) in KnownPrefixes.ENGINE_IDS) {
                        FilterChip(
                            selected = engineFilter == id,
                            onClick = { onEngineChange(if (engineFilter == id) null else id) },
                            label = { Text(name) },
                        )
                    }
                }
                FilterSection("Type") {
                    for (cat in listOf("games", "mods", "comics", "animations")) {
                        FilterChip(
                            selected = categoryFilter == cat,
                            onClick = { onCategoryChange(if (categoryFilter == cat) null else cat) },
                            label = { Text(cat.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                FilterSection("Rating") {
                    for (r in floatArrayOf(3f, 4f, 4.5f)) {
                        FilterChip(
                            selected = minRating == r,
                            onClick = { onMinRatingChange(if (minRating == r) 0f else r) },
                            label = { Text("≥ $r ★") },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            onSourceChange(null)
                            onPlatformChange(null)
                            onStatusChange(null)
                            onEngineChange(null)
                            onCategoryChange(null)
                            onMinRatingChange(0f)
                            onInstalledOnlyChange(false)
                            onNotInstalledOnlyChange(false)
                        },
                    ) { Text("Clear all") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { filterSheetOpen = false }) { Text("Done") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun ActiveFilterChip(label: String, onClear: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onClear,
        trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp)) },
        label = { Text(label, fontSize = 11.sp) },
    )
}

@Composable
private fun StatusFilterChip(id: Int, statusFilter: Int?, onStatusChange: (Int?) -> Unit) {
    FilterChip(
        selected = statusFilter == id,
        onClick = { onStatusChange(if (statusFilter == id) null else id) },
        label = { Text(statusLabel(id)) },
    )
}

@Composable
private fun CatalogRowCard(
    entry: SourceCatalogEntry,
    labels: CatalogLabels?,
    installed: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (installed) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.developer?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    entry.versionText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val tagNames = displayTags(entry, labels).take(6)
                if (tagNames.isNotEmpty()) {
                    Text(
                        tagNames.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(entry.source.displayName, fontSize = 10.sp) },
                        modifier = Modifier.height(24.dp),
                    )
                    entry.rating?.let {
                        Text("%.2f ★".format(it), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    entry.popularity?.takeIf { it > 0.0 }?.let {
                        Text(formatViews(it.toLong()), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("#${entry.sourceId}", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    entry.modifiedAt?.takeIf { it.isNotBlank() }?.let {
                        Text(formatIsoDate(it), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.OpenInBrowser, "Open thread", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CatalogDetailDialog(
    game: SourceCatalogEntry,
    labels: CatalogLabels?,
    installed: Boolean,
    onDismiss: () -> Unit,
    onOpenThread: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val wideDialog = configuration.screenWidthDp >= 700 || configuration.screenWidthDp > configuration.screenHeightDp
    val tagNames = displayTags(game, labels)
    var fullSizeOpen by remember { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .then(if (wideDialog) Modifier.fillMaxHeight(0.9f) else Modifier),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    game.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DiagnosticsScreenshotIconButton(namePrefix = "catalog-dialog")
            }
        },
        text = {
            val coverPane: @Composable (Modifier) -> Unit = { modifier ->
                if (!game.coverUrl.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = game.coverUrl,
                        contentDescription = "Cover",
                        modifier = modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { fullSizeOpen = true },
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
            val detailsPane: @Composable () -> Unit = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("Source: ${game.source.displayName}", style = MaterialTheme.typography.bodySmall)
                    game.developer?.let { Text("Developer: $it", style = MaterialTheme.typography.bodySmall) }
                    game.versionText?.let { Text("Version: $it", style = MaterialTheme.typography.bodySmall) }
                    game.rating?.let { Text("Rating: %.2f / 5".format(it), style = MaterialTheme.typography.bodySmall) }
                    game.popularity?.takeIf { it > 0.0 }?.let { Text("Popularity: ${formatViews(it.toLong())}", style = MaterialTheme.typography.bodySmall) }
                    game.publishedAt?.let { Text("Published: ${formatIsoDate(it)}", style = MaterialTheme.typography.bodySmall) }
                    game.modifiedAt?.let { Text("Updated: ${formatIsoDate(it)}", style = MaterialTheme.typography.bodySmall) }
                    Text("Source ID: #${game.sourceId}", style = MaterialTheme.typography.bodySmall)
                    if (game.platforms.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Platforms: ${game.platforms.joinToString(" • ")}",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                    if (tagNames.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Tags: ${tagNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (wideDialog && !game.coverUrl.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    coverPane(Modifier.width(280.dp).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight()) { detailsPane() }
                }
            } else {
                Column {
                    coverPane(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp))
                    if (!game.coverUrl.isNullOrBlank()) Spacer(Modifier.height(8.dp))
                    detailsPane()
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOpenThread(); onDismiss() }) { Text("Open thread") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
    if (fullSizeOpen && !game.coverUrl.isNullOrBlank()) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullSizeOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { fullSizeOpen = false },
                contentAlignment = Alignment.Center,
            ) {
                coil.compose.AsyncImage(
                    model = game.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }
    }
}

private fun sourceEntriesFromLegacyGames(games: List<CatalogGame>): List<SourceCatalogEntry> =
    games.map { game ->
        SourceCatalogEntry(
            source = CatalogSource.F95Zone,
            sourceId = game.thread_id.toString(),
            canonicalUrl = "https://f95zone.to/threads/${game.thread_id}/",
            title = game.title,
            developer = game.creator,
            versionText = game.version,
            modifiedAt = if (game.ts > 0L) java.time.Instant.ofEpochSecond(game.ts).toString() else null,
            tags = (game.prefixes + game.tags).map { it.toString() },
            rating = game.rating,
            popularity = game.views.toDouble(),
            coverUrl = game.cover,
        )
    }

private fun sourceTagIds(entry: SourceCatalogEntry): Set<Int> =
    entry.tags.mapNotNull { it.toIntOrNull() }.toSet()

private fun statusLabel(id: Int): String = when (id) {
    KnownPrefixes.COMPLETED -> "Completed"
    KnownPrefixes.ONHOLD -> "On hold"
    KnownPrefixes.ABANDONED -> "Abandoned"
    else -> "Status"
}

private fun platformDisplayName(platform: String): String = when (platform.lowercase()) {
    "windows" -> "Windows/VM"
    else -> platform
}

private fun platformMatches(platform: String, filter: String): Boolean {
    val normalized = platform.trim().lowercase()
    return normalized == filter.lowercase() ||
        (filter.equals("Mac", ignoreCase = true) && normalized == "macos") ||
        (filter.equals("Windows", ignoreCase = true) && normalized == "windows/pc")
}

private fun displayTags(entry: SourceCatalogEntry, labels: CatalogLabels?): List<String> =
    entry.tags.mapNotNull { raw ->
        val id = raw.toIntOrNull()
        when {
            id != null && labels != null -> labels.prefixes[raw] ?: labels.tags[raw] ?: raw
            raw.isBlank() -> null
            else -> raw
        }
    }.distinct()

private fun f95ThreadIdOrNull(entry: SourceCatalogEntry): Int? =
    if (entry.source == CatalogSource.F95Zone) entry.sourceId.toIntOrNull() else null

private fun formatIsoDate(value: String): String =
    value.take(10).ifBlank { value }

private fun formatViews(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.0fK".format(n / 1_000.0)
    else           -> n.toString()
}

private fun fmtEpochSec(epochSec: Long): String {
    if (epochSec <= 0L) return "—"
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
    return sdf.format(java.util.Date(epochSec * 1000L))
}

private fun formatRelativeTime(ms: Long): String {
    val now = System.currentTimeMillis()
    val deltaSec = (now - ms) / 1000
    if (deltaSec < 0) return "just now"
    return when {
        deltaSec < 60        -> "just now"
        deltaSec < 3600      -> "${deltaSec / 60}m ago"
        deltaSec < 86_400    -> "${deltaSec / 3600}h ago"
        deltaSec < 604_800   -> "${deltaSec / 86_400}d ago"
        else                 -> {
            val d = java.util.Date(ms)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(d)
        }
    }
}
