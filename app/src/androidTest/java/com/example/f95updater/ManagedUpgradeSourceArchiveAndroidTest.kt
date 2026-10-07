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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The picked archive belongs to the user, so an upgrade may only delete it when the user asked for
 * it — and the answer has to be the one that was in force when *this* upgrade started.
 *
 * `commitManagedUpgrade` used to delete the archive unconditionally, which meant a recovery run
 * days later deleted a file the user had since decided to keep (and reported "deleted" for archives
 * it had never removed).
 */
@RunWith(AndroidJUnit4::class)
class ManagedUpgradeSourceArchiveAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val createdIds = mutableListOf<String>()
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private var originalDeleteAfterInstall = false

    @Before
    fun setUp() = runBlocking {
        ManagedUpgradePendingStore.clear(context)
        originalDeleteAfterInstall = JoiPlaySettingsStore.deleteAfterInstall(context)
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        root = File(context.cacheDir, "upgrade-archive-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        JoiPlaySettingsStore.setDeleteAfterInstall(context, originalDeleteAfterInstall)
        val store = ManagedGameStore(context)
        createdIds.forEach { runCatching { store.delete(it) } }
        createdIds.clear()
        ManagedUpgradePendingStore.clear(context)
        root.walkTopDown().forEach { runCatching { it.setWritable(true) } }
        root.deleteRecursively()
        Unit
    }

    // ------------------------------------------------------------------ commit / recovery

    @Test
    fun anUpgradeThatDidNotAskForDeletionKeepsTheArchive() = runBlocking {
        val fixture = journalledUpgrade("keep", deleteSourceArchive = false)

        val outcome = recoverPendingManagedUpgrade(context)

        val result = (outcome as ManagedUpgradeRecoveryOutcome.Completed).result
        assertTrue("the archive is the user's until they say otherwise", fixture.archive.isFile)
        assertEquals(ManagedUpgradeSourceArchiveOutcome.NotRequested, result.sourceArchiveOutcome)
        assertFalse(result.sourceArchiveOutcome.deletedByAgm)
        assertFalse(result.sourceArchiveOutcome.deletionRequested)
        assertFalse(result.sourceArchiveOutcome.deletionFailed)
        assertEquals(fixture.archive.name, result.sourceArchiveName)
        assertEquals(
            "Source archive kept: ${fixture.archive.name}",
            ManagedUpgradeSourceArchive.summaryLine(result.sourceArchiveOutcome, fixture.archive.name),
        )
        assertTrue("keeping the archive is not a warning", result.warnings.isEmpty())
        assertEquals(fixture.newRoot.absolutePath, ManagedGameStore(context).find(fixture.game.id)?.storagePath)
        Unit
    }

    @Test
    fun anUpgradeStartedWithDeleteAfterInstallRemovesTheArchive() = runBlocking {
        val fixture = journalledUpgrade("delete", deleteSourceArchive = true)

        val outcome = recoverPendingManagedUpgrade(context)

        val result = (outcome as ManagedUpgradeRecoveryOutcome.Completed).result
        assertFalse("the archive is gone", fixture.archive.exists())
        assertEquals(ManagedUpgradeSourceArchiveOutcome.Deleted, result.sourceArchiveOutcome)
        assertTrue(result.sourceArchiveOutcome.deletedByAgm)
        assertTrue(result.sourceArchiveOutcome.deletionRequested)
        assertTrue(result.warnings.isEmpty())
        Unit
    }

    @Test
    fun recoveryHonoursTheRecordedChoiceAndNeverRereadsThePreference() = runBlocking {
        // The user flipped the setting on *after* starting an upgrade that recorded "keep".
        val keep = journalledUpgrade("keep-despite-preference", deleteSourceArchive = false)
        JoiPlaySettingsStore.setDeleteAfterInstall(context, true)

        assertTrue(recoverPendingManagedUpgrade(context) is ManagedUpgradeRecoveryOutcome.Completed)
        assertTrue("the preference changed, the recorded decision did not", keep.archive.isFile)

        // ...and the mirror image: recorded "delete", setting since turned off.
        val delete = journalledUpgrade("delete-despite-preference", deleteSourceArchive = true)
        JoiPlaySettingsStore.setDeleteAfterInstall(context, false)

        assertTrue(recoverPendingManagedUpgrade(context) is ManagedUpgradeRecoveryOutcome.Completed)
        assertFalse(delete.archive.exists())
        Unit
    }

    @Test
    fun anUnwritableArchiveIsKeptAndReported() = runBlocking {
        val fixture = journalledUpgrade("readonly", deleteSourceArchive = true)
        assertTrue("the fixture archive is really read-only", fixture.archive.setWritable(false))

        val outcome = recoverPendingManagedUpgrade(context)

        val result = (outcome as ManagedUpgradeRecoveryOutcome.Completed).result
        assertTrue("a read-only archive is never force-deleted", fixture.archive.isFile)
        assertEquals(
            "and is never reported as deleted",
            ManagedUpgradeSourceArchiveOutcome.Failed,
            result.sourceArchiveOutcome,
        )
        assertFalse(result.sourceArchiveOutcome.deletedByAgm)
        assertTrue(result.sourceArchiveOutcome.deletionRequested)
        assertTrue("a requested deletion that could not happen is a failure", result.sourceArchiveOutcome.deletionFailed)
        assertEquals(
            "Source archive could NOT be deleted: ${fixture.archive.name}",
            ManagedUpgradeSourceArchive.summaryLine(result.sourceArchiveOutcome, fixture.archive.name),
        )
        assertTrue(
            "the user is told why it is still there: ${result.warnings}",
            result.warnings.any { it.contains(fixture.archive.name) && it.contains("read-only") },
        )
        // The upgrade itself still committed: a kept archive is a warning, not a failure.
        assertEquals(fixture.newRoot.absolutePath, ManagedGameStore(context).find(fixture.game.id)?.storagePath)
        Unit
    }

    @Test
    fun anArchiveThatWasAlreadyGoneIsNeverReportedAsAFailedDeletion() = runBlocking {
        // The user (or a cleanup tool) removed the archive between the journal write and the commit.
        val fixture = journalledUpgrade("already-gone", deleteSourceArchive = true)
        assertTrue("the fixture archive is really gone", fixture.archive.delete())

        val outcome = recoverPendingManagedUpgrade(context)

        val result = (outcome as ManagedUpgradeRecoveryOutcome.Completed).result
        assertEquals(ManagedUpgradeSourceArchiveOutcome.AlreadyAbsent, result.sourceArchiveOutcome)
        assertFalse("AGM did not delete it", result.sourceArchiveOutcome.deletedByAgm)
        assertTrue("the choice was still to delete it", result.sourceArchiveOutcome.deletionRequested)
        assertFalse("nothing failed", result.sourceArchiveOutcome.deletionFailed)
        assertTrue("a file that was already gone is not a warning: ${result.warnings}", result.warnings.isEmpty())
        assertEquals(
            "Source archive was already gone: ${fixture.archive.name}",
            ManagedUpgradeSourceArchive.summaryLine(result.sourceArchiveOutcome, fixture.archive.name),
        )
        assertEquals(fixture.newRoot.absolutePath, ManagedGameStore(context).find(fixture.game.id)?.storagePath)
        Unit
    }

    // ------------------------------------------------------------------ capture at upgrade start
    @Test
    fun theFlowCapturesTheDeleteAfterInstallPreferenceWhenTheUpgradeStarts() = runBlocking {
        JoiPlaySettingsStore.setDeleteAfterInstall(context, true)
        val (game, app) = managedRenPyApp("Archive Preference Game")
        val archive = renPyUpgradeArchive("Archive Preference Game-2.0.zip", "Archive Preference Game-2.0")

        val flow = ManagedArchiveUpgradeFlow(context, scope)
        val journalled = mutableListOf<Boolean>()
        flow.stepListener = {
            ManagedUpgradePendingStore.load(context)?.let { journalled += it.deleteSourceArchive }
        }
        assertTrue(onMain { flow.start(archive, app) }.started)
        assertTrue("the upgrade settles", waitUntil { settled(flow) })

        assertNull("the upgrade succeeds: ${flow.errorMessage}", flow.errorMessage)
        val result = requireNotNull(flow.result)
        assertTrue("the journal recorded the live preference", journalled.isNotEmpty() && journalled.all { it })
        assertTrue("the archive was deleted as the setting asked", result.sourceArchiveOutcome.deletedByAgm)
        assertEquals(ManagedUpgradeSourceArchiveOutcome.Deleted, result.sourceArchiveOutcome)
        assertFalse(archive.exists())
        assertEquals(result.newFolder, ManagedGameStore(context).find(game.id)?.storagePath)
        onMain { flow.acknowledgeResult() }
        Unit
    }

    @Test
    fun theFlowKeepsTheArchiveWhenDeleteAfterInstallIsOff() = runBlocking {
        JoiPlaySettingsStore.setDeleteAfterInstall(context, false)
        val (game, app) = managedRenPyApp("Archive Kept Game")
        val archive = renPyUpgradeArchive("Archive Kept Game-2.0.zip", "Archive Kept Game-2.0")

        val flow = ManagedArchiveUpgradeFlow(context, scope)
        assertTrue(onMain { flow.start(archive, app) }.started)
        assertTrue("the upgrade settles", waitUntil { settled(flow) })

        assertNull("the upgrade succeeds: ${flow.errorMessage}", flow.errorMessage)
        val result = requireNotNull(flow.result)
        assertEquals(ManagedUpgradeSourceArchiveOutcome.NotRequested, result.sourceArchiveOutcome)
        assertTrue("the picked archive is still where the user left it", archive.isFile)
        assertEquals(result.newFolder, ManagedGameStore(context).find(game.id)?.storagePath)
        onMain { flow.acknowledgeResult() }
        Unit
    }

    // ------------------------------------------------------------------ helpers

    private class Fixture(val game: ManagedGame, val newRoot: File, val archive: File)

    /** A journalled, ready-to-commit upgrade whose only interesting property is the archive choice. */
    private suspend fun journalledUpgrade(name: String, deleteSourceArchive: Boolean): Fixture {
        val oldRoot = gameFolder("old-$name")
        val newRoot = gameFolder("new-$name")
        val archive = File(root, "$name-2.0.zip").apply { writeText("archive bytes") }
        val game = createGame(oldRoot, "Archive $name")
        val replacement = game.copy(
            canonicalPath = canonicalManagedGamePath(newRoot.absolutePath),
            storagePath = newRoot.absolutePath,
            storageFolderName = newRoot.name,
            versionName = "2.0",
        )
        ManagedUpgradePendingStore.save(
            context,
            ManagedUpgradePendingState(
                phase = ManagedUpgradePhase.StoreRelocationPending,
                managedGameId = game.id,
                label = game.label,
                oldRootPath = game.storagePath,
                oldCanonicalPath = game.canonicalPath,
                newRootPath = newRoot.absolutePath,
                newCanonicalPath = canonicalManagedGamePath(newRoot.absolutePath),
                sourceArchivePath = archive.absolutePath,
                deleteSourceArchive = deleteSourceArchive,
                originalGame = game,
                replacementGame = replacement,
            ),
        )
        return Fixture(game, newRoot, archive)
    }

    private fun gameFolder(name: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, "Game.sh").writeText("#!/bin/sh")
        }

    private suspend fun createGame(folder: File, label: String): ManagedGame {
        val game = ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = folder.absolutePath,
                storageFolderName = folder.name,
                label = label,
                versionName = "1.0",
                defaultRunner = ManagedRunnerKind.JoiPlay,
                runnerBindings = listOf(ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh")),
            ),
        )
        createdIds += game.id
        return game
    }

    private suspend fun managedRenPyApp(label: String): Pair<ManagedGame, InstalledApp> {
        val folder = File(root, label).apply { mkdirs() }
        File(folder, "renpy").mkdirs()
        File(folder, "game").apply { mkdirs() }
        File(folder, "game/script.rpy").writeText("label start:\n    return\n")
        File(folder, "Game.sh").writeText("#!/bin/sh\n")
        val game = createGame(folder, label)
        return game to InstalledApp(
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

    private fun renPyUpgradeArchive(name: String, folder: String): File {
        val file = File(root, name)
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
        }
        return file
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
