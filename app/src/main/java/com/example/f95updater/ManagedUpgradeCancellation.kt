package com.example.f95updater

/**
 * The steps a managed-archive upgrade walks once the archive itself has been read.
 *
 * The order is the order they run in, and that order is the whole point: everything up to
 * [MigratingSaves] only *reads* the installed game, so a cancellation there can be honoured by
 * deleting the freshly extracted folder and the recovery record. From [MigratingSaves] on, AGM is
 * writing the state the installed game will be resumed from, and the store relocation that follows
 * runs under `NonCancellable`, so there is no longer any point at which a cancellation could be
 * observed *and* undone.
 */
enum class ManagedUpgradeStep {
    /** Streaming the archive into a brand new sibling folder. */
    Extracting,

    /** Reading the extracted folder to learn which runners it supports. */
    Inspecting,

    /** Turning the discovered runners into the replacement game record. */
    PlanningBindings,

    /** Persisting the planned replacement into the recovery journal. The last cancellable step. */
    Journaling,

    /** Copying and verifying the player's saves into the new folder. The first committed step. */
    MigratingSaves,

    /** Handing the new paths to Winlator; its result decides whether AGM commits or rolls back. */
    RepointingWinlator,

    /** The irreversible AGM store relocation and its cleanup. */
    Committing,
}

/**
 * Where the cancellable half of a managed upgrade ends, and what the user is told when a
 * cancellation is accepted.
 *
 * Deliberately Android-free: the boundary is a rule about the flow, not about a button, and the UI,
 * the minimized card and the install-ownership guard all have to agree on it.
 */
object ManagedUpgradeCancellation {

    /**
     * The first step of the irreversible tail. Cancel must already be disabled when this step
     * starts, because from here a cancellation can no longer be both observed and fully undone.
     */
    val boundaryStep: ManagedUpgradeStep = ManagedUpgradeStep.MigratingSaves

    /** Whether Cancel may still be offered while [step] runs. */
    fun cancellableAt(step: ManagedUpgradeStep): Boolean = step.ordinal < boundaryStep.ordinal

    /** Whether accepting a cancellation at [step] would leave the installed game half-updated. */
    fun crossesBoundary(step: ManagedUpgradeStep): Boolean = !cancellableAt(step)

    /**
     * The non-success message an accepted cancellation surfaces.
     *
     * A cancelled upgrade is never reported as a success and never as a failure of the archive:
     * nothing was changed, and the user is told exactly that so a bulk run's pause reason and the
     * single-game dialog say the same thing.
     */
    fun message(label: String?): String {
        val subject = label?.takeIf { it.isNotBlank() } ?: "The game"
        return "The update of $subject was cancelled. The unfinished new folder was removed and " +
            "$subject was left exactly as it was."
    }

    /** The same outcome, worded for a bulk run that has to stop on it. */
    fun bulkMessage(itemName: String?, notice: String): String {
        val item = itemName?.takeIf { it.isNotBlank() }
        return (item?.let { "$it: " } ?: "") + notice +
            " The bulk install was paused; resume it to continue with the next file."
    }
}
