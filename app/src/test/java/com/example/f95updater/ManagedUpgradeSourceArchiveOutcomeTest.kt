package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happened to the source archive is one answer, not two booleans.
 *
 * `sourceArchiveDeleted = false` + `sourceArchiveDeleteRequested = true` used to mean both "AGM
 * tried and could not delete it" and "it was already gone before AGM looked", so the completion
 * dialog reported a deletion failure for files nobody had failed to delete. The outcome now names
 * the four real cases, and only [ManagedUpgradeSourceArchiveOutcome.Failed] is a failure.
 */
class ManagedUpgradeSourceArchiveOutcomeTest {

    @Test
    fun onlyAGenuineFailedRequestedDeletionIsAFailure() {
        assertFalse(ManagedUpgradeSourceArchiveOutcome.NotRequested.deletionFailed)
        assertFalse(ManagedUpgradeSourceArchiveOutcome.Deleted.deletionFailed)
        assertFalse(
            "already gone is not something AGM failed at",
            ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent.deletionFailed,
        )
        assertTrue(ManagedUpgradeSourceArchiveOutcome.Failed.deletionFailed)
    }

    @Test
    fun onlyADeletionAgmPerformedCountsAsDeleted() {
        assertTrue(ManagedUpgradeSourceArchiveOutcome.Deleted.deletedByAgm)
        listOf(
            ManagedUpgradeSourceArchiveOutcome.NotRequested,
            ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent,
            ManagedUpgradeSourceArchiveOutcome.Failed,
        ).forEach { assertFalse("$it must not claim AGM deleted the file", it.deletedByAgm) }
    }

    @Test
    fun theRequestedFlagSeparatesKeptFromEverythingElse() {
        assertFalse(ManagedUpgradeSourceArchiveOutcome.NotRequested.deletionRequested)
        listOf(
            ManagedUpgradeSourceArchiveOutcome.Deleted,
            ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent,
            ManagedUpgradeSourceArchiveOutcome.Failed,
        ).forEach { assertTrue("$it only happens when deletion was asked for", it.deletionRequested) }
    }

    @Test
    fun theDialogNeverClaimsAFailureForAnArchiveThatWasAlreadyGone() {
        val name = "Sunset Bay-2.0.zip"
        assertEquals(
            "Source archive was already gone: $name",
            ManagedUpgradeSourceArchive.summaryLine(ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent, name),
        )
        assertFalse(
            ManagedUpgradeSourceArchive
                .summaryLine(ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent, name)
                .contains("NOT"),
        )
        assertEquals(
            "Source archive deleted: $name",
            ManagedUpgradeSourceArchive.summaryLine(ManagedUpgradeSourceArchiveOutcome.Deleted, name),
        )
        assertEquals(
            "Source archive kept: $name",
            ManagedUpgradeSourceArchive.summaryLine(ManagedUpgradeSourceArchiveOutcome.NotRequested, name),
        )
        assertEquals(
            "Source archive could NOT be deleted: $name",
            ManagedUpgradeSourceArchive.summaryLine(ManagedUpgradeSourceArchiveOutcome.Failed, name),
        )
    }

    // ------------------------------------------------------------------ persistence safety

    @Test
    fun everyOutcomeRoundTripsThroughItsStorageKey() {
        ManagedUpgradeSourceArchiveOutcome.entries.forEach { outcome ->
            assertEquals(outcome, ManagedUpgradeSourceArchiveOutcome.fromStorageKey(outcome.storageKey))
        }
        assertEquals(
            "the keys are stable identifiers, not enum names that may be renamed",
            listOf("not_requested", "deleted", "already_absent", "failed"),
            ManagedUpgradeSourceArchiveOutcome.entries.map { it.storageKey },
        )
        assertEquals(
            ManagedUpgradeSourceArchiveOutcome.entries.size,
            ManagedUpgradeSourceArchiveOutcome.entries.map { it.storageKey }.toSet().size,
        )
    }

    @Test
    fun anUnknownOrMissingKeyFallsBackToTheOutcomeThatClaimsNothing() {
        listOf(null, "", "  ", "purged", "true").forEach { key ->
            assertEquals(
                "'$key' must never be read as a deletion",
                ManagedUpgradeSourceArchiveOutcome.NotRequested,
                ManagedUpgradeSourceArchiveOutcome.fromStorageKey(key),
            )
        }
        assertEquals(
            ManagedUpgradeSourceArchiveOutcome.NotRequested,
            ManagedUpgradeSourceArchiveOutcome.DEFAULT,
        )
        assertFalse(ManagedUpgradeSourceArchiveOutcome.DEFAULT.deletedByAgm)
        assertFalse(ManagedUpgradeSourceArchiveOutcome.DEFAULT.deletionFailed)
    }

    @Test
    fun aResultBuiltWithoutAnOutcomeReportsNothingAboutTheArchive() {
        val result = ManagedUpgradeResult(
            managedGameId = "game-1",
            label = "Sunset Bay",
            newFolder = "/games/Sunset Bay-2.0",
            oldFolder = "/games/upgraded_to_delete_Sunset Bay",
            oldFolderRenamed = true,
            saveItemsCopied = 3,
            sourceArchiveName = null,
            warnings = emptyList(),
        )
        assertEquals(ManagedUpgradeSourceArchiveOutcome.NotRequested, result.sourceArchiveOutcome)
    }
}
