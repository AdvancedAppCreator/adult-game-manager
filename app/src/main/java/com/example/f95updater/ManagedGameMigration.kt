package com.example.f95updater

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ManagedGameDeleteProgress(
    val removedEntries: Long,
)

data class ManagedMigrationSummary(
    val supplied: Int,
    val created: Int,
    val merged: Int,
    val skippedMissing: Int,
    val alreadyCompleted: Boolean,
    val deferredWinlatorMigration: Boolean = false,
)

data class JoiPlayManagedImportSummary(
    val backupGames: Int,
    val imported: Int,
    val skippedMissing: Int,
)

internal fun shouldRetainRecentlyBoundWinlator(
    binding: ManagedRunnerBinding.Winlator,
    now: Long = System.currentTimeMillis(),
): Boolean {
    val boundAt = binding.metadata["agmBoundAtMs"]?.toLongOrNull() ?: return false
    return boundAt > 0L && now - boundAt < 120_000L
}

suspend fun migrateLegacyManagedGames(
    context: Context,
    mappings: MappingRepository,
): ManagedMigrationSummary {
    val appContext = context.applicationContext
    val store = ManagedGameStore(appContext)
    if (store.isLegacyMigrationCompleted()) {
        return ManagedMigrationSummary(
            supplied = 0,
            created = 0,
            merged = 0,
            skippedMissing = 0,
            alreadyCompleted = true,
            deferredWinlatorMigration = false,
        )
    }

    JoiPlayBackupReader.pruneMissingFolders(appContext)

    val candidates = mutableListOf<InstalledApp>()
    var skipped = 0

    for (app in JoiPlayBackupReader.asInstalledApps(appContext)) {
        val validated = validateLegacyJoiPlay(app)
        if (validated == null) skipped++ else candidates += validated
    }
    for (app in JoiPlayScanner.scan(appContext)) {
        val validated = inspectFolderOnlyLegacy(app, context)
        if (validated == null) skipped++ else candidates += validated
    }
    var deferredWinlatorMigration = false
    val legacyWinlator = if (WinlatorClient.isInstalled(appContext)) {
        WinlatorClient.managedGames(appContext)
            .onFailure {
                deferredWinlatorMigration = true
                AppLog.w("ManagedMigration", "Deferring Winlator migration until its provider responds", it)
            }
            .getOrDefault(emptyList())
            .map { it.toInstalledApp() }
    } else {
        emptyList()
    }
    for (app in legacyWinlator) {
        val path = app.storagePath?.let(::readableDirectory)
        if (path == null) {
            skipped++
        } else {
            candidates += app.copy(storagePath = path.absolutePath)
        }
    }
    for (app in KirikiroidScanner.scan(appContext)) {
        val validated = inspectKirikiroidLegacy(app, context)
        if (validated == null) skipped++ else candidates += validated
    }

    val distinct = candidates
        .distinctBy { canonicalManagedGamePath(requireNotNull(it.storagePath)) to it.source }
    val result = store.migrateLegacyOnce(distinct)
    if (!result.alreadyCompleted) {
        val gamesByPath = result.games.associateBy { it.canonicalPath }
        for (legacy in distinct) {
            val path = canonicalManagedGamePath(requireNotNull(legacy.storagePath))
            val game = requireNotNull(gamesByPath[path]) {
                "Managed migration did not persist validated path $path"
            }
            migrateManagedGameUserState(appContext, mappings, legacy, game)
        }
        if (!deferredWinlatorMigration) store.completeLegacyMigration()
    }
    return ManagedMigrationSummary(
        supplied = result.supplied,
        created = result.created,
        merged = result.merged,
        skippedMissing = skipped,
        alreadyCompleted = result.alreadyCompleted,
        deferredWinlatorMigration = deferredWinlatorMigration,
    )
}

suspend fun importJoiPlayBackupIntoManagedGames(
    context: Context,
    uri: Uri,
    mappings: MappingRepository,
): JoiPlayManagedImportSummary {
    val appContext = context.applicationContext
    val backupGames = JoiPlayBackupReader.import(appContext, uri)
    return importCachedJoiPlayIntoManagedGames(appContext, mappings, backupGames)
}

suspend fun importCachedJoiPlayIntoManagedGames(
    context: Context,
    mappings: MappingRepository,
    backupGames: Int? = null,
): JoiPlayManagedImportSummary {
    val appContext = context.applicationContext
    val backupGameCount = backupGames ?: JoiPlayBackupReader.cachedGames(appContext).size
    val existing = ManagedGameStore(appContext).get().associateBy { it.canonicalPath }
    var skipped = 0
    val validatedApps = mutableListOf<InstalledApp>()
    val drafts = JoiPlayBackupReader.asInstalledApps(appContext).mapNotNull { app ->
        val validated = validateLegacyJoiPlay(app)
        if (validated == null) {
            skipped++
            null
        } else {
            validatedApps += validated
            val draft = legacyInstalledAppToManagedDraft(validated)
            val prior = existing[canonicalManagedGamePath(draft.storagePath)]
            val priorBinding = prior?.runnerBindings
                ?.filterIsInstance<ManagedRunnerBinding.JoiPlay>()
                ?.singleOrNull()
            draft.copy(
                defaultRunner = null,
                runnerBindings = draft.runnerBindings.map { incoming ->
                    if (incoming is ManagedRunnerBinding.JoiPlay && priorBinding != null) {
                        incoming.copy(
                            enabled = priorBinding.enabled,
                            compatible = priorBinding.compatible,
                        )
                    } else {
                        incoming
                    }
                },
            )
        }
    }
    val games = ManagedGameStore(appContext).importOrUpsert(drafts)
    val gamesByPath = games.associateBy { it.canonicalPath }
    for (legacy in validatedApps) {
        val path = canonicalManagedGamePath(requireNotNull(legacy.storagePath))
        migrateManagedGameUserState(
            appContext,
            mappings,
            legacy,
            requireNotNull(gamesByPath[path]),
        )
    }
    return JoiPlayManagedImportSummary(
        backupGames = backupGameCount,
        imported = drafts.size,
        skippedMissing = skipped,
    )
}

suspend fun refreshManagedWinlatorBindings(context: Context): List<ManagedGame> {
    val store = ManagedGameStore(context.applicationContext)
    val games = store.get()
    if (!WinlatorClient.isInstalled(context.applicationContext)) return games
    val liveGames = WinlatorClient.managedGames(context.applicationContext)
        .onFailure { AppLog.w("ManagedGames", "Winlator status refresh failed; retaining bindings", it) }
        .getOrElse { return games }
    val liveById = liveGames
        .map { it.toInstalledApp() }
        .mapNotNull { app -> app.winlatorGameId?.let { it to app } }
        .toMap()
    val updates = games.mapNotNull { game ->
        val binding = game.runnerBindings
            .filterIsInstance<ManagedRunnerBinding.Winlator>()
            .singleOrNull() ?: return@mapNotNull null
        val managedId = binding.managedId ?: return@mapNotNull null
        val live = liveById[managedId]
        val refreshed = refreshManagedWinlatorBinding(binding, live)
        if (refreshed == binding) null else game.id to refreshed
    }.toMap()
    return if (updates.isEmpty()) games else store.updateWinlatorBindings(updates)
}

internal fun refreshManagedWinlatorBinding(
    binding: ManagedRunnerBinding.Winlator,
    live: InstalledApp?,
    now: Long = System.currentTimeMillis(),
): ManagedRunnerBinding.Winlator = if (live == null) {
    if (shouldRetainRecentlyBoundWinlator(binding, now)) {
        binding.copy(state = "installing")
    } else {
        markWinlatorRegistrationMissing(binding)
    }
} else {
    binding.copy(
        executablePath = live.winlatorExecutablePath ?: binding.executablePath,
        executableDosPath = live.winlatorExecutableDosPath,
        containerId = live.winlatorContainerId,
        state = live.winlatorState,
        containerPolicy = live.winlatorContainerPolicy,
        containerShared = live.winlatorContainerShared,
        containerKey = live.winlatorContainerKey,
        containerReferenceCount = live.winlatorContainerReferenceCount,
        containerAllocatedSizeBytes = live.winlatorContainerAllocatedSizeBytes,
        configJson = live.winlatorConfigJson,
        configSha256 = live.winlatorConfigSha256,
        effectiveWinVersion = live.winlatorEffectiveWinVersion,
        winVersionSource = live.winlatorWinVersionSource,
    )
}

suspend fun deleteManagedGameFilesAndRecord(
    context: Context,
    app: InstalledApp,
    onProgress: (ManagedGameDeleteProgress) -> Unit = {},
): Boolean = withContext(Dispatchers.IO) {
    val startedAt = SystemClock.elapsedRealtime()
    require(app.source == AppSource.Managed) { "Only AGM-managed games can be deleted here." }
    val id = requireNotNull(app.managedGameId) { "Managed-game id is missing." }
    val path = requireNotNull(app.storagePath) { "Managed-game storage path is missing." }
    val store = ManagedGameStore(context.applicationContext)
    // Checked before a single file is removed: a pending patch backup may be the only copy of the
    // original files, and it lives outside the folder this would delete.
    store.requireNoUnresolvedPatchTransaction(id)
    val directory = File(path)
    AppLog.i("Delete", "Managed delete started label=${app.label} path=$path")
    var removedEntries = 0L
    var lastProgressAt = 0L
    suspend fun publishProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && removedEntries % 64L != 0L && now - lastProgressAt < 250L) return
        lastProgressAt = now
        withContext(Dispatchers.Main.immediate) {
            onProgress(ManagedGameDeleteProgress(removedEntries))
        }
    }
    publishProgress(force = true)
    if (directory.exists()) {
        require(directory.isAbsolute && directory.isDirectory) {
            "Managed-game storage path is not a directory: $path"
        }
        val markerFile = File(directory, MANAGED_GAME_OWNERSHIP_FILE)
        require(markerFile.isFile) {
            "AGM ownership marker is missing; refusing to delete $path"
        }
        val marker = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }.decodeFromString(ManagedGameOwnership.serializer(), markerFile.readText())
        require(marker.id == id && marker.canonicalPath == canonicalManagedGamePath(path)) {
            "AGM ownership marker does not match this managed game; refusing to delete $path"
        }
        directory.walkBottomUp().forEach { entry ->
            if (!entry.delete() && entry.exists()) return@withContext false
            removedEntries++
            publishProgress()
        }
    }
    publishProgress(force = true)
    store.delete(id).also { deleted ->
        AppLog.i(
            "Delete",
            "Managed delete finished label=${app.label} removedEntries=$removedEntries " +
                "recordDeleted=$deleted elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }
}

suspend fun attachWinlatorBindingId(
    context: Context,
    managedGameId: String,
    winlatorGameId: String,
): ManagedGame {
    val store = ManagedGameStore(context.applicationContext)
    val game = requireNotNull(store.find(managedGameId)) {
        "Managed game no longer exists: $managedGameId"
    }

    val binding = game.runnerBindings
        .filterIsInstance<ManagedRunnerBinding.Winlator>()
        .singleOrNull() ?: error("Managed game has no Winlator binding.")
    return store.update(
        game.copy(
            runnerBindings = game.runnerBindings.map {
                if (it.kind == ManagedRunnerKind.Winlator) {
                    binding.copy(
                        managedId = winlatorGameId,
                        state = "installing",
                        metadata = binding.metadata +
                            ("agmBoundAtMs" to System.currentTimeMillis().toString()),
                    )
                } else {
                    it
                }
            },
        ),
    )
}

suspend fun disableUnconfiguredWinlatorBinding(
    context: Context,
    managedGameId: String,
): ManagedGame? {
    val store = ManagedGameStore(context.applicationContext)
    val game = store.find(managedGameId) ?: return null
    val binding = game.runnerBindings
        .filterIsInstance<ManagedRunnerBinding.Winlator>()
        .singleOrNull() ?: return game
    if (binding.managedId != null || !binding.enabled) return game
    val enabledBindings = game.runnerBindings.map {
        if (it.kind == ManagedRunnerKind.Winlator) it.withState(false, it.compatible) else it
    }

    val defaultRunner = if (game.defaultRunner == ManagedRunnerKind.Winlator) {
        enabledBindings.firstOrNull { it.enabled && it.compatible }?.kind ?: return game
    } else {
        game.defaultRunner
    }
    return store.update(
        game.copy(
            defaultRunner = defaultRunner,
            runnerBindings = enabledBindings,
        ),
    )
}

suspend fun restoreManagedRunnerConfiguration(
    context: Context,
    managedGameId: String,
    rollback: ManagedRunnerRollback,
): ManagedGame? {
    val store = ManagedGameStore(context.applicationContext)
    val game = store.find(managedGameId) ?: return null
    return store.update(
        game.copy(
            defaultRunner = rollback.defaultRunner,
            runnerBindings = rollback.bindings,
        ),
    )
}

suspend fun reconcilePendingManagedWinlatorCreate(
    context: Context,
): PendingWinlatorAutoRecommend? {
    val operation = ManagedWinlatorPendingStore.load(context.applicationContext) ?: return null
    if (operation.kind == WinlatorOperationKind.RecoverPortable) return null
    val managedGameId = operation.managedGameId ?: return null
    return when (WinlatorClient.getGame(context.applicationContext, operation.gameId)) {
        is WinlatorClient.Read.Ok -> {
            attachWinlatorBindingId(
                context.applicationContext,
                managedGameId,
                operation.gameId,
            )
            PendingWinlatorAutoRecommend(
                gameId = operation.gameId,
                title = operation.title,
                detectedLanguage = operation.detectedLanguage,
                launchAfterApply = operation.launchAfterCreate,
            )
        }
        else -> null
    }
}

private fun validateLegacyJoiPlay(app: InstalledApp): InstalledApp? {
    val root = app.storagePath?.let(::readableDirectory) ?: return null
    val type = app.joiPlayType?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val exec = app.joiPlayExecFile?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val launchFile = File(exec).takeIf { it.isAbsolute } ?: File(root, exec)
    if (!launchFile.isFile || !launchFile.canRead() || !isWithin(root, launchFile)) return null
    return app.copy(
        storagePath = root.absolutePath,
        storageFolderName = root.name,
        joiPlayType = type,
        joiPlayExecFile = launchFile.relativeTo(root).invariantSeparatorsPath,
    )
}

private fun inspectFolderOnlyLegacy(app: InstalledApp, context: Context): InstalledApp? {
    val root = app.storagePath?.let(::readableDirectory) ?: return null
    val inspection = ManagedGameDiscovery.inspect(
        root = root,
        joiPlayAvailable = isJoiPlayInstalled(context),
        winlatorAvailable = WinlatorClient.isInstalled(context),
        kirikiroidAvailable = KirikiroidLauncher.isInstalled(context),
    )
    val binding = inspection.candidates
        .single { it.kind == ManagedRunnerKind.JoiPlay }
        .binding as? ManagedRunnerBinding.JoiPlay ?: return null
    return app.copy(
        storagePath = root.absolutePath,
        storageFolderName = root.name,
        joiPlayType = binding.type,
        joiPlayExecFile = binding.execFile,
    )
}

private fun inspectKirikiroidLegacy(app: InstalledApp, context: Context): InstalledApp? {
    val root = app.storagePath?.let(::readableDirectory) ?: return null
    val inspection = ManagedGameDiscovery.inspect(
        root = root,
        joiPlayAvailable = isJoiPlayInstalled(context),
        winlatorAvailable = WinlatorClient.isInstalled(context),
        kirikiroidAvailable = KirikiroidLauncher.isInstalled(context),
    )
    val binding = inspection.candidates
        .single { it.kind == ManagedRunnerKind.Kirikiroid }
        .binding as? ManagedRunnerBinding.Kirikiroid ?: return null
    return app.copy(
        storagePath = root.absolutePath,
        storageFolderName = root.name,
        kirikiroidStartupPath = binding.startupPath,
    )
}

private fun readableDirectory(path: String): File? {
    val directory = File(path)
    return directory.takeIf {
        it.isAbsolute && it.isDirectory && it.canRead() && it.canWrite()
    }?.canonicalFile
}

private fun isWithin(root: File, child: File): Boolean =
    child.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

private fun isJoiPlayInstalled(context: Context): Boolean =
    runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(JoiPlayUpdateChecker.PACKAGE, 0)
        true
    }.getOrDefault(false)
