package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Unattended" means an automatic bulk run is driving an item *right now* — nothing else.
 *
 * The bug this pins down shipped: every unattended branch in MainActivity asked
 * `st.batch?.automatic == true`, but a finished run's banner stays on screen until the user closes
 * it and `automatic` stays true the whole time. With that banner up, a completely unrelated,
 * user-initiated install silently lost its confirmations: an APK installed itself with no "Install
 * this?" prompt, an archive extracted with no confirmation and no destination question, a managed
 * game picked its own runner, and an extraction/upgrade failure was swallowed into a pause of a run
 * that had already ended (so the error never reached the user at all).
 *
 * These tests exercise the one predicate every branch now shares, through the exact session states
 * the UI can be in, and then re-play each affected flow's gate against it.
 */
class UnattendedBatchTest {

    private fun item(name: String, status: BatchItemStatus = BatchItemStatus.Queued) = BatchItem(
        path = "/storage/emulated/0/Download/$name",
        name = name,
        kind = BatchItemKind.Archive,
        status = status,
    )

    private fun run(automatic: Boolean, vararg names: String): BatchInstallSession =
        BatchInstallSession(items = names.map { item(it) }, runId = 77L, automatic = automatic)

    /** An automatic run that has started its first item: the only unattended state there is. */
    private fun activeRun(): BatchInstallSession = run(true, "a.zip", "b.zip").startNext()

    /** Everything settled. The banner is still on screen because the user has not closed it. */
    private fun completedRun(): BatchInstallSession {
        var session = activeRun()
        while (session.current != null) {
            session = session.settleActive(BatchItemStatus.Done).startNext()
        }
        return session
    }

    // ----------------------------------------------------------------- the predicate itself

    @Test
    fun anAutomaticRunDrivingAnItemIsUnattended() {
        val active = activeRun()
        assertTrue(active.automatic)
        assertEquals("a.zip", active.current?.name)
        assertTrue(active.isUnattendedActive)
        assertTrue(active.isUnattendedRun(77L))
    }

    @Test
    fun aCompletedRunWhoseBannerIsStillOnScreenIsNotUnattended() {
        val done = completedRun()
        // The banner is still there and the session still says automatic — that was the trap.
        assertTrue(done.automatic)
        assertTrue(done.isComplete)
        assertEquals(2, done.doneCount)
        assertNull(done.current)
        assertFalse(done.isUnattendedActive)
        assertFalse(done.isUnattendedRun(77L))
    }

    @Test
    fun anAutomaticRunThatHasNotStartedYetIsNotUnattended() {
        val queued = run(true, "a.zip")
        assertNull(queued.current)
        assertFalse(queued.isUnattendedActive)
    }

    @Test
    fun aPausedRunWithNoActiveItemIsNotUnattended() {
        // "Stop remaining" settles the active item, skips the rest and clears the pause: the run
        // owns nothing afterwards even though `automatic` never changes.
        val stopped = activeRun().pause("Extraction failed.").stopAfterError()
        assertTrue(stopped.automatic)
        assertNull(stopped.pausedError)
        assertNull(stopped.current)
        assertFalse(stopped.isUnattendedActive)
    }

    @Test
    fun aCancelledRunThatSettledItsLastItemIsNotUnattended() {
        val cancelled = activeRun().cancelRemaining().settleActive(BatchItemStatus.Failed).startNext()
        assertNull(cancelled.current)
        assertFalse(cancelled.isUnattendedActive)
    }

    @Test
    fun aPausedRunStillHoldingItsItemStaysUnattended() {
        // The pause dialog is modal and "Skip and continue" resumes the same run, so the run has
        // not handed the pipeline back to the user yet.
        val paused = activeRun().pause("Extraction failed.")
        assertEquals("a.zip", paused.current?.name)
        assertTrue(paused.isUnattendedActive)
        assertTrue(paused.continueAfterError().isUnattendedActive)
    }

    @Test
    fun aReviewModeRunIsNeverUnattended() {
        val review = run(false, "a.zip").startNext()
        assertEquals("a.zip", review.current?.name)
        assertFalse(review.isUnattendedActive)
    }

    @Test
    fun aLaterRunNeverInheritsAnEarlierRunsUnattendedAuthority() {
        val second = BatchInstallSession(items = listOf(item("a.zip")), runId = 78L, automatic = true)
            .startNext()
        assertTrue(second.isUnattendedActive)
        assertFalse("run 77's deferred work must not pause run 78", second.isUnattendedRun(77L))
        assertTrue(second.isUnattendedRun(78L))
    }

    // ------------------------------------------------- the flows the stale banner used to hijack

    /** Mirrors `unattendedBatch()` in MainActivity. */
    private fun unattended(session: BatchInstallSession?): Boolean = session?.isUnattendedActive == true

    private enum class ApkGate { AutoInstall, AskToInstall }

    /** Mirrors the `InstallRouting.PickRoute.InstallApk` branch of `routePickedFile`. */
    private fun apkGate(session: BatchInstallSession?): ApkGate =
        if (unattended(session)) ApkGate.AutoInstall else ApkGate.AskToInstall

    private enum class ExtractGate { AutoExtract, AskToExtract }

    /** Mirrors `startOrConfirmExtraction` / `prepareBucketExtract` / the Kirikiroid staging path. */
    private fun extractGate(session: BatchInstallSession?): ExtractGate =
        if (unattended(session)) ExtractGate.AutoExtract else ExtractGate.AskToExtract

    private enum class DestinationGate { PauseRun, AskForDestination }

    /** Mirrors the "no writable destination" branch of `prepareArchiveExtract`. */
    private fun destinationGate(session: BatchInstallSession?): DestinationGate =
        if (unattended(session)) DestinationGate.PauseRun else DestinationGate.AskForDestination

    private enum class RunnerGate { AutoPickRunner, ShowRunnerDialog }

    /** Mirrors the managed-runner selection branch. */
    private fun runnerGate(session: BatchInstallSession?): RunnerGate =
        if (unattended(session)) RunnerGate.AutoPickRunner else RunnerGate.ShowRunnerDialog

    private enum class ErrorGate { PauseRun, ShowErrorDialog }

    /** Mirrors the extraction-error, upgrade-error and upgrade-cancellation branches. */
    private fun errorGate(session: BatchInstallSession?): ErrorGate =
        if (unattended(session)) ErrorGate.PauseRun else ErrorGate.ShowErrorDialog

    private enum class ApkArchiveGate { AutoPickSingleApk, ShowExtractedFileBrowser }

    /** Mirrors the post-extraction Android branch that auto-installs the only APK it finds. */
    private fun apkArchiveGate(session: BatchInstallSession?): ApkArchiveGate =
        if (unattended(session)) ApkArchiveGate.AutoPickSingleApk else ApkArchiveGate.ShowExtractedFileBrowser

    @Test
    fun anIdleAppConfirmsEveryStandaloneInstallStep() {
        assertEquals(ApkGate.AskToInstall, apkGate(null))
        assertEquals(ExtractGate.AskToExtract, extractGate(null))
        assertEquals(DestinationGate.AskForDestination, destinationGate(null))
        assertEquals(RunnerGate.ShowRunnerDialog, runnerGate(null))
        assertEquals(ErrorGate.ShowErrorDialog, errorGate(null))
        assertEquals(ApkArchiveGate.ShowExtractedFileBrowser, apkArchiveGate(null))
    }

    @Test
    fun aStandaloneApkInstallStartedUnderACompletedRunsBannerStillAsks() {
        val banner = completedRun()
        assertEquals(ApkGate.AskToInstall, apkGate(banner))
    }

    @Test
    fun aStandaloneArchiveInstallStartedUnderACompletedRunsBannerStillAsks() {
        val banner = completedRun()
        assertEquals(ExtractGate.AskToExtract, extractGate(banner))
        assertEquals(DestinationGate.AskForDestination, destinationGate(banner))
        assertEquals(ApkArchiveGate.ShowExtractedFileBrowser, apkArchiveGate(banner))
    }

    @Test
    fun aStandaloneManagedInstallStartedUnderACompletedRunsBannerStillPicksItsRunner() {
        assertEquals(RunnerGate.ShowRunnerDialog, runnerGate(completedRun()))
    }

    @Test
    fun aStandaloneFailureUnderACompletedRunsBannerIsReportedNotSwallowedIntoAPause() {
        // Pausing a run that already finished would hide the error behind a banner nobody is
        // watching, and the pause dialog would then apply to items that were never re-run.
        assertEquals(ErrorGate.ShowErrorDialog, errorGate(completedRun()))
    }

    @Test
    fun everyStandaloneFlowIsUnaffectedByAPausedRunThatOwnsNothing() {
        val stopped = activeRun().pause("Extraction failed.").stopAfterError()
        assertEquals(ApkGate.AskToInstall, apkGate(stopped))
        assertEquals(ExtractGate.AskToExtract, extractGate(stopped))
        assertEquals(DestinationGate.AskForDestination, destinationGate(stopped))
        assertEquals(RunnerGate.ShowRunnerDialog, runnerGate(stopped))
        assertEquals(ErrorGate.ShowErrorDialog, errorGate(stopped))
        assertEquals(ApkArchiveGate.ShowExtractedFileBrowser, apkArchiveGate(stopped))
    }

    @Test
    fun anActiveAutomaticRunKeepsEveryUnattendedBehaviour() {
        val active = activeRun()
        assertEquals(ApkGate.AutoInstall, apkGate(active))
        assertEquals(ExtractGate.AutoExtract, extractGate(active))
        assertEquals(DestinationGate.PauseRun, destinationGate(active))
        assertEquals(RunnerGate.AutoPickRunner, runnerGate(active))
        assertEquals(ErrorGate.PauseRun, errorGate(active))
        assertEquals(ApkArchiveGate.AutoPickSingleApk, apkArchiveGate(active))
    }

    @Test
    fun aReviewModeRunConfirmsEveryStepExactlyLikeAStandaloneInstall() {
        val review = run(false, "a.zip").startNext()
        assertEquals(ApkGate.AskToInstall, apkGate(review))
        assertEquals(ExtractGate.AskToExtract, extractGate(review))
        assertEquals(DestinationGate.AskForDestination, destinationGate(review))
        assertEquals(RunnerGate.ShowRunnerDialog, runnerGate(review))
        assertEquals(ErrorGate.ShowErrorDialog, errorGate(review))
    }

    // -------------------------------------------------------------- active-run controls still work

    /** Mirrors `pauseAutomaticBatch`: pauses only a run that owns an item, else reports normally. */
    private fun pauseOrReport(session: BatchInstallSession?, message: String): Pair<BatchInstallSession?, String?> {
        val batch = session?.takeIf { it.isUnattendedActive } ?: return session to message
        return batch.pause(message) to null
    }

    @Test
    fun pausingAnActiveRunStopsItAndShowsNoSnackbar() {
        val (session, snackbar) = pauseOrReport(activeRun(), "No writable destination.")
        assertEquals("No writable destination.", session?.pausedError)
        assertNull(snackbar)
    }

    @Test
    fun theSameFailureUnderACompletedBannerBecomesAnOrdinarySnackbar() {
        val banner = completedRun()
        val (session, snackbar) = pauseOrReport(banner, "No writable destination.")
        assertNull("the finished run must not be re-opened", session?.pausedError)
        assertEquals(banner, session)
        assertEquals("No writable destination.", snackbar)
    }

    @Test
    fun cancelRemainingAndCloseStillControlAnActiveRun() {
        val active = activeRun()
        val cancelled = active.cancelRemaining()
        assertEquals("a.zip", cancelled.current?.name)
        assertEquals(1, cancelled.skippedCount)
        // The still-running item finishes naturally, and only then is the run complete.
        val finished = cancelled.settleActive(BatchItemStatus.Done).startNext()
        assertTrue(finished.isComplete)
        assertFalse(finished.isUnattendedActive)
    }

    @Test
    fun theProgressOverlayStopsTrackingARunAsSoonAsItCompletes() {
        // MainActivity keys the minimized-progress owner off a run that is not complete; the
        // predicate and that key must agree, or a finished banner would keep owning the overlay.
        val active = activeRun()
        assertEquals(77L, active.takeUnless { it.isComplete }?.runId)
        assertNull(completedRun().takeUnless { it.isComplete }?.runId)
    }
}
