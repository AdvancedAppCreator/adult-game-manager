package com.example.f95updater

import kotlinx.serialization.Serializable

/**
 * A user-defined tag filter group built from the Tags dropdown: the [label] is the search term the
 * user selected under (shown as a single chip), and [tokens] are the accepted tag tokens matched with
 * an OR gate. Different groups combine with an AND gate. Serializable so the selection persists.
 */
@Serializable
data class CatalogTagGroup(
    val label: String,
    val tokens: List<String>,
)

internal data class CatalogSearchEntry(
    val entry: SourceCatalogEntry,
    val titleLower: String,
    val tagLabels: List<String>,
    val tagTokens: Set<String>,
    val numericTagIds: Set<Int>,
) {
    companion object {
        fun from(
            entry: SourceCatalogEntry,
            labels: CatalogLabelsV2?,
        ): CatalogSearchEntry {
            val tagLabels = displayTags(entry, labels)
            return CatalogSearchEntry(
                entry = entry,
                titleLower = entry.title.lowercase(),
                tagLabels = tagLabels,
                tagTokens = tagLabels.flatMap { catalogTagSearchTokens(it) }.toSet(),
                numericTagIds = sourceTagIds(entry),
            )
        }
    }
}

private val TAG_TOKEN_NON_ALNUM = Regex("[^a-z0-9]+")

internal fun catalogTagFilterToken(label: String): String =
    label.trim()
        .lowercase()
        .replace(TAG_TOKEN_NON_ALNUM, "-")
        .trim('-')

internal fun catalogTagSearchTokens(label: String): Set<String> {
    val raw = label.trim().lowercase()
    val normalized = catalogTagFilterToken(label)
    return setOf(raw, normalized, normalized.replace('-', ' '))
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun catalogTagMatchesQuery(label: String, queryToken: String): Boolean {
    val query = queryToken.trim().lowercase()
    return query.isNotBlank() && catalogTagSearchTokens(label).any { it.contains(query) }
}

internal fun addCatalogTagFilter(query: String, label: String): String {
    val token = catalogTagFilterToken(label)
    if (token.isBlank()) return query
    val existing = parseTagFilters(query).map { it.lowercase() }.toSet()
    if (token.lowercase() in existing) return query
    return listOf(query.trim(), "tag:$token")
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .plus(" ")
}

internal fun removeCatalogTagFilter(query: String, labelOrToken: String): String {
    val token = catalogTagFilterToken(labelOrToken)
    if (token.isBlank()) return query
    return query.split(Regex("\\s+"))
        .filterNot { part ->
            part.startsWith("tag:", ignoreCase = true) &&
                part.substringAfter(':').equals(token, ignoreCase = true)
        }
        .joinToString(" ")
        .trim()
        .let { if (it.isBlank()) "" else "$it " }
}

internal fun catalogFilterLabels(entries: List<CatalogSearchEntry>): List<String> =
    entries.asSequence()
        .flatMap { it.tagLabels.asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { catalogTagFilterToken(it) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        .toList()

internal fun tagFilterLabelForToken(token: String, allLabels: List<String>): String =
    allLabels.firstOrNull { catalogTagFilterToken(it).equals(token, ignoreCase = true) } ?: token
