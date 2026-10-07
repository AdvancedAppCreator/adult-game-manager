package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogTextSearchIndexTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun document(
        key: String,
        title: String,
        aliases: List<String> = emptyList(),
        sourceEntry: SourceCatalogEntry? = null,
    ): CatalogTextSearchDocument = CatalogTextSearchDocument(
        key = key,
        displayTitle = title,
        titles = listOf(title) + aliases,
        sourceEntry = sourceEntry,
    )

    private fun keys(matches: List<CatalogTextSearchScoredMatch>): List<String> =
        matches.map { it.document.key }

    @Test
    fun exactNormalizedCleanAndNumberEquivalentNamesMatch() {
        CatalogTextSearchIndex.build(
            listOf(
                document("f95zone:1", "The X-Files"),
                document("f95zone:2", "Corrupted Kingdoms v0.15"),
                document("f95zone:3", "Chapter 21"),
            ),
        ).use { index ->
            assertEquals(listOf("f95zone:1"), keys(index.search("x files")))
            assertEquals(listOf("f95zone:2"), keys(index.search("Corrupted Kingdoms")))
            assertEquals(listOf("f95zone:3"), keys(index.search("Chapter Twenty One")))
            assertTrue(index.search("Chapter Twenty One").all { it.kind == CatalogNameMatchKind.Exact })
        }
    }

    @Test
    fun translatedAliasesSupportExactSubstringAndTokenPrefixSearch() {
        CatalogTextSearchIndex.build(
            listOf(
                document("f95zone:1", "月の旅", aliases = listOf("Moon Journey")),
                document("f95zone:2", "Moonlight Story"),
            ),
        ).use { index ->
            assertEquals(listOf("f95zone:1"), keys(index.search("Moon Journey")))
            assertEquals(listOf("f95zone:1"), keys(index.search("ourney")))
            assertEquals(listOf("f95zone:1"), keys(index.search("moon jour")))
            assertTrue(index.search("moon missing").isEmpty())
        }
    }

    @Test
    fun japaneseLiteralAndSubstringSearchWork() {
        CatalogTextSearchIndex.build(
            listOf(
                document("kimochi:rj123", "薬師兄妹のえっちな治験"),
                document("f95zone:2", "Unrelated title"),
            ),
        ).use { index ->
            assertEquals(listOf("kimochi:rj123"), keys(index.search("薬師兄妹のえっちな治験")))
            assertEquals(listOf("kimochi:rj123"), keys(index.search("えっちな")))
        }
    }

    @Test
    fun oneAndTwoCharacterQueriesScanStoredVariants() {
        CatalogTextSearchIndex.build(
            listOf(
                document("f95zone:1", "Moon Journey"),
                document("f95zone:2", "Moonlight Story"),
                document("f95zone:3", "Dark Souls"),
                document("f95zone:4", "One"),
            ),
        ).use { index ->
            assertEquals(listOf("f95zone:1"), keys(index.search("jo")))
            assertEquals(setOf("f95zone:1", "f95zone:2"), keys(index.search("m")).toSet())
            assertEquals(listOf("f95zone:3"), keys(index.search("DS")))
            assertEquals(listOf("f95zone:4"), keys(index.search("1")))
        }
    }

    @Test
    fun supplementaryUnicodeUsesCodePointAwareShortScans() {
        CatalogTextSearchIndex.build(
            listOf(
                document("emoji", "😀a journey"),
                document("deseret", "𐐀lpha Journey"),
                document("other", "Unrelated"),
            ),
        ).use { index ->
            assertEquals(listOf("emoji"), keys(index.search("😀a")))
            assertEquals(listOf("emoji"), keys(index.search("😀")))
            assertEquals(listOf("deseret"), keys(index.search("jou 𐐨lph")))
        }
    }

    @Test
    fun compoundCandidateDoesNotScanShortVariantsForIncidentalTokens() {
        CatalogTextSearchIndex.build(
            listOf(
                document("tamara", "Tamara Exposed 3"),
                document("unrelated", "PC Building Simulator"),
            ),
        ).use { index ->
            assertEquals(0, index.shortQueryScanCountForTest())
            assertEquals(
                listOf("tamara"),
                keys(index.search("TamaraExposed3-0.9.4-pc")),
            )
            assertEquals(0, index.shortQueryScanCountForTest())

            assertEquals(listOf("unrelated"), keys(index.search("pc")))
            assertEquals(1, index.shortQueryScanCountForTest())
        }
    }

    @Test
    fun derivedTwoCharacterCleanKeyScansStoredVariants() {
        CatalogTextSearchIndex.build(
            listOf(
                document("hs-tutor", "HS Tutor"),
                document("honey-select", "Honey Select"),
                document("unrelated", "Unrelated"),
            ),
        ).use { index ->
            assertTrue(index.search("HS-1.0-pc").isEmpty())
            assertEquals(1, index.shortQueryScanCountForTest())
        }
    }

    @Test
    fun punctuationAndFtsOperatorsAreAlwaysLiteral() {
        CatalogTextSearchIndex.build(
            listOf(
                document("one", "100% Pure"),
                document("two", "Under_score"),
                document("three", "Back\\Slash"),
                document("four", "Say \"Yes\" Again"),
            ),
        ).use { index ->
            assertEquals(listOf("one"), keys(index.search("100%")))
            assertEquals(listOf("two"), keys(index.search("er_sc")))
            assertEquals(listOf("three"), keys(index.search("ck\\sl")))
            assertEquals(listOf("four"), keys(index.search("\"yes\"")))
        }
    }

    @Test
    fun normalizedExactDoesNotHideOtherLiteralMatches() {
        CatalogTextSearchIndex.build(
            listOf(
                document("f95zone:1", "Foo-Bar"),
                document("f95zone:2", "Foobar DLC"),
            ),
        ).use { index ->
            assertEquals(
                setOf("f95zone:1", "f95zone:2"),
                keys(index.search("foobar")).toSet(),
            )
        }
    }

    @Test
    fun acronymsRequireAnUppercaseAcronymQuery() {
        CatalogTextSearchIndex.build(
            listOf(document("f95zone:1", "Bondage Bunker Age Simulator")),
        ).use { index ->
            assertEquals(listOf("f95zone:1"), keys(index.search("BB_AS")))
            assertTrue(index.search("bbas").isEmpty())
        }
    }

    @Test
    fun ordinarySearchDoesNotReturnTypoMatches() {
        CatalogTextSearchIndex.build(
            listOf(document("f95zone:1", "Pharmacist Siblings Trial")),
        ).use { index ->
            assertTrue(index.search("Xharmacist Siblings Trial").isEmpty())
            assertTrue(index.search("Pharmacits Siblings Trial").isEmpty())
        }
    }

    @Test
    fun boundedTypoRescueRequiresTwoSharedTokensAndOneLongTokenEdit() {
        CatalogTextSearchIndex.build(
            listOf(
                document("target", "Buchikome High Kick"),
                document("other", "Unrelated High Kick"),
            ),
        ).use { index ->
            assertEquals(listOf("target"), keys(index.searchTypoRescue("Buchicome High Kick")))
            assertTrue(index.searchTypoRescue("Buchicome Kick").isEmpty())
            assertTrue(index.searchTypoRescue("Buchicome Tall Kick").isEmpty())
            assertTrue(index.searchTypoRescue("Buxxixome High Kick").isEmpty())
        }
    }

    @Test
    fun exactIdentityLookupUsesDedicatedProductAndArchiveKeys() {
        val entry = SourceCatalogEntry(
            source = "otomi",
            sourceId = "1",
            canonicalUrl = "https://otomi-games.com/rj01154832/",
            title = "Hidden Japanese Title",
            productCodes = listOf("RJ01154832"),
            downloadAliases = listOf("otomi-games.com_CJDEBC73.rar"),
        )
        CatalogTextSearchIndex.build(
            listOf(document("otomi:1", entry.title, sourceEntry = entry)),
        ).use { index ->
            assertEquals(
                listOf("otomi:1"),
                keys(index.searchIdentity(CatalogIdentityKind.ProductCode, "RJ01154832")),
            )
            assertEquals(
                listOf("otomi:1"),
                keys(index.searchIdentity(CatalogIdentityKind.DownloadAlias, "otomi-games.com_cjdebc73")),
            )
        }
    }

    @Test
    fun aliasesDoNotDuplicateDocumentsAndResultsRemainUnsorted() {
        CatalogTextSearchIndex.build(
            listOf(
                document("one", "Moon Journey", aliases = listOf("Journey to the Moon")),
                document("two", "Moon"),
            ),
        ).use { index ->
            assertEquals(1, index.search("journey").count { it.document.key == "one" })
            assertEquals(
                setOf(CatalogNameMatchKind.Exact, CatalogNameMatchKind.Prefix),
                index.search("moon").map { it.kind }.toSet(),
            )
        }
    }

    @Test
    fun searchChecksCancellationWhileVerifyingLargeCandidateSets() {
        CatalogTextSearchIndex.build(
            (0 until 1_000).map { id ->
                document("f95zone:$id", "Needle title $id")
            },
        ).use { index ->
            var checks = 0
            assertThrows(InterruptedException::class.java) {
                index.search("needle") {
                    checks++
                    if (checks == 3) throw InterruptedException("cancelled")
                }
            }
            assertTrue(checks >= 3)
        }
    }

    @Test
    fun buildChecksCancellation() {
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            CatalogTextSearchIndex.build(
                (0 until 1_000).map { id -> document("f95zone:$id", "Title $id") },
                checkCancelled = {
                    checks++
                    if (checks == 3) throw InterruptedException("cancelled")
                },
            )
        }
        assertEquals(3, checks)
    }

    @Test
    fun persistentDatabaseIsReusedWhenSignaturesMatch() {
        val database = File(temporaryFolder.root, "search.db")
        val documents = listOf(document("one", "Original", aliases = listOf("Translated")))

        CatalogTextSearchIndex.build(documents, database).close()
        val preservedTimestamp = 1_600_000_000_000L
        assertTrue(database.setLastModified(preservedTimestamp))

        CatalogTextSearchIndex.build(documents, database).use { index ->
            assertEquals(listOf("one"), keys(index.search("Translated")))
        }
        assertEquals(preservedTimestamp, database.lastModified())
    }

    @Test
    fun translationAliasesAreReplacedWithoutRebuildingOriginalNames() {
        val database = File(temporaryFolder.root, "search.db")
        CatalogTextSearchIndex.build(
            listOf(document("one", "Original", aliases = listOf("Old Translation"))),
            database,
        ).close()

        CatalogTextSearchIndex.build(
            listOf(document("one", "Original", aliases = listOf("New Translation"))),
            database,
        ).use { index ->
            assertTrue(index.search("Old Translation").isEmpty())
            assertEquals(listOf("one"), keys(index.search("New Translation")))
            assertEquals(listOf("one"), keys(index.search("Original")))
        }
    }

    @Test
    fun changingOriginalCatalogRebuildsPersistentDatabase() {
        val database = File(temporaryFolder.root, "search.db")
        CatalogTextSearchIndex.build(listOf(document("one", "Old Original")), database).close()

        CatalogTextSearchIndex.build(listOf(document("one", "New Original")), database).use { index ->
            assertTrue(index.search("Old Original").isEmpty())
            assertEquals(listOf("one"), keys(index.search("New Original")))
        }
        assertFalse(File("${database.path}.tmp").exists())
    }

    @Test
    fun payloadChangesDoNotRewriteUnchangedNameRows() {
        val database = File(temporaryFolder.root, "search.db")
        val original = CatalogTextSearchDocument(
            key = "one",
            displayTitle = "Old display title",
            titles = listOf("Searchable title"),
        )
        CatalogTextSearchIndex.build(listOf(original), database).close()
        val originalRowIds = ftsRowIds(database, "one")

        CatalogTextSearchIndex.build(
            listOf(original.copy(displayTitle = "New display title")),
            database,
        ).use { index ->
            assertEquals("New display title", index.search("Searchable").single().document.displayTitle)
        }

        assertEquals(originalRowIds, ftsRowIds(database, "one"))
    }

    @Test
    fun persistentDatabaseAddsAndRemovesDocumentsIncrementally() {
        val database = File(temporaryFolder.root, "search.db")
        CatalogTextSearchIndex.build(listOf(document("one", "First title")), database).close()

        CatalogTextSearchIndex.build(
            listOf(document("one", "First title"), document("two", "Second title")),
            database,
        ).use { index ->
            assertEquals(listOf("two"), keys(index.search("Second")))
        }

        CatalogTextSearchIndex.build(listOf(document("two", "Second title")), database).use { index ->
            assertTrue(index.search("First").isEmpty())
            assertEquals(listOf("two"), keys(index.search("Second")))
        }
    }

    @Test
    fun indexSearchesAfterSourceDocumentCollectionIsReleased() {
        val documents = mutableListOf(
            document("one", "Persisted catalog title"),
            document("two", "Other title"),
        )
        CatalogTextSearchIndex.build(documents).use { index ->
            documents.clear()
            assertEquals(listOf("one"), keys(index.search("Persisted catalog")))
            assertFalse(
                CatalogTextSearchIndex::class.java.declaredFields.any { field ->
                    field.type == List::class.java ||
                        field.type == Map::class.java ||
                        field.type == BooleanArray::class.java
                },
            )
        }
    }

    @Test
    fun unreadablePersistentCacheIsDiscardedAndRebuilt() {
        val database = File(temporaryFolder.root, "search.db")
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(
            database.absolutePath,
            androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE or
                androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE,
        ).close()

        CatalogTextSearchIndex.build(listOf(document("one", "Recovered Title")), database).use { index ->
            assertEquals(listOf("one"), keys(index.search("Recovered Title")))
        }
    }

    private fun ftsRowIds(database: File, key: String): List<Long> =
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(
            database.absolutePath,
            androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY,
        ).use { connection ->
            buildList {
                connection.prepare(
                    "SELECT rowid FROM names_fts WHERE doc_key = ? ORDER BY rowid",
                ).use { statement ->
                    statement.bindText(1, key)
                    while (statement.step()) add(statement.getLong(0))
                }
            }
        }
}
