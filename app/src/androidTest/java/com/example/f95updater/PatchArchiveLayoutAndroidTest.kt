package com.example.f95updater

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end check of the two supported patch layouts against real archives: header listing ->
 * validation -> staging with the shared extractor -> staged-tree verification -> evidence.
 * The archives are generated here, so no third-party content is stored in the repository.
 */
@RunWith(AndroidJUnit4::class)
class PatchArchiveLayoutAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var workspace: File

    private val optionsRpy = """
        define config.name = _("Sample Game")
        define config.version = "0.2"
        define build.name = "SampleGame"
        define config.save_directory = "SampleGame-1614721066"
    """.trimIndent()

    @Before
    fun setUp() {
        workspace = File(context.cacheDir, "patch-layout-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    private fun zip(name: String, entries: Map<String, String>): File {
        val archive = File(workspace, name)
        ZipOutputStream(archive.outputStream().buffered()).use { out ->
            entries.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return archive
    }

    private suspend fun stage(archive: File, scan: PatchArchiveScan.Accepted): File {
        val destination = File(workspace, "stage-${archive.nameWithoutExtension}").apply { mkdirs() }
        val outcome = ArchiveExtractor.extract(
            context = context,
            archive = archive,
            format = ArchiveExtractor.Format.ZIP,
            password = null,
            destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination),
            suggestedName = "staging",
            forcedSubfolderName = "staging",
        ) {}
        val root = (outcome as ArchiveExtractor.Outcome.Ok).rootFolder
        val staged = (root as ArchiveExtractor.ExtractRoot.FileRoot).file
        assertEquals(StagedPatchVerifier.Outcome.Ok, StagedPatchVerifier.verify(staged, scan))
        return staged
    }

    private suspend fun scanOf(archive: File): PatchArchiveScan.Accepted {
        val listing = PatchArchiveReader.list(archive, ArchiveExtractor.Format.ZIP, null)
        val entries = (listing as PatchArchiveReader.Listing.Ok).entries
        return PatchArchiveScanner.scan(entries) as PatchArchiveScan.Accepted
    }

    @Test
    fun renPyContentAtTheArchiveRootStagesAndIdentifiesItself() = runBlocking {
        val archive = zip(
            "SampleGame-WT-v0.2.zip",
            mapOf(
                "options.rpy" to optionsRpy,
                "script.rpy" to "label start:\n    return\n",
                "screens.rpy" to "screen wt():\n    pass\n",
            ),
        )
        val scan = scanOf(archive)
        assertEquals(PatchDestination.GameFolder, scan.destination)
        val staged = stage(archive, scan)

        val evidence = PatchEvidenceReader.read(archive.name, staged, scan)
        assertEquals(PatchEngine.RenPy, evidence.engine)
        assertEquals("SampleGame-1614721066", evidence.options.saveDirectory)
        assertEquals("0.2", evidence.declaredVersion)
        assertEquals("options.rpy", evidence.optionsSource)
    }

    @Test
    fun aWrapperFolderWithAGameTreeStagesWithoutIdentityEvidence() = runBlocking {
        val archive = zip(
            "0000_WT_cheat_gallery_mod-Vers.0.15-pc.zip",
            mapOf(
                "WT cheat gallery mod/game/script.rpy" to "# end of version 0.15\n",
                "WT cheat gallery mod/game/gallery.rpy" to "screen gallery():\n    pass\n",
            ),
        )
        val scan = scanOf(archive)
        assertEquals("WT cheat gallery mod", scan.wrapperFolder)
        assertEquals(PatchDestination.InstallRoot, scan.destination)
        assertEquals(listOf("game/gallery.rpy", "game/script.rpy"), scan.relativePaths)
        val staged = stage(archive, scan)

        val evidence = PatchEvidenceReader.read(archive.name, staged, scan)
        assertEquals(PatchEngine.RenPy, evidence.engine)
        assertTrue(evidence.options.isEmpty)

        // A file-name hint is never identity proof, so matching must refuse.
        val outcome = PatchTargetMatcher.match(evidence, emptyList())
        assertTrue(
            (outcome as PatchMatchOutcome.Refused).reason.contains("no concrete Ren'Py identity"),
        )
    }

    @Test
    fun anArchiveWithATraversalEntryIsRejectedBeforeStaging() = runBlocking {
        val archive = zip(
            "evil.zip",
            mapOf(
                "game/script.rpy" to "ok\n",
                "../escape.rpy" to "bad\n",
            ),
        )
        val listing = PatchArchiveReader.list(archive, ArchiveExtractor.Format.ZIP, null)
        val entries = (listing as PatchArchiveReader.Listing.Ok).entries
        val scan = PatchArchiveScanner.scan(entries)
        assertTrue((scan as PatchArchiveScan.Rejected).reason.contains("traverse"))
    }
}
