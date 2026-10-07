package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshMatchingTest {
    private val game = CatalogGame(
        thread_id = 1,
        title = "薬師兄妹のえっちな治験",
        source = SOURCE_F95ZONE,
        sourceId = "1",
    )

    private fun index() = CatalogTextSearchIndex.build(
        listOf(
            CatalogTextSearchDocument(
                key = catalogTextSearchKey(game.source, game.sourceId, game.thread_id),
                displayTitle = game.title,
                titles = listOf(game.title, "Pharmacist Siblings Trial"),
                legacyGame = game,
            ),
        ),
    )

    @Test
    fun translatedNameCanSelectRefreshMatch() {
        index().use { searchIndex ->
            val result = scoredCatalogTitleAnalysis(
                searchIndex,
                listOf(NameCandidate("Pharmacist Siblings Trial", NameCandidateSource.Readme)),
            )

            assertTrue(result is ScoredCatalogTitleResult.Selected)
            assertEquals(game, (result as ScoredCatalogTitleResult.Selected).game)
        }
    }

    @Test
    fun strongCandidateWithOneChangedTokenUsesBoundedTypoRescue() {
        index().use { searchIndex ->
            val result = scoredCatalogTitleAnalysis(
                searchIndex,
                listOf(NameCandidate("Xharmacist Siblings Trial", NameCandidateSource.Readme)),
            )

            assertTrue(result is ScoredCatalogTitleResult.Selected)
            assertEquals(game, (result as ScoredCatalogTitleResult.Selected).game)
            assertEquals("typo:readme", result.via)
        }
    }
}
