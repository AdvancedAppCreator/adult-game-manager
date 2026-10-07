package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogRowsDatabaseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun row(
        source: String,
        sourceId: String,
        title: String,
        canonicalUrl: String = "",
        tags: List<String> = emptyList(),
        tagLabels: List<String> = emptyList(),
        numericTagIds: Set<Int> = emptySet(),
        platforms: List<String> = emptyList(),
        rating: Double? = null,
        popularity: Double? = null,
        publishedAt: String? = null,
        modifiedAt: String? = null,
        agmGroupId: String? = null,
        canonicalTags: List<String> = emptyList(),
    ): CatalogSearchEntry {
        val entry = SourceCatalogEntry(
            source = source,
            sourceId = sourceId,
            canonicalUrl = canonicalUrl,
            title = title,
            tags = tags,
            platforms = platforms,
            rating = rating,
            popularity = popularity,
            publishedAt = publishedAt,
            modifiedAt = modifiedAt,
            agmGroupId = agmGroupId,
            canonicalTags = canonicalTags,
        )
        return CatalogSearchEntry(
            entry = entry,
            titleLower = title.lowercase(),
            tagLabels = tagLabels,
            tagTokens = tagLabels.flatMap { catalogTagSearchTokens(it) }.toSet(),
            numericTagIds = numericTagIds,
        )
    }

    private fun defaultFilter(
        matchingKeys: Set<String>? = null,
        matchingGroupIds: Set<String>? = null,
        matchingKeyRanks: Map<String, Double>? = null,
        matchingGroupRanks: Map<String, Double>? = null,
        tagTokens: List<String> = emptyList(),
        canonicalTagIds: List<String> = emptyList(),
        tagTokenGroups: List<List<String>> = emptyList(),
        statusFilter: Int? = null,
        engineFilter: Int? = null,
        categoryFilter: String? = null,
        sourceFilter: String? = null,
        platformFilter: String? = null,
        minRating: Float = 0f,
        minPopularity: Long = 0,
        updatedSinceIso: String? = null,
        publishedSinceIso: String? = null,
        installedOnly: Boolean = false,
        notInstalledOnly: Boolean = false,
        installedCatalogKeys: Set<CatalogInstallKey> = emptySet(),
        installedCatalogUrls: Set<String> = emptySet(),
        installedAgmGroupIds: Set<String> = emptySet(),
        wishlistOnly: Boolean = false,
        notWishlistOnly: Boolean = false,
        wishlistGroupIds: Set<String> = emptySet(),
        ignoredOnly: Boolean = false,
        ignoredGroupIds: Set<String> = emptySet(),
        sortKey: CatalogSortKey = CatalogSortKey.Title,
        sortDesc: Boolean = false,
    ) = CatalogRowsFilter(
        matchingKeys = matchingKeys,
        matchingGroupIds = matchingGroupIds,
        matchingKeyRanks = matchingKeyRanks,
        matchingGroupRanks = matchingGroupRanks,
        tagTokens = tagTokens,
        canonicalTagIds = canonicalTagIds,
        tagTokenGroups = tagTokenGroups,
        statusFilter = statusFilter,
        engineFilter = engineFilter,
        categoryFilter = categoryFilter,
        sourceFilter = sourceFilter,
        platformFilter = platformFilter,
        minRating = minRating,
        minPopularity = minPopularity,
        updatedSinceIso = updatedSinceIso,
        publishedSinceIso = publishedSinceIso,
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
    )

    private fun buildDatabase(rows: List<CatalogSearchEntry>, signature: String = "signature"): File =
        File(temporaryFolder.root, "catalog-rows.db").also { file ->
            CatalogRowsDatabaseStore.build(file, signature, rows)
        }

    @Test
    fun buildRoundTripAndPagination() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Charlie"),
                row("f95zone", "2", "Alpha"),
                row("f95zone", "3", "Bravo"),
            ),
        )

        assertTrue(CatalogRowsDatabaseStore.isValid(database, "signature"))
        assertFalse(CatalogRowsDatabaseStore.isValid(database, "other"))
        assertEquals(3, CatalogRowsDatabaseStore.totalCount(database))

        CatalogRowsDatabaseStore.openQuery(database, defaultFilter()).use { query ->
            assertEquals(listOf("Alpha", "Bravo"), query.load(offset = 0, limit = 2).entries.map { it.title })
            assertEquals(listOf("Charlie"), query.load(offset = 2, limit = 2).entries.map { it.title })
            assertEquals(3, query.queryCount())
        }
    }

    @Test
    fun buildsIncrementallyFromSourceEntries() {
        val database = File(temporaryFolder.root, "catalog-source-rows.db")
        CatalogRowsDatabaseStore.buildFromSourceEntries(
            file = database,
            signature = "source-signature",
            entries = listOf(
                SourceCatalogEntry(
                    source = "f95zone",
                    sourceId = "1",
                    canonicalUrl = "https://f95zone.to/threads/1/",
                    title = "Incremental",
                    tags = listOf("18"),
                ),
            ),
            labels = CatalogLabelsV2(
                sources = mapOf(
                    "f95zone" to SourceLabels(tags = mapOf("18" to "Completed")),
                ),
            ),
        )

        assertTrue(CatalogRowsDatabaseStore.isValid(database, "source-signature"))
        assertEquals(listOf("Completed"), CatalogRowsDatabaseStore.tagLabels(database))
        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(statusFilter = 18),
        ).use { query ->
            assertEquals(listOf("Incremental"), query.load(0, 10).entries.map { it.title })
        }
    }

    @Test
    fun sortSemanticsMatchExistingCatalogOrders() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "One", rating = 3.0, popularity = 20.0),
                row("f95zone", "2", "Two", rating = 5.0, popularity = 10.0),
                row("f95zone", "3", "Three", rating = 5.0, popularity = 30.0),
            ),
        )

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(sortKey = CatalogSortKey.Rating, sortDesc = true),
        ).use { query ->
            assertEquals(
                listOf("Three", "Two", "One"),
                query.load(0, 10).entries.map { it.title },
            )
        }
    }

    @Test
    fun textAndMetadataFiltersAreCombined() {
        val database = buildDatabase(
            listOf(
                row(
                    source = "f95zone",
                    sourceId = "1",
                    title = "Matching",
                    tags = listOf("games"),
                    tagLabels = listOf("Ren'Py", "Completed"),
                    numericTagIds = setOf(7, 18),
                    platforms = listOf("windows/pc"),
                    rating = 4.5,
                ),
                row(
                    source = "other",
                    sourceId = "2",
                    title = "Excluded",
                    tags = listOf("games"),
                    tagLabels = listOf("Ren'Py"),
                    numericTagIds = setOf(7),
                    platforms = listOf("Android"),
                    rating = 5.0,
                ),
            ),
        )

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                matchingKeys = setOf("f95zone:1"),
                tagTokens = listOf("ren"),
                statusFilter = 18,
                engineFilter = 7,
                categoryFilter = "games",
                sourceFilter = "f95zone",
                platformFilter = "Windows",
                minRating = 4f,
            ),
        ).use { query ->
            assertEquals(listOf("Matching"), query.load(0, 10).entries.map { it.title })
        }
    }

    @Test
    fun emptyTextMatchSetReturnsNoRows() {
        val database = buildDatabase(listOf(row("f95zone", "1", "One")))

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(matchingKeys = emptySet()),
        ).use { query ->
            assertEquals(0, query.queryCount())
            assertTrue(query.load(0, 10).entries.isEmpty())
        }
    }

    @Test
    fun relevanceCombinesTitleAndSynopsisRanksButExplicitSortWins() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Title and synopsis", rating = 1.0, agmGroupId = "g-both"),
                row("f95zone", "2", "Title only", rating = 2.0, agmGroupId = "g-title"),
                row("f95zone", "3", "Synopsis strong", rating = 3.0, agmGroupId = "g-synopsis-strong"),
                row("f95zone", "4", "Synopsis weak", rating = 4.0, agmGroupId = "g-synopsis-weak"),
            ),
        )
        val titleRanks = mapOf(
            "f95zone:1" to 900.0,
            "f95zone:2" to 900.0,
        )
        val synopsisRanks = mapOf(
            "g-both" to -2.0,
            "g-synopsis-strong" to -5.0,
            "g-synopsis-weak" to -1.0,
        )

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                matchingKeys = titleRanks.keys,
                matchingGroupIds = synopsisRanks.keys,
                matchingKeyRanks = titleRanks,
                matchingGroupRanks = synopsisRanks,
                sortKey = CatalogSortKey.Relevance,
                sortDesc = true,
            ),
        ).use { query ->
            assertEquals(
                listOf("g-both", "g-title", "g-synopsis-strong", "g-synopsis-weak"),
                query.loadGroups(0, 10).groups.map { it.agmGroupId },
            )
        }

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                matchingKeys = titleRanks.keys,
                matchingGroupIds = synopsisRanks.keys,
                matchingKeyRanks = titleRanks,
                matchingGroupRanks = synopsisRanks,
                sortKey = CatalogSortKey.Rating,
                sortDesc = true,
            ),
        ).use { query ->
            assertEquals(
                listOf("g-synopsis-weak", "g-synopsis-strong", "g-title", "g-both"),
                query.loadGroups(0, 10).groups.map { it.agmGroupId },
            )
        }
    }

    @Test
    fun synopsisGroupPopularityDateAndInstalledIdentityFiltersCompose() {
        val database = buildDatabase(
            listOf(
                row(
                    "f95zone",
                    "1",
                    "Matching member",
                    popularity = 1_500.0,
                    publishedAt = "2025-02-01T00:00:00Z",
                    modifiedAt = "2025-03-01T00:00:00Z",
                    agmGroupId = "g-match",
                ),
                row(
                    "other",
                    "2",
                    "Same group",
                    popularity = 1_800.0,
                    publishedAt = "2025-02-02T00:00:00Z",
                    modifiedAt = "2025-03-02T00:00:00Z",
                    agmGroupId = "g-match",
                ),
                row(
                    "other",
                    "3",
                    "Too old",
                    popularity = 9_000.0,
                    publishedAt = "2024-01-01T00:00:00Z",
                    modifiedAt = "2024-01-01T00:00:00Z",
                    agmGroupId = "g-old",
                ),
            ),
        )

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                matchingKeys = emptySet(),
                matchingGroupIds = setOf("g-match"),
                minPopularity = 1_000,
                updatedSinceIso = "2025-01-01T00:00:00Z",
                publishedSinceIso = "2025-01-01T00:00:00Z",
                installedOnly = true,
                installedAgmGroupIds = setOf("g-match"),
            ),
        ).use { query ->
            assertEquals(
                setOf("Matching member", "Same group"),
                query.load(0, 10).entries.map { it.title }.toSet(),
            )
            assertEquals(listOf("g-match"), query.loadGroups(0, 10).groups.map { it.agmGroupId })
        }
    }

    @Test
    fun installedFiltersUseSourceIdentityAndCanonicalUrl() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "By key"),
                row("other", "2", "By URL", canonicalUrl = "https://example.test/game"),
                row("other", "3", "Not installed"),
            ),
        )
        val keys = setOf(CatalogInstallKey("f95zone", "1"))
        val urls = setOf("https://example.test/game")

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                installedOnly = true,
                installedCatalogKeys = keys,
                installedCatalogUrls = urls,
            ),
        ).use { query ->
            assertEquals(
                setOf("By key", "By URL"),
                query.load(0, 10).entries.map { it.title }.toSet(),
            )
        }

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                notInstalledOnly = true,
                installedCatalogKeys = keys,
                installedCatalogUrls = urls,
            ),
        ).use { query ->
            assertEquals(listOf("Not installed"), query.load(0, 10).entries.map { it.title })
        }
    }

    @Test
    fun wishlistFiltersUseCrossSourceGroupIdentity() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Wish source one", agmGroupId = "group-wish"),
                row("other", "2", "Wish source two", agmGroupId = "group-wish"),
                row("other", "3", "Not wished", agmGroupId = "group-other"),
            ),
        )

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                wishlistOnly = true,
                wishlistGroupIds = setOf("group-wish"),
            ),
        ).use { query ->
            assertEquals(
                setOf("Wish source one", "Wish source two"),
                query.load(0, 10).entries.map { it.title }.toSet(),
            )
            assertEquals(listOf("group-wish"), query.loadGroups(0, 10).groups.map { it.agmGroupId })
        }

        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(
                notWishlistOnly = true,
                wishlistGroupIds = setOf("group-wish"),
            ),
        ).use { query ->
            assertEquals(listOf("Not wished"), query.load(0, 10).entries.map { it.title })
        }
    }

    @Test
    fun tagLabelsAreDistinctByNormalizedToken() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "One", tagLabels = listOf("Ren'Py", "Completed")),
                row("f95zone", "2", "Two", tagLabels = listOf("ren py", "Completed")),
            ),
        )

        assertEquals(
            listOf("Completed", "Ren'Py"),
            CatalogRowsDatabaseStore.tagLabels(database),
        )
    }

    @Test
    fun synchronizationUpdatesMetadataWithoutChangingSearchTitles() {
        val database = File(temporaryFolder.root, "metadata-sync.db")
        val aliases = mapOf("f95zone:1" to listOf("Titre traduit"))
        CatalogRowsDatabaseStore.synchronize(
            file = database,
            signature = "first",
            rows = listOf(row("f95zone", "1", "Original Title", rating = 1.0)),
            translatedTitleAliases = aliases,
        )

        CatalogRowsDatabaseStore.synchronize(
            file = database,
            signature = "second",
            rows = listOf(row("f95zone", "1", "Original Title", rating = 5.0)),
            translatedTitleAliases = aliases,
        )

        assertTrue(CatalogRowsDatabaseStore.isValid(database, "second"))
        CatalogRowsDatabaseStore.openQuery(database, defaultFilter()).use { query ->
            assertEquals(5.0, query.load(0, 1).entries.single().rating)
        }
        CatalogTextSearchIndex.open(database).use { index ->
            assertEquals(
                listOf("Original Title"),
                index.search("Titre traduit").map { it.document.displayTitle },
            )
        }
    }

    @Test
    fun synchronizationRenamesAddsAndRemovesRowsAndSearchDocuments() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Old Name"),
                row("f95zone", "2", "Removed Name"),
            ),
            signature = "first",
        )

        CatalogRowsDatabaseStore.synchronize(
            file = database,
            signature = "second",
            rows = listOf(
                row("f95zone", "1", "New Name"),
                row("other", "3", "Added Name"),
            ),
        )

        assertEquals(2, CatalogRowsDatabaseStore.totalCount(database))
        CatalogRowsDatabaseStore.openQuery(database, defaultFilter()).use { query ->
            assertEquals(
                listOf("Added Name", "New Name"),
                query.load(0, 10).entries.map { it.title },
            )
        }
        CatalogTextSearchIndex.open(database).use { index ->
            assertTrue(index.search("Old Name").isEmpty())
            assertEquals(
                listOf("New Name"),
                index.search("New Name").map { it.document.displayTitle },
            )
            assertTrue(index.search("Removed Name").isEmpty())
        }
    }

    @Test
    fun synchronizationIsUnchangedForMatchingSignatureAndSupportsTargetedLookups() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "101", "Thread Game"),
                row("other", "other-id", "Other Game"),
            ),
            signature = "same",
        )

        CatalogRowsDatabaseStore.synchronize(
            file = database,
            signature = "same",
            rows = listOf(row("f95zone", "101", "Changed but skipped")),
        )

        assertEquals(
            listOf("Thread Game"),
            CatalogRowsDatabaseStore.entriesByCatalogKeys(database, setOf("f95zone:101"))
                .map { it.title },
        )
        assertTrue(CatalogRowsDatabaseStore.entriesByKeys(database, setOf("missing")).isEmpty())
        assertEquals(
            "Thread Game",
            CatalogRowsDatabaseStore.catalogGamesByF95ThreadIds(database, setOf(101, 999))[101]?.title,
        )
        assertEquals(null, CatalogRowsDatabaseStore.catalogGameByF95ThreadId(database, 999))
    }

    @Test
    fun failedSynchronizationRollsBackRowsSearchAndSignature() {
        val database = buildDatabase(
            listOf(row("f95zone", "1", "Preserved")),
            signature = "before",
        )
        var checks = 0

        try {
            CatalogRowsDatabaseStore.synchronize(
                file = database,
                signature = "after",
                rows = listOf(
                    row("f95zone", "1", "Changed"),
                    row("f95zone", "2", "Added"),
                ),
                checkCancelled = {
                    if (checks++ > 0) error("injected cancellation")
                },
            )
        } catch (expected: IllegalStateException) {
            // The injected producer failure must leave the prior committed catalog intact.
        }

        assertTrue(CatalogRowsDatabaseStore.isValid(database, "before"))
        assertFalse(CatalogRowsDatabaseStore.isValid(database, "after"))
        CatalogRowsDatabaseStore.openQuery(database, defaultFilter()).use { query ->
            assertEquals(listOf("Preserved"), query.load(0, 10).entries.map { it.title })
        }
        CatalogTextSearchIndex.open(database).use { index ->
            assertEquals(listOf("Preserved"), index.search("Preserved").map { it.document.displayTitle })
        }
    }

    @Test
    fun streamingSynchronizationBuildsSourceEntriesAndReportsExpectedProgress() {
        val database = File(temporaryFolder.root, "streaming-build.db")
        val progress = mutableListOf<Pair<Int, Int>>()

        CatalogRowsDatabaseStore.synchronizeStreamingFromSourceEntries(
            file = database,
            signature = "stream-first",
            expectedCount = 2,
            labels = CatalogLabelsV2(
                sources = mapOf(
                    SOURCE_F95ZONE to SourceLabels(tags = mapOf("18" to "Completed")),
                ),
            ),
            producer = { emit ->
                emit(CatalogRowsStreamEntry(sourceEntry("1", "First", tags = listOf("18"))))
                emit(CatalogRowsStreamEntry(sourceEntry("2", "Second")))
            },
            onProgress = { completed, total -> progress += completed to total },
        )

        assertTrue(CatalogRowsDatabaseStore.isValid(database, "stream-first"))
        assertEquals(2, CatalogRowsDatabaseStore.totalCount(database))
        assertEquals(listOf("Completed"), CatalogRowsDatabaseStore.tagLabels(database))
        assertEquals(listOf(2 to 2), progress)
    }

    @Test
    fun streamingSynchronizationUpdatesMetadataNamesAddsAndRemovesEntries() {
        val database = File(temporaryFolder.root, "streaming-update.db")
        CatalogRowsDatabaseStore.synchronizeStreamingFromSourceEntries(
            file = database,
            signature = "first",
            expectedCount = 2,
            labels = null,
            producer = { emit ->
                emit(CatalogRowsStreamEntry(sourceEntry("1", "Original", rating = 1.0)))
                emit(CatalogRowsStreamEntry(sourceEntry("2", "Removed")))
            },
        )

        CatalogRowsDatabaseStore.synchronizeStreamingFromSourceEntries(
            file = database,
            signature = "second",
            expectedCount = 2,
            labels = null,
            producer = { emit ->
                emit(
                    CatalogRowsStreamEntry(
                        entry = sourceEntry("1", "Renamed", rating = 5.0),
                        translatedTitleAliases = listOf("Translated Rename"),
                    ),
                )
                emit(CatalogRowsStreamEntry(sourceEntry("3", "Added")))
            },
        )

        assertTrue(CatalogRowsDatabaseStore.isValid(database, "second"))
        CatalogRowsDatabaseStore.openQuery(database, defaultFilter()).use { query ->
            assertEquals(
                listOf("Added", "Renamed"),
                query.load(0, 10).entries.map { it.title },
            )
            assertEquals(5.0, query.load(0, 10).entries.single { it.sourceId == "1" }.rating)
        }
        CatalogTextSearchIndex.open(database).use { index ->
            assertTrue(index.search("Original").isEmpty())
            assertTrue(index.search("Removed").isEmpty())
            assertEquals(listOf("Renamed"), index.search("Translated Rename").map { it.document.displayTitle })
        }
    }

    @Test
    fun streamingSynchronizationRollsBackProducerFailure() {
        val database = buildDatabase(
            listOf(row("f95zone", "1", "Preserved")),
            signature = "before",
        )

        val failure = runCatching {
            CatalogRowsDatabaseStore.synchronizeStreamingFromSourceEntries(
                file = database,
                signature = "after",
                expectedCount = 2,
                labels = null,
                producer = { emit ->
                    emit(CatalogRowsStreamEntry(sourceEntry("1", "Changed")))
                    error("injected producer failure")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(CatalogRowsDatabaseStore.isValid(database, "before"))
        assertFalse(CatalogRowsDatabaseStore.isValid(database, "after"))
        CatalogRowsDatabaseStore.openQuery(database, defaultFilter()).use { query ->
            assertEquals(listOf("Preserved"), query.load(0, 10).entries.map { it.title })
        }
    }

    private fun sourceEntry(
        sourceId: String,
        title: String,
        tags: List<String> = emptyList(),
        rating: Double? = null,
    ) = SourceCatalogEntry(
        source = SOURCE_F95ZONE,
        sourceId = sourceId,
        canonicalUrl = "https://f95zone.to/threads/$sourceId/",
        title = title,
        tags = tags,
        rating = rating,
    )

    @Test
    fun loadGroupsCollapsesSourcesByAgmGroupId() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Summertime Saga F95", agmGroupId = "g_sts",
                    rating = 4.5, modifiedAt = "2026-01-01"),
                row("dlsite", "2", "Summertime Saga DL", agmGroupId = "g_sts",
                    rating = 4.8, modifiedAt = "2026-02-01"),
                row("f95zone", "9", "Other Game", agmGroupId = "g_other", rating = 3.0),
            ),
        )
        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(sortKey = CatalogSortKey.Rating, sortDesc = true),
        ).use { query ->
            val page = query.loadGroups(offset = 0, limit = 10)
            // Two distinct groups even though there are three source rows.
            assertEquals(2, page.totalCount)
            assertEquals(listOf("g_sts", "g_other"), page.groups.map { it.agmGroupId })
            // The Summertime Saga group carries BOTH of its source members.
            val sts = page.groups.first { it.agmGroupId == "g_sts" }
            assertEquals(setOf("f95zone", "dlsite"), sts.members.map { it.source }.toSet())
        }
    }

    @Test
    fun loadGroupsMatchesByAnyMemberButReturnsAllSources() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Game A", agmGroupId = "g_a", canonicalTags = listOf("genre:rpg")),
                row("dlsite", "2", "Game A alt", agmGroupId = "g_a", canonicalTags = emptyList()),
                row("f95zone", "3", "Game B", agmGroupId = "g_b", canonicalTags = listOf("genre:vn")),
            ),
        )
        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(canonicalTagIds = listOf("genre:rpg")),
        ).use { query ->
            val page = query.loadGroups(offset = 0, limit = 10)
            // Only the group with an rpg member matches...
            assertEquals(1, page.totalCount)
            val group = page.groups.single()
            assertEquals("g_a", group.agmGroupId)
            // ...but the group still exposes BOTH of its sources for the detail popup.
            assertEquals(setOf("f95zone", "dlsite"), group.members.map { it.source }.toSet())
        }
    }

    @Test
    fun rowsWithoutAgmGroupIdRemainSingletonGroups() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Solo One"),
                row("f95zone", "2", "Solo Two"),
            ),
        )
        CatalogRowsDatabaseStore.openQuery(database, defaultFilter()).use { query ->
            val page = query.loadGroups(offset = 0, limit = 10)
            assertEquals(2, page.totalCount)
            assertTrue(page.groups.all { it.members.size == 1 })
        }
    }

    @Test
    fun tagTokenGroupsOrWithinAndAcrossGroups() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "OnlyAlpha", tagLabels = listOf("Alpha")),
                row("f95zone", "2", "OnlyBeta", tagLabels = listOf("Beta")),
                row("f95zone", "3", "AlphaGamma", tagLabels = listOf("Alpha", "Gamma")),
                row("f95zone", "4", "BetaGamma", tagLabels = listOf("Beta", "Gamma")),
            ),
        )
        // Group 1 = (Alpha OR Beta) -> matches all four; alone that is every row.
        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(tagTokenGroups = listOf(listOf("alpha", "beta"))),
        ).use { query ->
            assertEquals(
                listOf("AlphaGamma", "BetaGamma", "OnlyAlpha", "OnlyBeta"),
                query.load(0, 10).entries.map { it.title },
            )
        }
        // Group 1 = (Alpha OR Beta) AND Group 2 = (Gamma) -> only the two *Gamma rows.
        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(tagTokenGroups = listOf(listOf("alpha", "beta"), listOf("gamma"))),
        ).use { query ->
            assertEquals(
                listOf("AlphaGamma", "BetaGamma"),
                query.load(0, 10).entries.map { it.title },
            )
        }
    }

    @Test
    fun ignoredGroupsAreHiddenByDefaultAndCanBeShownExclusively() {
        val database = buildDatabase(
            listOf(
                row("f95zone", "1", "Visible", agmGroupId = "visible"),
                row("f95zone", "2", "Ignored", agmGroupId = "ignored"),
            ),
        )
        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(ignoredGroupIds = setOf("ignored")),
        ).use { query ->
            assertEquals(listOf("Visible"), query.loadGroups(0, 10).groups.flatMap { it.members }.map { it.title })
        }
        CatalogRowsDatabaseStore.openQuery(
            database,
            defaultFilter(ignoredOnly = true, ignoredGroupIds = setOf("ignored")),
        ).use { query ->
            assertEquals(listOf("Ignored"), query.loadGroups(0, 10).groups.flatMap { it.members }.map { it.title })
        }
    }
}
