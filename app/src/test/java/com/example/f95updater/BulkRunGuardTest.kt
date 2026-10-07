package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One bulk run at a time, and every write back into a run is scoped by that run's id.
 *
 * The two failures this pins down are real: a second "Bulk install games" while the first run was
 * still going replaced the session outright, and the first run's driver coroutine then settled the
 * *replacement* run's item — which it happily matched, because two runs picked from the same
 * download folder legitimately hold the very same file paths.
 */
class BulkRunGuardTest {

    private fun item(name: String, status: BatchItemStatus = BatchItemStatus.Queued) = BatchItem(
        path = "/storage/emulated/0/Download/$name",
        name = name,
        kind = BatchItemKind.Archive,
        status = status,
    )

    private fun session(runId: Long, vararg names: String): BatchInstallSession =
        BatchInstallSession(items = names.map { item(it) }, runId = runId, automatic = true)

    private fun preflight(runId: Long, scanned: Int, total: Int): BatchInstallPreflight =
        (1..scanned).fold(BatchInstallPreflight(runId = runId, total = total)) { acc, i ->
            acc.withItem(BatchPreflightItem(path = "/dl/$i.zip", name = "$i.zip", kind = BatchItemKind.Archive))
        }

    // ------------------------------------------------------------ refusing a second run

    @Test
    fun anIdleAppAcceptsANewBulkRun() {
        assertNull(BulkRunGuard.refuse(null, null))
        assertNull(BulkRunGuard.ownerOf(null, null))
    }

    @Test
    fun aSecondRunIsRefusedWhileThePreflightIsStillScanning() {
        val reason = BulkRunGuard.refuse(preflight(1L, scanned = 2, total = 9), null)
        assertNotNull(reason)
        assertEquals(
            "AGM is still preparing a bulk install (2 of 9 file(s) scanned). " +
                "Finish or cancel that one before starting another.",
            reason,
        )
    }

    @Test
    fun aSecondRunIsRefusedWhileThePreflightIsWaitingOnAnUpgradeDecision() {
        // Scan finished, but the user has not answered the "does this replace X?" question yet.
        val waiting = preflight(1L, scanned = 3, total = 3).finishScan()
        assertFalse(waiting.isResolved)
        assertNotNull(BulkRunGuard.refuse(waiting, null))
    }

    @Test
    fun aSecondRunIsRefusedWhileTheQueueIsActive() {
        val running = session(1L, "a.zip", "b.zip", "c.zip").startNext()
        assertEquals(
            "A bulk install is still running (item 1 of 3). Cancel it or let it finish " +
                "before starting another.",
            BulkRunGuard.refuse(null, running),
        )
    }

    @Test
    fun aSecondRunIsRefusedWhileTheLastItemIsStillCompleting() {
        // Two of three settled, the third is Active: the run is "completing", not complete.
        val completing = session(1L, "a.zip", "b.zip", "c.zip")
            .startNext().settleActive(BatchItemStatus.Done).startNext()
            .settleActive(BatchItemStatus.Done).startNext()
        assertFalse(completing.isComplete)
        assertNotNull(completing.current)
        assertNotNull(BulkRunGuard.refuse(null, completing))
    }

    @Test
    fun aSecondRunIsAlsoRefusedWhilePausedOnAnError() {
        val paused = session(1L, "a.zip", "b.zip").startNext().pause("Extraction failed.")
        assertFalse(paused.isComplete)
        assertNotNull(BulkRunGuard.refuse(null, paused))
    }

    @Test
    fun aFinishedRunOnlyLeavesItsBannerAndNoLongerBlocksANewRun() {
        var finished = session(1L, "a.zip", "b.zip").startNext()
        finished = finished.settleActive(BatchItemStatus.Done).startNext()
        finished = finished.settleActive(BatchItemStatus.Done).startNext()
        assertTrue(finished.isComplete)
        assertNull(BulkRunGuard.refuse(null, finished))
        assertNull(BulkRunGuard.ownerOf(null, finished))
    }

    @Test
    fun aLiveRunOwnsTheWholeInstallPipelineNotJustTheBulkMenu() {
        val running = session(1L, "a.zip", "b.zip").startNext()
        val owner = BulkRunGuard.ownerOf(null, running)!!
        assertEquals(InstallOperationKind.Bulk, owner.kind)
        // A patch or a single install started underneath a running queue would fight it for the
        // same flows, so those are refused too.
        assertNotNull(InstallOperationGuard.refuse(owner, InstallOperationKind.Patch))
        assertNotNull(InstallOperationGuard.refuse(owner, InstallOperationKind.Extract))
        assertNotNull(InstallOperationGuard.refuse(owner, InstallOperationKind.Upgrade))
    }

    // ------------------------------------------------------------ run-scoped settling

    @Test
    fun aRunOnlySettlesItsOwnActiveItem() {
        val running = session(42L, "a.zip", "b.zip").startNext()
        assertTrue(running.ownsActiveItem(42L, "/storage/emulated/0/Download/a.zip"))
        assertFalse(running.ownsActiveItem(42L, "/storage/emulated/0/Download/b.zip"))
        assertFalse("a different run never owns it", running.ownsActiveItem(43L, "/storage/emulated/0/Download/a.zip"))
    }

    @Test
    fun anOldDriverCannotSettleAReplacementRunThatHasTheSameFilePaths() {
        // Run 1 starts on a.zip and is then cancelled and replaced by run 2 over the same folder.
        val first = session(1L, "a.zip", "b.zip").startNext()
        val activePath = first.current!!.path
        val second = session(2L, "a.zip", "b.zip").startNext()
        assertEquals("both runs hold the identical path", activePath, second.current!!.path)

        // The stale driver of run 1 asks whether it may settle "a.zip". Path alone says yes.
        assertEquals(activePath, second.current!!.path)
        // Run-id scoping says no, so run 2's first item is never skipped past.
        assertFalse(second.ownsActiveItem(1L, activePath))
        assertTrue(second.ownsActiveItem(2L, activePath))

        // Which is exactly what keeps run 2 on item 1 of 2 instead of jumping to item 2.
        val settledByStaleDriver = if (second.ownsActiveItem(1L, activePath)) {
            second.settleActive(BatchItemStatus.Done).startNext()
        } else {
            second
        }
        assertEquals("a.zip", settledByStaleDriver.current!!.name)
        assertEquals(0, settledByStaleDriver.doneCount)
    }

    @Test
    fun anOldDriverCannotSettleAfterItsOwnRunWasCancelled() {
        val run = session(7L, "a.zip", "b.zip").startNext()
        val activePath = run.current!!.path
        val cancelled = run.cancelRemaining().settleActive(BatchItemStatus.Skipped)
        assertFalse(cancelled.ownsActiveItem(7L, activePath))
        assertNull(cancelled.current)
    }

    @Test
    fun theRunIdSurvivesThePreflightToQueueHandoff() {
        val resolved = preflight(1234L, scanned = 2, total = 2).finishScan().chooseMode(automatic = true)
        assertTrue(resolved.isResolved)
        val queue = resolved.toSession()
        assertEquals(1234L, queue.runId)
        assertEquals(1234L, queue.startNext().runId)
        assertTrue(queue.startNext().ownsActiveItem(1234L, "/dl/1.zip"))
    }
}
