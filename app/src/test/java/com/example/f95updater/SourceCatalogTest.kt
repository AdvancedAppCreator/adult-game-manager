package com.example.f95updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceCatalogTest {
    @Test
    fun adultGameWorldTitleParserExtractsVersionAndDeveloper() {
        val parsed = AdultGameWorldTitleParser.parse("Guilty Pleasure – New Version 0.60 [Quonix]")

        assertEquals("Guilty Pleasure", parsed.gameTitle)
        assertEquals("Version 0.60", parsed.versionText)
        assertEquals("Quonix", parsed.developer)
    }

    @Test
    fun latestReleaseSelectorChoosesHigherParsedVersion() {
        val f95 = SourceCatalogEntry(
            source = CatalogSource.F95Zone,
            sourceId = "1",
            canonicalUrl = "https://f95zone.to/threads/1/",
            title = "Example",
            versionText = "v0.5",
            modifiedAt = "2026-05-20T00:00:00Z",
        )
        val agw = SourceCatalogEntry(
            source = CatalogSource.AdultGameWorld,
            sourceId = "2",
            canonicalUrl = "https://adultgamesworld.com/example/",
            title = "Example",
            versionText = "Version 0.60",
            modifiedAt = "2026-05-21T00:00:00Z",
        )

        assertEquals(agw, LatestReleaseSelector.choose(listOf(f95, agw)))
    }

    @Test
    fun generatedCatalogIndexRoundTrips() {
        val original = SourceCatalogIndex(
            generatedAt = "2026-05-27T00:00:00Z",
            catalogs = listOf(
                SourceCatalogIndexEntry(
                    source = CatalogSource.AdultGameWorld,
                    url = "https://example.test/catalogs/adultgameworld/catalog.json.gz",
                    count = 42,
                ),
            ),
        )

        val json = Json.encodeToString(SourceCatalogIndex.serializer(), original)
        assertEquals(original, Json.decodeFromString(SourceCatalogIndex.serializer(), json))
    }

    @Test
    fun displayTagsHidesUnknownNumericIds() {
        val entry = SourceCatalogEntry(
            source = CatalogSource.F95Zone,
            sourceId = "1",
            canonicalUrl = "https://f95zone.to/threads/1/",
            title = "Example",
            tags = listOf("7", "999999", "custom text"),
        )
        val labels = CatalogLabels(
            prefixes = mapOf("7" to "Ren'Py"),
        )

        assertEquals(listOf("Ren'Py", "custom text"), displayTags(entry, labels))
    }

    @Test
    fun catalogTagFilterTokenSupportsMultiWordTags() {
        assertEquals("male-protagonist", catalogTagFilterToken("Male Protagonist"))
        assertEquals("futa-trans", catalogTagFilterToken("futa/trans"))
        assertEquals("ren-py", catalogTagFilterToken("Ren'Py"))
    }

    @Test
    fun catalogTagMatchesNormalizedAndRawQueries() {
        assertEquals(true, catalogTagMatchesQuery("Male Protagonist", "male-protagonist"))
        assertEquals(true, catalogTagMatchesQuery("Male Protagonist", "protag"))
        assertEquals(true, catalogTagMatchesQuery("futa/trans", "futa-trans"))
    }

    @Test
    fun catalogSearchEntryCachesResolvedTagTokens() {
        val entry = SourceCatalogEntry(
            source = CatalogSource.F95Zone,
            sourceId = "1",
            canonicalUrl = "https://f95zone.to/threads/1/",
            title = "Example",
            tags = listOf("7", "130"),
        )
        val labels = CatalogLabels(
            prefixes = mapOf("7" to "Ren'Py"),
            tags = mapOf("130" to "Male Protagonist"),
        )

        val indexed = CatalogSearchEntry.from(entry, labels)

        assertEquals(setOf(7, 130), indexed.numericTagIds)
        assertEquals(true, indexed.tagTokens.contains("male-protagonist"))
        assertEquals(true, indexed.tagTokens.contains("male protagonist"))
        assertEquals(true, indexed.tagTokens.contains("ren-py"))
    }

    @Test
    fun catalogFilterLabelsOnlyIncludesTagsPresentInEntries() {
        val labels = CatalogLabels(
            prefixes = mapOf("7" to "Ren'Py", "2" to "RPGM"),
            tags = mapOf("130" to "Male Protagonist", "999" to "Unused Label"),
        )
        val entries = listOf(
            CatalogSearchEntry.from(
                SourceCatalogEntry(
                    source = CatalogSource.F95Zone,
                    sourceId = "1",
                    canonicalUrl = "https://f95zone.to/threads/1/",
                    title = "Example",
                    tags = listOf("7", "130"),
                ),
                labels,
            ),
        )

        assertEquals(listOf("Male Protagonist", "Ren'Py"), catalogFilterLabels(entries))
    }

    @Test
    fun addAndRemoveCatalogTagFiltersUseNormalizedTokens() {
        val query = addCatalogTagFilter("alice", "Male Protagonist")

        assertEquals("alice tag:male-protagonist ", query)
        assertEquals("alice ", removeCatalogTagFilter(query, "Male Protagonist"))
    }
}
