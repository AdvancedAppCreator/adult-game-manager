package com.example.f95updater

internal data class CatalogNavigationIdentity(
    val agmGroupId: String? = null,
    val source: String? = null,
    val sourceId: String? = null,
    val canonicalUrl: String? = null,
)

internal fun catalogNavigationIdentity(game: CatalogGame): CatalogNavigationIdentity? {
    val groupId = game.agmGroupId?.trim()?.takeIf(String::isNotEmpty)
    val source = game.source.trim().takeIf(String::isNotEmpty)
    val sourceId = game.sourceId?.trim()?.takeIf(String::isNotEmpty)
        ?: game.thread_id.takeIf { it > 0 && source == SOURCE_F95ZONE }?.toString()
    val url = game.canonicalUrl.trim().takeIf(String::isNotEmpty)
    if (groupId == null && (source == null || sourceId == null) && url == null) return null
    return CatalogNavigationIdentity(groupId, source, sourceId, url)
}

internal fun confirmedCatalogNavigationIdentity(mapping: AppMapping?): CatalogNavigationIdentity? {
    mapping ?: return null
    if (mapping.notOnF95) return null
    val groupId = mapping.mappedAgmGroupId?.trim()?.takeIf(String::isNotEmpty)
    val mappedSource = mapping.mappedCatalogSource?.trim()?.takeIf(String::isNotEmpty)
    val mappedSourceId = mapping.mappedCatalogSourceId?.trim()?.takeIf(String::isNotEmpty)
    val f95ThreadId = mapping.threadId
        ?: F95UrlParser.extractThreadId(mapping.f95Url)
        ?: mapping.mappedCatalogId?.takeIf {
            it > 0 && (mappedSource == null || mappedSource == SOURCE_F95ZONE)
        }
    val source = mappedSource ?: f95ThreadId?.let { SOURCE_F95ZONE }
    val sourceId = mappedSourceId ?: f95ThreadId?.toString()
    val url = mapping.mappedCatalogUrl?.trim()?.takeIf(String::isNotEmpty)
        ?: mapping.f95Url?.trim()?.takeIf(String::isNotEmpty)
    if (groupId == null && (source == null || sourceId == null) && url == null) return null
    return CatalogNavigationIdentity(groupId, source, sourceId, url)
}

internal fun installedPackageForCatalogGroup(
    group: CatalogRowsGroup,
    mappings: Map<String, AppMapping>,
    installedPackageNames: Set<String>,
): String? {
    val groupKeys = group.members.mapTo(hashSetOf(), ::catalogInstallKey)
    val groupUrls = group.members.mapNotNullTo(hashSetOf()) {
        it.canonicalUrl.trim().takeIf(String::isNotEmpty)
    }
    return installedPackageNames.asSequence()
        .sorted()
        .firstOrNull { packageName ->
            val mapping = mappings[packageName] ?: return@firstOrNull false
            val identity = confirmedCatalogNavigationIdentity(mapping) ?: return@firstOrNull false
            identity.agmGroupId == group.agmGroupId ||
                (identity.source != null && identity.sourceId != null &&
                    CatalogInstallKey(identity.source, identity.sourceId) in groupKeys) ||
                (identity.canonicalUrl != null && identity.canonicalUrl in groupUrls)
        }
}

internal fun revealInstalledNavigationDestination(
    filteredRows: List<AppRow>,
    destination: AppRow?,
): List<AppRow> {
    destination ?: return filteredRows
    return if (filteredRows.any { it.installed.packageName == destination.installed.packageName }) {
        filteredRows
    } else {
        listOf(destination) + filteredRows
    }
}
