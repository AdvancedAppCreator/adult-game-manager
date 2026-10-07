package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An upgrade that fails inside an *automatic* bulk run must stop that run, not decorate it.
 *
 * The failure this pins down was silent: the "Upgrade failed" dialog was unconditionally modal, so
 * an unattended automatic run sat on it, and the moment it was dismissed the flow went idle — which
 * is exactly the signal the bulk driver settles an item on. The item was therefore recorded as
 * **Done** for an upgrade that had failed. A failure now behaves like an extraction failure and like
 * a cancellation: it is acknowledged for the flow and turned into a pause carrying the exact error,
 * and only the user's answer to that pause settles the item.
 */
class BulkUpgradeFailurePauseTest {

    private val error = "The installed folder no longer exists: /storage/emulated/0/Games/Sunset Bay"

    private fun item(name: String) = BatchItem(
        path = "/storage/emulated/0/Download/$name",
        name = name,
        kind = BatchItemKind.Archive,
        decision = BatchInstallDecision.Upgrade("game-1", "Sunset Bay"),
    )

    private fun automaticRun(vararg names: String): BatchInstallSession =
        BatchInstallSession(items = names.map { item(it) }, automatic = true, runId = 99L).startNext()

    /** Exactly what MainActivity does when `upgradeFlow.errorMessage` appears during an auto run. */
    private fun reactToUpgradeFailure(session: BatchInstallSession, message: String): BatchInstallSession =
        session.pause(ManagedUpgradeFailure.bulkMessage(session.current?.name, message))

    // ------------------------------------------------------------------ the pause itself

    @Test
    fun theBulkPauseReasonCarriesTheExactUpgradeError() {
        val message = ManagedUpgradeFailure.bulkMessage("Sunset Bay-2.0.zip", error)
        assertTrue(message.startsWith("Sunset Bay-2.0.zip: "))
        assertTrue("the precise error survives", message.contains(error))
        assertTrue(message.contains("bulk install was paused"))
        // Without a file name it is still a complete sentence.
        assertTrue(ManagedUpgradeFailure.bulkMessage(null, error).startsWith(error))
        assertTrue(ManagedUpgradeFailure.bulkMessage("   ", error).startsWith(error))
    }

    @Test
    fun aBlankErrorStillProducesAReadablePause() {
        val message = ManagedUpgradeFailure.bulkMessage("Sunset Bay-2.0.zip", "   ")
        assertTrue(message.contains(ManagedUpgradeFailure.GENERIC))
        assertTrue(ManagedUpgradeFailure.bulkMessage(null, null).startsWith(ManagedUpgradeFailure.GENERIC))
    }

    @Test
    fun aFailedUpgradePausesTheRunAndTheItemIsNeverDone() {
        val running = automaticRun("a.zip", "b.zip", "c.zip")
        assertEquals("a.zip", running.current!!.name)

        val paused = reactToUpgradeFailure(running, error)

        assertNotNull("the run stops on the failure", paused.pausedError)
        assertTrue(paused.pausedError!!.contains(error))
        assertEquals("the failed item is still the active one", "a.zip", paused.current!!.name)
        assertEquals("nothing may be reported as installed", 0, paused.doneCount)
        assertFalse(paused.items.any { it.status == BatchItemStatus.Done })
        // A paused run never promotes the next item on its own.
        assertEquals(BatchItemStatus.Queued, paused.items[1].status)
        assertEquals(paused, paused.startNext())
    }

    // ------------------------------------------------------------------ the user's two answers

    @Test
    fun skipAndContinueMarksTheFailedItemFailedAndMovesOn() {
        val paused = reactToUpgradeFailure(automaticRun("a.zip", "b.zip", "c.zip"), error)

        val resumed = paused.continueAfterError()

        assertNull("the pause is cleared", resumed.pausedError)
        assertEquals(BatchItemStatus.Failed, resumed.items[0].status)
        assertEquals("the run advances to the next file", "b.zip", resumed.current!!.name)
        assertEquals(0, resumed.doneCount)
        assertEquals(1, resumed.failedCount)
        assertFalse(resumed.isComplete)
    }

    @Test
    fun stopRemainingMarksTheFailedItemFailedAndSkipsTheRest() {
        val paused = reactToUpgradeFailure(automaticRun("a.zip", "b.zip", "c.zip"), error)

        val stopped = paused.stopAfterError()

        assertNull(stopped.pausedError)
        assertEquals(BatchItemStatus.Failed, stopped.items[0].status)
        assertEquals(BatchItemStatus.Skipped, stopped.items[1].status)
        assertEquals(BatchItemStatus.Skipped, stopped.items[2].status)
        assertNull("nothing is left running", stopped.current)
        assertEquals(0, stopped.doneCount)
        assertTrue("the run is over", stopped.isComplete)
    }

    @Test
    fun aSecondFailureInTheSameRunPausesAgainInsteadOfSettlingDone() {
        val first = reactToUpgradeFailure(automaticRun("a.zip", "b.zip"), error)
        val second = reactToUpgradeFailure(first.continueAfterError(), "Extraction failed: bad archive")

        assertNotNull(second.pausedError)
        assertTrue(second.pausedError!!.startsWith("b.zip: "))
        assertEquals(0, second.doneCount)
        assertEquals(BatchItemStatus.Active, second.items[1].status)
    }

    @Test
    fun aManualRunIsUntouchedByThisPathAndKeepsItsModalDialog() {
        // The automatic flag is the whole switch: a manual run still shows "Upgrade failed" and the
        // user decides, so its session is never paused behind their back.
        val manual = BatchInstallSession(
            items = listOf(item("a.zip"), item("b.zip")),
            automatic = false,
            runId = 5L,
        ).startNext()
        assertFalse(manual.automatic)
        assertNull(manual.pausedError)
    }
}
