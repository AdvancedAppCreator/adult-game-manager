package com.example.f95updater

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The explicit compatibility override driven through the real [PatchInstallFlow]: a real archive on
 * disk, a real managed game in the library, the real staging/verification pipeline and the real
 * [PatchInstallTransaction].
 *
 * The main fixture reproduces the production case: a Ren'Py patch with no options.rpy whose only
 * identity is its own source, matched uniquely against an installed "GrandmasHouse-V0.16-pc" whose
 * highest structured marker (`v002`) has no defined version mapping.
 */
@RunWith(AndroidJUnit4::class)
class PatchCompatibilityOverrideAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var workspace: File
    private lateinit var library: File
    private lateinit var scope: CoroutineScope
    private lateinit var flow: PatchInstallFlow
    private val createdGameIds = mutableListOf<String>()

    private val cast = (1..48).map { "c$it" to "Name$it" }

    @Before
    fun setUp() {
        clearJournal()
        PasswordVault.init(context)
        workspace = File(context.cacheDir, "patch-override-${System.nanoTime()}").apply { mkdirs() }
        library = File(workspace, "library").apply { mkdirs() }
        scope = CoroutineScope(Dispatchers.Main)
        flow = PatchInstallFlow(context, scope)
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        clearJournal()
        val store = ManagedGameStore(context)
        createdGameIds.forEach { id -> runCatching { store.delete(id) } }
        createdGameIds.clear()
        workspace.deleteRecursively()
        cacheEntries("patch_in_").forEach { it.deleteRecursively() }
        cacheEntries("patch_stage_").forEach { it.deleteRecursively() }
        Unit
    }

    private fun clearJournal() {
        runCatching { PatchTransactionStore.load(context) }
            .getOrDefault(emptyList())
            .forEach { record ->
                PatchTransactionStore.remove(context, record.transactionId)
                File(record.workDir).deleteRecursively()
            }
    }

    private fun cacheEntries(prefix: String): List<File> =
        context.cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }.orEmpty()

    // ------------------------------------------------------------- fixtures

    private fun characterSource(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("\n") { (variable, name) ->
            "define $variable = Character(\"$name\", color=\"#c8ffc8\")"
        }

    private suspend fun installedGame(
        label: String,
        pairs: List<Pair<String, String>> = cast,
        version: String? = "V0.16",
        buildName: String = "GrandmasHouse",
        saveDirectory: String = "GrandmasHouse-1629239078",
    ): ManagedGame {
        val root = File(library, label).apply { mkdirs() }
        File(root, "renpy").mkdirs()
        val game = File(root, "game").apply { mkdirs() }
        File(game, "script.rpy").writeText("label start:\n    return\n")
        File(game, "variables.rpy").writeText(characterSource(pairs))
        File(game, "options.rpy").writeText(
            buildString {
                appendLine("define config.name = _(\"$label\")")
                appendLine("define build.name = \"$buildName\"")
                if (version != null) appendLine("define config.version = \"$version\"")
                appendLine("define config.save_directory = \"$saveDirectory\"")
            },
        )
        return ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = root.absolutePath,
                label = label,
                runnerBindings = listOf(ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "script.rpy")),
            ),
        ).also { createdGameIds += it.id }
    }

    private fun archive(name: String, entries: Map<String, String>): Uri {
        val file = File(workspace, name)
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (entry, content) ->
                out.putNextEntry(ZipEntry(entry))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    /** The production-shaped patch: wrapper folder, `game/` tree, source-only identity. */
    private fun signaturePatch(
        name: String = "GH V0.16 Taboo Patch.zip",
        pairs: List<Pair<String, String>> = cast,
        labels: List<String> = listOf("v001", "v002"),
        extra: Map<String, String> = emptyMap(),
    ): Uri = archive(
        name,
        buildMap {
            put("GH V0.16 Taboo Patch/game/variables.rpy", characterSource(pairs) + "\n")
            put(
                "GH V0.16 Taboo Patch/game/gallery_scenes.rpy",
                labels.joinToString("\n") { "label $it:\n    return\n" },
            )
            putAll(extra)
        },
    )

    private fun await(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25L)
        }
        fail("Timed out waiting for $what (phase=${flow.phase} progress=${flow.progress})")
    }

    private fun awaitSettled() =
        await("the patch analysis to settle") {
            flow.preview != null || flow.refusal != null || flow.errorMessage != null
        }

    // ---------------------------------------------------- the override itself

    @Test
    fun aGrandmasHouseStyleSignaturePatchReachesTheOverrideAndInstallsOnlyAfterAcknowledgement() {
        val game = runBlocking { installedGame("GrandmasHouse-V0.16-pc") }
        val originalVariables = File(game.storagePath, "game/variables.rpy").readText()

        flow.start(signaturePatch())
        awaitSettled()

        val preview = flow.preview
        assertNotNull("the GH patch must reach a preview, not a refusal: ${flow.refusal?.reason}", preview)
        assertEquals(PatchCompatibility.Unproven, preview!!.compatibility)
        assertEquals(game.id, preview.targetManagedGameId)
        assertEquals("GrandmasHouse-V0.16-pc", preview.targetLabel)
        assertEquals("V0.16", preview.installedVersion)
        assertEquals("v002", preview.patchVersionMarker)
        assertTrue(preview.requiresOverrideAcknowledgement)
        assertTrue(preview.compatibilityReason!!.contains("no defined mapping"))
        assertTrue(preview.replacements.contains("game/variables.rpy"))
        assertTrue(preview.additions.contains("game/gallery_scenes.rpy"))

        // Stage 1: the confirm button is inert until the acknowledgement is ticked.
        assertFalse(flow.overrideAcknowledged)
        assertFalse(PatchOverrideGate.mayInstall(preview, flow.overrideAcknowledged))
        val refused = flow.confirmInstall()
        assertTrue(refused is InstallStartOutcome.Refused)
        assertTrue((refused as InstallStartOutcome.Refused).reason.contains("acknowledgement"))
        assertEquals("nothing was written", originalVariables, File(game.storagePath, "game/variables.rpy").readText())
        assertNotNull("the preview stays open after a refused commit", flow.preview)

        // Stage 2: acknowledge, then install.
        flow.acknowledgeOverride(true)
        assertTrue(flow.overrideAcknowledged)
        assertTrue(PatchOverrideGate.mayInstall(flow.preview, flow.overrideAcknowledged))
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Started)
        await("the override install to finish") { flow.result != null || flow.errorMessage != null }

        val result = flow.result
        assertNotNull("install failed: ${flow.errorMessage}", result)
        assertTrue(result!!.compatibilityOverridden)
        assertTrue(result.compatibilityOverrideReason!!.contains("no defined mapping"))
        assertEquals(game.id, result.managedGameId)
        assertEquals(1, result.replacedCount)
        assertEquals(1, result.addedCount)
        assertEquals(
            characterSource(cast) + "\n",
            File(game.storagePath, "game/variables.rpy").readText(),
        )
        assertTrue(File(game.storagePath, "game/gallery_scenes.rpy").isFile)

        // The record persists the override so the installed-patches list can report it.
        val record = PatchTransactionStore.find(context, game.id)!!
        assertEquals(PatchTransactionPhase.Committed, record.phase)
        assertTrue(record.compatibilityOverridden)
        assertTrue(record.compatibilityOverrideReason!!.contains("no defined mapping"))
        assertTrue(record.isUsableRollbackPoint)
        assertTrue(
            PatchOverrideGate.installedNote(record.compatibilityOverrideReason)
                .contains("explicit compatibility override"),
        )

        // An overridden install is an ordinary, fully rollbackable transaction.
        flow.acknowledgeResult()
        assertTrue(flow.rollback(record) is InstallStartOutcome.Started)
        await("the rollback to finish") { flow.rollbackOutcome != null || flow.errorMessage != null }
        val rolledBack = flow.rollbackOutcome as PatchRollbackOutcome.RolledBack
        assertEquals(1, rolledBack.restoredCount)
        assertEquals(1, rolledBack.removedCount)
        assertEquals(originalVariables, File(game.storagePath, "game/variables.rpy").readText())
        assertFalse(File(game.storagePath, "game/gallery_scenes.rpy").exists())
        assertNull(PatchTransactionStore.find(context, game.id))
    }

    @Test
    fun aProvenVersionMismatchOffersTheSameExplicitOverride() {
        val game = runBlocking { installedGame("GrandmasHouse-V0.16-pc", version = "V0.16") }
        // This patch declares its own identity *and* a different version, so the mismatch is proven.
        val patch = archive(
            "GH V0.14 Taboo Patch.zip",
            mapOf(
                "GH V0.14 Taboo Patch/game/options.rpy" to
                    "define build.name = \"GrandmasHouse\"\n" +
                    "define config.version = \"0.14\"\n" +
                    "define config.save_directory = \"GrandmasHouse-1629239078\"\n",
                "GH V0.14 Taboo Patch/game/gallery_scenes.rpy" to "label v001:\n    return\n",
            ),
        )

        flow.start(patch)
        awaitSettled()

        val preview = flow.preview
        assertNotNull("the mismatch must be an override, not a refusal: ${flow.refusal?.reason}", preview)
        assertEquals(PatchCompatibility.ExplicitMismatch, preview!!.compatibility)
        assertEquals("Incompatible version", PatchOverrideGate.title(preview))
        assertEquals(game.id, preview.targetManagedGameId)
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Refused)

        flow.acknowledgeOverride(true)
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Started)
        await("the mismatch override install to finish") { flow.result != null || flow.errorMessage != null }
        val result = flow.result
        assertNotNull("install failed: ${flow.errorMessage}", result)
        assertTrue(result!!.compatibilityOverridden)
        assertTrue(result.compatibilityOverrideReason!!.contains("0.14"))
        assertTrue(PatchTransactionStore.find(context, game.id)!!.compatibilityOverridden)
    }

    @Test
    fun aDeclaredIdentityWithNoConfigVersionStillReachesTheOverrideForAnUnresolvedMarker() {
        // Regression: the patch proves its identity from options.rpy but declares no config.version,
        // so its only version evidence is the unresolvable marker `v002`. This must be an override
        // decision, never a preview that claims the patch fits the installed build.
        val game = runBlocking { installedGame("GrandmasHouse-V0.16-pc", version = "V0.16") }
        val before = File(game.storagePath, "game/variables.rpy").readText()
        val patch = archive(
            "GH Taboo Patch no version.zip",
            mapOf(
                "GH Taboo Patch/game/options.rpy" to
                    "define build.name = \"GrandmasHouse\"\n" +
                    "define config.save_directory = \"GrandmasHouse-1629239078\"\n",
                "GH Taboo Patch/game/variables.rpy" to characterSource(cast) + "\n",
                "GH Taboo Patch/game/gallery_scenes.rpy" to
                    "label v001:\n    return\nlabel v002:\n    return\n",
            ),
        )

        flow.start(patch)
        awaitSettled()

        val preview = flow.preview
        assertNotNull("expected an override preview, not a refusal: ${flow.refusal?.reason}", preview)
        assertEquals(PatchCompatibility.Unproven, preview!!.compatibility)
        assertEquals(game.id, preview.targetManagedGameId)
        assertEquals("v002", preview.patchVersionMarker)
        assertEquals("V0.16", preview.installedVersion)
        assertTrue(preview.requiresOverrideAcknowledgement)
        assertTrue(preview.compatibilityReason!!.contains("no defined mapping"))
        assertFalse(
            "the headline must never claim a proven fit here",
            PatchOverrideGate.headline(preview).contains("belongs to this game and fits"),
        )
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Refused)
        assertEquals(before, File(game.storagePath, "game/variables.rpy").readText())
        flow.cancelPreview()
        assertNull(PatchTransactionStore.find(context, game.id))
    }

    @Test
    fun aProvenMatchStillInstallsWithNoAcknowledgementAtAll() {
        val game = runBlocking { installedGame("GrandmasHouse-V0.16-pc", version = "0.16") }
        flow.start(signaturePatch(labels = listOf("v0_16")))
        awaitSettled()

        val preview = flow.preview
        assertNotNull("expected a plain preview: ${flow.refusal?.reason}", preview)
        assertEquals(PatchCompatibility.Proven, preview!!.compatibility)
        assertFalse(preview.requiresOverrideAcknowledgement)
        assertNull(preview.compatibilityReason)
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Started)
        await("the proven install to finish") { flow.result != null || flow.errorMessage != null }
        val result = flow.result
        assertNotNull("install failed: ${flow.errorMessage}", result)
        assertFalse(result!!.compatibilityOverridden)
        assertNull(result.compatibilityOverrideReason)
        assertFalse(PatchTransactionStore.find(context, game.id)!!.compatibilityOverridden)
    }

    // --------------------------------------------- refusals never offer this

    @Test
    fun aPatchWithNoProvableIdentityNeverExposesAnOverride() {
        runBlocking { installedGame("GrandmasHouse-V0.16-pc") }
        flow.start(signaturePatch(pairs = cast.take(3)))
        awaitSettled()

        assertNull(flow.preview)
        assertFalse(flow.overrideAcknowledged)
        assertTrue(flow.refusal!!.reason.contains("no concrete Ren'Py identity"))
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Refused)
        assertTrue(cacheEntries("patch_stage_").isEmpty())
    }

    @Test
    fun anAmbiguousIdentityNeverExposesAnOverride() {
        runBlocking {
            installedGame("GrandmasHouse-V0.16-pc")
            installedGame("GrandmasHouse-copy", saveDirectory = "GrandmasHouseCopy-1")
        }
        flow.start(signaturePatch())
        awaitSettled()

        assertNull(flow.preview)
        assertTrue(flow.refusal!!.reason.contains("more than one managed game"))
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Refused)
        assertTrue(cacheEntries("patch_stage_").isEmpty())
    }

    @Test
    fun anUnsafeArchiveIsRefusedBeforeStagingAndNeverExposesAnOverride() {
        val game = runBlocking { installedGame("GrandmasHouse-V0.16-pc") }
        val before = File(game.storagePath, "game/variables.rpy").readText()
        flow.start(
            signaturePatch(extra = mapOf("GH V0.16 Taboo Patch/game/.agm-owner.json" to "{}")),
        )
        awaitSettled()

        assertNull(flow.preview)
        assertFalse(flow.overrideAcknowledged)
        assertTrue(flow.refusal!!.reason.contains(AGM_RESERVED_NAME_PREFIX))
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Refused)
        assertEquals(before, File(game.storagePath, "game/variables.rpy").readText())
        assertTrue("no staging survives a header rejection", cacheEntries("patch_stage_").isEmpty())
    }

    // ------------------------------------------------------- flow/UI state

    @Test
    fun cancellingTheOverrideDecisionRemovesTheStagingAndChangesNothing() {
        val game = runBlocking { installedGame("GrandmasHouse-V0.16-pc") }
        val before = File(game.storagePath, "game/variables.rpy").readText()
        flow.start(signaturePatch())
        awaitSettled()
        assertNotNull(flow.preview)
        assertFalse(cacheEntries("patch_stage_").isEmpty())

        flow.acknowledgeOverride(true)
        flow.cancelPreview()

        assertNull(flow.preview)
        assertFalse("consent dies with the preview", flow.overrideAcknowledged)
        assertTrue("staging is removed on cancel", cacheEntries("patch_stage_").isEmpty())
        assertTrue("the cached archive copy is removed too", cacheEntries("patch_in_").isEmpty())
        assertEquals(before, File(game.storagePath, "game/variables.rpy").readText())
        assertNull(PatchTransactionStore.find(context, game.id))
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Refused)
    }

    @Test
    fun acknowledgementCannotBeSetWithoutAnOpenOverrideAndNeverSurvivesANewAnalysis() {
        // Nothing open: consent is not storable at all.
        flow.acknowledgeOverride(true)
        assertFalse(flow.overrideAcknowledged)

        val game = runBlocking { installedGame("GrandmasHouse-V0.16-pc") }
        flow.start(signaturePatch())
        awaitSettled()
        val first = flow.preview!!
        flow.acknowledgeOverride(true)
        assertTrue(flow.overrideAcknowledged)

        // A rebuilt dialog re-reads the flow, so the target it can act on cannot drift.
        assertEquals(game.id, flow.preview!!.targetManagedGameId)
        assertEquals(first, flow.preview)

        flow.cancelPreview()
        flow.start(signaturePatch(name = "GH V0.16 Taboo Patch second.zip"))
        awaitSettled()
        assertNotNull(flow.preview)
        assertFalse("a new preview always starts unacknowledged", flow.overrideAcknowledged)
        assertTrue(flow.confirmInstall() is InstallStartOutcome.Refused)
        assertNull(PatchTransactionStore.find(context, game.id))
        flow.cancelPreview()
    }

    @Test
    fun aProvenPreviewIgnoresAnAcknowledgementItDoesNotNeed() {
        runBlocking { installedGame("GrandmasHouse-V0.16-pc", version = "0.16") }
        flow.start(signaturePatch(labels = listOf("v0_16")))
        awaitSettled()
        assertEquals(PatchCompatibility.Proven, flow.preview!!.compatibility)
        flow.acknowledgeOverride(true)
        assertFalse(flow.overrideAcknowledged)
        flow.cancelPreview()
    }
}
