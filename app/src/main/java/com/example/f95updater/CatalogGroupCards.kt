package com.example.f95updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AGM 2.x compiled catalog models (produced by the server "catalog compiler" and served from the
 * separate `catalog2` feed): one representative card per cross-source game group, plus the canonical
 * faceted tag taxonomy the filter UI renders. Deserialized with `ignoreUnknownKeys = true`, so new
 * server fields never break older clients.
 */
@Serializable
data class CatalogGroupCardsEnvelope(
    val schemaVersion: Int = 2,
    val generatedAt: String? = null,
    val count: Int = 0,
    val cards: List<CatalogGroupCard> = emptyList(),
)

@Serializable
data class CatalogGroupCard(
    val agmGroupId: String,
    val title: String = "",
    val canonicalName: String? = null,
    val developer: String? = null,
    val coverUrl: String? = null,
    val thumbnailUrl: String? = null,
    val sources: List<String> = emptyList(),
    val sourceCount: Int = 0,
    val latestRelease: CatalogGroupRelease? = null,
    val versions: List<CatalogGroupRelease> = emptyList(),
    val canonicalTags: List<String> = emptyList(),
    val rating: Double? = null,
    val popularity: Double? = null,
    val engine: String? = null,
    val suggestedRunner: String? = null,
    val identity: CatalogGroupIdentity? = null,
)

@Serializable
data class CatalogGroupRelease(
    val source: String? = null,
    val sourceId: String? = null,
    val versionText: String? = null,
    val modifiedAt: String? = null,
    val canonicalUrl: String? = null,
)

@Serializable
data class CatalogGroupIdentity(
    val game: List<String> = emptyList(),
    val creator: List<String> = emptyList(),
)

@Serializable
data class CanonicalTagTaxonomy(
    val schemaVersion: Int = 2,
    val generatedAt: String? = null,
    val facets: List<String> = emptyList(),
    val tags: List<CanonicalTag> = emptyList(),
) {
    /** Facet id -> ordered tags in that facet, for rendering grouped filter chips. */
    fun byFacet(): Map<String, List<CanonicalTag>> =
        facets.associateWith { facet -> tags.filter { it.facet == facet } }

    fun labelOf(id: String): String? = tags.firstOrNull { it.id == id }?.label
}

@Serializable
data class CanonicalTag(
    val id: String,
    val facet: String,
    val label: String,
)

/** Pointer JSON (`search-db.json`) describing the prebuilt SQLite name-search database: its content
 *  signature (to decide re-download), size, and blob URL. */
@Serializable
data class CatalogSearchDbManifest(
    val schemaVersion: Int = 2,
    @SerialName("searchSchemaVersion") val searchSchemaVersion: String = "",
    @SerialName("synopsisSchemaVersion") val synopsisSchemaVersion: String = "",
    val generatedAt: String? = null,
    val databaseGeneratedAt: String? = null,
    val contentSignature: String = "",
    val synopsisCorpusFingerprint: String = "",
    val documents: Int = 0,
    val synopsisGroups: Int = 0,
    val sizeBytes: Long = 0,
    val expandedSizeBytes: Long = 0,
    val databaseSha256: String = "",
    val compressedSha256: String = "",
    val snapshotId: String = "",
    val capabilities: List<String> = emptyList(),
    val url: String = "",
)
