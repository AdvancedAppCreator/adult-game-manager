package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership rule for AGM's single install pipeline.
 *
 * Two installs running at once do not produce two games: they produce one install writing over the
 * other's cache copy, staging folder, destination folder and recovery journal. So every entry point
 * refuses instead of queueing, and the refusal has to be specific enough that the user knows what
 * to wait for.
 */
class InstallOperationGuardTest {

    private fun status(
        kind: ProgressOperationKind,
        name: String = "Game v1.zip",
        cancelEnabled: Boolean = true,
    ) = MinimizedProgress(
        kind = kind,
        archiveName = name,
        phase = JoiPlayExtractFlow.Phase.Extracting,
        progress = null,
        cancelEnabled = cancelEnabled,
    )

    @Test
    fun anIdlePipelineRefusesNothing() {
        assertNull(InstallOperationGuard.ownerOf(null))
        InstallOperationKind.entries.forEach {
            assertNull(InstallOperationGuard.refuse(null, it))
        }
    }

    @Test
    fun aCancellableOperationOffersCancellingItFirst() {
        val owner = InstallOperationGuard.ownerOf(status(ProgressOperationKind.Extract))
        assertEquals(
            "AGM is already installing Game v1.zip. Cancel it or let it finish before installing a patch.",
            InstallOperationGuard.refuse(owner, InstallOperationKind.Patch),
        )
    }

    @Test
    fun aNonCancellableOperationTellsTheUserToWaitInstead() {
        // The patch commit and the managed-upgrade store relocation cannot be interrupted, so the
        // refusal must not offer a cancel that the flow would silently ignore.
        val owner = InstallOperationGuard.ownerOf(
            status(ProgressOperationKind.Patch, "Taboo Patch.zip", cancelEnabled = false),
        )
        val reason = InstallOperationGuard.refuse(owner, InstallOperationKind.Extract)!!
        assertEquals(
            "AGM is already installing the patch Taboo Patch.zip. That step can't be interrupted, " +
                "so wait for it to finish before installing another game.",
            reason,
        )
    }

    @Test
    fun everyRequestedKindIsRefusedWhileAnyOperationOwnsThePipeline() {
        val owners = listOf(
            InstallOperationGuard.ownerOf(status(ProgressOperationKind.Extract)),
            InstallOperationGuard.ownerOf(status(ProgressOperationKind.Upgrade)),
            InstallOperationGuard.ownerOf(status(ProgressOperationKind.Patch, cancelEnabled = false)),
            BulkRunGuard.ownerOf(null, BatchInstallSession(listOf(BatchItem("/a", "a", BatchItemKind.Archive)))),
        )
        for (owner in owners) {
            for (requested in InstallOperationKind.entries) {
                assertNotNull(
                    "$owner must refuse $requested",
                    InstallOperationGuard.refuse(owner, requested),
                )
            }
        }
    }

    @Test
    fun anOperationThatOnlyWaitsOnADialogSaysSoInsteadOfOfferingACancel() {
        // Nothing is running; the result/error dialog still owns the cached archive and folders.
        val owner = InstallOperationOwner(
            kind = InstallOperationKind.Patch,
            detail = "Taboo Patch.zip",
            cancellable = true,
            awaitingUser = true,
        )
        assertEquals(
            "AGM is still showing the outcome of the last patch (Taboo Patch.zip). " +
                "Close that dialog before installing a patch.",
            InstallOperationGuard.refuse(owner, InstallOperationKind.Patch),
        )
        assertEquals(
            "AGM is still showing the outcome of the last install. " +
                "Close that dialog before starting a bulk install.",
            InstallOperationGuard.refuse(
                InstallOperationOwner(InstallOperationKind.Extract, awaitingUser = true),
                InstallOperationKind.Bulk,
            ),
        )
    }

    @Test
    fun theFirstOwnerInPriorityOrderWins() {
        val bulk = InstallOperationOwner(InstallOperationKind.Bulk)
        val patch = InstallOperationOwner(InstallOperationKind.Patch)
        assertEquals(bulk, InstallOperationGuard.firstOwner(null, bulk, patch))
        assertEquals(patch, InstallOperationGuard.firstOwner(null, null, patch))
        assertNull(InstallOperationGuard.firstOwner(null, null, null))
    }

    /**
     * The chosen invariant: minimizing is offered for every running operation — including the
     * non-cancellable ones — precisely *because* re-entry is blocked identically either way.
     * `ProgressMinimizeState` is therefore not an input to the guard, and this test pins that: the
     * exact same refusal comes back whether the dialog is on screen or behind the compact card.
     */
    @Test
    fun minimizingAnOperationNeverChangesWhatAgmRefuses() {
        val running = status(ProgressOperationKind.Upgrade, "Big Game.7z", cancelEnabled = false)
        val owner = InstallOperationGuard.ownerOf(running)

        var shown = ProgressMinimize.onActiveOperation(ProgressMinimizeState(), running.key, null)
        val whileShown = InstallOperationKind.entries.map { InstallOperationGuard.refuse(owner, it) }

        shown = ProgressMinimize.minimize(shown, running.key, null)
        assertTrue("a non-cancellable operation may still be minimized", shown.minimized)
        val whileMinimized = InstallOperationKind.entries.map { InstallOperationGuard.refuse(owner, it) }

        assertEquals(whileShown, whileMinimized)
        assertTrue(whileShown.all { it != null })

        val restored = ProgressMinimize.restore(shown)
        assertEquals(
            whileShown,
            InstallOperationKind.entries.map { InstallOperationGuard.refuse(owner, it) },
        )
        assertTrue(!restored.minimized)
    }

    /** The card's cancel affordance stays coherent with the flow's real cancellability. */
    @Test
    fun theMinimizedCardOnlyOffersCancelWhenTheOperationIsCancellable() {
        assertTrue(status(ProgressOperationKind.Extract).cancelEnabled)
        assertTrue(!status(ProgressOperationKind.Patch, cancelEnabled = false).cancelEnabled)
        assertEquals(
            InstallOperationGuard.ownerOf(status(ProgressOperationKind.Patch, cancelEnabled = false))!!.cancellable,
            status(ProgressOperationKind.Patch, cancelEnabled = false).cancelEnabled,
        )
    }
}
