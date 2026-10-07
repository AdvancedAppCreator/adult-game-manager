package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * The guard that stops a patch rollback from trusting the paths it recorded, plus the journal
 * reconciliation rules the managed-game store relies on.
 */
class PatchJournalLifecycleTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun library(): File = temporary.newFolder("library")

    private fun game(root: File, id: String = UUID.randomUUID().toString()): ManagedGame =
        validateManagedGame(
            ManagedGame(
                id = id,
                canonicalPath = canonicalManagedGamePath(root.absolutePath),
                storagePath = root.absolutePath,
                label = "Sample Game",
                defaultRunner = ManagedRunnerKind.JoiPlay,
                runnerBindings = listOf(
                    ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "script.rpy"),
                ),
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

    private fun record(
        game: ManagedGame,
        destination: File,
        phase: PatchTransactionPhase = PatchTransactionPhase.Committed,
        invalidated: Boolean = false,
        unresolvedReason: String? = null,
    ) = PatchTransactionRecord(
        transactionId = UUID.randomUUID().toString(),
        phase = phase,
        managedGameId = game.id,
        label = game.label,
        storagePath = game.storagePath,
        destinationRoot = destination.absolutePath,
        workDir = File(File(game.storagePath).parentFile, ".agm-patch-tx").absolutePath,
        backupDir = File(File(game.storagePath).parentFile, ".agm-patch-tx/backup").absolutePath,
        archiveName = "patch.zip",
        added = listOf(PatchAddedFile("gallery.rpy", "aa", 2L)),
        replaced = listOf(PatchReplacedFile("script.rpy", "bb", 3L)),
        createdDirectories = emptyList(),
        invalidated = invalidated,
        unresolvedReason = unresolvedReason,
    )

    @Test
    fun aMatchingGameAndDestinationIsNotBlocked() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        assertNull(patchRollbackBlockReason(record(game, destination), game))
    }

    @Test
    fun aDeletedGameBlocksTheRollback() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val reason = patchRollbackBlockReason(record(game(root), destination), null)
        assertNotNull(reason)
        assertTrue(reason!!.contains("no longer in the AGM library"))
        assertTrue(reason.contains(destination.absolutePath))
    }

    @Test
    fun aRelocatedGameBlocksTheRollback() {
        val library = library()
        val oldRoot = File(library, "Sample Game").apply { mkdirs() }
        val destination = File(oldRoot, "game").apply { mkdirs() }
        val newRoot = File(library, "Sample Game v2").apply { mkdirs() }
        val stale = record(game(oldRoot), destination)
        val moved = game(newRoot, stale.managedGameId)

        val reason = patchRollbackBlockReason(stale, moved)
        assertNotNull(reason)
        assertTrue(reason!!.contains(newRoot.absolutePath))
        assertTrue(reason.contains(oldRoot.absolutePath))
    }

    @Test
    fun aVanishedDestinationRootBlocksTheRollback() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game")
        val game = game(root)
        val reason = patchRollbackBlockReason(record(game, destination), game)
        assertNotNull(reason)
        assertTrue(reason!!.contains("does not exist any more"))
    }

    @Test
    fun aVanishedStorageRootBlocksTheRollback() {
        val root = File(library(), "Sample Game")
        val destination = File(root, "game")
        val game = game(root)
        val reason = patchRollbackBlockReason(record(game, destination), game)
        assertNotNull(reason)
        assertTrue(reason!!.contains(root.absolutePath))
    }

    @Test
    fun aDestinationOutsideTheGameFolderBlocksTheRollback() {
        val library = library()
        val root = File(library, "Sample Game").apply { mkdirs() }
        val outside = File(library, "Somewhere Else").apply { mkdirs() }
        val game = game(root)
        val reason = patchRollbackBlockReason(record(game, outside), game)
        assertNotNull(reason)
        assertTrue(reason!!.contains("is not inside"))
    }

    @Test
    fun anInvalidatedRecordIsAlwaysBlockedAndKeepsItsReason() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val stale = record(
            game,
            destination,
            phase = PatchTransactionPhase.Orphaned,
            invalidated = true,
            unresolvedReason = "the backup folder could not be deleted",
        )
        assertEquals("the backup folder could not be deleted", patchRollbackBlockReason(stale, game))
        assertFalse(stale.isUsableRollbackPoint)
        assertFalse(stale.blocksNewPatch)
    }

    @Test
    fun onlyAnUnresolvedTransactionBlocksTheLifecycle() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val committed = record(game, destination)
        assertNull(unresolvedPatchBlocker(listOf(committed), game.id))

        val rollingBack = committed.copy(phase = PatchTransactionPhase.RollingBack)
        val reason = unresolvedPatchBlocker(listOf(rollingBack), game.id)
        assertNotNull(reason)
        assertTrue(reason!!.contains("unresolved patch transaction"))

        // Another game's transaction never blocks this one.
        assertNull(unresolvedPatchBlocker(listOf(rollingBack), UUID.randomUUID().toString()))
        // An already invalidated record has nothing left to resolve.
        assertNull(
            unresolvedPatchBlocker(
                listOf(rollingBack.copy(invalidated = true, phase = PatchTransactionPhase.Orphaned)),
                game.id,
            ),
        )
    }

    @Test
    fun onlyTheRecordsOfTheOldLocationAreSelectedForInvalidation() {
        val library = library()
        val oldRoot = File(library, "Sample Game").apply { mkdirs() }
        val newRoot = File(library, "Sample Game v2").apply { mkdirs() }
        val game = game(oldRoot)
        val old = record(game, File(oldRoot, "game"))
        val moved = record(game(newRoot, game.id), File(newRoot, "game"))
        val other = record(game(File(library, "Other").apply { mkdirs() }))

        val selected = patchRecordsAtLocation(
            listOf(old, moved, other),
            game.id,
            canonicalManagedGamePath(oldRoot.absolutePath),
        )
        assertEquals(listOf(old.transactionId), selected.map { it.transactionId })
    }

    @Test
    fun anInvalidatedRecordIsNeverSelectedTwice() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val game = game(root)
        val invalidated = record(
            game,
            File(root, "game"),
            phase = PatchTransactionPhase.Orphaned,
            invalidated = true,
        )
        assertTrue(
            patchRecordsAtLocation(
                listOf(invalidated),
                game.id,
                canonicalManagedGamePath(root.absolutePath),
            ).isEmpty(),
        )
    }

    @Test
    fun anInvalidatedRecordSurvivesAJournalRoundTrip() {        val root = File(library(), "Sample Game").apply { mkdirs() }
        val game = game(root)
        val invalidated = record(
            game,
            File(root, "game"),
            phase = PatchTransactionPhase.Orphaned,
            invalidated = true,
            unresolvedReason = "left over",
        )
        val decoded = PatchTransactionStore.decode(PatchTransactionStore.encode(listOf(invalidated)))
        assertEquals(1, decoded.size)
        assertTrue(decoded.single().invalidated)
        assertEquals(PatchTransactionPhase.Orphaned, decoded.single().phase)
        assertEquals("left over", decoded.single().unresolvedReason)
    }

    @Test
    fun orphaningRemembersTheExactPhaseTheRecordWasIn() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        for (origin in listOf(
            PatchTransactionPhase.Committed,
            PatchTransactionPhase.Committing,
            PatchTransactionPhase.RollingBack,
        )) {
            val orphaned = record(game, destination, phase = origin).markOrphaned("storage vanished")
            assertEquals(PatchTransactionPhase.Orphaned, orphaned.phase)
            assertEquals(origin, orphaned.orphanedFrom)
            assertEquals(origin, orphaned.restoredPhase)
            assertEquals("storage vanished", orphaned.unresolvedReason)
            assertFalse(orphaned.isUsableRollbackPoint)
        }
    }

    @Test
    fun orphaningAnAlreadyOrphanedRecordKeepsTheFirstProvenance() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val once = record(game, destination, phase = PatchTransactionPhase.RollingBack)
            .markOrphaned("first")
        val twice = once.markOrphaned("second")
        assertEquals(PatchTransactionPhase.RollingBack, twice.orphanedFrom)
        assertEquals(PatchTransactionPhase.RollingBack, twice.restoredPhase)
        assertEquals("second", twice.unresolvedReason)
    }

    @Test
    fun onlyAnInterruptedMutationIsResumedByRecovery() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val base = record(game, destination)

        assertTrue(base.copy(phase = PatchTransactionPhase.Committing).isResumableRollback)
        assertTrue(base.copy(phase = PatchTransactionPhase.RollingBack).isResumableRollback)
        assertFalse(base.copy(phase = PatchTransactionPhase.Committed).isResumableRollback)

        assertTrue(
            base.copy(phase = PatchTransactionPhase.Committing).markOrphaned("gone").isResumableRollback,
        )
        assertTrue(
            base.copy(phase = PatchTransactionPhase.RollingBack).markOrphaned("gone").isResumableRollback,
        )
        // A committed patch is the user's rollback point; recovery never undoes it on its own.
        assertFalse(base.markOrphaned("gone").isResumableRollback)
        // An abandoned backup can never be restored from.
        assertFalse(
            base.copy(phase = PatchTransactionPhase.Committing, invalidated = true)
                .markOrphaned("gone").isResumableRollback,
        )
    }

    @Test
    fun aRestoredOrphanReturnsToItsOwnPhaseAndOnlyACommittedOneIsUsableAgain() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val origins = listOf(
            PatchTransactionPhase.Committed,
            PatchTransactionPhase.Committing,
            PatchTransactionPhase.RollingBack,
        )
        for (origin in origins) {
            val restored = record(game, destination, phase = origin)
                .markOrphaned("storage vanished")
                .let { it.copy(phase = it.restoredPhase!!, orphanedFrom = null, unresolvedReason = null) }
            assertEquals(origin, restored.phase)
            assertNull(restored.orphanedFrom)
            assertEquals(
                origin == PatchTransactionPhase.Committed,
                restored.isUsableRollbackPoint,
            )
        }
    }

    @Test
    fun provenanceSurvivesAJournalRoundTrip() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val game = game(root)
        val orphaned = record(game, File(root, "game"), phase = PatchTransactionPhase.Committing)
            .markOrphaned("the library could not be read")
        val decoded = PatchTransactionStore
            .decode(PatchTransactionStore.encode(listOf(orphaned)))
            .single()
        assertEquals(PatchTransactionPhase.Orphaned, decoded.phase)
        assertEquals(PatchTransactionPhase.Committing, decoded.orphanedFrom)
        assertEquals(PatchTransactionPhase.Committing, decoded.restoredPhase)
        assertTrue(decoded.isResumableRollback)
    }

    @Test
    fun aLegacyOrphanedRecordWithoutProvenanceIsNeverPromotedOrResumed() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val game = game(root)
        val orphaned = record(game, File(root, "game"), phase = PatchTransactionPhase.Committed)
            .markOrphaned("written by an older AGM")
        val encoded = PatchTransactionStore.encode(listOf(orphaned))
        assertTrue(encoded.contains("\"orphanedFrom\":\"Committed\""))

        // Exactly what a journal written before the field existed looks like.
        val legacyJson = encoded.replace("\"orphanedFrom\":\"Committed\",", "")
        assertFalse(legacyJson.contains("orphanedFrom"))
        val legacy = PatchTransactionStore.decode(legacyJson).single()

        assertEquals(PatchTransactionPhase.Orphaned, legacy.phase)
        assertNull(legacy.orphanedFrom)
        // Nothing may be assumed: it is neither promoted to Committed nor rolled back by recovery.
        assertNull(legacy.restoredPhase)
        assertFalse(legacy.isResumableRollback)
        assertFalse(legacy.isUsableRollbackPoint)
        assertTrue(legacy.isUnresolved)
    }

    @Test
    fun noInterruptedOrResumableRecordMayEverBeForgotten() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val base = record(game, destination)

        val interrupted = listOf(
            base.copy(phase = PatchTransactionPhase.Committing),
            base.copy(phase = PatchTransactionPhase.RollingBack),
            base.copy(phase = PatchTransactionPhase.Committing).markOrphaned("storage vanished"),
            base.copy(phase = PatchTransactionPhase.RollingBack).markOrphaned("storage vanished"),
        )
        for (candidate in interrupted) {
            assertTrue(candidate.isResumableRollback)
            assertFalse(candidate.isSafeToForget)
            assertNotNull(patchForgetBlockReason(candidate))
            assertEquals(
                listOf(PatchRecordAction.RetryRollback),
                patchRecordActions(candidate),
            )
        }
    }

    @Test
    fun aRecordWhoseBackupMayStillBeNeededIsNeverForgettable() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val base = record(game, destination)

        // A usable rollback point is rolled back or explicitly discarded, never "forgotten".
        assertFalse(base.isSafeToForget)
        assertNotNull(patchForgetBlockReason(base))
        assertEquals(
            listOf(PatchRecordAction.RollBack, PatchRecordAction.DiscardBackup),
            patchRecordActions(base),
        )

        // Orphaned from a committed patch: the backup still holds the pre-patch files.
        val orphanedFromCommitted = base.markOrphaned("the folder looked missing")
        assertFalse(orphanedFromCommitted.isSafeToForget)
        assertTrue(patchForgetBlockReason(orphanedFromCommitted)!!.contains(base.backupDir))
        assertEquals(
            listOf(PatchRecordAction.RetryRollback),
            patchRecordActions(orphanedFromCommitted),
        )

        // Legacy orphan with unknown provenance: recoverability wins over cleanup.
        val legacy = base.copy(phase = PatchTransactionPhase.Orphaned, orphanedFrom = null)
        assertFalse(legacy.isSafeToForget)
        assertTrue(patchForgetBlockReason(legacy)!!.contains("cannot prove"))
        assertEquals(listOf(PatchRecordAction.RetryRollback), patchRecordActions(legacy))
    }

    @Test
    fun onlyAnInvalidatedRecordMayBeRemovedAsCleanup() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val invalidated = record(
            game,
            destination,
            phase = PatchTransactionPhase.Orphaned,
            invalidated = true,
            unresolvedReason = "the backup folder could not be deleted",
        )
        assertTrue(invalidated.isSafeToForget)
        assertFalse(invalidated.isResumableRollback)
        assertFalse(invalidated.isUsableRollbackPoint)
        assertNull(patchForgetBlockReason(invalidated))
        assertEquals(listOf(PatchRecordAction.RemoveRecord), patchRecordActions(invalidated))
        assertEquals(
            "the backup folder could not be deleted",
            patchRecordUnresolvedSummary(invalidated),
        )

        // Even an invalidated record that still claims an interrupted provenance is only cleanup:
        // its backup is gone, so there is nothing left to restore from.
        val invalidatedInterrupted = record(
            game,
            destination,
            phase = PatchTransactionPhase.Committing,
            invalidated = true,
        ).markOrphaned("the game was deleted")
        assertTrue(invalidatedInterrupted.isSafeToForget)
        assertFalse(invalidatedInterrupted.isResumableRollback)
    }

    @Test
    fun theUnresolvedSummaryAlwaysNamesAPreciseReason() {
        val root = File(library(), "Sample Game").apply { mkdirs() }
        val destination = File(root, "game").apply { mkdirs() }
        val game = game(root)
        val committing = record(game, destination, phase = PatchTransactionPhase.Committing)
        // No stored reason yet: the summary still explains the state instead of going blank.
        assertNull(committing.unresolvedReason)
        assertTrue(patchRecordUnresolvedSummary(committing).contains("interrupted patch install"))
        assertTrue(patchRecordUnresolvedSummary(committing).contains(committing.backupDir))

        val rollingBack = record(game, destination, phase = PatchTransactionPhase.RollingBack)
        assertTrue(patchRecordUnresolvedSummary(rollingBack).contains("unfinished rollback"))

        val withReason = committing.copy(unresolvedReason = "the folder is unreadable")
        assertEquals("the folder is unreadable", patchRecordUnresolvedSummary(withReason))
    }

    private fun record(game: ManagedGame) = record(game, File(game.storagePath, "game"))
}
