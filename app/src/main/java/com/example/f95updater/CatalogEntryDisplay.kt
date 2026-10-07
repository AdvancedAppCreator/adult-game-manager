package com.example.f95updater

internal fun sourceTagIds(entry: SourceCatalogEntry): Set<Int> =
    entry.tags.mapNotNull { it.toIntOrNull() }.toSet()

internal fun statusLabel(id: Int): String = when (id) {
    KnownPrefixes.COMPLETED -> "Completed"
    KnownPrefixes.ONHOLD -> "On hold"
    KnownPrefixes.ABANDONED -> "Abandoned"
    else -> "Status"
}

internal fun platformDisplayName(platform: String): String = when (platform.lowercase()) {
    "windows" -> "Windows/VM"
    else -> platform
}

internal fun displayTags(entry: SourceCatalogEntry, labels: CatalogLabelsV2?): List<String> {
    val sourceLabels = labels?.forSource(entry.source)
    return entry.tags.mapNotNull { raw ->
        val id = raw.toIntOrNull()
        when {
            id != null -> sourceLabels?.prefixes?.get(raw) ?: sourceLabels?.tags?.get(raw)
            raw.isBlank() -> null
            else -> raw
        }
    }.distinct()
}
