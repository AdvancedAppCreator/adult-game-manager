package com.example.f95updater

import java.time.Instant
import java.time.temporal.ChronoUnit

enum class DateRangeFilter(val label: String, val days: Long?) {
    Any("Any time", null),
    Last7Days("Last 7 days", 7),
    Last30Days("Last 30 days", 30),
    Last90Days("Last 90 days", 90),
    LastYear("Last year", 365),
    ;

    fun cutoffEpochMs(nowEpochMs: Long): Long? =
        days?.let { nowEpochMs - it * 24L * 60L * 60L * 1000L }

    fun cutoffIso(nowEpochMs: Long): String? =
        days?.let { Instant.ofEpochMilli(nowEpochMs).minus(it, ChronoUnit.DAYS).toString() }
}

enum class CatalogDateField(val label: String) {
    Updated("Updated"),
    Published("Published"),
}

enum class InstalledDateField(val label: String) {
    Installed("Installed"),
    AppUpdated("App updated"),
    LastUsed("Last used"),
}

enum class StorageFilter(val label: String, val minBytes: Long) {
    Any("Any size", 0L),
    Over100Mb("100 MB+", 100L * 1024L * 1024L),
    Over1Gb("1 GB+", 1024L * 1024L * 1024L),
    Over5Gb("5 GB+", 5L * 1024L * 1024L * 1024L),
}

internal data class CatalogAdvancedFilterState(
    val includeSynopsis: Boolean = false,
    val searchMode: CatalogSearchMode = CatalogSearchMode.Normal,
    val statusFilter: Int? = null,
    val engineFilter: Int? = null,
    val categoryFilter: String? = null,
    val sourceFilter: String? = null,
    val platformFilter: String? = null,
    val minRating: Float = 0f,
    val minPopularity: Long = 0L,
    val dateField: CatalogDateField = CatalogDateField.Updated,
    val dateRange: DateRangeFilter = DateRangeFilter.Any,
    val installedOnly: Boolean = false,
    val notInstalledOnly: Boolean = false,
    val wishlistOnly: Boolean = false,
    val notWishlistOnly: Boolean = false,
    val ignoredOnly: Boolean = false,
    val selectedTagTokens: Set<String> = emptySet(),
    val canonicalTagIds: Set<String> = emptySet(),
    val tagGroups: List<CatalogTagGroup> = emptyList(),
) {
    fun cleared(): CatalogAdvancedFilterState = CatalogAdvancedFilterState()
}

internal data class InstalledAdvancedFilterState(
    val searchMode: CatalogSearchMode = CatalogSearchMode.Normal,
    val activeStatuses: Set<UpdateStatus> = emptySet(),
    val sourceFilter: AppSource? = null,
    val manualOnly: Boolean = false,
    val threadUpdatedAfterInstallOnly: Boolean = false,
    val userStatus: UserGameStatus? = null,
    val minPersonalRating: Int = 0,
    val gameState: GameState? = null,
    val selectedUserTags: Set<String> = emptySet(),
    val hasSavesOnly: Boolean = false,
    val hasBackupOnly: Boolean = false,
    val storageFilter: StorageFilter = StorageFilter.Any,
    val dateField: InstalledDateField = InstalledDateField.Installed,
    val dateRange: DateRangeFilter = DateRangeFilter.Any,
) {
    fun cleared(): InstalledAdvancedFilterState = InstalledAdvancedFilterState()
}

internal data class AdvancedFilterSummary(
    val items: List<String>,
) {
    val count: Int get() = items.size
    val fullText: String
        get() = if (items.isEmpty()) {
            "No active advanced filters"
        } else {
            "$count ${if (count == 1) "filter" else "filters"}: ${items.joinToString(" · ")}"
        }

    fun truncated(maxCharacters: Int): String {
        require(maxCharacters > 0)
        val text = fullText
        if (text.length <= maxCharacters) return text
        if (maxCharacters == 1) return "…"
        return text.take(maxCharacters - 1).trimEnd() + "…"
    }
}

internal fun useFullScreenAdvancedDialog(screenWidthDp: Int): Boolean = screenWidthDp < 600

internal fun matchingTagLabels(labels: List<String>, search: String, limit: Int = 100): List<String> {
    val needle = search.trim()
    return labels.asSequence()
        .filter { needle.isBlank() || it.contains(needle, ignoreCase = true) }
        .take(limit)
        .toList()
}

internal fun regexValidationError(query: String, mode: CatalogSearchMode): String? {
    if (mode != CatalogSearchMode.Regex) return null
    val parsed = parseSearchQuery(query)
    val patterns = buildList {
        parsed.freeText.takeIf(String::isNotBlank)?.let(::add)
        addAll(parsed.excludedText.filter(String::isNotBlank))
    }
    return patterns.firstNotNullOfOrNull { pattern ->
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            .exceptionOrNull()
            ?.message
            ?.let { "Invalid regex: $it" }
    }
}

internal fun catalogAdvancedFilterSummary(
    state: CatalogAdvancedFilterState,
    taxonomy: CanonicalTagTaxonomy = CanonicalTagTaxonomy(),
    availableTagLabels: List<String> = emptyList(),
): AdvancedFilterSummary = AdvancedFilterSummary(buildList {
    if (state.includeSynopsis) add("Synopsis")
    when (state.searchMode) {
        CatalogSearchMode.Normal -> Unit
        CatalogSearchMode.WholeWord -> add("Whole word")
        CatalogSearchMode.Regex -> add("Regex")
    }
    state.statusFilter?.let(::catalogStatusFilterLabel)?.let(::add)
    state.engineFilter?.let { id ->
        add(KnownPrefixes.ENGINE_IDS.firstOrNull { it.first == id }?.second ?: "Engine $id")
    }
    state.categoryFilter?.let { add(it.replaceFirstChar(Char::uppercase)) }
    state.sourceFilter?.let { add(it.sourceDisplayName) }
    state.platformFilter?.let { add(platformDisplayName(it)) }
    if (state.installedOnly) add("Installed")
    if (state.notInstalledOnly) add("Not installed")
    if (state.wishlistOnly) add("Wishlist")
    if (state.notWishlistOnly) add("Not wishlist")
    if (state.ignoredOnly) add("Ignored")
    state.selectedTagTokens.sorted().forEach { token ->
        add(tagFilterLabelForToken(token, availableTagLabels))
    }
    state.tagGroups.forEach { add(it.label) }
    state.canonicalTagIds.sorted().forEach { add(taxonomy.labelOf(it) ?: it) }
    if (state.minRating > 0f) add("Rating ${state.minRating}+")
    if (state.minPopularity > 0L) add("Popularity ${formatFilterCount(state.minPopularity)}+")
    if (state.dateRange != DateRangeFilter.Any) {
        add("${state.dateField.label}: ${state.dateRange.label}")
    }
})

internal fun installedAdvancedFilterSummary(
    state: InstalledAdvancedFilterState,
): AdvancedFilterSummary = AdvancedFilterSummary(buildList {
    when (state.searchMode) {
        CatalogSearchMode.Normal -> Unit
        CatalogSearchMode.WholeWord -> add("Whole word")
        CatalogSearchMode.Regex -> add("Regex")
    }
    state.activeStatuses.sortedBy(::statusLabel).forEach { add(statusLabel(it)) }
    state.sourceFilter?.let { add(installedSourceFilterLabel(it)) }
    if (state.manualOnly) add("Manually matched")
    if (state.threadUpdatedAfterInstallOnly) add("Thread updated")
    state.userStatus?.let { add(it.label) }
    if (state.minPersonalRating > 0) add("Personal rating ${state.minPersonalRating}+")
    state.gameState?.let { add(it.label) }
    state.selectedUserTags.sorted().forEach { add("Tag: $it") }
    if (state.hasSavesOnly) add("Has saves")
    if (state.hasBackupOnly) add("Has backup")
    if (state.storageFilter != StorageFilter.Any) add(state.storageFilter.label)
    if (state.dateRange != DateRangeFilter.Any) {
        add("${state.dateField.label}: ${state.dateRange.label}")
    }
})

internal fun matchesLibrarySearchText(
    app: InstalledApp,
    mapping: AppMapping?,
    associatedCatalogTitle: String?,
    query: String,
    mode: CatalogSearchMode,
): Boolean {
    if (query.isBlank()) return true
    val values = sequenceOf(
        effectiveGameName(app, mapping, associatedCatalogTitle),
        app.label,
        app.launcherLabel,
        associatedCatalogTitle,
        mapping?.mappedCatalogTitle,
        app.packageName,
        app.storagePath,
        app.storageFolderName,
    ).filterNotNull()
    return when (mode) {
        CatalogSearchMode.Normal -> values.any { it.contains(query, ignoreCase = true) }
        CatalogSearchMode.WholeWord -> values.any { SaveSearchMatcher.matches(it, query, wholeWord = true) }
        CatalogSearchMode.Regex -> {
            val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
                ?: return false
            values.any(regex::containsMatchIn)
        }
    }
}

internal fun installedDateValue(app: InstalledApp, mapping: AppMapping?, field: InstalledDateField): Long =
    when (field) {
        InstalledDateField.Installed -> effectiveInstalledDate(app, mapping)
        InstalledDateField.AppUpdated -> appUpdatedAt(app)
        InstalledDateField.LastUsed -> app.lastUsedTime
    }

private fun catalogStatusFilterLabel(id: Int): String = when (id) {
    KnownPrefixes.COMPLETED -> "Completed"
    KnownPrefixes.ONHOLD -> "On hold"
    KnownPrefixes.ABANDONED -> "Abandoned"
    else -> "Status $id"
}

private fun installedSourceFilterLabel(source: AppSource): String = when (source) {
    AppSource.Android -> "Android"
    AppSource.Managed -> "Managed"
    AppSource.JoiPlay -> "JoiPlay available"
    AppSource.Winlator -> "Winlator available"
    AppSource.Kirikiroid -> "Kirikiroid available"
}

private fun formatFilterCount(value: Long): String = when {
    value >= 1_000_000L -> "${value / 1_000_000L}M"
    value >= 1_000L -> "${value / 1_000L}K"
    else -> value.toString()
}
