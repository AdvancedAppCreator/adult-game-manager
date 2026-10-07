package com.example.f95updater

/** Result of [scoredCatalogTitleAnalysis]: either a single safe auto-match, a ranked candidate
 *  list for manual review (top identity unsafe or too close to the runner-up), or no candidates
 *  at all. Mirrors the old [CatalogTitleMatch]/[CatalogAmbiguousTitleMatch] shapes so refresh
 *  callers only need to branch once instead of chaining bestTitleMatch + ambiguousTitleMatch. */
internal sealed class ScoredCatalogTitleResult {
    data class Selected(val game: CatalogGame, val via: String) : ScoredCatalogTitleResult()
    data class Ambiguous(val candidates: List<CatalogGame>, val via: String) : ScoredCatalogTitleResult()
    object NoMatch : ScoredCatalogTitleResult()
}

/** If the runner-up identity's final score is within this margin of the top identity, treat
 *  the result as ambiguous rather than auto-mapping a near-tie. */
private const val AMBIGUOUS_MARGIN = 40

/** Bonus per additional independent [NameCandidateSource] whose own best match lands on the
 *  same catalog identity, capped so a flood of weak, same-tier sources can't out-vote a single
 *  strong, specific one indefinitely. Purely a ranking/tie-break aid — see [isSafeAutoMatch]
 *  for the actual auto-map safety gate. */
private const val CORROBORATION_BONUS = 30
private const val MAX_CORROBORATION_EXTRA_SOURCES = 3

private data class ScoredIdentityAggregate(
    var weightedScore: Int,
    var bestKind: CatalogNameMatchKind,
    var via: NameCandidateSource,
    var game: CatalogGame,
    val sources: MutableSet<NameCandidateSource> = linkedSetOf(),
)

internal val CatalogTextSearchDocument.matchIdentityKey: String
    get() = sourceEntry?.agmGroupId?.trim()?.takeIf { it.isNotEmpty() } ?: key

/** Exact deterministic matches can auto-map from one source. Partial deterministic matches still
 *  require a strong source or corroboration from an independent name source. */
private fun isSafeAutoMatch(aggregate: ScoredIdentityAggregate): Boolean =
    aggregate.bestKind == CatalogNameMatchKind.Exact ||
        aggregate.sources.any { it.strong } ||
        aggregate.sources.size >= 2

/** Runs every distinct name candidate through the same deterministic name search used by the UI. */
internal fun scoredCatalogTitleAnalysis(
    index: CatalogTextSearchIndex,
    candidates: List<NameCandidate>,
    identityCandidates: List<CatalogIdentityCandidate> = emptyList(),
    checkCancelled: () -> Unit = {},
): ScoredCatalogTitleResult {
    val identityAggregates = LinkedHashMap<String, CatalogGame>()
    val matchedIdentityKinds = linkedSetOf<CatalogIdentityKind>()
    for (identity in identityCandidates) {
        val matches = index.searchIdentity(identity.kind, identity.value, checkCancelled)
        if (matches.isEmpty()) continue
        matchedIdentityKinds += identity.kind
        for (match in matches) {
            val game = match.document.sourceEntry?.let(::sourceEntryToCatalogGame)
                ?: match.document.legacyGame
                ?: continue
            val key = match.document.matchIdentityKey
            val current = identityAggregates[key]
            if (current == null ||
                SourceRegistry.priority(game.source) > SourceRegistry.priority(current.source)
            ) {
                identityAggregates[key] = game
            }
        }
    }
    if (identityAggregates.size == 1) {
        val kind = when {
            CatalogIdentityKind.ProductCode in matchedIdentityKinds ->
                CatalogIdentityKind.ProductCode.wireValue
            else -> CatalogIdentityKind.DownloadAlias.wireValue
        }
        return ScoredCatalogTitleResult.Selected(identityAggregates.values.single(), "identity:$kind")
    }
    if (identityAggregates.size > 1) {
        return ScoredCatalogTitleResult.Ambiguous(
            identityAggregates.values.take(12),
            "identity-conflict",
        )
    }

    val representativeTextByKey = LinkedHashMap<String, String>()
    val sourcesByKey = LinkedHashMap<String, MutableSet<NameCandidateSource>>()
    for (candidate in candidates) {
        val trimmed = candidate.text.trim()
        if (trimmed.isBlank()) continue
        val key = trimmed.lowercase()
        representativeTextByKey.putIfAbsent(key, trimmed)
        sourcesByKey.getOrPut(key) { linkedSetOf() }.add(candidate.source)
    }
    if (representativeTextByKey.isEmpty()) return ScoredCatalogTitleResult.NoMatch

    fun aggregateHits(
        search: (String) -> List<CatalogTextSearchScoredMatch>,
    ): LinkedHashMap<String, ScoredIdentityAggregate> {
        val aggregates = LinkedHashMap<String, ScoredIdentityAggregate>()
        for ((key, text) in representativeTextByKey) {
            val sources = sourcesByKey.getValue(key)
            val hits = search(text)
            if (hits.isEmpty()) continue
            val strongestSource = sources.maxBy { it.weight }
            for (hit in hits) {
            val game = hit.document.sourceEntry?.let(::sourceEntryToCatalogGame) ?: hit.document.legacyGame ?: continue
            val weighted = hit.score + strongestSource.weight
            val aggregate = aggregates.getOrPut(hit.document.matchIdentityKey) {
                ScoredIdentityAggregate(
                    weightedScore = weighted,
                    bestKind = hit.kind,
                    via = strongestSource,
                    game = game,
                )
            }
            val betterRepresentative = weighted == aggregate.weightedScore &&
                hit.kind == aggregate.bestKind &&
                SourceRegistry.priority(game.source) > SourceRegistry.priority(aggregate.game.source)
            if (weighted > aggregate.weightedScore ||
                (weighted == aggregate.weightedScore && hit.kind.score > aggregate.bestKind.score) ||
                betterRepresentative
            ) {
                aggregate.bestKind = hit.kind
                aggregate.weightedScore = weighted
                aggregate.via = strongestSource
                aggregate.game = game
            }
            aggregate.sources += sources
        }
        }
        return aggregates
    }

    var aggregates = aggregateHits { text -> index.search(text, checkCancelled) }
    var usedTypoRescue = false
    if (aggregates.isEmpty()) {
        aggregates = aggregateHits { text -> index.searchTypoRescue(text, checkCancelled) }
        usedTypoRescue = aggregates.isNotEmpty()
    }
    if (aggregates.isEmpty()) return ScoredCatalogTitleResult.NoMatch

    val ranked = aggregates.values
        .map { aggregate ->
            val corroboration = (aggregate.sources.size - 1).coerceIn(0, MAX_CORROBORATION_EXTRA_SOURCES)
            val finalScore = aggregate.weightedScore + corroboration * CORROBORATION_BONUS
            finalScore to aggregate
        }
        .sortedWith(
            compareByDescending<Pair<Int, ScoredIdentityAggregate>> { it.first }
                .thenByDescending { it.second.game.views }
                .thenByDescending { it.second.game.ts },
        )

    val top = ranked[0]
    val second = ranked.getOrNull(1)
    val tooClose = second != null && (top.first - second.first) < AMBIGUOUS_MARGIN

    return if (isSafeAutoMatch(top.second) && !tooClose) {
        val prefix = if (usedTypoRescue) "typo" else "scored"
        ScoredCatalogTitleResult.Selected(top.second.game, "$prefix:${top.second.via.name.lowercase()}")
    } else {
        val via = if (usedTypoRescue) "typo-index" else "scored-index"
        ScoredCatalogTitleResult.Ambiguous(
            ranked.map { it.second.game }.distinctBy(CatalogGame::matchIdentityKey).take(12),
            via,
        )
    }
}
