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
import java.util.Collections
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device coverage of the managed-upgrade cancellation boundary.
 *
 * `finishExtraction` used to keep `cancellationAllowed = true` across the discovery, the binding
 * plan and the save migration. None of those suspend, so a cancel accepted there was only ever
 * *observed* later: either inside the `NonCancellable` commit (the upgrade committed anyway, after
 * the user had asked AGM to stop) or inside the Winlator capability round-trip (leaving the new
 * folder and the recovery journal behind for good).
 *
 * These tests drive the real flow and press Cancel at exact steps through the flow's step seam, so
 * they assert the boundary itself rather than a race.
 */
@RunWith(AndroidJUnit4::class)
class ManagedUpgradeCancellationAndroidTest {

    private lateinit var context: Context
    private lateinit var work: File
    private lateinit var scope: CoroutineScope
    private val createdGameIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "upgrade-cancel-${System.nanoTime()}").apply {
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

    private fun waitUntil(timeoutMs: Long = 120_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** A Ren'Py-shaped upgrade archive; [assets] pads it so the extraction is observably in flight. */
    private fun renPyUpgradeArchive(name: String, folder: String, assets: Int = 2): File {
        val file = File(work, name)
        val random = Random(name.hashCode().toLong())
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            mapOf(
                "$folder/game/script.rpy" to "label start:\n    return\n",
                "$folder/renpy/__init__.py" to "# renpy\n",
                "$folder/Game.sh" to "#!/bin/sh\nexec ./Game.py\n",
                "$folder/Game.py" to "# launcher\n",
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            repeat(assets) { index ->
                zip.putNextEntry(ZipEntry("$folder/game/asset$index.rpa"))
                val block = ByteArray(64 * 1024)
                var written = 0
                while (written < 512 * 1024) {
                    random.nextBytes(block)
                    zip.write(block)
                    written += block.size
                }
                zip.closeEntry()
            }
        }
        return file
    }

    /** A Winlator-shaped upgrade archive: the same relative .exe the installed game points at. */
    private fun winlatorUpgradeArchive(name: String, folder: String): File {
        val file = File(work, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            mapOf(
                "$folder/Game.exe" to "MZ new executable\n",
                "$folder/data/readme.txt" to "v2\n",
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun installedApp(game: ManagedGame, runner: ManagedRunnerKind): InstalledApp = InstalledApp(
        packageName = "managed:${game.id}",
        label = game.label,
        versionName = game.versionName,
        versionCode = 0L,
        source = AppSource.Managed,
        managedGameId = game.id,
        managedDefaultRunner = runner,
        storagePath = game.storagePath,
    )

    private suspend fun managedRenPyApp(label: String): Pair<ManagedGame, InstalledApp> {
        val root = File(work, label).apply { mkdirs() }
        File(root, "renpy").mkdirs()
        File(root, "game").apply { mkdirs() }.let {
            File(it, "script.rpy").writeText("label start:\n    return\n")
            // A save the migration would have to copy, so "nothing changed" is checkable.
            File(it, "saves").apply { mkdirs() }
            File(File(it, "saves"), "1-1-LT1.save").writeText("old save\n")
        }
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
        return game to installedApp(game, ManagedRunnerKind.JoiPlay)
    }

    private suspend fun managedWinlatorApp(label: String): Pair<ManagedGame, InstalledApp> {
        val root = File(work, label).apply { mkdirs() }
        File(root, "Game.exe").writeText("MZ old executable\n")
        File(root, "data").mkdirs()
        val game = ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = root.absolutePath,
                storageFolderName = root.name,
                label = label,
                versionName = "1.0",
                defaultRunner = ManagedRunnerKind.Winlator,
                runnerBindings = listOf(
                    ManagedRunnerBinding.Winlator(
                        managedId = "winlator-cancel-test",
                        executablePath = File(root, "Game.exe").absolutePath,
                    ),
                ),
            ),
        ).also { createdGameIds += it.id }
        return game to installedApp(game, ManagedRunnerKind.Winlator)
    }

    /** What the upgrade had already put on disk at the exact moment Cancel was pressed. */
    private class CancelProbe {
        val seen = mutableListOf<Pair<ManagedUpgradeStep, Boolean>>()
        var newRootExisted: Boolean? = null
        var journalExisted: Boolean? = null
    }

    /** Runs an upgrade that presses Cancel exactly when [at] starts, and waits for it to settle. */
    private fun upgradeCancellingAt(
        at: ManagedUpgradeStep,
        archive: File,
        app: InstalledApp,
        newRoot: File,
        probe: CancelProbe = CancelProbe(),
    ): ManagedArchiveUpgradeFlow {
        val flow = ManagedArchiveUpgradeFlow(context, scope)
        flow.stepListener = { step ->
            probe.seen += step to flow.cancellationAllowed
            if (step == at) {
                probe.newRootExisted = newRoot.isDirectory
                probe.journalExisted = ManagedUpgradePendingStore.load(context) != null
                flow.cancelInProgress()
            }
        }
        assertTrue(onMain { flow.start(archive, app) }.started)
        return flow
    }

    private fun settled(flow: ManagedArchiveUpgradeFlow): Boolean =
        flow.cancellationNotice != null || flow.errorMessage != null || flow.result != null

    // ------------------------------------------------------------------ before the boundary

    @Test
    fun cancelWhileInspectingTheExtractedFolderRemovesItAndItsJournal() = runBlocking {
        val (game, app) = managedRenPyApp("Cancel Inspecting Game")
        val archive = renPyUpgradeArchive("Cancel Inspecting Game-2.0.zip", "Cancel Inspecting Game-2.0")
        val newRoot = File(work, "Cancel Inspecting Game-2.0")
        val probe = CancelProbe()
        val flow = upgradeCancellingAt(ManagedUpgradeStep.Inspecting, archive, app, newRoot, probe)

        assertTrue("the cancellation settles", waitUntil(60_000) { settled(flow) })
        assertNull("a cancel is not a failure: ${flow.errorMessage}", flow.errorMessage)
        assertNull("a cancel never produces a result", flow.result)
        assertNotNull("the cancellation is surfaced", flow.cancellationNotice)
        assertTrue(flow.cancellationNotice!!.contains(game.label))

        assertEquals("the archive really had been extracted", true, probe.newRootExisted)
        assertEquals("and journalled", true, probe.journalExisted)
        assertNull("the recovery record is cleared", ManagedUpgradePendingStore.load(context))
        assertFalse("the extracted folder is deleted", newRoot.exists())
        assertTrue("the source archive is kept", archive.isFile)
        assertEquals(
            "the library still points at the installed folder",
            game.storagePath,
            ManagedGameStore(context).find(game.id)?.storagePath,
        )
        assertTrue(
            "the installed save is untouched",
            File(File(game.storagePath), "game/saves/1-1-LT1.save").readText().contains("old save"),
        )
        Unit
    }

    @Test
    fun cancelAtTheLastCancellableStepClearsTheJournalItJustWrote() = runBlocking {
        val (game, app) = managedRenPyApp("Cancel Journaling Game")
        val archive = renPyUpgradeArchive("Cancel Journaling Game-2.0.zip", "Cancel Journaling Game-2.0")
        val newRoot = File(work, "Cancel Journaling Game-2.0")
        val probe = CancelProbe()
        val flow = upgradeCancellingAt(ManagedUpgradeStep.Journaling, archive, app, newRoot, probe)

        assertTrue("the cancellation settles", waitUntil(60_000) { settled(flow) })
        assertNull("a cancel is not a failure: ${flow.errorMessage}", flow.errorMessage)
        assertNull(flow.result)
        assertNotNull(flow.cancellationNotice)

        assertEquals("the archive really had been extracted", true, probe.newRootExisted)
        assertEquals("and journalled", true, probe.journalExisted)
        assertNull("the journal written a moment earlier is cleared", ManagedUpgradePendingStore.load(context))
        assertFalse("the extracted folder is deleted", newRoot.exists())
        assertTrue("the source archive is kept", archive.isFile)
        assertEquals(
            game.storagePath,
            ManagedGameStore(context).find(game.id)?.storagePath,
        )
        assertFalse(
            "the save migration never ran",
            probe.seen.any { it.first == ManagedUpgradeStep.MigratingSaves },
        )
        assertTrue("Cancel was still offered at the step it was pressed", probe.seen.last().second)

        // The cancellation still owns the pipeline until the user acknowledges it, exactly like an
        // error does, so a bulk run has something to settle on and cannot race a second upgrade in.
        assertTrue(flow.busy)
        assertFalse(flow.inProgress)
        assertNotNull(onMain { flow.start(archive, app) }.refusalOrNull)
        onMain { flow.acknowledgeCancellation() }
        assertFalse(flow.busy)
        Unit
    }

    @Test
    fun cancelBeforeTheBoundaryNeverReachesTheWinlatorCapabilityRoundTrip() = runBlocking {
        val (game, app) = managedWinlatorApp("Cancel Winlator Game")
        val archive = winlatorUpgradeArchive("Cancel Winlator Game-2.0.zip", "Cancel Winlator Game-2.0")
        val newRoot = File(work, "Cancel Winlator Game-2.0")
        val probe = CancelProbe()
        val flow = upgradeCancellingAt(ManagedUpgradeStep.Journaling, archive, app, newRoot, probe)

        assertTrue("the cancellation settles", waitUntil(60_000) { settled(flow) })
        // Winlator is not installed on the test device: had the cancel been swallowed until the
        // capability check suspended, this would be that check's error and the journal would still
        // be on disk pointing at an orphaned folder.
        assertNull("the Winlator capability check was never reached: ${flow.errorMessage}", flow.errorMessage)
        assertNull("no Winlator round-trip was requested", flow.winlatorRequest)
        assertNull(flow.result)
        assertNotNull(flow.cancellationNotice)
        assertFalse(probe.seen.any { it.first == ManagedUpgradeStep.RepointingWinlator })

        assertEquals("the Winlator upgrade really had been extracted", true, probe.newRootExisted)
        assertEquals("and journalled", true, probe.journalExisted)
        assertNull("no stale recovery record", ManagedUpgradePendingStore.load(context))
        assertFalse("no orphaned folder", newRoot.exists())
        assertTrue("the source archive is kept", archive.isFile)
        val live = ManagedGameStore(context).find(game.id)
        assertEquals("the installed folder is untouched", game.storagePath, live?.storagePath)
        assertEquals(
            "the Winlator binding still points at the original executable",
            File(File(game.storagePath), "Game.exe").absolutePath,
            (live?.runnerBindings?.firstOrNull { it.kind == ManagedRunnerKind.Winlator }
                as? ManagedRunnerBinding.Winlator)?.executablePath,
        )
        Unit
    }

    // ------------------------------------------------------------------ after the boundary

    @Test
    fun cancelDuringTheSaveMigrationIsRefusedAndTheUpgradeCompletesConsistently() = runBlocking {
        val (game, app) = managedRenPyApp("Committed Upgrade Game")
        val archive = renPyUpgradeArchive("Committed Upgrade Game-2.0.zip", "Committed Upgrade Game-2.0")
        val seen = Collections.synchronizedList(mutableListOf<Pair<ManagedUpgradeStep, Boolean>>())

        val flow = ManagedArchiveUpgradeFlow(context, scope)
        flow.stepListener = { step ->
            seen += step to flow.cancellationAllowed
            if (ManagedUpgradeCancellation.crossesBoundary(step)) {
                // Pressing Cancel past the boundary must be impossible, not merely ignored later.
                flow.cancelInProgress()
                seen += step to flow.cancellationAllowed
            }
        }
        assertTrue(onMain { flow.start(archive, app) }.started)
        assertTrue("the upgrade settles", waitUntil(120_000) { settled(flow) })

        assertNull("the refused cancel must not break the upgrade: ${flow.errorMessage}", flow.errorMessage)
        assertNull("a refused cancel is not surfaced as a cancellation", flow.cancellationNotice)
        val result = flow.result
        assertNotNull("the upgrade completes", result)
        assertTrue(result!!.newFolder.endsWith("Committed Upgrade Game-2.0"))
        assertNull("a committed upgrade leaves no pending record", ManagedUpgradePendingStore.load(context))
        assertEquals(
            "the library points at the upgraded folder",
            result.newFolder,
            ManagedGameStore(context).find(game.id)?.storagePath,
        )
        assertTrue("the upgraded folder exists", File(result.newFolder).isDirectory)
        assertTrue("the saves were migrated", result.saveItemsCopied > 0)

        val boundary = seen.filter { ManagedUpgradeCancellation.crossesBoundary(it.first) }
        assertTrue("the boundary steps ran", boundary.isNotEmpty())
        assertTrue("Cancel is gone from every step past the boundary", boundary.none { it.second })
        assertTrue(
            "Cancel was still offered before the boundary",
            seen.filter { ManagedUpgradeCancellation.cancellableAt(it.first) }.all { it.second },
        )
        onMain { flow.acknowledgeResult() }
        Unit
    }

    @Test
    fun theMinimizedCardLosesItsCancelWhenTheBoundaryIsCrossed() = runBlocking {
        val (_, app) = managedRenPyApp("Minimized Boundary Game")
        val archive = renPyUpgradeArchive("Minimized Boundary Game-2.0.zip", "Minimized Boundary Game-2.0")
        val cards = Collections.synchronizedList(mutableListOf<Pair<ManagedUpgradeStep, Boolean>>())

        val flow = ManagedArchiveUpgradeFlow(context, scope)
        flow.stepListener = { step ->
            // Exactly how MainActivity builds the compact card while the dialog is minimized.
            val card = MinimizedProgress(
                kind = ProgressOperationKind.Upgrade,
                archiveName = flow.archiveName ?: "archive",
                phase = flow.phase,
                progress = flow.progress,
                cancelEnabled = flow.cancellationAllowed,
            )
            cards += step to card.cancelEnabled
        }
        assertTrue(onMain { flow.start(archive, app) }.started)
        assertTrue(waitUntil(120_000) { settled(flow) })
        assertNull(flow.errorMessage)
        assertNotNull(flow.result)

        val snapshot = cards.toList()
        assertTrue(
            "the card offers Cancel for every cancellable step",
            snapshot.filter { ManagedUpgradeCancellation.cancellableAt(it.first) }.all { it.second },
        )
        assertTrue(
            "and drops it for every committed step",
            snapshot.filter { ManagedUpgradeCancellation.crossesBoundary(it.first) }.none { it.second },
        )
        // The transition happens once and never flips back while the upgrade is running.
        val firstDisabled = snapshot.indexOfFirst { !it.second }
        assertTrue("the boundary is reached", firstDisabled >= 0)
        assertEquals(
            ManagedUpgradeCancellation.boundaryStep,
            snapshot[firstDisabled].first,
        )
        assertTrue(snapshot.drop(firstDisabled).none { it.second })
        onMain { flow.acknowledgeResult() }
        Unit
    }

    // ------------------------------------------------------------------ journal ownership

    @Test
    fun aSubsequentUpgradeCannotOverwriteAnAbandonedJournal() = runBlocking {
        val (game, app) = managedRenPyApp("Abandoned Journal Game")
        val archive = renPyUpgradeArchive("Abandoned Journal Game-2.0.zip", "Abandoned Journal Game-2.0")
        val orphan = File(work, "abandoned-upgrade-folder").apply { mkdirs() }
        val abandoned = ManagedUpgradePendingState(
            phase = ManagedUpgradePhase.WinlatorRollbackRequested,
            managedGameId = game.id,
            label = game.label,
            oldRootPath = game.storagePath,
            oldCanonicalPath = game.canonicalPath,
            newRootPath = orphan.absolutePath,
            newCanonicalPath = canonicalManagedGamePath(orphan.absolutePath),
            sourceArchivePath = archive.absolutePath,
            originalGame = game,
            replacementGame = game,
        )
        ManagedUpgradePendingStore.save(context, abandoned)

        val refused = onMain { ManagedArchiveUpgradeFlow(context, scope).start(archive, app) }
        val reason = refused.refusalOrNull
        assertNotNull("an upgrade over an unresolved journal must be refused", reason)
        assertTrue(reason!!.contains(game.label))

        val still = ManagedUpgradePendingStore.load(context)
        assertNotNull("the abandoned record survives the refusal", still)
        assertEquals(orphan.absolutePath, still!!.newRootPath)
        assertEquals(ManagedUpgradePhase.WinlatorRollbackRequested, still.phase)
        assertTrue("the abandoned folder is left for recovery", orphan.isDirectory)
        assertFalse("nothing was extracted", File(work, "Abandoned Journal Game-2.0").exists())
        Unit
    }

    @Test
    fun aCancelledUpgradeReleasesThePipelineForTheNextOne() = runBlocking {
        val (game, app) = managedRenPyApp("Retry After Cancel Game")
        val archive = renPyUpgradeArchive("Retry After Cancel Game-2.0.zip", "Retry After Cancel Game-2.0")
        val cancelled = upgradeCancellingAt(
            ManagedUpgradeStep.Journaling,
            archive,
            app,
            File(work, "Retry After Cancel Game-2.0"),
        )
        assertTrue(waitUntil(60_000) { settled(cancelled) })
        assertNotNull(cancelled.cancellationNotice)
        onMain { cancelled.acknowledgeCancellation() }
        assertNull("the cancelled upgrade left no journal", ManagedUpgradePendingStore.load(context))

        // The retry is a brand new generation and must be able to write its own journal.
        val retry = ManagedArchiveUpgradeFlow(context, scope)
        assertTrue(onMain { retry.start(archive, app) }.started)
        assertTrue(waitUntil(120_000) { settled(retry) })
        assertNull("the retry succeeds: ${retry.errorMessage}", retry.errorMessage)
        assertNotNull(retry.result)
        assertEquals(
            retry.result!!.newFolder,
            ManagedGameStore(context).find(game.id)?.storagePath,
        )
        assertNull(ManagedUpgradePendingStore.load(context))
        onMain { retry.acknowledgeResult() }
        Unit
    }
}
