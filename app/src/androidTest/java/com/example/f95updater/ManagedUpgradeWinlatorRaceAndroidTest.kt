package com.example.f95updater

import android.content.Context
import android.content.Intent
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The cancellation / Winlator hand-off race.
 *
 * A cancellation accepted a moment before the boundary is only *observed* later, and the upgrade's
 * remaining work publishes an Intent that tells Winlator to run the game from the freshly extracted
 * folder. Deleting that folder (and the journal that records where it is) while the Intent is on its
 * way to Winlator is the one outcome that cannot be undone, so ownership of the request has to be
 * decided atomically: either the cancellation withdraws it before anyone can launch it, or it has
 * been launched and the cancellation must keep everything for recovery.
 *
 * These tests drive the real flow and land the cancellation at the three interesting instants
 * through the flow's own seams, so they are deterministic rather than timing-dependent.
 */
@RunWith(AndroidJUnit4::class)
class ManagedUpgradeWinlatorRaceAndroidTest {

    private lateinit var context: Context
    private lateinit var work: File
    private lateinit var scope: CoroutineScope
    private val createdGameIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "upgrade-race-${System.nanoTime()}").apply {
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

    // ------------------------------------------------------------------ the three race points

    @Test
    fun aCancelThatLandsBeforeTheRequestIsPublishedUndoesEverything() = runBlocking {
        val fixture = winlatorUpgrade("Race Before Publish")
        val published = Collections.synchronizedList(mutableListOf<ManagedUpgradeWinlatorRequest>())
        val flow = ManagedArchiveUpgradeFlow(context, scope)
        flow.winlatorRequestListener = { published += it }
        flow.winlatorCapabilityCheck = {
            // The cancel is accepted while the capability round-trip is still in flight, i.e. after
            // the boundary the UI checks but before anything has been handed to Winlator.
            scope.cancel()
            Result.success(Unit)
        }

        assertTrue(onMain { flow.start(fixture.archive, fixture.app) }.started)
        assertTrue("the cancellation settles", waitUntil { settled(flow) })

        assertTrue("no Intent was ever published", published.isEmpty())
        assertNull("and none is waiting to be launched", flow.winlatorRequest)
        assertNull("a cancel is not a failure: ${flow.errorMessage}", flow.errorMessage)
        assertNull(flow.result)
        assertNotNull("the cancellation is surfaced", flow.cancellationNotice)
        assertFalse("the extracted folder is removed", fixture.newRoot.exists())
        assertNull("and its journal with it", ManagedUpgradePendingStore.load(context))
        assertInstalledGameUntouched(fixture)
        Unit
    }

    @Test
    fun aCancelThatLandsAfterThePublishButBeforeTheLaunchWithdrawsTheRequest() = runBlocking {
        val fixture = winlatorUpgrade("Race Before Launch")
        val published = Collections.synchronizedList(mutableListOf<ManagedUpgradeWinlatorRequest>())
        val flow = ManagedArchiveUpgradeFlow(context, scope)
        flow.winlatorCapabilityCheck = { Result.success(Unit) }
        flow.winlatorRequestListener = { request ->
            published += request
            // The request is queued but the Activity has not claimed it yet.
            scope.cancel()
        }

        assertTrue(onMain { flow.start(fixture.archive, fixture.app) }.started)
        assertTrue("the cancellation settles", waitUntil { settled(flow) })

        val request = published.single()
        assertNull("the queued request is withdrawn", flow.winlatorRequest)
        assertNull(
            "and a launcher that wakes up late cannot claim it",
            onMain { flow.claimWinlatorRequest(request.sequence) },
        )
        assertNull("a cancel is not a failure: ${flow.errorMessage}", flow.errorMessage)
        assertNull(flow.result)
        assertNotNull(flow.cancellationNotice)
        // The Intent still targets the new folder, which is exactly why it must never be launchable.
        assertTrue(
            request.intent.getStringExtra("executable_path")!!.startsWith(fixture.newRoot.absolutePath),
        )
        assertFalse("the extracted folder is removed", fixture.newRoot.exists())
        assertNull("and its journal with it", ManagedUpgradePendingStore.load(context))
        assertInstalledGameUntouched(fixture)
        Unit
    }

    @Test
    fun aCancelThatLosesToTheLaunchKeepsBothFoldersAndTheJournal() = runBlocking {
        val fixture = winlatorUpgrade("Race After Launch")
        val launched = Collections.synchronizedList(mutableListOf<Intent>())
        val flow = ManagedArchiveUpgradeFlow(context, scope)
        flow.winlatorCapabilityCheck = { Result.success(Unit) }
        flow.winlatorRequestListener = { request ->
            // Exactly what MainActivity's LaunchedEffect does, in the instant before the cancel.
            flow.claimWinlatorRequest(request.sequence)?.let { launched += it }
            scope.cancel()
        }

        assertTrue(onMain { flow.start(fixture.archive, fixture.app) }.started)
        assertTrue("the upgrade settles", waitUntil { settled(flow) })

        val intent = launched.single()
        assertNull("a lost race is not reported as a clean cancellation", flow.cancellationNotice)
        assertNotNull("it is surfaced as unresolved instead", flow.errorMessage)
        assertTrue(flow.errorMessage!!.contains("Winlator"))
        assertNull(flow.result)

        val executable = File(requireNotNull(intent.getStringExtra("executable_path")))
        assertTrue("the launched Intent targets the new folder", executable.absolutePath.startsWith(fixture.newRoot.absolutePath))
        assertTrue("which is still there: nothing Winlator was pointed at was deleted", executable.isFile)
        assertTrue(fixture.newRoot.isDirectory)
        assertTrue("the installed folder is kept too", File(fixture.game.storagePath).isDirectory)

        val journal = ManagedUpgradePendingStore.load(context)
        assertNotNull("the journal is kept so recovery can resolve the hand-off", journal)
        assertEquals(fixture.newRoot.absolutePath, journal!!.newRootPath)
        assertEquals(fixture.game.storagePath, journal.oldRootPath)
        assertEquals(ManagedUpgradePhase.WinlatorRequested, journal.phase)
        assertEquals(fixture.plan.winlatorGameId, journal.winlator?.winlatorGameId)
        assertEquals(
            "AGM still points at the installed folder until the hand-off is resolved",
            fixture.game.storagePath,
            ManagedGameStore(context).find(fixture.game.id)?.storagePath,
        )
        Unit
    }

    @Test
    fun aWithdrawnRequestCanNeverBeLaunchedTwice() = runBlocking {
        val fixture = winlatorUpgrade("Race Claim Once")
        val claimed = Collections.synchronizedList(mutableListOf<Intent?>())
        val flow = ManagedArchiveUpgradeFlow(context, scope)
        flow.winlatorCapabilityCheck = { Result.success(Unit) }
        flow.winlatorRequestListener = { request ->
            claimed += flow.claimWinlatorRequest(request.sequence)
            // A recomposition that fires the effect again must not launch the same Intent twice.
            claimed += flow.claimWinlatorRequest(request.sequence)
            scope.cancel()
        }

        assertTrue(onMain { flow.start(fixture.archive, fixture.app) }.started)
        assertTrue(waitUntil { settled(flow) })

        assertNotNull("the first claim wins", claimed[0])
        assertNull("the second claim gets nothing", claimed[1])
        assertNull(flow.winlatorRequest)
        assertNotNull("and the upgrade is still held for recovery", ManagedUpgradePendingStore.load(context))
        Unit
    }

    // ------------------------------------------------------------------ helpers

    private class Fixture(
        val game: ManagedGame,
        val app: InstalledApp,
        val archive: File,
        val newRoot: File,
        val plan: ManagedUpgradeBindingMigration.WinlatorRepath,
    )

    /** An installed Winlator game plus the archive that upgrades it in place. */
    private suspend fun winlatorUpgrade(label: String): Fixture {
        val installed = File(work, label).apply { mkdirs() }
        File(installed, "Game.exe").writeText("MZ old executable\n")
        File(installed, "data").mkdirs()
        val game = ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = installed.absolutePath,
                storageFolderName = installed.name,
                label = label,
                versionName = "1.0",
                defaultRunner = ManagedRunnerKind.Winlator,
                runnerBindings = listOf(
                    ManagedRunnerBinding.Winlator(
                        managedId = "winlator-race-${label.hashCode()}",
                        executablePath = File(installed, "Game.exe").absolutePath,
                    ),
                ),
            ),
        ).also { createdGameIds += it.id }
        val folder = "$label-2.0"
        val archive = File(work, "$folder.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            mapOf(
                "$folder/Game.exe" to "MZ new executable\n",
                "$folder/data/readme.txt" to "v2\n",
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val newRoot = File(work, folder)
        return Fixture(
            game = game,
            app = InstalledApp(
                packageName = "managed:${game.id}",
                label = game.label,
                versionName = game.versionName,
                versionCode = 0L,
                source = AppSource.Managed,
                managedGameId = game.id,
                managedDefaultRunner = ManagedRunnerKind.Winlator,
                storagePath = game.storagePath,
            ),
            archive = archive,
            newRoot = newRoot,
            plan = ManagedUpgradeBindingMigration.WinlatorRepath(
                winlatorGameId = "winlator-race-${label.hashCode()}",
                oldGamePath = installed.absolutePath,
                oldExecutablePath = File(installed, "Game.exe").absolutePath,
                newGamePath = newRoot.absolutePath,
                newExecutablePath = File(newRoot, "Game.exe").absolutePath,
            ),
        )
    }

    private suspend fun assertInstalledGameUntouched(fixture: Fixture) {
        val live = ManagedGameStore(context).find(fixture.game.id)
        assertEquals("the library still points at the installed folder", fixture.game.storagePath, live?.storagePath)
        assertEquals(
            "and its Winlator binding still points at the original executable",
            fixture.plan.oldExecutablePath,
            (live?.runnerBindings?.firstOrNull { it.kind == ManagedRunnerKind.Winlator }
                as? ManagedRunnerBinding.Winlator)?.executablePath,
        )
        assertTrue("the source archive is kept", fixture.archive.isFile)
    }

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

    private fun settled(flow: ManagedArchiveUpgradeFlow): Boolean =
        flow.cancellationNotice != null || flow.errorMessage != null || flow.result != null
}
