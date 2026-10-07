package com.example.f95updater

internal val JOIPLAY_WRAPPER_FOLDERS = setOf("www", "game", "app", "src", "resources")

internal fun joiPlaySizeKey(app: InstalledApp): String? {
    if (app.source == AppSource.Managed) return app.packageName
    if (app.source != AppSource.JoiPlay) return null
    val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
    if (!path.isNullOrBlank()) {
        val basename = path.substringAfterLast('/').ifBlank { path }
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            .substringAfterLast('/')
            .ifBlank { "" }
        if (basename.lowercase() in JOIPLAY_WRAPPER_FOLDERS && parent.isNotBlank()) {
            return parent
        }
    }
    return app.storageFolderName?.ifBlank { null } ?: path?.substringAfterLast('/')?.ifBlank { null }
}

internal fun effectiveInstalledSize(
    app: InstalledApp,
    joiPlaySizeInfo: JoiPlayScanner.SizeInfo?,
): Long =
    if (app.source == AppSource.JoiPlay || app.source == AppSource.Managed) {
        (joiPlaySizeInfo?.totalBytes ?: 0L).takeIf { it > 0L } ?: app.totalSize
    } else {
        app.totalSize
    }

/**
 * Size to show on a game card, source-aware. Winlator rows have no apk/data size, so prefer the
 * scanned on-disk game-files size, then Winlator's provider-reported container-allocation size.
 */
internal fun effectiveDisplaySize(
    app: InstalledApp,
    joiPlaySizeInfo: JoiPlayScanner.SizeInfo?,
): Long {
    if (app.source == AppSource.Winlator) {
        return app.winlatorContainerAllocatedSizeBytes?.takeIf { it > 0L } ?: app.totalSize
    }
    val folderSize = effectiveInstalledSize(app, joiPlaySizeInfo)
    if (folderSize > 0L) return folderSize
    val hasWinlator = app.managedRunnerBindings.any {
        it.kind == ManagedRunnerKind.Winlator && it.enabled
    }
    return if (app.source == AppSource.Managed && hasWinlator) {
        app.winlatorContainerAllocatedSizeBytes?.takeIf { it > 0L } ?: folderSize
    } else {
        folderSize
    }
}

internal fun effectiveInstalledTotalSize(
    apps: List<InstalledApp>,
    joiPlaySizeInfo: Map<String, JoiPlayScanner.SizeInfo>,
): Long = apps.sumOf { app ->
    effectiveInstalledSize(app, joiPlaySizeKey(app)?.let { joiPlaySizeInfo[it] })
}

internal fun joiPlaySizeTarget(app: InstalledApp): JoiPlayScanner.SizeTarget? {
    val key = joiPlaySizeKey(app) ?: return null
    val path = app.storagePath?.replace('\\', '/')?.trimEnd()
    val scanPath = if (app.source == AppSource.Managed) {
        path
    } else path?.let {
        val basename = it.substringAfterLast('/').ifBlank { it }
        if (basename.lowercase() in JOIPLAY_WRAPPER_FOLDERS) {
            it.substringBeforeLast('/', missingDelimiterValue = it)
        } else {
            it
        }

    }
    return JoiPlayScanner.SizeTarget(
        key = key,
        label = app.label,
        storagePath = scanPath,
        folderName = key,
    )
}

internal fun managedGameSizeTargets(apps: List<InstalledApp>): List<JoiPlayScanner.SizeTarget> =
    apps.asSequence()
        .filter { it.source == AppSource.Managed || it.source == AppSource.JoiPlay }
        .mapNotNull(::joiPlaySizeTarget)
        .distinctBy { it.key }
        .toList()
