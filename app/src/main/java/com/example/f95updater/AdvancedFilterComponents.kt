@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.example.f95updater

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AdvancedFilterBar(
    summary: AdvancedFilterSummary,
    regexError: String?,
    onOpen: () -> Unit,
) {
    var showFullSummary by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 12.dp)) {
                BadgedBox(
                    badge = {
                        if (summary.count > 0) {
                            Badge { Text(summary.count.toString()) }
                        }
                    },
                ) {
                    Icon(Icons.Default.FilterList, null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Advanced")
            }
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    summary.fullText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = onOpen, onLongClick = { showFullSummary = true })
                        .semantics { contentDescription = summary.fullText }
                        .padding(vertical = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(
                    expanded = showFullSummary,
                    onDismissRequest = { showFullSummary = false },
                ) {
                    Text(
                        summary.fullText,
                        modifier = Modifier.widthIn(min = 260.dp, max = 440.dp).padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        regexError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CatalogAdvancedFilterDialog(
    state: CatalogAdvancedFilterState,
    query: String,
    availableTagLabels: List<String>,
    taxonomy: CanonicalTagTaxonomy,
    onApply: (CatalogAdvancedFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(state) { mutableStateOf(state) }
    var tagSearch by rememberSaveable { mutableStateOf("") }
    val regexError = regexValidationError(query, draft.searchMode)
    ResponsiveAdvancedDialog(
        title = "Catalog advanced filters",
        canApply = regexError == null,
        onApply = {
            onApply(draft)
            onDismiss()
        },
        onClear = { draft = draft.cleared() },
        onDismiss = onDismiss,
    ) {
        AdvancedFilterSection("Search behavior", initiallyExpanded = true) {
            ToggleFilterRow(
                "Include synopsis search",
                "Search the server-built English synopsis index when the search box is not blank.",
                draft.includeSynopsis,
            ) { draft = draft.copy(includeSynopsis = it) }
            SearchModeChoices(draft.searchMode) { draft = draft.copy(searchMode = it) }
            regexError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (draft.searchMode == CatalogSearchMode.Regex && draft.includeSynopsis) {
                Text(
                    "Regex applies to titles only. Synopsis search resumes when Regex is turned off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AdvancedFilterSection("Availability and status") {
            ExclusiveToggleChips(
                firstLabel = "Only installed",
                first = draft.installedOnly,
                secondLabel = "Not installed",
                second = draft.notInstalledOnly,
            ) { first, second -> draft = draft.copy(installedOnly = first, notInstalledOnly = second) }
            ExclusiveToggleChips(
                firstLabel = "Only wishlist",
                first = draft.wishlistOnly,
                secondLabel = "Not wishlist",
                second = draft.notWishlistOnly,
            ) { first, second -> draft = draft.copy(wishlistOnly = first, notWishlistOnly = second) }
            ToggleFilterRow(
                label = "Show ignored only",
                supporting = "Ignored games are hidden from normal catalog results.",
                checked = draft.ignoredOnly,
            ) { draft = draft.copy(ignoredOnly = it) }
            ChoiceChips(
                choices = listOf(
                    KnownPrefixes.COMPLETED to "Completed",
                    KnownPrefixes.ONHOLD to "On hold",
                    KnownPrefixes.ABANDONED to "Abandoned",
                ),
                selected = draft.statusFilter,
                onSelect = { draft = draft.copy(statusFilter = it) },
            )
        }
        AdvancedFilterSection("Source and platform") {
            ChoiceChips(
                choices = SourceRegistry.all().map { it.id to it.displayName }
                    .ifEmpty {
                        listOf(
                            SOURCE_F95ZONE to SOURCE_F95ZONE.sourceDisplayName,
                            SOURCE_ADULTGAMEWORLD to SOURCE_ADULTGAMEWORLD.sourceDisplayName,
                        )
                    },
                selected = draft.sourceFilter,
                onSelect = { draft = draft.copy(sourceFilter = it) },
            )
            ChoiceChips(
                choices = listOf("Android", "Windows", "Mac", "Linux").map { it to platformDisplayName(it) },
                selected = draft.platformFilter,
                onSelect = { draft = draft.copy(platformFilter = it) },
            )
        }
        AdvancedFilterSection("Engine and category") {
            ChoiceChips(
                choices = KnownPrefixes.ENGINE_IDS,
                selected = draft.engineFilter,
                onSelect = { draft = draft.copy(engineFilter = it) },
            )
            ChoiceChips(
                choices = listOf("games", "mods", "comics", "animations").map {
                    it to it.replaceFirstChar(Char::uppercase)
                },
                selected = draft.categoryFilter,
                onSelect = { draft = draft.copy(categoryFilter = it) },
            )
        }
        AdvancedFilterSection("Tags and canonical groups") {
            OutlinedTextField(
                value = tagSearch,
                onValueChange = { tagSearch = it },
                placeholder = { Text("Search catalog tags") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (tagSearch.isNotBlank()) {
                        IconButton(onClick = { tagSearch = "" }) {
                            Icon(Icons.Default.Close, "Clear tag search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val visibleTags = remember(availableTagLabels, tagSearch) {
                matchingTagLabels(availableTagLabels, tagSearch)
            }
            if (visibleTags.isNotEmpty() && tagSearch.isNotBlank()) {
                TextButton(
                    onClick = {
                        val tokens = visibleTags.map(::catalogTagFilterToken).distinct()
                        draft = draft.copy(
                            tagGroups = draft.tagGroups + CatalogTagGroup(tagSearch.trim(), tokens),
                        )
                        tagSearch = ""
                    },
                ) {
                    Text("Add matching tags as one OR group")
                }
            }
            if (visibleTags.isEmpty()) {
                Text(
                    if (availableTagLabels.isEmpty()) "No catalog tags loaded." else "No matching tags.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleTags.forEach { label ->
                        val token = catalogTagFilterToken(label)
                        FilterChip(
                            selected = token in draft.selectedTagTokens,
                            onClick = {
                                draft = draft.copy(
                                    selectedTagTokens =
                                        if (token in draft.selectedTagTokens) {
                                            draft.selectedTagTokens - token
                                        } else {
                                            draft.selectedTagTokens + token
                                        },
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
            draft.tagGroups.forEach { group ->
                FilterChip(
                    selected = true,
                    onClick = { draft = draft.copy(tagGroups = draft.tagGroups - group) },
                    label = { Text("${group.label} ×") },
                )
            }
            taxonomy.byFacet().forEach { (facet, tags) ->
                Text(
                    facet.replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tag.id in draft.canonicalTagIds,
                            onClick = {
                                draft = draft.copy(
                                    canonicalTagIds =
                                        if (tag.id in draft.canonicalTagIds) {
                                            draft.canonicalTagIds - tag.id
                                        } else {
                                            draft.canonicalTagIds + tag.id
                                        },
                                )
                            },
                            label = { Text(tag.label) },
                        )
                    }
                }
            }
        }
        AdvancedFilterSection("Rating, popularity, and dates") {
            Text("Minimum rating", style = MaterialTheme.typography.titleSmall)
            ChoiceChips(
                choices = listOf(0f to "Any", 3f to "3+", 4f to "4+", 4.5f to "4.5+"),
                selected = draft.minRating,
                allowClear = false,
                onSelect = { draft = draft.copy(minRating = it ?: 0f) },
            )
            Text("Minimum popularity", style = MaterialTheme.typography.titleSmall)
            ChoiceChips(
                choices = listOf(0L to "Any", 1_000L to "1K+", 10_000L to "10K+", 100_000L to "100K+"),
                selected = draft.minPopularity,
                allowClear = false,
                onSelect = { draft = draft.copy(minPopularity = it ?: 0L) },
            )
            ChoiceChips(
                choices = CatalogDateField.entries.map { it to it.label },
                selected = draft.dateField,
                allowClear = false,
                onSelect = { draft = draft.copy(dateField = it ?: CatalogDateField.Updated) },
            )
            ChoiceChips(
                choices = DateRangeFilter.entries.map { it to it.label },
                selected = draft.dateRange,
                allowClear = false,
                onSelect = { draft = draft.copy(dateRange = it ?: DateRangeFilter.Any) },
            )
        }
    }
}

@Composable
internal fun InstalledAdvancedFilterDialog(
    state: InstalledAdvancedFilterState,
    query: String,
    availableUserTags: List<String>,
    onApply: (InstalledAdvancedFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(state) { mutableStateOf(state) }
    var tagSearch by rememberSaveable { mutableStateOf("") }
    val regexError = regexValidationError(query, draft.searchMode)
    ResponsiveAdvancedDialog(
        title = "Installed advanced filters",
        canApply = regexError == null,
        onApply = {
            onApply(draft)
            onDismiss()
        },
        onClear = { draft = draft.cleared() },
        onDismiss = onDismiss,
    ) {
        AdvancedFilterSection("Search behavior", initiallyExpanded = true) {
            SearchModeChoices(draft.searchMode) { draft = draft.copy(searchMode = it) }
            regexError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        AdvancedFilterSection("Update and mapping status") {
            val statuses = UpdateStatus.entries
                .filterNot { it == UpdateStatus.CheckFailed || it == UpdateStatus.Unknown }
                .sortedBy(::statusLabel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                statuses.forEach { status ->
                    FilterChip(
                        selected = status in draft.activeStatuses,
                        onClick = {
                            draft = draft.copy(
                                activeStatuses =
                                    if (status in draft.activeStatuses) {
                                        draft.activeStatuses - status
                                    } else {
                                        draft.activeStatuses + status
                                    },
                            )
                        },
                        label = { Text(statusLabel(status)) },
                    )
                }
            }
            ToggleFilterRow("Manually matched", null, draft.manualOnly) {
                draft = draft.copy(manualOnly = it)
            }
            ToggleFilterRow("Thread updated after install", null, draft.threadUpdatedAfterInstallOnly) {
                draft = draft.copy(threadUpdatedAfterInstallOnly = it)
            }
        }
        AdvancedFilterSection("Local source and runners") {
            ChoiceChips(
                choices = listOf(
                    AppSource.Android to "Android",
                    AppSource.Managed to "Managed",
                    AppSource.JoiPlay to "JoiPlay available",
                    AppSource.Winlator to "Winlator available",
                    AppSource.Kirikiroid to "Kirikiroid available",
                ),
                selected = draft.sourceFilter,
                onSelect = { draft = draft.copy(sourceFilter = it) },
            )
        }
        AdvancedFilterSection("User status and rating") {
            ChoiceChips(
                choices = UserGameStatus.entries.filterNot { it == UserGameStatus.None }.map { it to it.label },
                selected = draft.userStatus,
                onSelect = { draft = draft.copy(userStatus = it) },
            )
            ChoiceChips(
                choices = GameState.assignable.map { it to it.label },
                selected = draft.gameState,
                onSelect = { draft = draft.copy(gameState = it) },
            )
            Text("Minimum personal rating", style = MaterialTheme.typography.titleSmall)
            ChoiceChips(
                choices = (0..5).map { it to if (it == 0) "Any" else "$it+" },
                selected = draft.minPersonalRating,
                allowClear = false,
                onSelect = { draft = draft.copy(minPersonalRating = it ?: 0) },
            )
        }
        AdvancedFilterSection("User tags") {
            OutlinedTextField(
                value = tagSearch,
                onValueChange = { tagSearch = it },
                placeholder = { Text("Search user tags") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (tagSearch.isNotBlank()) {
                        IconButton(onClick = { tagSearch = "" }) {
                            Icon(Icons.Default.Close, "Clear tag search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val visibleTags = remember(availableUserTags, tagSearch) {
                matchingTagLabels(availableUserTags, tagSearch)
            }
            if (visibleTags.isEmpty()) {
                Text(
                    if (availableUserTags.isEmpty()) "No user tags have been assigned." else "No matching tags.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleTags.forEach { tag ->
                        FilterChip(
                            selected = tag in draft.selectedUserTags,
                            onClick = {
                                draft = draft.copy(
                                    selectedUserTags =
                                        if (tag in draft.selectedUserTags) {
                                            draft.selectedUserTags - tag
                                        } else {
                                            draft.selectedUserTags + tag
                                        },
                                )
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
        AdvancedFilterSection("Saves, backups, storage, and dates") {
            ToggleFilterRow("Has saves", null, draft.hasSavesOnly) {
                draft = draft.copy(hasSavesOnly = it)
            }
            ToggleFilterRow("Has backup", null, draft.hasBackupOnly) {
                draft = draft.copy(hasBackupOnly = it)
            }
            ChoiceChips(
                choices = StorageFilter.entries.map { it to it.label },
                selected = draft.storageFilter,
                allowClear = false,
                onSelect = { draft = draft.copy(storageFilter = it ?: StorageFilter.Any) },
            )
            ChoiceChips(
                choices = InstalledDateField.entries.map { it to it.label },
                selected = draft.dateField,
                allowClear = false,
                onSelect = { draft = draft.copy(dateField = it ?: InstalledDateField.Installed) },
            )
            ChoiceChips(
                choices = DateRangeFilter.entries.map { it to it.label },
                selected = draft.dateRange,
                allowClear = false,
                onSelect = { draft = draft.copy(dateRange = it ?: DateRangeFilter.Any) },
            )
        }
    }
}

@Composable
private fun ResponsiveAdvancedDialog(
    title: String,
    canApply: Boolean,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val fullScreen = useFullScreenAdvancedDialog(configuration.screenWidthDp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (fullScreen) 1f else 0.9f)
                .fillMaxHeight(if (fullScreen) 1f else 0.9f)
                .then(if (fullScreen) Modifier else Modifier.widthIn(max = 900.dp)),
            shape = if (fullScreen) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { content() }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Default.ClearAll, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear all")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onApply, enabled = canApply) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun AdvancedFilterSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { expanded = true },
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ToggleFilterRow(
    label: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onCheckedChange(!checked) },
                onLongClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SearchModeChoices(
    selected: CatalogSearchMode,
    onSelect: (CatalogSearchMode) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = selected == CatalogSearchMode.WholeWord,
            onClick = {
                onSelect(
                    if (selected == CatalogSearchMode.WholeWord) {
                        CatalogSearchMode.Normal
                    } else {
                        CatalogSearchMode.WholeWord
                    },
                )
            },
            label = { Text("Whole word") },
        )
        FilterChip(
            selected = selected == CatalogSearchMode.Regex,
            onClick = {
                onSelect(
                    if (selected == CatalogSearchMode.Regex) {
                        CatalogSearchMode.Normal
                    } else {
                        CatalogSearchMode.Regex
                    },
                )
            },
            label = { Text("Regex") },
        )
    }
}

@Composable
private fun ExclusiveToggleChips(
    firstLabel: String,
    first: Boolean,
    secondLabel: String,
    second: Boolean,
    onChange: (Boolean, Boolean) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = first,
            onClick = { onChange(!first, false) },
            label = { Text(firstLabel) },
        )
        FilterChip(
            selected = second,
            onClick = { onChange(false, !second) },
            label = { Text(secondLabel) },
        )
    }
}

@Composable
private fun <T> ChoiceChips(
    choices: List<Pair<T, String>>,
    selected: T?,
    allowClear: Boolean = true,
    onSelect: (T?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = {
                    onSelect(if (allowClear && selected == value) null else value)
                },
                label = { Text(label) },
            )
        }
    }
}
