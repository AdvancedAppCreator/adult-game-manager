package com.example.f95updater

/**
 * Derives the display fields for a grouped Catalog row from the group's member source entries.
 * Grouping itself is done server-side (`agmGroupId`) and in the rows DB; this only chooses which
 * member's title/cover to show and aggregates version/rating across sources. Pure (no Android types)
 * so it is unit-testable.
 */
data class CatalogGroupDisplay(
    val agmGroupId: String,
    val representative: SourceCatalogEntry,
    val title: String,
    val developer: String?,
    val coverUrl: String?,
    val thumbnailUrl: String?,
    val engine: String?,
    val versionText: String?,
    val rating: Double?,
    val popularity: Double?,
    val sources: List<String>,
    val canonicalTags: List<String>,
    val members: List<SourceCatalogEntry>,
) {
    val sourceCount: Int get() = sources.size
}

object CatalogGroupPresentation {

    /** The member shown as the group's face: highest source priority, then most-recently updated,
     *  then title — mirroring the server compiler's representative selection. */
    fun representative(members: List<SourceCatalogEntry>): SourceCatalogEntry =
        members.maxWithOrNull(
            compareBy<SourceCatalogEntry> { SourceRegistry.priority(it.source) }
                .thenBy { it.modifiedAt ?: it.publishedAt ?: "" }
                .thenByDescending { it.title },
        ) ?: members.first()

    fun of(agmGroupId: String, members: List<SourceCatalogEntry>): CatalogGroupDisplay {
        val rep = representative(members)
        val newest = LatestReleaseSelector.choose(members) ?: rep
        val sources = members.map { it.source }
            .distinct()
            .sortedWith(
                compareByDescending<String> { SourceRegistry.priority(it) }.thenBy { it },
            )
        val canonicalTags = members.flatMap { it.canonicalTags }.distinct()
        val cover = members.firstNotNullOfOrNull { it.coverUrl?.takeIf { url -> url.isNotBlank() } }
            ?: rep.coverUrl
        val thumbnail = members.firstNotNullOfOrNull { it.thumbnailUrl?.takeIf { url -> url.isNotBlank() } }
            ?: rep.thumbnailUrl
        return CatalogGroupDisplay(
            agmGroupId = agmGroupId,
            representative = rep,
            title = rep.title,
            developer = members.firstNotNullOfOrNull { it.developer?.takeIf { d -> d.isNotBlank() } },
            coverUrl = cover,
            thumbnailUrl = thumbnail,
            engine = members.firstNotNullOfOrNull { it.engine?.takeIf { e -> e.isNotBlank() } },
            versionText = newest.versionText?.takeIf { it.isNotBlank() },
            rating = members.mapNotNull { it.rating }.maxOrNull(),
            popularity = members.mapNotNull { it.popularity }.filter { it > 0.0 }.maxOrNull(),
            sources = sources,
            canonicalTags = canonicalTags,
            members = members,
        )
    }
}
