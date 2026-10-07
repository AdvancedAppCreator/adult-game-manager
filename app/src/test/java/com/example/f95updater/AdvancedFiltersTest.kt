package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFiltersTest {
    @Test
    fun matchingTagLabelsFiltersCaseInsensitivelyAndLimitsResults() {
        assertEquals(
            listOf("Favorite", "family"),
            matchingTagLabels(listOf("Favorite", "Completed", "family"), "FAV".dropLast(1)),
        )
        assertEquals(2, matchingTagLabels(listOf("a", "b", "c"), "", limit = 2).size)
    }

    @Test
    fun catalogSummaryCountsEveryDialogSelectionAndClearResetsAll() {
        val taxonomy = CanonicalTagTaxonomy(
            facets = listOf("genre"),
            tags = listOf(CanonicalTag("genre:rpg", "genre", "RPG")),
        )
        val state = CatalogAdvancedFilterState(
            includeSynopsis = true,
            searchMode = CatalogSearchMode.WholeWord,
            sourceFilter = SOURCE_F95ZONE,
            selectedTagTokens = setOf("harem"),
            canonicalTagIds = setOf("genre:rpg"),
            minRating = 4f,
        )

        val summary = catalogAdvancedFilterSummary(state, taxonomy, listOf("Harem"))

        assertEquals(6, summary.count)
        assertTrue(summary.fullText.contains("Synopsis"))
        assertTrue(summary.fullText.contains("Whole word"))
        assertTrue(summary.fullText.contains("Harem"))
        assertTrue(summary.fullText.contains("RPG"))
        assertEquals(CatalogAdvancedFilterState(), state.cleared())
    }

    @Test
    fun installedSummaryContainsOnlyInstalledDialogFilters() {
        val state = InstalledAdvancedFilterState(
            searchMode = CatalogSearchMode.Regex,
            activeStatuses = setOf(UpdateStatus.UpdateAvailable),
            sourceFilter = AppSource.JoiPlay,
            userStatus = UserGameStatus.Completed,
            minPersonalRating = 4,
            selectedUserTags = setOf("favorite"),
            hasSavesOnly = true,
        )

        val summary = installedAdvancedFilterSummary(state)

        assertEquals(7, summary.count)
        assertTrue(summary.fullText.contains("Regex"))
        assertTrue(summary.fullText.contains("JoiPlay available"))
        assertTrue(summary.fullText.contains("Completed"))
        assertFalse(summary.fullText.contains("Synopsis"))
        assertEquals(InstalledAdvancedFilterState(), state.cleared())
    }

    @Test
    fun regexValidationAllowsBlankAndPreservesAUsefulInlineError() {
        assertNull(regexValidationError("", CatalogSearchMode.Regex))
        assertNull(regexValidationError("(game)", CatalogSearchMode.Regex))
        assertTrue(regexValidationError("(", CatalogSearchMode.Regex)?.startsWith("Invalid regex:") == true)
        assertTrue(regexValidationError("game -(", CatalogSearchMode.Regex)?.startsWith("Invalid regex:") == true)
        assertNull(regexValidationError("game -other", CatalogSearchMode.Regex))
        assertNull(regexValidationError("(", CatalogSearchMode.Normal))
    }

    @Test
    fun wholeWordAndRegexSearchUseOneExclusiveMode() {
        val app = InstalledApp("pkg", "Space Station", versionName = "", versionCode = 1)

        assertTrue(matchesLibrarySearchText(app, null, null, "station", CatalogSearchMode.WholeWord))
        assertFalse(matchesLibrarySearchText(app, null, null, "stat", CatalogSearchMode.WholeWord))
        assertTrue(matchesLibrarySearchText(app, null, null, "space\\s+station", CatalogSearchMode.Regex))
        assertFalse(matchesLibrarySearchText(app, null, null, "(", CatalogSearchMode.Regex))
    }

    @Test
    fun summaryTruncationUsesAnEllipsisAndPresentationBreakpointIsStable() {
        val summary = AdvancedFilterSummary(listOf("Android", "RPGM", "Completed", "Has backup"))

        assertEquals("4 filters…", summary.truncated(10))
        assertTrue(summary.truncated(18).endsWith("…"))
        assertTrue(useFullScreenAdvancedDialog(599))
        assertFalse(useFullScreenAdvancedDialog(600))
    }

    @Test
    fun dateRangeCutoffIsDeterministic() {
        val now = 1_000_000_000L
        assertEquals(
            now - 30L * 24L * 60L * 60L * 1000L,
            DateRangeFilter.Last30Days.cutoffEpochMs(now),
        )
        assertNull(DateRangeFilter.Any.cutoffEpochMs(now))
    }
}
