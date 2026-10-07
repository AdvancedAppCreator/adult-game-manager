package com.example.f95updater

internal data class CatalogInstallKey(
    val source: String,
    val sourceId: String,
)

internal data class InstalledCatalogIdentities(
    val keys: Set<CatalogInstallKey>,
    val urls: Set<String>,
    val agmGroupIds: Set<String>,
)

/** Catalog identities considered "installed", derived from the live scan: a
 *  mapping contributes its keys/urls only when its local game
 *  ([installedPackageNames]) is currently installed. This keeps the catalog's
 *  "Installed" badge/filter in sync with the actual games list rather than with
 *  every mapping ever persisted. */
internal fun installedCatalogIdentities(
    mappings: Map<String, AppMapping>,
    installedPackageNames: Set<String>,
): InstalledCatalogIdentities {
    val keys = HashSet<CatalogInstallKey>()
    val urls = HashSet<String>()
    val agmGroupIds = HashSet<String>()
    for ((pkg, mapping) in mappings) {
        if (pkg !in installedPackageNames || mapping.notOnF95) continue
        keys += catalogInstallKeys(mapping)
        urls += catalogInstallUrls(mapping)
        mapping.mappedAgmGroupId?.takeIf { it.isNotBlank() }?.let(agmGroupIds::add)
    }
    return InstalledCatalogIdentities(keys, urls, agmGroupIds)
}

internal fun catalogInstallKey(entry: SourceCatalogEntry): CatalogInstallKey =
    CatalogInstallKey(entry.source, entry.sourceId)

internal fun catalogInstallKeys(mapping: AppMapping): Set<CatalogInstallKey> = buildSet {
    if (mapping.notOnF95) return@buildSet
    val mappedSource = mapping.mappedCatalogSource
    val mappedSourceId = mapping.mappedCatalogSourceId?.takeIf { it.isNotBlank() }
    if (mappedSource != null && mappedSourceId != null) {
        add(CatalogInstallKey(mappedSource, mappedSourceId))
    }
    val f95ThreadId = mapping.threadId ?: F95UrlParser.extractThreadId(mapping.f95Url)
    if (f95ThreadId != null) {
        add(CatalogInstallKey(SOURCE_F95ZONE, f95ThreadId.toString()))
    }
}

internal fun catalogInstallUrls(mapping: AppMapping): Set<String> = buildSet {
    if (mapping.notOnF95) return@buildSet
    mapping.mappedCatalogUrl?.takeIf { it.isNotBlank() }?.let(::add)
    mapping.f95Url?.takeIf { it.isNotBlank() }?.let(::add)
}
