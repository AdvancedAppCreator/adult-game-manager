package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The managed-upgrade cancellation boundary, as a rule rather than as a button.
 *
 * `finishExtraction` used to leave Cancel enabled all the way through the discovery, the binding
 * plan and the save migration — none of which suspend. A cancel accepted there was therefore only
 * *observed* inside the `NonCancellable` commit (so the upgrade committed anyway) or inside the
 * Winlator capability round-trip (so the new folder and the recovery journal were left behind).
 */
class ManagedUpgradeCancellationTest {

    @Test
    fun everyStepBeforeTheSaveMigrationIsCancellable() {
        listOf(
            ManagedUpgradeStep.Extracting,
            ManagedUpgradeStep.Inspecting,
            ManagedUpgradeStep.PlanningBindings,
            ManagedUpgradeStep.Journaling,
        ).forEach { step ->
            assertTrue("$step must still offer Cancel", ManagedUpgradeCancellation.cancellableAt(step))
            assertFalse("$step is before the boundary", ManagedUpgradeCancellation.crossesBoundary(step))
        }
    }

    @Test
    fun theSaveMigrationAndEverythingAfterItIsNotCancellable() {
        listOf(
            ManagedUpgradeStep.MigratingSaves,
            ManagedUpgradeStep.RepointingWinlator,
            ManagedUpgradeStep.Committing,
        ).forEach { step ->
            assertFalse("$step must not offer Cancel", ManagedUpgradeCancellation.cancellableAt(step))
            assertTrue("$step is past the boundary", ManagedUpgradeCancellation.crossesBoundary(step))
        }
    }

    @Test
    fun theBoundaryIsTheVerifiedSaveMigration() {
        assertEquals(ManagedUpgradeStep.MigratingSaves, ManagedUpgradeCancellation.boundaryStep)
        // The step order is the contract: the boundary must not be able to drift behind a step that
        // already wrote something the installed game is resumed from.
        assertEquals(
            listOf(
                ManagedUpgradeStep.Extracting,
                ManagedUpgradeStep.Inspecting,
                ManagedUpgradeStep.PlanningBindings,
                ManagedUpgradeStep.Journaling,
                ManagedUpgradeStep.MigratingSaves,
                ManagedUpgradeStep.RepointingWinlator,
                ManagedUpgradeStep.Committing,
            ),
            ManagedUpgradeStep.entries.toList(),
        )
    }

    @Test
    fun theCancellationMessageIsNeitherSuccessNorFailure() {
        val message = ManagedUpgradeCancellation.message("Sunset Bay")
        assertTrue(message.contains("Sunset Bay"))
        assertTrue("the new folder is reported as removed", message.contains("removed"))
        assertTrue("the installed game is reported as untouched", message.contains("left exactly as it was"))
        assertFalse("a cancellation is not a failure", message.lowercase().contains("failed"))
    }

    @Test
    fun theCancellationMessageSurvivesAMissingLabel() {
        val message = ManagedUpgradeCancellation.message(null)
        assertTrue(message.contains("The game"))
        assertEquals(message, ManagedUpgradeCancellation.message("   "))
    }

    @Test
    fun theBulkWordingNamesTheFileAndSaysTheRunStopped() {
        val notice = ManagedUpgradeCancellation.message("Sunset Bay")
        val bulk = ManagedUpgradeCancellation.bulkMessage("Sunset Bay-2.0.zip", notice)
        assertTrue(bulk.startsWith("Sunset Bay-2.0.zip: "))
        assertTrue(bulk.contains(notice))
        assertTrue(bulk.contains("bulk install was paused"))
        // Without a file name it is still a complete sentence.
        assertTrue(ManagedUpgradeCancellation.bulkMessage(null, notice).startsWith(notice))
    }

    // ------------------------------------------------------------- minimized card / ownership

    private fun card(step: ManagedUpgradeStep) = MinimizedProgress(
        kind = ProgressOperationKind.Upgrade,
        archiveName = "Sunset Bay-2.0.zip",
        phase = JoiPlayExtractFlow.Phase.Extracting,
        progress = null,
        cancelEnabled = ManagedUpgradeCancellation.cancellableAt(step),
    )

    @Test
    fun theMinimizedCardLosesCancelExactlyAtTheBoundary() {
        assertTrue(card(ManagedUpgradeStep.Journaling).cancelEnabled)
        assertFalse(card(ManagedUpgradeStep.MigratingSaves).cancelEnabled)
        assertFalse(card(ManagedUpgradeStep.Committing).cancelEnabled)
    }

    @Test
    fun theRefusalTextFollowsTheCardAcrossTheBoundary() {
        val before = InstallOperationGuard.ownerOf(card(ManagedUpgradeStep.Journaling))
        val after = InstallOperationGuard.ownerOf(card(ManagedUpgradeStep.MigratingSaves))
        assertNotNull(before)
        assertNotNull(after)
        assertTrue(before!!.cancellable)
        assertFalse(after!!.cancellable)

        val beforeReason = InstallOperationGuard.refuse(before, InstallOperationKind.Extract)
        val afterReason = InstallOperationGuard.refuse(after, InstallOperationKind.Extract)
        assertNotNull(beforeReason)
        assertNotNull(afterReason)
        assertTrue("before the boundary the user may cancel", beforeReason!!.contains("Cancel it or let it finish"))
        assertTrue("after it the user can only wait", afterReason!!.contains("can't be interrupted"))
    }

    @Test
    fun minimizingDoesNotMoveTheBoundary() {
        // Minimizing is UI only: the same step yields the same card identity and the same cancel
        // rule whether the dialog is on screen or behind the compact card.
        val running = card(ManagedUpgradeStep.MigratingSaves)
        val minimized = ProgressMinimize.minimize(ProgressMinimizeState(), running.key, null)
        assertTrue(minimized.minimized)
        assertEquals(running.key, minimized.operationKey)
        assertFalse(running.cancelEnabled)
        assertNull(
            "a free pipeline still refuses nothing",
            InstallOperationGuard.refuse(null, InstallOperationKind.Upgrade),
        )
    }
}
