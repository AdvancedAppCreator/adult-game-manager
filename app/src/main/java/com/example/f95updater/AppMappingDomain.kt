package com.example.f95updater

internal fun statusOrder(s: UpdateStatus): Int = when (s) {
    UpdateStatus.UpdateAvailable -> 0
    UpdateStatus.Unknown -> 1
    UpdateStatus.CheckFailed -> 2
    UpdateStatus.UpToDate -> 3
    UpdateStatus.NotMapped -> 4
}

internal fun AppMapping.withPersonalFieldsFrom(existing: AppMapping?): AppMapping =
    if (existing == null) this else copy(
        displayNameOverride = existing.displayNameOverride,
        userStatus = existing.userStatus,
        personalRating = existing.personalRating,
        personalNotes = existing.personalNotes,
        manualCorrectionNote = existing.manualCorrectionNote,
        manualInstalledVersion = existing.manualInstalledVersion,
        manualInstalledVersionFingerprint = existing.manualInstalledVersionFingerprint,
        manualInstalledDate = existing.manualInstalledDate,
        manualInstalledDateFingerprint = existing.manualInstalledDateFingerprint,
        manualInstalledDateSource = existing.manualInstalledDateSource,
        manualLocalIdentity = existing.manualLocalIdentity,
    )

internal fun AppMapping.withCatalogAssociationFrom(existing: AppMapping?): AppMapping =
    if (existing == null) this else copy(
        mappedCatalogId = existing.mappedCatalogId,
        mappedCatalogSource = existing.mappedCatalogSource,
        mappedCatalogSourceId = existing.mappedCatalogSourceId,
        mappedAgmGroupId = existing.mappedAgmGroupId,
        mappedCatalogTitle = existing.mappedCatalogTitle,
        mappedCatalogVersion = existing.mappedCatalogVersion,
        mappedCatalogUrl = existing.mappedCatalogUrl,
        mappedCatalogUpdatedAt = existing.mappedCatalogUpdatedAt,
        mappedCatalogPublishedAt = existing.mappedCatalogPublishedAt,
        mappedCatalogModifiedAt = existing.mappedCatalogModifiedAt,
        mappedCatalogCoverUrl = existing.mappedCatalogCoverUrl,
        mappedCatalogThumbnailUrl = existing.mappedCatalogThumbnailUrl,
    )

internal fun AppMapping.hasPersonalFields(): Boolean =
    displayNameOverride.isNotBlank() ||
        userStatus != UserGameStatus.None ||
        personalRating != null ||
        personalNotes.isNotBlank() ||
        manualCorrectionNote.isNotBlank() ||
        manualInstalledVersion.isNotBlank() ||
        manualInstalledDate > 0L

internal fun AppMapping.withoutCatalogAssociation(
    notOnF95: Boolean = false,
    matchSource: String? = null,
): AppMapping = copy(
    f95Url = null,
    lastSeenVersion = null,
    lastChecked = 0L,
    acknowledgedVersion = null,
    threadId = null,
    notOnF95 = notOnF95,
    matchSource = matchSource,
    mappedCatalogId = null,
    mappedCatalogSource = null,
    mappedCatalogSourceId = null,
    mappedAgmGroupId = null,
    mappedCatalogTitle = "",
    mappedCatalogVersion = null,
    mappedCatalogUrl = null,
    mappedCatalogUpdatedAt = 0L,
    mappedCatalogPublishedAt = 0L,
    mappedCatalogModifiedAt = 0L,
    mappedCatalogCoverUrl = null,
    mappedCatalogThumbnailUrl = null,
)

internal fun installedVersionFingerprint(app: InstalledApp): String =
    listOf(
        app.source.name,
        app.versionName,
        app.versionCode.toString(),
        app.lastUpdateTime.toString(),
        app.storagePath.orEmpty(),
        app.storageFolderName.orEmpty(),
        app.joiPlayGameId.orEmpty(),
    ).joinToString("|")

internal fun effectiveInstalledVersion(app: InstalledApp, mapping: AppMapping?): String {
    val manual = mapping?.manualInstalledVersion?.trim()?.ifBlank { null } ?: return app.versionName
    val fingerprint = mapping.manualInstalledVersionFingerprint
    return if (fingerprint.isBlank() || fingerprint == installedVersionFingerprint(app)) manual else app.versionName
}

internal fun hasActiveManualInstalledVersion(app: InstalledApp, mapping: AppMapping?): Boolean =
    mapping?.manualInstalledVersion?.isNotBlank() == true &&
        mapping.manualInstalledVersionFingerprint == installedVersionFingerprint(app)

internal fun effectiveInstalledDate(app: InstalledApp, mapping: AppMapping?): Long {
    val manual = mapping?.manualInstalledDate?.takeIf { it > 0L } ?: return app.firstInstallTime
    val fingerprint = mapping.manualInstalledDateFingerprint
    return if (fingerprint.isBlank() || fingerprint == installedVersionFingerprint(app)) manual else app.firstInstallTime
}

internal fun hasActiveManualInstalledDate(app: InstalledApp, mapping: AppMapping?): Boolean =
    (mapping?.manualInstalledDate ?: 0L) > 0L &&
        mapping?.manualInstalledDateFingerprint == installedVersionFingerprint(app)

internal fun catalogInstalledDateCandidate(game: CatalogGame?): Pair<Long, String>? {
    val catalogGame = game ?: return null
    return when {
        catalogGame.publishedAt > 0L -> catalogGame.publishedAt * 1000L to "catalog published date"
        catalogGame.modifiedAt > 0L -> catalogGame.modifiedAt * 1000L to "catalog modified date"
        catalogGame.ts > 0L -> catalogGame.ts * 1000L to "catalog update date"
        else -> null
    }
}

internal fun localIdentityTokens(app: InstalledApp): List<String> {
    val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
    val pathBase = path?.substringAfterLast('/')
    val pathParent = path?.substringBeforeLast('/', missingDelimiterValue = "")?.substringAfterLast('/')
    val execBase = app.joiPlayExecFile
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.', missingDelimiterValue = "")
    val raw = buildList {
        add(app.packageName)
        addAll(catalogMatchLabels(app))
        add(app.joiPlayGameId)
        add(pathBase)
        add(pathParent)
        add(execBase)
    }
    val ignored = setOf("pc", "game", "www", "app", "src", "resources", "joiplay", "android")
    return raw.mapNotNull { value ->
        CatalogRepository.normalizeTitle(value.orEmpty()).takeIf { it.length >= 3 && it !in ignored }
    }.distinct()
}

internal fun AppMapping.withLocalIdentityFrom(app: InstalledApp): AppMapping =
    copy(manualLocalIdentity = localIdentityTokens(app))

internal fun AppMapping.withCatalogSnapshot(game: CatalogGame): AppMapping =
    copy(
        mappedCatalogId = game.thread_id,
        mappedCatalogSource = game.source,
        mappedCatalogSourceId = game.sourceId,
        mappedAgmGroupId = game.agmGroupId,
        mappedCatalogTitle = game.title,
        mappedCatalogVersion = game.version,
        mappedCatalogUrl = game.canonicalUrl,
        mappedCatalogUpdatedAt = game.ts,
        mappedCatalogPublishedAt = game.publishedAt,
        mappedCatalogModifiedAt = game.modifiedAt,
        mappedCatalogCoverUrl = game.cover,
        mappedCatalogThumbnailUrl = game.thumbnailUrl,
    )

internal fun AppMapping.withExternalSnapshot(result: ExternalMirrorResult): AppMapping =
    copy(
        mappedCatalogId = result.threadId ?: result.mirrorUrl.hashCode(),
        mappedCatalogSource = if (result.sourceHost.equals("adultgameworld.com", ignoreCase = true)) {
            SOURCE_ADULTGAMEWORLD
        } else {
            SOURCE_F95ZONE
        },
        mappedCatalogSourceId = result.threadId?.toString(),
        mappedAgmGroupId = null,
        mappedCatalogTitle = result.title,
        mappedCatalogVersion = result.version,
        mappedCatalogUrl = result.mirrorUrl,
        mappedCatalogUpdatedAt = 0L,
        mappedCatalogPublishedAt = 0L,
        mappedCatalogModifiedAt = 0L,
        mappedCatalogCoverUrl = null,
        mappedCatalogThumbnailUrl = null,
    )


internal fun AppMapping.toCatalogSnapshot(): CatalogGame? {
    val fallbackUrl = f95Url?.takeIf { it.isNotBlank() }
    val id = mappedCatalogId ?: fallbackUrl?.hashCode() ?: return null
    val source = mappedCatalogSource
        ?: if (fallbackUrl?.contains("adultgameworld", ignoreCase = true) == true) {
            SOURCE_ADULTGAMEWORLD
        } else {
            SOURCE_F95ZONE
        }
    return CatalogGame(
        thread_id = id,
        title = mappedCatalogTitle.ifBlank { fallbackUrl.orEmpty() },
        version = mappedCatalogVersion ?: lastSeenVersion,
        source = source,
        sourceId = mappedCatalogSourceId,
        agmGroupId = mappedAgmGroupId,
        sourceUrl = mappedCatalogUrl ?: fallbackUrl,
        ts = mappedCatalogUpdatedAt,
        publishedAt = mappedCatalogPublishedAt,
        modifiedAt = mappedCatalogModifiedAt.takeIf { it > 0L } ?: mappedCatalogUpdatedAt,
        cover = mappedCatalogCoverUrl,
        thumbnailUrl = mappedCatalogThumbnailUrl,
    )
}

internal fun mappedCatalogGame(
    mapping: AppMapping?,
    catalogById: Map<Int, CatalogGame>?,
): CatalogGame? {
    if (mapping == null) return null
    val tid = mapping.mappedCatalogId
        ?: mapping.threadId
        ?: F95UrlParser.extractThreadId(mapping.f95Url)
    return tid?.let { catalogById?.get(it) } ?: mapping.toCatalogSnapshot()
}

internal fun AppMapping.f95CatalogThreadId(): Int? {
    if (mappedCatalogSource == SOURCE_F95ZONE) {
        mappedCatalogSourceId?.toIntOrNull()?.let { return it }
    }
    return threadId
        ?: F95UrlParser.extractThreadId(f95Url)
        ?: mappedCatalogId?.takeIf {
            it > 0 && (mappedCatalogSource == null || mappedCatalogSource == SOURCE_F95ZONE)
        }
}

internal fun threadUpdatedAfterInstall(row: AppRow, catalogById: Map<Int, CatalogGame>?): Boolean {
    val installedAt = effectiveInstalledDate(row.installed, row.mapping)
    val updatedAt = mappedCatalogGame(row.mapping, catalogById)?.ts ?: 0L
    return installedAt > 0L && updatedAt > 0L && updatedAt * 1000L > installedAt
}
