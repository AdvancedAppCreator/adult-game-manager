package com.example.f95updater

/**
 * What actually happened to the archive a managed upgrade was installed from.
 *
 * This used to be two booleans (`sourceArchiveDeleted` + `sourceArchiveDeleteRequested`), and their
 * four combinations could not tell "AGM deleted it" from "AGM tried and failed" from "it was
 * already gone before AGM looked". The completion dialog therefore told the user an archive could
 * NOT be deleted whenever the file had simply been moved or removed already — a deletion failure
 * that never happened.
 *
 * The values are ordered by how much AGM did, and each carries a stable [storageKey] so the outcome
 * stays readable if it is ever journalled: an unknown or missing key decodes to [NotRequested], the
 * only value that never claims AGM touched the user's file.
 */
enum class ManagedUpgradeSourceArchiveOutcome(val storageKey: String) {

    /** Nothing to report: this upgrade was not started with delete-after-install on. */
    NotRequested("not_requested"),

    /** AGM was asked to delete the archive and did. */
    Deleted("deleted"),

    /** AGM was asked to delete the archive, but it was already gone. Not a failure. */
    AlreadyAbsent("already_absent"),

    /** AGM was asked to delete the archive, it was still there, and it could not be removed. */
    Failed("failed");

    /** Whether the upgrade recorded the user's "delete after install" choice as on. */
    val deletionRequested: Boolean get() = this != NotRequested

    /** Whether AGM itself removed the file. Only ever true for [Deleted]. */
    val deletedByAgm: Boolean get() = this == Deleted

    /** Whether the archive is still on disk because AGM could not remove it. */
    val deletionFailed: Boolean get() = this == Failed

    companion object {
        /** The safe default: never claim a deletion that was not recorded. */
        val DEFAULT: ManagedUpgradeSourceArchiveOutcome = NotRequested

        /** Decodes a persisted [storageKey]; anything unknown falls back to [DEFAULT]. */
        fun fromStorageKey(key: String?): ManagedUpgradeSourceArchiveOutcome =
            entries.firstOrNull { it.storageKey == key?.trim() } ?: DEFAULT
    }
}

/** The user-facing wording of a [ManagedUpgradeSourceArchiveOutcome]. */
object ManagedUpgradeSourceArchive {

    /** The line the "Upgrade complete" dialog shows about the archive named [name]. */
    fun summaryLine(outcome: ManagedUpgradeSourceArchiveOutcome, name: String): String =
        when (outcome) {
            ManagedUpgradeSourceArchiveOutcome.Deleted -> "Source archive deleted: $name"
            ManagedUpgradeSourceArchiveOutcome.NotRequested -> "Source archive kept: $name"
            ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent -> "Source archive was already gone: $name"
            ManagedUpgradeSourceArchiveOutcome.Failed -> "Source archive could NOT be deleted: $name"
        }
}
