package com.example.f95updater

internal data class CatalogIndexedTitle(
    val game: CatalogGame,
    val title: String,
)

internal fun catalogIndexedTitles(
    games: List<CatalogGame>,
    translatedTitles: Map<String, String>,
): List<CatalogIndexedTitle> = buildList {
    for (game in games) {
        add(CatalogIndexedTitle(game, game.title))
        val key = game.sourceId?.let { "${game.source}:$it" }
        val translated = key?.let(translatedTitles::get)?.trim()
        if (!translated.isNullOrBlank() &&
            CatalogRepository.normalizeTitle(translated) != CatalogRepository.normalizeTitle(game.title)
        ) {
            add(CatalogIndexedTitle(game, translated))
        }
    }
}.distinctBy {
    "${catalogTextSearchKey(it.game.source, it.game.sourceId, it.game.thread_id)}:" +
        CatalogRepository.normalizeTitle(it.title)
}

internal fun catalogSearchTranslatedTitle(
    game: CatalogGame,
    translatedTitles: Map<String, String>,
): String? {
    val key = game.sourceId?.let { "${game.source}:$it" } ?: return null
    return translatedTitles[key]
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.takeIf {
            CatalogRepository.normalizeTitle(it) != CatalogRepository.normalizeTitle(game.title)
        }
}

internal fun uniqueHighestPriorityCatalogGame(games: List<CatalogGame>): CatalogGame? {
    if (games.isEmpty()) return null
    val highestPriority = games.maxOf { SourceRegistry.priority(it.source) }
    return games.filter { SourceRegistry.priority(it.source) == highestPriority }.singleOrNull()
}

/** Parses an ISO-8601 instant into epoch seconds, returning 0 for null/blank/unparseable input. */
internal fun parseIsoEpochSeconds(value: String?): Long =
    value?.takeIf { it.isNotBlank() }?.let {
        runCatching { java.time.Instant.parse(it).epochSecond }.getOrDefault(0L)
    } ?: 0L

/** Converts a [SourceCatalogEntry] (the multi-source catalog model) into the legacy
 *  [CatalogGame] shape used for installed-app matching/mapping. Top-level + internal so it's
 *  directly unit-testable without instantiating [CatalogRepository] (which needs a [Context]).
 *  Must carry [SourceCatalogEntry.thumbnailUrl] through to [CatalogGame.thumbnailUrl] — losing
 *  it here would make the installed library fall back to the full [CatalogGame.cover] for list
 *  thumbnails, defeating the point of a separate display-sized image. */
internal fun sourceEntryToCatalogGame(entry: SourceCatalogEntry): CatalogGame {
    val f95ThreadId = if (entry.source == SOURCE_F95ZONE) entry.sourceId.toIntOrNull() else null
    // Deterministic negative synthetic id for non-F95 entries. Kept (not a workaround
    // to remove): CatalogGame.thread_id is an Int identity persisted in AppMapping; a
    // stable hash of "source:sourceId" preserves back-compat for already-saved non-F95
    // mappings. Robust identity is (source, sourceId), also persisted on the mapping.
    val syntheticId = -((("${entry.source}:${entry.sourceId}".hashCode() and Int.MAX_VALUE).takeIf { it > 0 }) ?: 1)
    return CatalogGame(
        thread_id = f95ThreadId ?: syntheticId,
        title = entry.title,
        creator = entry.developer,
        version = entry.versionText,
        rating = entry.rating,
        views = entry.popularity?.toLong() ?: 0L,
        ts = parseIsoEpochSeconds(entry.modifiedAt ?: entry.publishedAt),
        publishedAt = parseIsoEpochSeconds(entry.publishedAt),
        modifiedAt = parseIsoEpochSeconds(entry.modifiedAt),
        cover = entry.coverUrl,
        source = entry.source,
        sourceId = entry.sourceId,
        sourceUrl = entry.canonicalUrl,
        agmGroupId = entry.agmGroupId,
        thumbnailUrl = entry.thumbnailUrl,
    )
}
