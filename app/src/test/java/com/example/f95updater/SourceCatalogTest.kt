package com.example.f95updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCatalogTest {
    private val labelsV2 = CatalogLabelsV2(
        sources = mapOf(
            SOURCE_F95ZONE to SourceLabels(
                prefixes = mapOf("7" to "Ren'Py", "2" to "RPGM"),
                tags = mapOf("130" to "Male Protagonist", "999" to "Unused Label"),
            ),
            "dikgames" to SourceLabels(
                // Same numeric id, different meaning — must never collide with f95.
                tags = mapOf("7" to "Anal"),
            ),
        ),
    )

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
            source = SOURCE_F95ZONE,
            sourceId = "1",
            canonicalUrl = "https://f95zone.to/threads/1/",
            title = "Example",
            versionText = "v0.5",
            modifiedAt = "2026-05-20T00:00:00Z",
        )
        val agw = SourceCatalogEntry(
            source = SOURCE_ADULTGAMEWORLD,
            sourceId = "2",
            canonicalUrl = "https://adultgamesworld.com/example/",
            title = "Example",
            versionText = "Version 0.60",
            modifiedAt = "2026-05-21T00:00:00Z",
        )

        assertEquals(agw, LatestReleaseSelector.choose(listOf(f95, agw)))
    }

    @Test
    fun latestReleaseSelectorBreaksVersionDateTieByRegistryPriority() {
        SourceRegistry.update(
            listOf(
                CatalogSourceInfo(id = SOURCE_F95ZONE, priority = 100),
                CatalogSourceInfo(id = SOURCE_ADULTGAMEWORLD, priority = 10),
            ),
        )
        val f95 = SourceCatalogEntry(
            source = SOURCE_F95ZONE, sourceId = "1",
            canonicalUrl = "https://f95zone.to/threads/1/", title = "Example",
            versionText = "v1.0", modifiedAt = "2026-05-20T00:00:00Z",
        )
        val agw = SourceCatalogEntry(
            source = SOURCE_ADULTGAMEWORLD, sourceId = "2",
            canonicalUrl = "https://adultgamesworld.com/example/", title = "Example",
            versionText = "v1.0", modifiedAt = "2026-05-20T00:00:00Z",
        )

        assertEquals(f95, LatestReleaseSelector.choose(listOf(agw, f95)))
    }

    @Test
    fun exactTitleDuplicatesPreferOneUniqueHighestPrioritySource() {
        SourceRegistry.update(
            listOf(
                CatalogSourceInfo(id = SOURCE_F95ZONE, priority = 100),
                CatalogSourceInfo(id = "kimochi", priority = 10),
                CatalogSourceInfo(id = "otomi", priority = 10),
            ),
        )
        val f95 = CatalogGame(thread_id = 1, title = "Example", source = SOURCE_F95ZONE)
        val kimochi = CatalogGame(thread_id = -2, title = "Example", source = "kimochi")
        val otomi = CatalogGame(thread_id = -3, title = "Example", source = "otomi")

        assertEquals(f95, uniqueHighestPriorityCatalogGame(listOf(kimochi, f95, otomi)))
        assertEquals(null, uniqueHighestPriorityCatalogGame(listOf(kimochi, otomi)))
    }

    @Test
    fun catalogSourceRegistryRoundTrips() {
        val original = CatalogSourceRegistry(
            generatedAt = "2026-05-27T00:00:00Z",
            catalogs = listOf(
                CatalogSourceInfo(
                    id = SOURCE_ADULTGAMEWORLD,
                    displayName = "Adult Game World",
                    url = "https://example.test/catalogs/adultgameworld/catalog.json.gz",
                    count = 42,
                    enabled = true,
                    priority = 50,
                    threadUrlTemplate = "https://adultgamesworld.com/{id}/",
                    minAppVersion = "1.1.0",
                    detailsUrlTemplate = "https://example.test/details/{shard}.json.gz",
                    detailsGeneratedAt = "2026-05-27T00:00:00Z",
                ),
            ),
        )

        val json = Json.encodeToString(CatalogSourceRegistry.serializer(), original)
        assertEquals(original, Json.decodeFromString(CatalogSourceRegistry.serializer(), json))
    }

    @Test
    fun registryDecodeIgnoresUnknownFieldsAndKeepsFutureSources() {
        // A future source id the app has never heard of must decode (string model), not throw.
        val raw = """
            {"schemaVersion":2,"generatedAt":"t","catalogs":[
              {"id":"brandnewsource","displayName":"Brand New","url":"u","futureField":true}
            ]}
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val registry = json.decodeFromString(CatalogSourceRegistry.serializer(), raw)
        assertEquals("brandnewsource", registry.catalogs.single().id)
    }

    @Test
    fun displayTagsResolvesPerSourceWithoutCrossSourceCollision() {
        val f95 = SourceCatalogEntry(
            source = SOURCE_F95ZONE, sourceId = "1",
            canonicalUrl = "https://f95zone.to/threads/1/", title = "Example",
            tags = listOf("7", "999999", "custom text"),
        )
        assertEquals(listOf("Ren'Py", "custom text"), displayTags(f95, labelsV2))

        // Same numeric id "7" resolves to the dikgames-scoped label, not f95's.
        val dik = SourceCatalogEntry(
            source = "dikgames", sourceId = "9",
            canonicalUrl = "https://dikgames.com/9/", title = "Example",
            tags = listOf("7"),
        )
        assertEquals(listOf("Anal"), displayTags(dik, labelsV2))
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
            source = SOURCE_F95ZONE,
            sourceId = "1",
            canonicalUrl = "https://f95zone.to/threads/1/",
            title = "Example",
            tags = listOf("7", "130"),
        )

        val indexed = CatalogSearchEntry.from(entry, labelsV2)

        assertEquals(setOf(7, 130), indexed.numericTagIds)
        assertTrue(indexed.tagTokens.contains("male-protagonist"))
        assertTrue(indexed.tagTokens.contains("male protagonist"))
        assertTrue(indexed.tagTokens.contains("ren-py"))
    }

    @Test
    fun translatedCatalogTitlesAreIndexedAsAliases() {
        val game = CatalogGame(
            thread_id = 1,
            title = "Original Name",
            source = SOURCE_F95ZONE,
            sourceId = "1",
        )

        val indexed = catalogIndexedTitles(
            games = listOf(game),
            translatedTitles = mapOf("f95zone:1" to "Translated Name"),
        )

        assertEquals(listOf("Original Name", "Translated Name"), indexed.map { it.title })
        assertEquals(
            "Translated Name",
            catalogSearchTranslatedTitle(
                game,
                mapOf("f95zone:1" to "Translated Name"),
            ),
        )
        assertEquals(
            null,
            catalogSearchTranslatedTitle(
                game,
                mapOf("f95zone:1" to " original name "),
            ),
        )
    }

    @Test
    fun translatedAliasesKeepSourceIdentityWhenSyntheticIdsCollide() {
        val first = CatalogGame(
            thread_id = -42,
            title = "First Original",
            source = "source-a",
            sourceId = "1",
        )
        val second = CatalogGame(
            thread_id = -42,
            title = "Second Original",
            source = "source-b",
            sourceId = "2",
        )

        val indexed = catalogIndexedTitles(
            games = listOf(first, second),
            translatedTitles = mapOf(
                "source-a:1" to "First Translation",
                "source-b:2" to "Second Translation",
            ),
        )

        assertEquals(
            setOf("First Original", "First Translation", "Second Original", "Second Translation"),
            indexed.map { it.title }.toSet(),
        )
    }

    @Test
    fun legacySearchIdentityMatchesFallbackCatalogRows() {
        assertEquals(
            catalogTextSearchKey(SOURCE_F95ZONE, "42"),
            catalogTextSearchKey(SOURCE_F95ZONE, null, 42),
        )
    }

    @Test
    fun titleNormalizationKeepsNonLatinLetters() {
        assertEquals("\u5c11\u5973\u306e\u65c5", CatalogRepository.normalizeTitle("\u5c11\u5973\u306e\u65c5"))
    }

    @Test
    fun catalogFilterLabelsOnlyIncludesTagsPresentInEntries() {
        val entries = listOf(
            CatalogSearchEntry.from(
                SourceCatalogEntry(
                    source = SOURCE_F95ZONE,
                    sourceId = "1",
                    canonicalUrl = "https://f95zone.to/threads/1/",
                    title = "Example",
                    tags = listOf("7", "130"),
                ),
                labelsV2,
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

    @Test
    fun sourceEntryToCatalogGameCarriesThumbnailUrlThrough() {
        val entry = SourceCatalogEntry(
            source = SOURCE_F95ZONE,
            sourceId = "42",
            canonicalUrl = "https://f95zone.to/threads/42/",
            title = "Example",
            coverUrl = "https://example.test/cover-full.jpg",
            thumbnailUrl = "https://example.test/cover-thumb.jpg",
        )

        val game = sourceEntryToCatalogGame(entry)

        assertEquals(42, game.thread_id)
        assertEquals("https://example.test/cover-full.jpg", game.cover)
        assertEquals("https://example.test/cover-thumb.jpg", game.thumbnailUrl)
    }

    @Test
    fun sourceEntryToCatalogGameToleratesMissingThumbnailUrl() {
        val entry = SourceCatalogEntry(
            source = "dikgames",
            sourceId = "7",
            canonicalUrl = "https://dikgames.com/7/",
            title = "No thumbnail entry",
            coverUrl = "https://example.test/cover-full.jpg",
        )

        val game = sourceEntryToCatalogGame(entry)

        assertEquals("https://example.test/cover-full.jpg", game.cover)
        assertEquals(null, game.thumbnailUrl)
    }

    @Test
    fun catalogImageUrlsPrefersThumbnailForListingAndCoverForFullScreen() {
        val game = CatalogGame(
            thread_id = 1,
            cover = "https://example.test/full.jpg",
            thumbnailUrl = "https://example.test/thumb.jpg",
        )

        val images = catalogImageUrls(game)

        assertEquals("https://example.test/thumb.jpg", images.thumbnailUrl)
        assertEquals("https://example.test/full.jpg", images.coverUrl)
    }

    @Test
    fun catalogImageUrlsFallsBackToCoverWhenThumbnailMissing() {
        val game = CatalogGame(thread_id = 1, cover = "https://example.test/full.jpg")

        val images = catalogImageUrls(game)

        assertEquals("https://example.test/full.jpg", images.thumbnailUrl)
        assertEquals("https://example.test/full.jpg", images.coverUrl)
    }

    @Test
    fun catalogImageUrlsFallsBackToThumbnailWhenCoverMissing() {
        val game = CatalogGame(thread_id = 1, thumbnailUrl = "https://example.test/thumb.jpg")

        val images = catalogImageUrls(game)

        assertEquals("https://example.test/thumb.jpg", images.thumbnailUrl)
        assertEquals("https://example.test/thumb.jpg", images.coverUrl)
    }

    @Test
    fun catalogImageUrlsHandlesNullGameAndBlankUrls() {
        assertEquals(CatalogImageUrls(null, null), catalogImageUrls(null))

        val blank = CatalogGame(thread_id = 1, cover = "  ", thumbnailUrl = "")
        assertEquals(CatalogImageUrls(null, null), catalogImageUrls(blank))
    }
}
