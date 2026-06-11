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
}
