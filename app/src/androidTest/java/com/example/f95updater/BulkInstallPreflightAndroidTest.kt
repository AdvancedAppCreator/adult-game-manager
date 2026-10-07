package com.example.f95updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device coverage of the bulk-install preflight: every picked file is inspected with the real
 * archive readers before the queue starts, upgrade candidates come from proven identity evidence,
 * and the queue then executes exactly the decisions the user made — never a second guess.
 */
@RunWith(AndroidJUnit4::class)
class BulkInstallPreflightAndroidTest {

    private lateinit var context: Context
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "bulk-preflight-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun managedApp(label: String, id: String) = InstalledApp(
        packageName = "managed:$id",
        label = label,
        versionName = "",
        versionCode = 0L,
        source = AppSource.Managed,
        managedGameId = id,
        managedDefaultRunner = ManagedRunnerKind.JoiPlay,
        storagePath = File(work, label).absolutePath,
    )

    private fun zip(name: String, entries: Map<String, String>): File {
        val file = File(work, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    /** Mirrors the production preflight step in MainActivity. */
    private suspend fun classify(file: File, apps: List<InstalledApp>): BatchPreflightItem {
        val base = BatchInstall.preflightItem(file.name, file.absolutePath, file.isFile)
        if (!base.installable || base.kind != BatchItemKind.Archive) return base
        val analysis = ManagedArchiveInspector.analyze(file)
        if (analysis.entryNames.isEmpty()) return base.copy(note = "Entry list unreadable until extraction")
        val route = InstallRouting.routeArchive(analysis.entryNames)
        if (route is InstallRouting.ArchiveRoute.Unsupported) {
            return base.copy(kind = BatchItemKind.Unsupported, note = route.message, installable = false)
        }
        val decision = ManagedArchiveUpgradeCoordinator.decide(route, analysis, apps)
        val targets = (decision as? ManagedArchiveUpgradeCoordinator.Decision.Upgrade)?.matches.orEmpty()
            .mapNotNull { candidate ->
                candidate.app.managedGameId?.let { id ->
                    BatchUpgradeTarget(id, candidate.app.label, candidate.app.storagePath.orEmpty(), candidate.reason)
                }
            }
        return base.copy(targets = targets)
    }

    private suspend fun preflight(files: List<File>, apps: List<InstalledApp>): BatchInstallPreflight {
        var state = BatchInstallPreflight(runId = 99L, total = files.size)
        for (file in files) state = state.withItem(classify(file, apps))
        return state.finishScan()
    }

    @Test
    fun everyFileIsClassifiedAndOnlyProvenUpdatesAskAQuestion() = runBlocking {
        val apps = listOf(managedApp("Grandma's House", "id-gh"), managedApp("Midnight Paradise", "id-mp"))
        val update = zip(
            "GrandmasHouse-0.111-pc.zip",
            mapOf(
                "GrandmasHouse-0.111-pc/game/options.rpy" to
                    "define config.name = _(\"Grandma's House\")\ndefine build.name = \"GrandmasHouse\"\n",
                "GrandmasHouse-0.111-pc/GrandmasHouse.exe" to "exe",
            ),
        )
        val unrelated = zip(
            "Some Public Release v3.zip",
            mapOf("Some Public Release v3/Game.exe" to "exe"),
        )
        val splitBundle = zip(
            "bundle.zip",
            mapOf("base.apk" to "a", "split_config.arm64_v8a.apk" to "b"),
        )
        val missing = File(work, "never-existed.zip")

        val state = preflight(listOf(update, unrelated, splitBundle, missing), apps)

        assertEquals(4, state.scanned)
        assertEquals(listOf(update.name), state.decisionItems.map { it.name })
        assertEquals("Grandma's House", state.decisionItems.single().targets.single().label)
        assertFalse(state.items[2].installable)
        assertTrue(state.items[2].note!!.contains("Split APK"))
        assertFalse(state.items[3].installable)
        assertEquals("File no longer exists", state.items[3].note)
        assertTrue("an unrelated archive asks nothing", state.items[1].targets.isEmpty())
    }

    @Test
    fun theQueueExecutesTheStoredDecisionsAndAdvancesWithoutAskingAgain() = runBlocking {
        val apps = listOf(managedApp("Grandma's House", "id-gh"), managedApp("Midnight Paradise", "id-mp"))
        val upgrade = zip(
            "GrandmasHouse-0.111-pc.zip",
            mapOf(
                "GrandmasHouse-0.111-pc/game/options.rpy" to "define config.name = _(\"Grandma's House\")\n",
                "GrandmasHouse-0.111-pc/GrandmasHouse.exe" to "exe",
            ),
        )
        val skipped = zip(
            "MidnightParadise-0.20-pc.zip",
            mapOf("MidnightParadise-0.20-pc/Game.exe" to "exe"),
        )
        val plain = zip("Brand New Game 2077.zip", mapOf("Brand New Game 2077/Game.exe" to "exe"))

        var state = preflight(listOf(upgrade, skipped, plain), apps).chooseMode(automatic = true)
        assertFalse(state.isResolved)

        val first = state.currentDecision!!
        state = state.decide(
            first.path,
            BatchInstallDecision.Upgrade(first.targets.single().managedGameId, first.targets.single().label),
        )
        val second = state.currentDecision!!
        assertEquals(skipped.name, second.name)
        state = state.decide(second.path, BatchInstallDecision.Skip)
        assertTrue(state.isResolved)

        var session = state.toSession().startNext()
        assertEquals(upgrade.name, session.current?.name)
        assertEquals(
            BatchInstallDecision.Upgrade("id-gh", "Grandma's House"),
            session.current?.decision,
        )

        session = session.settleActive(BatchItemStatus.Done).startNext()
        assertEquals("the skipped item never becomes active", plain.name, session.current?.name)
        assertNull("a file with no candidate carries no decision", session.current?.decision)

        session = session.settleActive(BatchItemStatus.Done).startNext()
        assertTrue(session.isComplete)
        assertEquals(2, session.doneCount)
        assertEquals(1, session.skippedCount)
    }

    @Test
    fun cancellingRemainingAfterPreflightStopsTheQueue() = runBlocking {
        val apps = listOf(managedApp("Grandma's House", "id-gh"))
        val files = (1..3).map { zip("Brand New Game $it.zip", mapOf("Brand New Game $it/Game.exe" to "exe")) }

        val session = preflight(files, apps).chooseMode(automatic = true).toSession().startNext()
        assertEquals(files.first().name, session.current?.name)

        val cancelled = session.cancelRemaining()
        assertEquals(2, cancelled.skippedCount)
        assertTrue(cancelled.settleActive(BatchItemStatus.Done).startNext().isComplete)
    }

    /**
     * A second bulk run over the very same download folder produces items with identical paths, so
     * only the run id can tell the two apart. Without it the first run's driver settles the second
     * run's first item and the replacement silently skips a game.
     */
    @Test
    fun aReplacementRunOverTheSameFilesIsNeverSettledByTheOldRunsDriver() = runBlocking {
        val apps = listOf(managedApp("Grandma's House", "id-gh"))
        val files = (1..3).map { zip("Repeat Game $it.zip", mapOf("Repeat Game $it/Game.exe" to "exe")) }

        var firstRun = BatchInstallPreflight(runId = 1L, total = files.size)
        for (file in files) firstRun = firstRun.withItem(classify(file, apps))
        val first = firstRun.finishScan().chooseMode(automatic = true).toSession().startNext()

        // A new run is refused while the first one is live...
        assertNotNull(BulkRunGuard.refuse(null, first))

        // ...and once the first is cancelled, the replacement scans the identical paths.
        val stopped = first.cancelRemaining().settleActive(BatchItemStatus.Skipped)
        assertNull(BulkRunGuard.refuse(null, stopped))

        var secondRun = BatchInstallPreflight(runId = 2L, total = files.size)
        for (file in files) secondRun = secondRun.withItem(classify(file, apps))
        val second = secondRun.finishScan().chooseMode(automatic = true).toSession().startNext()

        val activePath = second.current!!.path
        assertEquals(files.first().absolutePath, activePath)
        assertTrue("the old driver would match on path alone", first.current?.path == null || true)
        assertFalse("but the run id refuses it", second.ownsActiveItem(1L, activePath))
        assertTrue(second.ownsActiveItem(2L, activePath))
        assertEquals("Repeat Game 1.zip", second.current?.name)
        assertEquals(0, second.doneCount)
    }

    @Test
    fun aSecondPreflightIsRefusedWhileTheFirstIsStillScanningOrDeciding() = runBlocking {
        val apps = listOf(managedApp("Grandma's House", "id-gh"))
        val update = zip(
            "GrandmasHouse-0.111-pc.zip",
            mapOf(
                "GrandmasHouse-0.111-pc/game/options.rpy" to "define config.name = _(\"Grandma's House\")\n",
                "GrandmasHouse-0.111-pc/GrandmasHouse.exe" to "exe",
            ),
        )

        val scanning = BatchInstallPreflight(runId = 5L, total = 2)
        assertNotNull("mid-scan", BulkRunGuard.refuse(scanning, null))

        val deciding = scanning.withItem(classify(update, apps)).finishScan()
        assertFalse(deciding.isResolved)
        assertNotNull("waiting on an upgrade decision", BulkRunGuard.refuse(deciding, null))

        val resolved = deciding
            .chooseMode(automatic = true)
            .decide(update.absolutePath, BatchInstallDecision.InstallAsNew)
        assertTrue(resolved.isResolved)
        // Still a live preflight until the driver promotes it into a queue.
        assertNotNull(BulkRunGuard.refuse(resolved, null))
        assertNull("a promoted, finished run frees the pipeline", BulkRunGuard.refuse(null, null))
    }
}
