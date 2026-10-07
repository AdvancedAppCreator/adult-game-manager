package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RenPyPatchIdentityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sampleOptions = """
        init offset = -2

        define config.name = _("Sample Game")

        define gui.show_name = True

        define config.version = "0.2"

        define build.name = "SampleGame"

        define config.save_directory = "SampleGame-1614721066"
    """.trimIndent()

    @Test
    fun parsesTheFourIdentityFields() {
        val options = RenPyOptionsParser.parse(sampleOptions)
        assertEquals("Sample Game", options.configName)
        assertEquals("0.2", options.version)
        assertEquals("SampleGame", options.buildName)
        assertEquals("SampleGame-1614721066", options.saveDirectory)
    }

    @Test
    fun commentedAssignmentsAreIgnoredAndConflictsAreNotEvidence() {
        val options = RenPyOptionsParser.parse(
            """
            # define config.name = _("Other Game")
            define config.name = _("Sample Game")
            define config.version = "0.2"
            define config.version = "0.3"
            """.trimIndent(),
        )
        assertEquals("Sample Game", options.configName)
        assertNull(options.version)
    }

    @Test
    fun versionNormalisationIsLiteralNotNumeric() {
        assertEquals("0.2", normalizePatchVersion(" v0.2 "))
        assertEquals("0.2", normalizePatchVersion("Version 0.2"))
        assertTrue(normalizePatchVersion("0.2") != normalizePatchVersion("0.20"))
        assertNull(normalizePatchVersion("  "))
    }

    private fun renPyGame(
        name: String,
        options: String?,
        buildLaunchers: String? = null,
        nested: Boolean = false,
    ): ManagedGame {
        val storage = temp.newFolder(name)
        val root = if (nested) File(storage, "Inner-1.0").apply { mkdirs() } else storage
        File(root, "renpy").mkdirs()
        val gameFolder = File(root, "game").apply { mkdirs() }
        File(gameFolder, "script.rpy").writeText("label start:\n    return\n")
        File(gameFolder, "scripts.rpa").writeText("archive")
        options?.let { File(gameFolder, "options.rpy").writeText(it) }
        buildLaunchers?.let {
            File(root, "$it.py").writeText("import renpy\n")
            File(root, "$it.sh").writeText("#!/bin/sh\n")
        }
        return managedGame(name, storage)
    }

    private fun managedGame(label: String, storage: File): ManagedGame = validateManagedGame(
        ManagedGame(
            id = java.util.UUID.randomUUID().toString(),
            canonicalPath = canonicalManagedGamePath(storage.absolutePath),
            storagePath = storage.absolutePath,
            label = label,
            versionName = "0.9",
            defaultRunner = ManagedRunnerKind.JoiPlay,
            runnerBindings = listOf(
                ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "script.rpy"),
            ),
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )

    private fun patch(
        options: RenPyOptions,
        destination: PatchDestination = PatchDestination.GameFolder,
        engine: PatchEngine = PatchEngine.RenPy,
    ) = PatchEvidence(
        archiveName = "patch.rar",
        engine = engine,
        destination = destination,
        options = options,
        optionsSource = "options.rpy",
        fileNameVersionHint = null,
    )

    @Test
    fun probeFindsTheRenPyRootEvenWhenNested() {
        val game = renPyGame("nested", sampleOptions, buildLaunchers = "SampleGame", nested = true)
        val evidence = RenPyGameProbe.probe(game)
        assertEquals(PatchEngine.RenPy, evidence.engine)
        assertTrue(evidence.renPyRoot!!.endsWith("Inner-1.0"))
        assertEquals("SampleGame-1614721066", evidence.options.saveDirectory)
        assertEquals("SampleGame", evidence.provenBuildName)
        assertEquals("0.2", evidence.provenVersion)
        assertTrue(evidence.layoutSignatures.contains("game/script.rpy"))
    }

    @Test
    fun probeUsesLauncherNamesWhenOptionsAreStripped() {
        val game = renPyGame("stripped", options = null, buildLaunchers = "SampleGame")
        val evidence = RenPyGameProbe.probe(game)
        assertEquals("SampleGame", evidence.provenBuildName)
        assertNull(evidence.provenVersion)
    }

    @Test
    fun aUniqueSaveDirectoryProvesTheTarget() {
        val target = RenPyGameProbe.probe(renPyGame("target", sampleOptions))
        val other = RenPyGameProbe.probe(
            renPyGame(
                "other",
                sampleOptions
                    .replace("Sample Game", "Another Game")
                    .replace("SampleGame-1614721066", "AnotherGame-99")
                    .replace("\"SampleGame\"", "\"AnotherGame\""),
            ),
        )
        val outcome = PatchTargetMatcher.match(
            patch(RenPyOptionsParser.parse(sampleOptions)),
            listOf(target, other),
        )
        assertTrue(outcome is PatchMatchOutcome.Matched)
        val matched = outcome as PatchMatchOutcome.Matched
        assertEquals(target.managedGameId, matched.target.managedGameId)
        assertTrue(matched.proofs.any { it.startsWith("config.save_directory") })
    }

    @Test
    fun aPatchWithoutConcreteIdentityIsRefused() {
        // The second real-world sample: a wrapper plus game/script.rpy, but no options.rpy at all.
        val target = RenPyGameProbe.probe(renPyGame("target", sampleOptions))
        val outcome = PatchTargetMatcher.match(
            patch(RenPyOptions.EMPTY, destination = PatchDestination.InstallRoot),
            listOf(target),
        )
        val refusal = outcome as PatchMatchOutcome.Refused
        assertTrue(refusal.reason.contains("no concrete Ren'Py identity"))
        assertTrue(refusal.reason.contains("archive name is only a hint"))
    }

    @Test
    fun aNonRenPyPatchIsRefused() {
        val target = RenPyGameProbe.probe(renPyGame("target", sampleOptions))
        val outcome = PatchTargetMatcher.match(
            patch(RenPyOptionsParser.parse(sampleOptions), engine = PatchEngine.Unknown),
            listOf(target),
        )
        assertTrue((outcome as PatchMatchOutcome.Refused).reason.contains("only installs Ren'Py"))
    }

    @Test
    fun aVersionMismatchIsAnExplicitlyOverridableMismatch() {
        val target = RenPyGameProbe.probe(renPyGame("target", sampleOptions.replace("\"0.2\"", "\"0.3\"")))
        val outcome = PatchTargetMatcher.match(
            patch(RenPyOptionsParser.parse(sampleOptions)),
            listOf(target),
        )
        val overridable = outcome as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.ExplicitMismatch, overridable.compatibility)
        assertEquals(target.managedGameId, overridable.target.managedGameId)
        assertEquals("0.3", overridable.installedVersion)
        assertTrue(overridable.reason.contains("targets version 0.2"))
        assertTrue(overridable.reason.contains("is version 0.3"))
    }

    @Test
    fun anUnprovableInstalledVersionIsAnUnprovenOverride() {
        val target = RenPyGameProbe.probe(
            renPyGame("target", options = null, buildLaunchers = "SampleGame"),
        )
        val outcome = PatchTargetMatcher.match(
            patch(RenPyOptionsParser.parse(sampleOptions)),
            listOf(target),
        )
        val overridable = outcome as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.Unproven, overridable.compatibility)
        assertNull(overridable.installedVersion)
        assertTrue(overridable.reason.contains("cannot prove which"))
    }

    @Test
    fun anAmbiguousMatchIsRefused() {
        val first = RenPyGameProbe.probe(renPyGame("first", sampleOptions))
        val second = RenPyGameProbe.probe(renPyGame("second", sampleOptions))
        val outcome = PatchTargetMatcher.match(
            patch(RenPyOptionsParser.parse(sampleOptions)),
            listOf(first, second),
        )
        assertTrue((outcome as PatchMatchOutcome.Refused).reason.contains("more than one managed game"))
    }

    @Test
    fun aConflictingDisplayNameBlocksAnOtherwiseMatchingBuildName() {
        val target = RenPyGameProbe.probe(
            renPyGame("target", sampleOptions.replace("Sample Game", "Totally Different")),
        )
        val outcome = PatchTargetMatcher.match(
            patch(
                RenPyOptions(
                    configName = "Sample Game",
                    buildName = "SampleGame",
                    version = null,
                    saveDirectory = null,
                ),
            ),
            listOf(target),
        )
        assertTrue((outcome as PatchMatchOutcome.Refused).reason.contains("No managed game proved"))
    }

    @Test
    fun stagedPatchEvidenceIsReadFromTheApprovedOptionsPath() {
        val staging = temp.newFolder("staging")
        File(staging, "options.rpy").writeText(sampleOptions)
        File(staging, "script.rpy").writeText("label start:\n    return\n")
        val scan = PatchArchiveScanner.scan(
            listOf(
                PatchArchiveEntry("options.rpy", File(staging, "options.rpy").length(), PatchEntryKind.File),
                PatchArchiveEntry("script.rpy", File(staging, "script.rpy").length(), PatchEntryKind.File),
            ),
        ) as PatchArchiveScan.Accepted
        val evidence = PatchEvidenceReader.read("SampleGame-WT-v0.2.rar", staging, scan)
        assertEquals(PatchEngine.RenPy, evidence.engine)
        assertEquals(PatchDestination.GameFolder, evidence.destination)
        assertEquals("SampleGame-1614721066", evidence.options.saveDirectory)
        assertEquals("0.2", evidence.declaredVersion)
        assertEquals("0.2", evidence.fileNameVersionHint)
    }
}
