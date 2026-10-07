package com.example.f95updater

import kotlinx.serialization.Serializable

data class ApkPostInstall(
    val pkgName: String?,
    val sourceArchive: java.io.File?,
    val extractedRoot: java.io.File? = null,
    val catalogGame: CatalogGame? = null,
)
data class PendingArchiveDestination(
    val archive: java.io.File,
    val target: InstallRouting.Target,
)
data class PendingInstallDestinationSelection(
    val archive: java.io.File,
    val target: InstallRouting.Target,
    val currentRoot: ArchiveExtractor.ExtractRoot,
    val initialPath: String,
)
data class WinlatorExecutableCandidate(
    val file: java.io.File,
    val archiveTitle: String? = null,
    val executableTitle: String,
    val folderTitle: String? = null,
    val readmeTitle: String? = null,
    val sourceArchiveToDelete: java.io.File? = null,
    val managedGameId: String? = null,
    val managedRunnerRollback: ManagedRunnerRollback? = null,
)
@Serializable
data class ManagedRunnerRollback(
    val defaultRunner: ManagedRunnerKind,
    val bindings: List<ManagedRunnerBinding>,
)
enum class WinlatorTitleSource(val label: String) {
    Archive("Archive name"),
    Executable("Executable name"),
    Folder("Folder name"),
    Readme("README name"),
    Other("Other"),
}
@Serializable
enum class WinlatorOperationKind {
    CreatePortable,
    CreateInstaller,
    RecoverPortable,
    Configure,
    RunInstaller,
    Launch,
    Delete,
    MoveToIsolated,
}
data class PendingWinlatorOperation(
    val kind: WinlatorOperationKind,
    val gameId: String,
    val title: String,
    val sourceArchiveToDelete: java.io.File? = null,
    val configChangeId: String? = null,
    val retryAfterApply: Boolean = false,
    val managedGameId: String? = null,
    val managedRunnerRollback: ManagedRunnerRollback? = null,
    val launchAfterCreate: Boolean = false,
    val deleteSharedFiles: Boolean = false,
    val detectedLanguage: String? = null,
    val recoveryCandidate: WinlatorPortableRecoveryCandidate? = null,
)
data class PendingWinlatorAutoRecommend(
    val gameId: String,
    val title: String,
    val detectedLanguage: String? = null,
    val launchAfterApply: Boolean = false,
)
data class PendingWinlatorRefresh(
    val sequence: Int,
    val gameId: String?,
    val gameJson: String? = null,
    val matchCatalog: Boolean = false,
    val removeGame: Boolean = false,
)

internal fun preferredWinlatorTitleSource(candidate: WinlatorExecutableCandidate): WinlatorTitleSource =
    when {
        !candidate.archiveTitle.isNullOrBlank() -> WinlatorTitleSource.Archive
        !candidate.folderTitle.isNullOrBlank() -> WinlatorTitleSource.Folder
        candidate.executableTitle.isNotBlank() -> WinlatorTitleSource.Executable
        else -> WinlatorTitleSource.Other
    }

internal fun selectedWinlatorTitle(
    candidate: WinlatorExecutableCandidate,
    source: WinlatorTitleSource,
    customTitle: String,
): String = when (source) {
    WinlatorTitleSource.Archive -> candidate.archiveTitle
    WinlatorTitleSource.Executable -> candidate.executableTitle
    WinlatorTitleSource.Folder -> candidate.folderTitle
    WinlatorTitleSource.Readme -> candidate.readmeTitle
    WinlatorTitleSource.Other -> customTitle
}.orEmpty().trim()

internal fun shouldRefreshAfterWinlatorOperation(kind: WinlatorOperationKind?): Boolean =
    kind != null && kind != WinlatorOperationKind.Launch

internal fun shouldMatchCatalogAfterWinlatorOperation(kind: WinlatorOperationKind?): Boolean =
    kind == WinlatorOperationKind.CreatePortable || kind == WinlatorOperationKind.CreateInstaller

internal fun shouldRefreshAfterWinlatorEvent(eventType: String): Boolean =
    eventType == "install_completed" ||
        eventType == "install_failed" ||
        // settings_changed is Winlator's post-commit refetch/reconciliation notification: refetch so
        // the persisted config (e.g. resolution) is reflected even if AGM missed the sync result.
        eventType == "settings_changed"

/**
 * Builds the user-facing message for a Winlator `game_exited` event. Winlator 11.1-secure.23 sets
 * the event's `success` extra to `true` unconditionally, so it cannot be trusted; a game that never
 * started (e.g. a per-game locale that Winlator failed to generate) still reports `success=true`.
 * We therefore key off `runtimeReached` (from the event or its diagnostic): when the runtime was
 * demonstrably not reached, surface a launch failure with any concrete [reason], otherwise treat it
 * as a normal session end. `runtimeReached == null` (older/bare events) stays a normal session end.
 */
internal fun winlatorGameExitedMessage(runtimeReached: Boolean?, reason: String?): String =
    if (runtimeReached == false) {
        buildString {
            append("Winlator could not start this game")
            val trimmed = reason?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed != null) append(": ").append(trimmed) else append(". The runtime did not start.")
        }
    } else {
        "Winlator game session ended."
    }
