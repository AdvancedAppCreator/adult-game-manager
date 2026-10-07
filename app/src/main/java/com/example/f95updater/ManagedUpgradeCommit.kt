package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a live Winlator game currently points, relative to an in-flight upgrade. */
object ManagedUpgradeWinlatorVerification {
    enum class Location { NewPath, OldPath, Unknown }

    fun classify(
        gamePath: String?,
        executablePath: String?,
        plan: ManagedUpgradeWinlatorPlan,
    ): Location {
        val game = normalize(gamePath)
        val executable = normalize(executablePath)
        if (game == null || executable == null) return Location.Unknown
        if (game == normalize(plan.newGamePath) && executable == normalize(plan.newExecutablePath)) {
            return Location.NewPath
        }
        if (game == normalize(plan.oldGamePath) && executable == normalize(plan.oldExecutablePath)) {
            return Location.OldPath
        }
        return Location.Unknown
    }

    private fun normalize(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { canonicalManagedGamePath(raw) }.getOrNull()
    }
}

sealed interface ManagedUpgradeRecoveryOutcome {
    data object None : ManagedUpgradeRecoveryOutcome
    data class Discarded(val label: String, val reason: String) : ManagedUpgradeRecoveryOutcome
    data class Completed(val result: ManagedUpgradeResult) : ManagedUpgradeRecoveryOutcome
    data class Failed(val message: String) : ManagedUpgradeRecoveryOutcome
}

private val managedUpgradeRecoveryMutex = Mutex()

internal suspend fun readWinlatorManagedGame(
    context: Context,
    gameId: String,
): WinlatorApi.ManagedGame? =
    when (val read = WinlatorClient.getGame(context.applicationContext, gameId)) {
        is WinlatorClient.Read.Ok ->
            runCatching { WinlatorApi.ManagedGame.parse(JSONObject(read.payload)) }
                .onFailure { AppLog.w("ManagedUpgrade", "Could not parse Winlator game $gameId", it) }
                .getOrNull()
        is WinlatorClient.Read.Err -> {
            AppLog.w("ManagedUpgrade", "Winlator read failed for $gameId: ${read.code}: ${read.message}")
            null
        }
        is WinlatorClient.Read.Unavailable -> {
            AppLog.w("ManagedUpgrade", "Winlator unavailable while reading $gameId: ${read.reason}")
            null
        }
    }

/** Replaces the prospective Winlator binding with one rebuilt from the verified live game. */
internal fun applyLiveWinlatorBinding(
    replacement: ManagedGame,
    live: WinlatorApi.ManagedGame?,
): ManagedGame {
    if (live == null) return replacement
    val binding = replacement.runnerBindings
        .filterIsInstance<ManagedRunnerBinding.Winlator>()
        .singleOrNull() ?: return replacement
    val refreshed = refreshManagedWinlatorBinding(binding, live.toInstalledApp())
    return validateManagedGame(
        replacement.copy(
            runnerBindings = replacement.runnerBindings.map {
                if (it.kind == ManagedRunnerKind.Winlator) refreshed else it
            },
        ),
    )
}

internal fun uniqueUpgradedFolder(parent: File, oldName: String): File {
    val base = "upgraded_to_delete_$oldName"
    var candidate = File(parent, base)
    var index = 2
    while (candidate.exists()) {
        candidate = File(parent, "${base}_$index")
        index++
    }
    return candidate
}

/**
 * Commits the AGM store relocation, then performs best-effort cleanup. Only a store-relocation
 * failure throws; cleanup problems are surfaced as warnings on the returned result.
 */
internal suspend fun commitManagedUpgrade(
    context: Context,
    state: ManagedUpgradePendingState,
    live: WinlatorApi.ManagedGame?,
): ManagedUpgradeResult {
    val appContext = context.applicationContext
    val replacement = applyLiveWinlatorBinding(state.replacementGame, live)
    val relocated = ManagedGameStore(appContext).relocate(state.oldCanonicalPath, replacement)
    AppLog.i(
        "ManagedUpgrade",
        "Store relocation committed id=${relocated.id} path=${relocated.canonicalPath}",
    )
    ManagedUpgradePendingStore.clear(appContext)

    val warnings = mutableListOf<String>()
    val archive = state.sourceArchivePath?.takeIf { it.isNotBlank() }?.let(::File)
    val archiveOutcome = resolveCommittedSourceArchive(archive, state.deleteSourceArchive, warnings)

    val oldRoot = File(state.oldRootPath)
    var remainingOld = oldRoot
    var renamed = false
    if (oldRoot.isDirectory) {
        val parent = oldRoot.parentFile
        val target = parent?.let { uniqueUpgradedFolder(it, oldRoot.name) }
        renamed = target != null && runCatching { oldRoot.renameTo(target) }
            .onFailure { AppLog.w("ManagedUpgrade", "Could not rename ${oldRoot.absolutePath}", it) }
            .getOrDefault(false)
        if (renamed && target != null) {
            remainingOld = target
        } else {
            warnings += "The previous folder could not be renamed; delete ${oldRoot.name} yourself " +
                "once the upgrade is verified."
        }
    }
    if (remainingOld.isDirectory) {
        val marker = File(remainingOld, MANAGED_GAME_OWNERSHIP_FILE)
        if (marker.isFile && !runCatching { marker.delete() }.getOrDefault(false)) {
            AppLog.w("ManagedUpgrade", "Could not remove stale marker ${marker.absolutePath}")
            warnings += "The previous folder still carries an AGM ownership marker."
        }
    }
    writeUpgradeInstallMarker(state, relocated, remainingOld)
    AppLog.i(
        "ManagedUpgrade",
        "Cleanup done renamed=$renamed archive=$archiveOutcome warnings=${warnings.size}",
    )
    return ManagedUpgradeResult(
        managedGameId = relocated.id,
        label = relocated.label,
        newFolder = relocated.storagePath,
        oldFolder = remainingOld.absolutePath,
        oldFolderRenamed = renamed,
        saveItemsCopied = state.saveItemsCopied,
        sourceArchiveName = archive?.name,
        sourceArchiveOutcome = archiveOutcome,
        warnings = warnings,
    )
}

/**
 * Decides — and, where asked to, performs — what happens to the archive the upgrade installed from.
 *
 * The caller has already recorded *this* upgrade's delete-after-install choice, so the preference is
 * never re-read here. Only a deletion AGM was asked for, on a file that was still there, can fail:
 * an archive that had already been moved or removed is reported as [ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent]
 * and warns about nothing, because AGM neither removed it nor failed to.
 */
private fun resolveCommittedSourceArchive(
    archive: File?,
    deleteRequested: Boolean,
    warnings: MutableList<String>,
): ManagedUpgradeSourceArchiveOutcome {
    if (archive == null) return ManagedUpgradeSourceArchiveOutcome.NotRequested
    if (!deleteRequested) {
        AppLog.i("ManagedUpgrade", "Keeping ${archive.name}: this upgrade did not ask for its deletion")
        return ManagedUpgradeSourceArchiveOutcome.NotRequested
    }
    if (!runCatching { archive.exists() }.getOrDefault(false)) {
        AppLog.i("ManagedUpgrade", "Source archive ${archive.name} was already gone; nothing to delete")
        return ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent
    }
    if (!runCatching { archive.canWrite() }.getOrDefault(false)) {
        AppLog.w("ManagedUpgrade", "Source archive ${archive.absolutePath} is not writable; keeping it")
        warnings += "The source archive ${archive.name} is read-only, so AGM kept it; remove it yourself."
        return ManagedUpgradeSourceArchiveOutcome.Failed
    }
    val deleted = runCatching { archive.delete() }
        .onFailure { AppLog.w("ManagedUpgrade", "Could not delete ${archive.absolutePath}", it) }
        .getOrDefault(false)
    if (deleted) return ManagedUpgradeSourceArchiveOutcome.Deleted
    warnings += "The source archive ${archive.name} could not be deleted; remove it yourself."
    return ManagedUpgradeSourceArchiveOutcome.Failed
}

private fun writeUpgradeInstallMarker(
    state: ManagedUpgradePendingState,
    relocated: ManagedGame,
    remainingOld: File,
) {
    val archive = state.sourceArchivePath?.takeIf { it.isNotBlank() }?.let(::File)
    val marker = JSONObject().apply {
        put("schemaVersion", 1)
        put("source", "agm-managed-upgrade")
        put("sourceArchive", archive?.name ?: JSONObject.NULL)
        put(
            "detectedVersion",
            archive?.name?.let { JoiPlayVersionDetector.extractVersionFromArchiveName(it) } ?: JSONObject.NULL,
        )
        put("installedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
        put("managedGameId", relocated.id)
        put("previousFolderPath", state.oldRootPath)
        put(
            "rollbackFolderPath",
            if (remainingOld.isDirectory) remainingOld.absolutePath else JSONObject.NULL,
        )
        put("previousInstalledVersion", state.originalGame.versionName.ifBlank { JSONObject.NULL })
        put("appLabel", relocated.label)
    }
    runCatching {
        File(relocated.storagePath, JoiPlayVersionDetector.INSTALL_MARKER_FILE)
            .writeText(marker.toString(2), Charsets.UTF_8)
    }.onFailure { AppLog.w("ManagedUpgrade", "Could not write the AGM install marker", it) }
}

internal fun discardUpgradedFolder(state: ManagedUpgradePendingState): Boolean {
    val newRoot = File(state.newRootPath)
    if (!newRoot.exists()) return true
    val deleted = runCatching { newRoot.deleteRecursively() }
        .onFailure { AppLog.w("ManagedUpgrade", "Could not delete ${newRoot.absolutePath}", it) }
        .getOrDefault(false)
    if (!deleted) AppLog.w("ManagedUpgrade", "Left behind partial upgrade folder ${newRoot.absolutePath}")
    return deleted
}

/**
 * Resolves an upgrade that was interrupted by process death. Must run before the first installed
 * library snapshot so the UI never shows a stale managed path.
 */
suspend fun recoverPendingManagedUpgrade(context: Context): ManagedUpgradeRecoveryOutcome {
    return managedUpgradeRecoveryMutex.withLock {
        recoverPendingManagedUpgradeLocked(context)
    }
}

private suspend fun recoverPendingManagedUpgradeLocked(context: Context): ManagedUpgradeRecoveryOutcome {
    val appContext = context.applicationContext
    val state = ManagedUpgradePendingStore.load(appContext) ?: return ManagedUpgradeRecoveryOutcome.None
    AppLog.i(
        "ManagedUpgrade",
        "Recovering interrupted upgrade phase=${state.phase} game=${state.managedGameId} " +
            "old=${state.oldRootPath} new=${state.newRootPath}",
    )
    return when (state.phase) {
        ManagedUpgradePhase.SavesPending -> {
            discardUpgradedFolder(state)
            ManagedUpgradePendingStore.clear(appContext)
            ManagedUpgradeRecoveryOutcome.Discarded(
                state.label,
                "AGM was closed before ${state.label}'s save data finished copying, so the unfinished " +
                    "upgrade folder was removed. The installed game was not changed.",
            )
        }

        ManagedUpgradePhase.StoreRelocationPending -> finishRelocation(appContext, state, live = null)

        ManagedUpgradePhase.WinlatorRequested -> {
            val plan = state.winlator
                ?: return finishRelocation(appContext, state, live = null)
            val live = readWinlatorManagedGame(appContext, plan.winlatorGameId)
            when (ManagedUpgradeWinlatorVerification.classify(live?.gamePath, live?.executablePath, plan)) {
                ManagedUpgradeWinlatorVerification.Location.NewPath ->
                    finishRelocation(appContext, state, live)
                ManagedUpgradeWinlatorVerification.Location.OldPath -> {
                    discardUpgradedFolder(state)
                    ManagedUpgradePendingStore.clear(appContext)
                    ManagedUpgradeRecoveryOutcome.Discarded(
                        state.label,
                        "Winlator never moved ${state.label} to the upgraded folder, so the upgrade was " +
                            "discarded and the installed game was left unchanged.",
                    )
                }
                ManagedUpgradeWinlatorVerification.Location.Unknown ->
                    ManagedUpgradeRecoveryOutcome.Failed(
                        "AGM can't tell whether Winlator moved ${state.label} to ${state.newRootPath}. " +
                            "Both folders were kept. Check the game in Winlator before deleting either one.",
                    )
            }
        }

        ManagedUpgradePhase.WinlatorRollbackRequested -> {
            val plan = state.winlator
                ?: return ManagedUpgradeRecoveryOutcome.Failed(
                    "An interrupted Winlator rollback for ${state.label} has no recorded paths. " +
                        "Both folders were kept.",
                )
            val live = readWinlatorManagedGame(appContext, plan.winlatorGameId)
            when (ManagedUpgradeWinlatorVerification.classify(live?.gamePath, live?.executablePath, plan)) {
                ManagedUpgradeWinlatorVerification.Location.OldPath -> {
                    discardUpgradedFolder(state)
                    ManagedUpgradePendingStore.clear(appContext)
                    ManagedUpgradeRecoveryOutcome.Discarded(
                        state.label,
                        "${state.label} could not be upgraded, so AGM restored the original installation.",
                    )
                }
                else -> ManagedUpgradeRecoveryOutcome.Failed(
                    "${state.label} is in an inconsistent state: AGM still points at ${state.oldRootPath} " +
                        "while Winlator was not confirmed back on it. Both folders were kept.",
                )
            }
        }
    }
}

private suspend fun finishRelocation(
    context: Context,
    state: ManagedUpgradePendingState,
    live: WinlatorApi.ManagedGame?,
): ManagedUpgradeRecoveryOutcome =
    runCatching { commitManagedUpgrade(context, state, live) }
        .fold(
            onSuccess = { ManagedUpgradeRecoveryOutcome.Completed(it) },
            onFailure = {
                AppLog.e("ManagedUpgrade", "Recovery relocation failed for ${state.label}", it)
                ManagedUpgradeRecoveryOutcome.Failed(
                    "AGM could not finish the interrupted upgrade of ${state.label}: ${it.message}. " +
                        "Both folders were kept.",
                )
            },
        )
