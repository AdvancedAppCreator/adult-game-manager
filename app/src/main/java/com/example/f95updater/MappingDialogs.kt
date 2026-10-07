package com.example.f95updater

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
private fun EditMappingSearchResults(
    query: String,
    searching: Boolean,
    results: List<CatalogSearchResult>,
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
                    results = results,
                    onPick = onPickCatalog,
                    modifier = if (fillResults) Modifier.weight(1f, fill = true) else Modifier,
                    fillAvailable = fillResults,
                )
            }
        }
    }
}

@Composable
internal fun EditMappingDialog(
    row: AppRow,
    associatedCatalogTitle: String?,
    isHidden: Boolean,
    catalog: CatalogRepository,
    searcher: WebSearcher,
    onDismiss: () -> Unit,
    onSave: (String, String, UserGameStatus, Int?, String, String) -> Unit,
    onPickCatalog: (CatalogGame) -> Unit,
    onPickExternal: (ExternalMirrorResult) -> Unit,
    onMarkNotOnF95: () -> Unit,
    onClearNotOnF95: () -> Unit,
    onClear: () -> Unit,
    onMarkInstalled: () -> Unit,
    onToggleHide: () -> Unit,
) {
    var url by remember { mutableStateOf(row.mapping?.f95Url ?: "") }
    val defaultName = defaultGameName(row.installed, row.mapping, associatedCatalogTitle)
    var gameName by remember(row.installed.packageName, row.mapping?.displayNameOverride, associatedCatalogTitle) {
        mutableStateOf(effectiveGameName(row.installed, row.mapping, associatedCatalogTitle))
    }
    var userStatus by remember(row.installed.packageName) { mutableStateOf(row.mapping?.userStatus ?: UserGameStatus.None) }
    var personalRating by remember(row.installed.packageName) { mutableStateOf(row.mapping?.personalRating) }
    var personalNotes by remember(row.installed.packageName) { mutableStateOf(row.mapping?.personalNotes.orEmpty()) }
    var manualCorrectionNote by remember(row.installed.packageName) { mutableStateOf(row.mapping?.manualCorrectionNote.orEmpty()) }
    var query by remember { mutableStateOf(row.installed.readmeTitle ?: row.installed.label) }
    var results by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
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
    // Live search: debounce ~150 ms after typing, then query the local catalog.
    LaunchedEffect(query) {
        showExternalResults = false
        externalResults = emptyList()
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(180)
        try {
            results = catalog.search(q, limit = 25)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLog.w("CatalogSearch", "Manual title search failed", e)
            results = emptyList()
        } finally {
            searching = false
        }
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
            OutlinedTextField(
                value = gameName,
                onValueChange = { gameName = it },
                label = { Text("Game name") },
                supportingText = { Text("Display only; catalog association remains separate.") },
                singleLine = true,
                trailingIcon = {
                    if (gameName != defaultName) {
                        IconButton(onClick = { gameName = defaultName }) {
                            Icon(Icons.Default.Restore, contentDescription = "Reset game name")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Catalog association: ${associatedCatalogTitle?.takeIf { it.isNotBlank() } ?: "Not associated"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.installed.label != defaultName) {
                Text(
                    "Detected name: ${row.installed.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                "External source choices save as manual/external.",
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
                    gameName.ifBlank { defaultName },
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
                            .dialogVerticalScroll(dialogScrollState),
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
                        .dialogVerticalScroll(dialogScrollState),
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
                    Button(
                        onClick = {
                            onSave(
                                url,
                                displayNameOverrideFor(gameName, defaultName),
                                userStatus,
                                personalRating,
                                personalNotes,
                                manualCorrectionNote,
                            )
                        },
                    ) { Text("Save") }
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
                        Button(
                            onClick = {
                                onSave(
                                    url,
                                    displayNameOverrideFor(gameName, defaultName),
                                    userStatus,
                                    personalRating,
                                    personalNotes,
                                    manualCorrectionNote,
                                )
                            },
                        ) { Text("Save") }
                    }
                }
            }
        },
    )
}
