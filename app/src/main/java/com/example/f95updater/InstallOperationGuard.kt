package com.example.f95updater

/**
 * AGM installs one thing at a time. Extraction, managed upgrade, patch install and the bulk queue
 * all write to the same shared places (the install roots, the managed-game store, the pending
 * upgrade journal, the patch transaction journal), so a second operation started while one is
 * already running is never "two installs" — it is one install quietly corrupting the other's state.
 *
 * The rule is therefore an ownership rule, not a UI rule: whoever started first owns the install
 * pipeline until it settles, and every entry point refuses rather than queues.
 *
 * Deliberately Android-free so both the flows and the UI can share it and it can be unit-tested.
 */
enum class InstallOperationKind { Extract, Upgrade, Patch, Bulk }

/**
 * The one operation that currently owns the install pipeline.
 *
 * [cancellable] mirrors the running flow's own `cancellationAllowed`: a patch commit, a managed
 * upgrade store relocation and a Winlator round-trip cannot be interrupted, so the refusal has to
 * tell the user to wait instead of offering a cancel that does not exist.
 */
data class InstallOperationOwner(
    val kind: InstallOperationKind,
    val detail: String? = null,
    val cancellable: Boolean = true,
    /**
     * True when nothing is running and the operation only still owns the pipeline because its
     * result, error or password dialog is waiting for the user. Telling the user to "cancel or
     * wait" there would be wrong: there is nothing to wait for, only a dialog to close.
     */
    val awaitingUser: Boolean = false,
)

/** What a flow's `start`-shaped entry point answers. */
sealed interface InstallStartOutcome {
    data object Started : InstallStartOutcome
    data class Refused(val reason: String) : InstallStartOutcome

    val started: Boolean get() = this is Started
    val refusalOrNull: String? get() = (this as? Refused)?.reason
}

object InstallOperationGuard {

    /**
     * Maps the single running archive operation onto its owner.
     *
     * The minimize state is deliberately *not* an input. Minimizing only hides a dialog, so the set
     * of actions AGM refuses must be identical whether the progress dialog is on screen or behind
     * the compact card — which is exactly what makes minimizing a non-cancellable operation safe.
     */
    fun ownerOf(status: MinimizedProgress?): InstallOperationOwner? = status?.let {
        InstallOperationOwner(
            kind = when (it.kind) {
                ProgressOperationKind.Extract -> InstallOperationKind.Extract
                ProgressOperationKind.Upgrade -> InstallOperationKind.Upgrade
                ProgressOperationKind.Patch -> InstallOperationKind.Patch
            },
            detail = it.archiveName,
            cancellable = it.cancelEnabled,
        )
    }

    /** The first owner in priority order, or null when the pipeline is free. */
    fun firstOwner(vararg owners: InstallOperationOwner?): InstallOperationOwner? =
        owners.firstOrNull { it != null }

    /**
     * The message shown when [requested] cannot start because [owner] holds the pipeline, or null
     * when the pipeline is free and [requested] may start.
     */
    fun refuse(owner: InstallOperationOwner?, requested: InstallOperationKind): String? {
        if (owner == null) return null
        val goal = when (requested) {
            InstallOperationKind.Extract -> "before installing another game"
            InstallOperationKind.Upgrade -> "before updating a game"
            InstallOperationKind.Patch -> "before installing a patch"
            InstallOperationKind.Bulk -> "before starting a bulk install"
        }
        if (owner.awaitingUser) {
            val subject = when (owner.kind) {
                InstallOperationKind.Extract -> "the last install"
                InstallOperationKind.Upgrade -> "the last game update"
                InstallOperationKind.Patch -> "the last patch"
                InstallOperationKind.Bulk -> "the last bulk install"
            }
            return "AGM is still showing the outcome of $subject" +
                owner.detail?.let { " ($it)" }.orEmpty() +
                ". Close that dialog $goal."
        }
        val busy = when (owner.kind) {
            InstallOperationKind.Extract -> "installing ${owner.detail ?: "a game"}"
            InstallOperationKind.Upgrade -> "updating an installed game from ${owner.detail ?: "an archive"}"
            InstallOperationKind.Patch -> "installing the patch ${owner.detail ?: "you picked"}"
            InstallOperationKind.Bulk -> "running a bulk install${owner.detail?.let { " ($it)" }.orEmpty()}"
        }
        val wait = if (owner.cancellable) {
            "Cancel it or let it finish"
        } else {
            "That step can't be interrupted, so wait for it to finish"
        }
        return "AGM is already $busy. $wait $goal."
    }
}

/**
 * The second half of the same ownership rule, for the bulk runner: one preflight scan and one queue
 * at a time.
 *
 * A bulk run carries a `runId` so a driver coroutine belonging to a finished/cancelled run can never
 * settle an item of a replacement run — the two runs can legitimately hold the very same file paths.
 */
object BulkRunGuard {

    /** Why a new bulk run cannot start now, or null when one may start. */
    fun refuse(preflight: BatchInstallPreflight?, session: BatchInstallSession?): String? {
        if (preflight != null) {
            return "AGM is still preparing a bulk install (${preflight.scanned} of ${preflight.total} " +
                "file(s) scanned). Finish or cancel that one before starting another."
        }
        if (session != null && !session.isComplete) {
            return "A bulk install is still running (item ${session.activePosition} of ${session.total}). " +
                "Cancel it or let it finish before starting another."
        }
        return null
    }

    /** The pipeline owner a live bulk run represents, or null when no run is in flight. */
    fun ownerOf(preflight: BatchInstallPreflight?, session: BatchInstallSession?): InstallOperationOwner? = when {
        preflight != null -> InstallOperationOwner(
            kind = InstallOperationKind.Bulk,
            detail = "scanning ${preflight.scanned} of ${preflight.total}",
            cancellable = true,
        )
        session != null && !session.isComplete -> InstallOperationOwner(
            kind = InstallOperationKind.Bulk,
            detail = "item ${session.activePosition} of ${session.total}",
            cancellable = true,
        )
        else -> null
    }
}
