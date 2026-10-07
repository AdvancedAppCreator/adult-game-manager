package com.example.f95updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device coverage of a managed upgrade that *fails* while an automatic bulk run is driving it.
 *
 * The "Upgrade failed" dialog used to be unconditionally modal, which an unattended automatic run
 * cannot answer — and dismissing it left the flow idle, which is exactly the signal the bulk driver
 * settles an item on, so the item was recorded as **Done** for an upgrade that had failed. A failure
 * is now acknowledged for the flow and turned into a pause carrying the exact error, mirroring what
 * an extraction failure and a cancellation already do.
 *
 * These tests drive the real flow to a real failure and then apply the production reaction to it.
 */
@RunWith(AndroidJUnit4::class)
class BulkManagedUpgradeFailureAndroidTest {

    private lateinit var context: Context
    private lateinit var work: File
    private lateinit var scope: CoroutineScope
    private val createdGameIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "bulk-upgrade-failure-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        ManagedUpgradePendingStore.clear(context)
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        val store = ManagedGameStore(context)
        createdGameIds.forEach { runCatching { store.delete(it) } }
        createdGameIds.clear()
        ManagedUpgradePendingStore.clear(context)
        work.deleteRecursively()
        Unit
    }

    // ------------------------------------------------------------------ helpers

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(timeoutMs: Long = 60_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun session(vararg names: String): BatchInstallSession = BatchInstallSession(
        items = names.map {
            BatchItem(
                path = File(work, it).absolutePath,
                name = it,
                kind = BatchItemKind.Archive,
                decision = BatchInstallDecision.Upgrade("game-1", "Broken Upgrade Game"),
            )
        },
        automatic = true,
        runId = 4242L,
    ).startNext()

    /**
     * Mirrors MainActivity's automatic-bulk reaction to `upgradeFlow.errorMessage`: acknowledge the
     * flow (so it releases the install pipeline) and pause the run with the precise error.
     */
    private fun reactToFailure(
        flow: ManagedArchiveUpgradeFlow,
        run: BatchInstallSession,
    ): BatchInstallSession {
        val message = requireNotNull(flow.errorMessage) { "the flow did not fail" }
        val itemName = run.current?.name
        onMain { flow.acknowledgeError() }
        return run.pause(ManagedUpgradeFailure.bulkMessage(itemName, message))
    }

    /** A real, unreadable archive: the extractor fails on it exactly as it would on a bad download. */
    private fun corruptArchive(name: String): File =
        File(work, name).apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }

    private suspend fun managedRenPyApp(label: String): InstalledApp {
        val root = File(work, label).apply { mkdirs() }
        File(root, "renpy").mkdirs()
        File(root, "game").apply { mkdirs() }
        File(root, "game/script.rpy").writeText("label start:\n    return\n")
        File(root, "Game.sh").writeText("#!/bin/sh\n")
        val game = ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = root.absolutePath,
                storageFolderName = root.name,
                label = label,
                versionName = "1.0",
                defaultRunner = ManagedRunnerKind.JoiPlay,
                runnerBindings = listOf(ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh")),
            ),
        ).also { createdGameIds += it.id }
        return InstalledApp(
            packageName = "managed:${game.id}",
            label = game.label,
            versionName = game.versionName,
            versionCode = 0L,
            source = AppSource.Managed,
            managedGameId = game.id,
            managedDefaultRunner = ManagedRunnerKind.JoiPlay,
            storagePath = game.storagePath,
        )
    }

    // ------------------------------------------------------------------ the failure itself

    @Test
    fun aRealUpgradeFailureDuringAnAutomaticRunPausesItAndTheItemIsNeverDone() = runBlocking {
        val app = managedRenPyApp("Broken Upgrade Game")
        val archive = corruptArchive("Broken Upgrade Game-2.0.zip")
        var run = session("Broken Upgrade Game-2.0.zip", "next.zip", "last.zip")
        val flow = ManagedArchiveUpgradeFlow(context, scope)

        assertTrue(onMain { flow.start(archive, app) }.started)
        assertTrue("the upgrade fails", waitUntil { flow.errorMessage != null })
        val error = requireNotNull(flow.errorMessage)
        assertNull("a failure is not a result", flow.result)
        assertNull("a failure is not a cancellation", flow.cancellationNotice)

        run = reactToFailure(flow, run)

        assertNull("the flow no longer holds the install pipeline", flow.errorMessage)
        assertFalse("...and is free for the next item", flow.busy)
        assertNotNull("the run is paused", run.pausedError)
        assertTrue("the pause carries the exact error", run.pausedError!!.contains(error))
        assertTrue(run.pausedError!!.startsWith("Broken Upgrade Game-2.0.zip: "))
        assertEquals("the failed item is still active", "Broken Upgrade Game-2.0.zip", run.current!!.name)
        assertEquals("nothing may be reported as installed", 0, run.doneCount)
        assertFalse(run.items.any { it.status == BatchItemStatus.Done })
        assertEquals("a paused run never advances on its own", run, run.startNext())

        // Nothing was left behind by the failure itself.
        assertNull(ManagedUpgradePendingStore.load(context))
        assertTrue("the picked archive is never removed by a failure", archive.isFile)
        Unit
    }

    @Test
    fun theUserCanSkipTheFailedItemAndTheRunContinues() = runBlocking {
        val app = managedRenPyApp("Skip After Failure Game")
        val archive = corruptArchive("Skip After Failure Game-2.0.zip")
        var run = session("Skip After Failure Game-2.0.zip", "next.zip", "last.zip")
        val flow = ManagedArchiveUpgradeFlow(context, scope)

        assertTrue(onMain { flow.start(archive, app) }.started)
        assertTrue("the upgrade fails", waitUntil { flow.errorMessage != null })
        run = reactToFailure(flow, run).continueAfterError()

        assertNull(run.pausedError)
        assertEquals(BatchItemStatus.Failed, run.items[0].status)
        assertEquals("the run moves on to the next file", "next.zip", run.current!!.name)
        assertEquals(0, run.doneCount)
        assertEquals(1, run.failedCount)
        // A settled failure releases the pipeline, so the next item can really start.
        assertNull(InstallOperationGuard.refuse(flow.activeOperation, InstallOperationKind.Upgrade))
        Unit
    }

    @Test
    fun theUserCanStopTheRunAndTheRemainingFilesAreSkipped() = runBlocking {
        val app = managedRenPyApp("Stop After Failure Game")
        val archive = corruptArchive("Stop After Failure Game-2.0.zip")
        var run = session("Stop After Failure Game-2.0.zip", "next.zip", "last.zip")
        val flow = ManagedArchiveUpgradeFlow(context, scope)

        assertTrue(onMain { flow.start(archive, app) }.started)
        assertTrue("the upgrade fails", waitUntil { flow.errorMessage != null })
        run = reactToFailure(flow, run).stopAfterError()

        assertNull(run.pausedError)
        assertEquals(BatchItemStatus.Failed, run.items[0].status)
        assertEquals(BatchItemStatus.Skipped, run.items[1].status)
        assertEquals(BatchItemStatus.Skipped, run.items[2].status)
        assertNull("nothing is left running", run.current)
        assertEquals(0, run.doneCount)
        assertTrue("the run is over", run.isComplete)
        Unit
    }
}
