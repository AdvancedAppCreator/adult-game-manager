package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchInstallTest {
    private fun sessionOf(namesAndPaths: List<Pair<String, String>>) = BatchInstallSession(
        items = namesAndPaths.map { (name, path) ->
            BatchItem(path = path, name = name, kind = BatchInstall.classify(name))
        },
    )

    @Test
    fun classifyMapsFileNamesToPlatformKinds() {
        assertEquals(BatchItemKind.Apk, BatchInstall.classify("game.apk"))
        assertEquals(BatchItemKind.Winlator, BatchInstall.classify("Setup.exe"))
        assertEquals(BatchItemKind.JoiPlay, BatchInstall.classify("start.sh"))
        assertEquals(BatchItemKind.JoiPlay, BatchInstall.classify("index.html"))
        assertEquals(BatchItemKind.Archive, BatchInstall.classify("release.7z"))
        assertEquals(BatchItemKind.Archive, BatchInstall.classify("GAME.RAR"))
        assertEquals(BatchItemKind.Unsupported, BatchInstall.classify("notes.txt"))
        // Split bundles are routed Unsupported by InstallRouting.
        assertEquals(BatchItemKind.Unsupported, BatchInstall.classify("bundle.xapk"))
    }

    @Test
    fun sessionStartsWithEverythingQueuedAndNoActive() {
        val session = sessionOf(
            listOf("a.apk" to "/x/a.apk", "b.exe" to "/x/b.exe"),
        )
        assertEquals(2, session.total)
        assertEquals(-1, session.activeIndex)
        assertTrue(session.items.all { it.status == BatchItemStatus.Queued })
        assertFalse(session.isComplete)
        assertEquals(null, session.current)
    }

    @Test
    fun startNextActivatesItemsInOrder() {
        var session = sessionOf(
            listOf("a.apk" to "/x/a.apk", "b.exe" to "/x/b.exe"),
        ).startNext()
        assertEquals(0, session.activeIndex)
        assertEquals("a.apk", session.current?.name)

        session = session.settleActive(BatchItemStatus.Done).startNext()
        assertEquals(1, session.activeIndex)
        assertEquals("b.exe", session.current?.name)
        assertEquals(1, session.doneCount)

        session = session.settleActive(BatchItemStatus.Failed).startNext()
        assertEquals(-1, session.activeIndex)
        assertTrue(session.isComplete)
        assertEquals(1, session.doneCount)
        assertEquals(1, session.failedCount)
        assertEquals(null, session.current)
    }

    @Test
    fun settleActiveRejectsNonTerminalStatus() {
        val session = sessionOf(listOf("a.apk" to "/x/a.apk")).startNext()
        runCatching { session.settleActive(BatchItemStatus.Active) }
            .let { assertTrue(it.isFailure) }
    }

    @Test
    fun cancelRemainingSkipsQueuedButLetsActiveFinish() {
        val session = sessionOf(
            listOf("a.apk" to "/x/a.apk", "b.exe" to "/x/b.exe", "c.zip" to "/x/c.zip"),
        ).startNext().settleActive(BatchItemStatus.Done).startNext()
        // a=Done, b=Active, c=Queued
        val cancelled = session.cancelRemaining()
        assertEquals(BatchItemStatus.Done, cancelled.items[0].status)
        assertEquals(BatchItemStatus.Active, cancelled.items[1].status)
        assertEquals(BatchItemStatus.Skipped, cancelled.items[2].status)
        assertFalse(cancelled.isComplete)
        assertEquals(1, cancelled.activeIndex)
        // Once the active item settles, the run completes (no queued items remain).
        val finished = cancelled.settleActive(BatchItemStatus.Done).startNext()
        assertTrue(finished.isComplete)
        assertEquals(-1, finished.activeIndex)
    }

    @Test
    fun automaticSessionPausesUntilUserChoosesHowToContinue() {
        val paused = sessionOf(
            listOf("a.apk" to "/x/a.apk", "b.apk" to "/x/b.apk"),
        ).copy(automatic = true).startNext().pause("failed")

        assertEquals("a.apk", paused.current?.name)
        assertEquals("failed", paused.pausedError)
        assertEquals("b.apk", paused.continueAfterError().current?.name)
        assertTrue(paused.stopAfterError().isComplete)
    }
}
