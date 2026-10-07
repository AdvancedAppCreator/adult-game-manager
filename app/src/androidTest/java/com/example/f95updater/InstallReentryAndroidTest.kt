package com.example.f95updater

import android.content.Context
import android.net.Uri
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
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device coverage of the one-install-at-a-time ownership rule, driven through the real flows.
 *
 * Each flow's `start` used to begin by cancelling whatever was running — which is a no-op exactly
 * when it matters, because a patch commit, a store relocation and a Winlator round-trip all hold
 * `cancellationAllowed = false`. The second start then took over `pending`, and the first job's
 * cleanup deleted the second one's cache copy, staging folder and destination.
 */
@RunWith(AndroidJUnit4::class)
class InstallReentryAndroidTest {

    private lateinit var context: Context
    private lateinit var work: File
    private lateinit var scope: CoroutineScope
    private val createdGameIds = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "reentry-test-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        ManagedUpgradePendingStore.clear(context)
        clearPatchJournal()
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        val store = ManagedGameStore(context)
        createdGameIds.forEach { runCatching { store.delete(it) } }
        createdGameIds.clear()
        ManagedUpgradePendingStore.clear(context)
        clearPatchJournal()
        work.deleteRecursively()
        Unit
    }

    private fun clearPatchJournal() {
        runCatching { PatchTransactionStore.load(context) }
            .getOrDefault(emptyList())
            .forEach { record ->
                PatchTransactionStore.remove(context, record.transactionId)
                File(record.workDir).deleteRecursively()
            }
    }

    // ------------------------------------------------------------------ fixtures

    private fun bigArchive(name: String, entries: Int = 10, entryBytes: Int = 1024 * 1024): File {
        val file = File(work, name)
        val random = Random(name.hashCode().toLong())
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            repeat(entries) { index ->
                zip.putNextEntry(ZipEntry("Game/data/asset$index.bin"))
                val block = ByteArray(64 * 1024)
                var written = 0
                while (written < entryBytes) {
                    random.nextBytes(block)
                    zip.write(block)
                    written += block.size
                }
                zip.closeEntry()
            }
        }
        return file
    }

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

    private fun destination(name: String): File = File(work, name).apply { mkdirs() }

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

    private fun operationKey(flow: JoiPlayExtractFlow): String? =
        flow.archiveName?.takeIf { flow.inProgress }?.let { "${ProgressOperationKind.Extract}:$it" }

    // ------------------------------------------------------------------ extraction

    @Test
    fun aSecondExtractionIsRefusedWhileTheFirstIsRunningEvenWhenMinimized() {
        val flow = JoiPlayExtractFlow(context, scope)
        val first = bigArchive("first.zip")
        val second = zip("second.zip", mapOf("Other/readme.txt" to "hi"))
        val firstDest = destination("first-out")
        val secondDest = destination("second-out")

        val started = onMain {
            flow.start(Uri.fromFile(first), ArchiveExtractor.ExtractRoot.FileRoot(firstDest))
        }
        assertTrue(started.started)
        assertTrue(flow.inProgress)

        // Minimizing is UI only, so the refusal below must be identical either way.
        val minimized = ProgressMinimize.minimize(ProgressMinimizeState(), operationKey(flow), null)
        assertTrue(minimized.minimized)

        val refused = onMain {
            flow.start(Uri.fromFile(second), ArchiveExtractor.ExtractRoot.FileRoot(secondDest))
        }
        val reason = refused.refusalOrNull
        assertNotNull("a second extraction must be refused", reason)
        assertTrue(reason!!.contains("AGM is already installing"))
        assertEquals("the running operation keeps the flow", "first.zip", flow.archiveName)

        assertTrue("the first extraction still finishes", waitUntil { flow.extractedRoot != null || flow.errorMessage != null })
        assertNull(flow.errorMessage)
        val root = flow.extractedRoot as ArchiveExtractor.ExtractRoot.FileRoot
        assertTrue("the refused start never touched the running one", File(root.file, "data/asset0.bin").isFile)
        assertEquals("the refused destination stayed empty", 0, secondDest.listFiles()!!.size)

        onMain { flow.acknowledgeResult() }
    }

    @Test
    fun aSecondExtractionIsRefusedWhileTheResultOfTheFirstIsStillOnScreen() {
        val flow = JoiPlayExtractFlow(context, scope)
        val first = zip("held.zip", mapOf("Game/readme.txt" to "one"))
        val dest = destination("held-out")
        assertTrue(onMain { flow.start(Uri.fromFile(first), ArchiveExtractor.ExtractRoot.FileRoot(dest)) }.started)
        assertTrue(waitUntil { flow.extractedRoot != null || flow.errorMessage != null })
        assertNull(flow.errorMessage)

        // The result dialog still owns the extracted folder; a new pick must not replace it.
        val refused = onMain {
            flow.start(
                Uri.fromFile(zip("next.zip", mapOf("Game/readme.txt" to "two"))),
                ArchiveExtractor.ExtractRoot.FileRoot(destination("next-out")),
            )
        }
        assertNotNull(refused.refusalOrNull)
        assertNotNull(flow.extractedRoot)

        onMain { flow.acknowledgeResult() }
        // Once acknowledged the pipeline is free again.
        val accepted = onMain {
            flow.start(
                Uri.fromFile(zip("after.zip", mapOf("Game/readme.txt" to "three"))),
                ArchiveExtractor.ExtractRoot.FileRoot(destination("after-out")),
            )
        }
        assertTrue(accepted.started)
        assertTrue(waitUntil { flow.extractedRoot != null || flow.errorMessage != null })
        onMain { flow.acknowledgeResult() }
    }

    // ------------------------------------------------------------------ patch install

    private suspend fun managedRenPyGame(label: String, version: String): ManagedGame {
        val root = File(work, label).apply { mkdirs() }
        File(root, "renpy").mkdirs()
        val game = File(root, "game").apply { mkdirs() }
        File(game, "script.rpy").writeText("label start:\n    return\n")
        File(game, "options.rpy").writeText(
            "define config.name = _(\"$label\")\n" +
                "define build.name = \"${label.replace(" ", "")}\"\n" +
                "define config.save_directory = \"${label.replace(" ", "")}-1629239078\"\n" +
                "define config.version = \"$version\"\n",
        )
        return ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = root.absolutePath,
                storageFolderName = root.name,
                label = label,
                versionName = version,
                defaultRunner = ManagedRunnerKind.JoiPlay,
                runnerBindings = listOf(ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "script.rpy")),
            ),
        ).also { createdGameIds += it.id }
    }

    private fun patchArchive(name: String, label: String, version: String, body: String): File = zip(
        name,
        mapOf(
            "game/options.rpy" to
                "define config.name = _(\"$label\")\n" +
                    "define build.name = \"${label.replace(" ", "")}\"\n" +
                    "define config.save_directory = \"${label.replace(" ", "")}-1629239078\"\n" +
                    "define config.version = \"$version\"\n",
            "game/patch_content.rpy" to body,
        ),
    )

    @Test
    fun aSecondPatchIsRefusedWhileTheUninterruptibleCommitIsRunning() = runBlocking {
        val game = managedRenPyGame("Reentry Patch Game", "0.9")
        val first = patchArchive("first-patch.zip", game.label, "0.9", "label patched_one:\n    return\n")
        val second = patchArchive("second-patch.zip", game.label, "0.9", "label patched_two:\n    return\n")
        val flow = PatchInstallFlow(context, scope)

        assertTrue(onMain { flow.start(Uri.fromFile(first)) }.started)
        assertTrue("the preview appears", waitUntil { flow.preview != null || flow.refusal != null || flow.errorMessage != null })
        assertNull("no refusal: ${flow.refusal?.reason}", flow.refusal)
        assertNull(flow.errorMessage)
        assertNotNull(flow.preview)

        // Commit and immediately re-enter, in the same main-thread turn: the commit job is launched
        // but cannot have run yet, so this is exactly the non-cancellable window.
        val refusedDuringCommit = onMain {
            flow.confirmInstall()
            flow.start(Uri.fromFile(second))
        }
        assertFalse("the commit is not cancellable", flow.cancellationAllowed)
        val reason = refusedDuringCommit.refusalOrNull
        assertNotNull("a patch started during the commit must be refused", reason)
        assertTrue(reason!!.contains("can't be interrupted"))

        assertTrue("the commit finishes", waitUntil { flow.result != null || flow.errorMessage != null })
        assertNull("the refused start must not break the commit: ${flow.errorMessage}", flow.errorMessage)
        val installed = flow.result!!
        assertEquals("first-patch.zip", installed.archiveName)
        assertTrue(File(File(game.storagePath), "game/patch_content.rpy").readText().contains("patched_one"))

        // The result dialog still owns the pipeline.
        assertNotNull(onMain { flow.start(Uri.fromFile(second)) }.refusalOrNull)
        onMain { flow.acknowledgeResult() }
        Unit
    }

    @Test
    fun aPatchRollbackIsRefusedWhileAnAnalysisIsRunning() = runBlocking {
        val game = managedRenPyGame("Rollback Guard Game", "0.9")
        val patch = patchArchive("rollback-patch.zip", game.label, "0.9", "label patched:\n    return\n")
        val flow = PatchInstallFlow(context, scope)

        assertTrue(onMain { flow.start(Uri.fromFile(patch)) }.started)
        assertTrue(waitUntil { flow.preview != null || flow.refusal != null || flow.errorMessage != null })
        assertNotNull(flow.preview)
        onMain { flow.confirmInstall() }
        assertTrue(waitUntil { flow.result != null || flow.errorMessage != null })
        assertNull(flow.errorMessage)
        onMain { flow.acknowledgeResult() }
        assertTrue(waitUntil { flow.usableRollbackPoints.isNotEmpty() })
        val record = flow.usableRollbackPoints.single()

        // A rollback rewrites the very files a running analysis stages, so it refuses.
        val second = patchArchive("second-analysis.zip", game.label, "0.9", "label other:\n    return\n")
        val refused = onMain {
            flow.start(Uri.fromFile(second))
            flow.rollback(record)
        }
        assertNotNull(refused.refusalOrNull)

        assertTrue(waitUntil { !flow.inProgress })
        // The still-installed patch was never rolled back behind the analysis' back.
        assertTrue(File(File(game.storagePath), "game/patch_content.rpy").readText().contains("patched"))
        onMain {
            flow.acknowledgeRefusal()
            flow.acknowledgeError()
            flow.cancelPreview()
        }
        Unit
    }

    // ------------------------------------------------------------------ managed upgrade

    /** A Ren'Py-shaped upgrade archive big enough that the extraction is observably in flight. */
    private fun renPyUpgradeArchive(name: String, folder: String, assets: Int = 8): File {
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
                while (written < 1024 * 1024) {
                    random.nextBytes(block)
                    zip.write(block)
                    written += block.size
                }
                zip.closeEntry()
            }
        }
        return file
    }

    private suspend fun managedApp(label: String): Pair<ManagedGame, InstalledApp> {
        val root = File(work, label).apply { mkdirs() }
        File(root, "renpy").mkdirs()
        File(root, "game").apply { mkdirs() }.let { File(it, "script.rpy").writeText("label start:\n    return\n") }
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
        val app = InstalledApp(
            packageName = "managed:${game.id}",
            label = label,
            versionName = "1.0",
            versionCode = 0L,
            source = AppSource.Managed,
            managedGameId = game.id,
            managedDefaultRunner = ManagedRunnerKind.JoiPlay,
            storagePath = root.absolutePath,
        )
        return game to app
    }

    @Test
    fun aSecondManagedUpgradeIsRefusedAndTheFirstKeepsItsRecoveryJournal() = runBlocking {
        val (game, app) = managedApp("Reentry Upgrade Game")
        val upgrade = renPyUpgradeArchive("Reentry Upgrade Game-2.0.zip", "Reentry Upgrade Game-2.0")
        val other = renPyUpgradeArchive("Reentry Upgrade Game-3.0.zip", "Reentry Upgrade Game-3.0", assets = 1)
        val flow = ManagedArchiveUpgradeFlow(context, scope)

        assertTrue(onMain { flow.start(upgrade, app) }.started)
        assertTrue("the upgrade engages", waitUntil(30_000) { flow.inProgress || flow.errorMessage != null || flow.result != null })

        val refused = onMain { flow.start(other, app) }
        assertNotNull("a second upgrade must be refused", refused.refusalOrNull)

        assertTrue("the first upgrade settles", waitUntil { flow.result != null || flow.errorMessage != null })
        assertNull("the refused start must not corrupt the first upgrade: ${flow.errorMessage}", flow.errorMessage)
        val result = flow.result!!
        assertEquals(game.label, result.label)
        assertTrue("the first archive's folder is the one that landed", result.newFolder.endsWith("Reentry Upgrade Game-2.0"))
        assertFalse("the refused archive was never extracted", File(work, "Reentry Upgrade Game-3.0").exists())
        // The journal belongs to whoever finished; a stale job may never clear or overwrite it.
        assertNull("a settled upgrade leaves no pending record", ManagedUpgradePendingStore.load(context))
        assertEquals(
            "the library points at the folder the first upgrade produced",
            result.newFolder,
            ManagedGameStore(context).find(game.id)?.storagePath,
        )
        onMain { flow.acknowledgeResult() }
        Unit
    }

    @Test
    fun aManagedUpgradeIsRefusedWhileItsOwnResultIsStillOnScreen() = runBlocking {
        val (_, app) = managedApp("Result Held Game")
        val upgrade = renPyUpgradeArchive("Result Held Game-2.0.zip", "Result Held Game-2.0", assets = 1)
        val flow = ManagedArchiveUpgradeFlow(context, scope)

        assertTrue(onMain { flow.start(upgrade, app) }.started)
        assertTrue(waitUntil { flow.result != null || flow.errorMessage != null })
        assertNull(flow.errorMessage)

        assertNotNull(onMain { flow.start(upgrade, app) }.refusalOrNull)
        onMain { flow.acknowledgeResult() }
        Unit
    }
}
