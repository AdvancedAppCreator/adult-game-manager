package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoredCatalogTitleAnalysisTest {
    private fun game(id: Int, title: String): CatalogGame = CatalogGame(
        thread_id = id,
        title = title,
        source = SOURCE_F95ZONE,
        sourceId = id.toString(),
    )

    private fun document(g: CatalogGame): CatalogTextSearchDocument = CatalogTextSearchDocument(
        key = catalogTextSearchKey(g.source, g.sourceId, g.thread_id),
        displayTitle = g.title,
        titles = listOf(g.title),
        legacyGame = g,
    )

    private fun sourceDocument(
        source: String,
        sourceId: String,
        title: String,
        groupId: String,
    ): CatalogTextSearchDocument {
        val entry = SourceCatalogEntry(
            source = source,
            sourceId = sourceId,
            canonicalUrl = "https://example.test/$source/$sourceId",
            title = title,
            agmGroupId = groupId,
        )
        return CatalogTextSearchDocument(
            key = catalogTextSearchKey(source, sourceId),
            displayTitle = title,
            titles = listOf(title),
            sourceEntry = entry,
        )
    }

    @Test
    fun noCandidatesYieldsNoMatch() {
        val index = CatalogTextSearchIndex.build(listOf(document(game(1, "Anything"))))

        val result = scoredCatalogTitleAnalysis(index, emptyList())

        assertEquals(ScoredCatalogTitleResult.NoMatch, result)
    }

    @Test
    fun candidatesWithNoHitsYieldNoMatch() {
        val index = CatalogTextSearchIndex.build(listOf(document(game(1, "Completely Unrelated Title"))))

        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(NameCandidate("Zzz Nomatch Qqq", NameCandidateSource.AppLabel)),
        )

        assertEquals(ScoredCatalogTitleResult.NoMatch, result)
    }

    @Test
    fun exactReadmeCandidateSelectsSafeMatch() {
        val g = game(1, "Original Game Title")
        val index = CatalogTextSearchIndex.build(listOf(document(g)))

        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(NameCandidate("Original Game Title", NameCandidateSource.Readme)),
        )

        assertTrue(result is ScoredCatalogTitleResult.Selected)
        assertEquals(g, (result as ScoredCatalogTitleResult.Selected).game)
    }

    @Test
    fun singleWeakFolderPrefixIsNotSelected() {
        // Regression test: a folder literally named "Legacy" is a *literal text prefix* of the
        // catalog title "Legacy of the Void" (index Prefix tier), but a single weak FolderName
        // source must never be enough, alone, to auto-map a prefix hit.
        val g = game(1, "Legacy of the Void")
        val index = CatalogTextSearchIndex.build(listOf(document(g)))

        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(NameCandidate("Legacy", NameCandidateSource.FolderName)),
        )

        assertTrue("expected Legacy -> Legacy of the Void to stay unmapped, was $result", result !is ScoredCatalogTitleResult.Selected)
        assertTrue(result is ScoredCatalogTitleResult.Ambiguous)
        assertTrue(g in (result as ScoredCatalogTitleResult.Ambiguous).candidates)
    }

    @Test
    fun singleWeakWordPrefixCandidateIsAmbiguousWithoutCorroboration() {
        // The colon keeps "Whisper Falls" from also being a literal Prefix hit, isolating the
        // word-list-prefix (Word) tier.
        val g = game(1, "Whisper: Falls Final")
        val index = CatalogTextSearchIndex.build(listOf(document(g)))

        // "Whisper Falls" word-list-prefixes "Whisper: Falls Final" (a harmless release-tag
        // suffix), but a single weak folder-name source isn't enough on its own to clear the
        // safe-auto-match bar.
        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(NameCandidate("Whisper Falls", NameCandidateSource.FolderName)),
        )

        assertTrue(result is ScoredCatalogTitleResult.Ambiguous)
    }

    @Test
    fun identicalTextFromTwoIndependentSourcesCorroboratesIntoSafeMatch() {
        val g = game(1, "Whisper: Falls Final")
        val index = CatalogTextSearchIndex.build(listOf(document(g)))

        // Same text from two independent sources: grouping performs one indexed lookup for
        // "Whisper Falls", but both contributing sources still count toward corroboration.
        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(
                NameCandidate("Whisper Falls", NameCandidateSource.FolderName),
                NameCandidate("whisper falls", NameCandidateSource.JoiPlayInternalTitle),
            ),
        )

        assertTrue(result is ScoredCatalogTitleResult.Selected)
        assertEquals(g, (result as ScoredCatalogTitleResult.Selected).game)
    }

    @Test
    fun differentTextsFromIndependentWeakSourcesConvergeIntoSafeCorroboration() {
        val g = game(1, "Legacy of the Void")
        val index = CatalogTextSearchIndex.build(listOf(document(g)))

        // Two different (weak) sources, with two different literal-prefix texts, land on the
        // same catalog identity and still corroborate each other into a safe auto-match.
        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(
                NameCandidate("Legacy", NameCandidateSource.FolderName),
                NameCandidate("Legacy of", NameCandidateSource.JoiPlayInternalTitle),
            ),
        )

        assertTrue(result is ScoredCatalogTitleResult.Selected)
        assertEquals(g, (result as ScoredCatalogTitleResult.Selected).game)
    }

    @Test
    fun singleStrongReadmeSourcePrefixMatchIsSelected() {
        val g = game(1, "Enchanted Path Extended")
        val index = CatalogTextSearchIndex.build(listOf(document(g)))

        // A prefix hit from a *strong* source (README) is trusted enough to auto-map alone,
        // unlike the same tier from a weak folder-name source.
        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(NameCandidate("Enchanted Path", NameCandidateSource.Readme)),
        )

        assertTrue(result is ScoredCatalogTitleResult.Selected)
        assertEquals(g, (result as ScoredCatalogTitleResult.Selected).game)
    }

    @Test
    fun repeatingTheSameTextUnderOneSourceDoesNotFabricateCorroboration() {
        val g = game(1, "Whisper: Falls Final")
        val index = CatalogTextSearchIndex.build(listOf(document(g)))

        // Same source, same text (only casing/whitespace differ) repeated three times must
        // still be treated as exactly one independent source, not three.
        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(
                NameCandidate("Whisper Falls", NameCandidateSource.FolderName),
                NameCandidate("whisper falls", NameCandidateSource.FolderName),
                NameCandidate("  Whisper Falls  ", NameCandidateSource.FolderName),
            ),
        )

        assertTrue(result is ScoredCatalogTitleResult.Ambiguous)
    }

    @Test
    fun tooCloseTopCandidatesStayAmbiguousInsteadOfAutoMapping() {
        val first = game(1, "Foo Bar")
        val second = game(2, "Foo Baz")
        val index = CatalogTextSearchIndex.build(listOf(document(first), document(second)))

        // Even a strong (README) source can't break a tie: a literal-prefix hit that equally
        // matches two distinct catalog identities is too close to safely auto-pick either one.
        val result = scoredCatalogTitleAnalysis(
            index,
            listOf(NameCandidate("Foo Ba", NameCandidateSource.Readme)),
        )

        assertTrue(result is ScoredCatalogTitleResult.Ambiguous)
        val candidates = (result as ScoredCatalogTitleResult.Ambiguous).candidates
        assertTrue(first in candidates)
        assertTrue(second in candidates)
    }

    @Test
    fun exactHitsFromTheSameCatalogGroupAreOneIdentity() {
        val index = CatalogTextSearchIndex.build(
            listOf(
                sourceDocument("kimochi", "amana", "アマナアライブ!!", "g_amana"),
                sourceDocument("otomi", "27631", "アマナアライブ!!", "g_amana"),
            ),
        )

        index.use {
            val result = scoredCatalogTitleAnalysis(
                it,
                listOf(NameCandidate("アマナアライブ!!", NameCandidateSource.AppLabel)),
            )

            assertTrue(result is ScoredCatalogTitleResult.Selected)
            assertEquals("g_amana", (result as ScoredCatalogTitleResult.Selected).game.agmGroupId)
        }
    }

    @Test
    fun singleWeakTypoHitIsReviewableButNotAutomaticallySelected() {
        val target = game(1, "Buchikome High Kick")
        val index = CatalogTextSearchIndex.build(listOf(document(target)))

        index.use {
            val result = scoredCatalogTitleAnalysis(
                it,
                listOf(NameCandidate("Buchicome High Kick", NameCandidateSource.AppLabel)),
            )

            assertTrue(result is ScoredCatalogTitleResult.Ambiguous)
            assertEquals(listOf(target), (result as ScoredCatalogTitleResult.Ambiguous).candidates)
            assertEquals("typo-index", result.via)
        }
    }

    @Test
    fun strongTypoHitCanBeAutomaticallySelected() {
        val target = game(1, "Buchikome High Kick")
        val index = CatalogTextSearchIndex.build(listOf(document(target)))

        index.use {
            val result = scoredCatalogTitleAnalysis(
                it,
                listOf(NameCandidate("Buchicome High Kick", NameCandidateSource.Readme)),
            )

            assertTrue(result is ScoredCatalogTitleResult.Selected)
            assertEquals(target, (result as ScoredCatalogTitleResult.Selected).game)
            assertEquals("typo:readme", result.via)
        }
    }

    @Test
    fun uniqueExactProductCodeSelectsCrossSourceGroupBeforeTitleMatching() {
        val entry = SourceCatalogEntry(
            source = "otomi",
            sourceId = "22763",
            canonicalUrl = "https://otomi-games.com/rj01154832/",
            title = "どうして私なんかを痴漢するの",
            agmGroupId = "g_rj01154832",
            productCodes = listOf("RJ01154832"),
        )
        val index = CatalogTextSearchIndex.build(
            listOf(
                CatalogTextSearchDocument(
                    key = "otomi:22763",
                    displayTitle = entry.title,
                    titles = listOf(entry.title),
                    sourceEntry = entry,
                ),
            ),
        )

        index.use {
            val result = scoredCatalogTitleAnalysis(
                index = it,
                candidates = emptyList(),
                identityCandidates = listOf(
                    CatalogIdentityCandidate(
                        CatalogIdentityKind.ProductCode,
                        "RJ01154832",
                        NameCandidateSource.FolderName,
                    ),
                ),
            )

            assertTrue(result is ScoredCatalogTitleResult.Selected)
            assertEquals("identity:product_code", (result as ScoredCatalogTitleResult.Selected).via)
            assertEquals("g_rj01154832", result.game.agmGroupId)
        }
    }

    @Test
    fun identityCollisionRemainsAmbiguous() {
        val documents = listOf("one", "two").map { id ->
            val entry = SourceCatalogEntry(
                source = "otomi",
                sourceId = id,
                canonicalUrl = "https://example.test/$id",
                title = "Title $id",
                agmGroupId = "g_$id",
                downloadAliases = listOf("otomi-games.com_COLLISION"),
            )
            CatalogTextSearchDocument(
                key = "otomi:$id",
                displayTitle = entry.title,
                titles = listOf(entry.title),
                sourceEntry = entry,
            )
        }
        val index = CatalogTextSearchIndex.build(documents)

        index.use {
            val result = scoredCatalogTitleAnalysis(
                index = it,
                candidates = emptyList(),
                identityCandidates = listOf(
                    CatalogIdentityCandidate(
                        CatalogIdentityKind.DownloadAlias,
                        "otomi-games.com_collision",
                        NameCandidateSource.FolderName,
                    ),
                ),
            )

            assertTrue(result is ScoredCatalogTitleResult.Ambiguous)
            assertEquals("identity-conflict", (result as ScoredCatalogTitleResult.Ambiguous).via)
            assertEquals(2, result.candidates.size)
        }
    }
}
