package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure model coverage for the bulk-install preflight: every "does this replace an installed game?"
 * question is answered before the queue starts, and the queue then runs exactly those answers.
 */
class BatchInstallPreflightTest {

    private fun target(id: String, label: String) = BatchUpgradeTarget(
        managedGameId = id,
        label = label,
        storagePath = "/storage/emulated/0/Games/$label",
        reason = "The archive file name reads \u201C$label\u201D.",
    )

    private fun archive(name: String, targets: List<BatchUpgradeTarget> = emptyList()) =
        BatchPreflightItem(
            path = "/dl/$name",
            name = name,
            kind = BatchItemKind.Archive,
            targets = targets,
        )

    private fun scanned(vararg items: BatchPreflightItem): BatchInstallPreflight =
        items.fold(BatchInstallPreflight(runId = 7L, total = items.size)) { acc, item -> acc.withItem(item) }
            .finishScan()

    @Test
    fun classificationHappensBeforeExecutionForMissingAndUnsupportedFiles() {
        val missing = BatchInstall.preflightItem("gone.zip", "/dl/gone.zip", exists = false)
        assertFalse(missing.installable)
        assertEquals("File no longer exists", missing.note)

        val split = BatchInstall.preflightItem("bundle.xapk", "/dl/bundle.xapk", exists = true)
        assertFalse(split.installable)
        assertEquals(BatchItemKind.Unsupported, split.kind)
        assertTrue(split.note!!.contains("Split APK"))

        val ok = BatchInstall.preflightItem("game.zip", "/dl/game.zip", exists = true)
        assertTrue(ok.installable)
        assertEquals(BatchItemKind.Archive, ok.kind)
        assertNull(ok.note)
    }

    @Test
    fun scanningBlocksTheRunUntilEveryDecisionIsMade() {
        var preflight = BatchInstallPreflight(runId = 1L, total = 2)
        assertFalse(preflight.isResolved)
        preflight = preflight.withItem(archive("a.zip", listOf(target("id-a", "Alpha"))))
        preflight = preflight.withItem(archive("b.zip"))
        assertFalse("still scanning", preflight.isResolved)

        preflight = preflight.finishScan()
        assertFalse("mode not chosen", preflight.isResolved)

        preflight = preflight.chooseMode(automatic = true)
        assertFalse("decision outstanding", preflight.isResolved)
        assertEquals("a.zip", preflight.currentDecision?.name)

        preflight = preflight.decide("/dl/a.zip", BatchInstallDecision.InstallAsNew)
        assertTrue(preflight.isResolved)
        assertNull(preflight.currentDecision)
    }

    @Test
    fun onlyArchivesWithProvenTargetsNeedADecision() {
        val preflight = scanned(
            archive("a.zip", listOf(target("id-a", "Alpha"))),
            archive("b.zip"),
            BatchPreflightItem("/dl/c.apk", "c.apk", BatchItemKind.Apk),
            BatchInstall.preflightItem("gone.zip", "/dl/gone.zip", exists = false),
        ).chooseMode(automatic = false)

        assertEquals(listOf("a.zip"), preflight.decisionItems.map { it.name })
        assertEquals(4, preflight.total)
        assertEquals(3, preflight.installableCount)
    }

    @Test
    fun decisionsSurviveIntoTheExecutedQueue() {
        val preflight = scanned(
            archive("alpha.zip", listOf(target("id-a", "Alpha"))),
            archive("beta.zip", listOf(target("id-b", "Beta"))),
            archive("gamma.zip", listOf(target("id-c", "Gamma"))),
            archive("plain.zip"),
        )
            .chooseMode(automatic = true)
            .decide("/dl/alpha.zip", BatchInstallDecision.Upgrade("id-a", "Alpha"))
            .decide("/dl/beta.zip", BatchInstallDecision.InstallAsNew)
            .decide("/dl/gamma.zip", BatchInstallDecision.Skip)

        assertTrue(preflight.isResolved)
        val session = preflight.toSession()
        assertTrue(session.automatic)
        assertEquals(7L, session.runId)
        assertEquals(
            BatchInstallDecision.Upgrade("id-a", "Alpha"),
            session.items[0].decision,
        )
        assertEquals(BatchInstallDecision.InstallAsNew, session.items[1].decision)
        assertEquals(BatchItemStatus.Skipped, session.items[2].status)
        assertNull(session.items[3].decision)
        assertEquals(BatchItemStatus.Queued, session.items[3].status)
    }

    @Test
    fun unsupportedAndMissingFilesAreSettledBeforeTheQueueRuns() {
        val session = scanned(
            BatchInstall.preflightItem("gone.zip", "/dl/gone.zip", exists = false),
            BatchInstall.preflightItem("bundle.xapk", "/dl/bundle.xapk", exists = true),
            archive("plain.zip"),
        ).chooseMode(automatic = true).toSession()

        assertEquals(BatchItemStatus.Skipped, session.items[0].status)
        assertEquals(BatchItemStatus.Skipped, session.items[1].status)
        assertEquals(BatchItemStatus.Queued, session.items[2].status)
        assertEquals("plain.zip", session.startNext().current?.name)
    }

    @Test
    fun theQueueAdvancesThroughEveryDecisionWithoutAskingAgain() {
        var session = scanned(
            archive("alpha.zip", listOf(target("id-a", "Alpha"))),
            archive("beta.zip", listOf(target("id-b", "Beta"))),
            archive("gamma.zip", listOf(target("id-c", "Gamma"))),
        )
            .chooseMode(automatic = true)
            .decide("/dl/alpha.zip", BatchInstallDecision.Upgrade("id-a", "Alpha"))
            .decide("/dl/beta.zip", BatchInstallDecision.Skip)
            .decide("/dl/gamma.zip", BatchInstallDecision.InstallAsNew)
            .toSession()
            .startNext()

        assertEquals("alpha.zip", session.current?.name)
        assertEquals(BatchInstallDecision.Upgrade("id-a", "Alpha"), session.current?.decision)

        session = session.settleActive(BatchItemStatus.Done).startNext()
        // beta was settled as Skipped in the preflight, so the queue goes straight to gamma.
        assertEquals("gamma.zip", session.current?.name)
        assertEquals(BatchInstallDecision.InstallAsNew, session.current?.decision)

        session = session.settleActive(BatchItemStatus.Done).startNext()
        assertTrue(session.isComplete)
        assertEquals(2, session.doneCount)
        assertEquals(1, session.skippedCount)
    }

    @Test
    fun dismissingEveryRemainingDecisionSkipsThemInsteadOfStalling() {
        val preflight = scanned(
            archive("alpha.zip", listOf(target("id-a", "Alpha"))),
            archive("beta.zip", listOf(target("id-b", "Beta"))),
        ).chooseMode(automatic = false).skipRemaining()

        assertTrue(preflight.isResolved)
        val session = preflight.toSession()
        assertTrue(session.items.all { it.status == BatchItemStatus.Skipped })
        assertTrue(session.startNext().isComplete)
    }

    @Test
    fun cancellingRemainingLeavesDecisionsIntactForSettledItems() {
        var session = scanned(
            archive("alpha.zip", listOf(target("id-a", "Alpha"))),
            archive("beta.zip"),
            archive("gamma.zip"),
        )
            .chooseMode(automatic = true)
            .decide("/dl/alpha.zip", BatchInstallDecision.Upgrade("id-a", "Alpha"))
            .toSession()
            .startNext()

        session = session.cancelRemaining()
        assertEquals("alpha.zip", session.current?.name)
        assertEquals(BatchItemStatus.Skipped, session.items[1].status)
        assertEquals(BatchItemStatus.Skipped, session.items[2].status)
        session = session.settleActive(BatchItemStatus.Done).startNext()
        assertTrue(session.isComplete)
    }

    @Test
    fun anErrorPauseStillOffersSkipAndStopAfterAPreflightRun() {
        val paused = scanned(archive("alpha.zip"), archive("beta.zip"))
            .chooseMode(automatic = true)
            .toSession()
            .startNext()
            .pause("no destination configured")

        assertEquals("no destination configured", paused.pausedError)
        assertEquals("beta.zip", paused.continueAfterError().current?.name)
        assertTrue(paused.stopAfterError().isComplete)
    }
}
