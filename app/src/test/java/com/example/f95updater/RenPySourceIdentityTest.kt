package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Characterisation of patch identity for archives that ship no options.rpy.
 *
 * The generated fixtures reproduce the validated real-world case: a Ren'Py patch whose only
 * readable identity is Ren'Py source under `game/`, holding 48 Character definitions of which 47
 * match the installed game verbatim, and whose highest structured label marker is `v16` while the
 * installed game declares config.version 0.110.
 */
class RenPySourceIdentityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cast = listOf(
        "gm" to "Grandma", "kn" to "Kate", "ly" to "Lily", "em" to "Emma", "js" to "Jessica",
        "am" to "Amber", "vi" to "Violet", "ol" to "Olivia", "sp" to "Sophia", "mi" to "Mia",
        "ch" to "Charlotte", "ab" to "Abigail", "el" to "Ella", "sc" to "Scarlett", "gr" to "Grace",
        "ch2" to "Chloe", "vic" to "Victoria", "ri" to "Riley", "au" to "Aurora", "za" to "Zoey",
        "pe" to "Penelope", "la" to "Layla", "no" to "Nora", "hz" to "Hazel", "el2" to "Eleanor",
        "st" to "Stella", "au2" to "Aubrey", "vi2" to "Violeta", "sa" to "Savannah", "au3" to "Audrey",
        "br" to "Brooklyn", "be" to "Bella", "cl" to "Claire", "sk" to "Skylar", "lu" to "Lucy",
        "pa" to "Paisley", "ev" to "Everly", "an" to "Anna", "ca" to "Caroline", "no2" to "Nova",
        "ge" to "Genesis", "em2" to "Emilia", "ke" to "Kennedy", "sa2" to "Samantha", "ma" to "Maya",
        "wi" to "Willow", "ki" to "Kinsley", "na" to "Naomi",
    )

    private fun characterSource(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("\n") { (variable, name) ->
            "define $variable = Character(\"$name\", color=\"#c8ffc8\")"
        }

    /** The patch: source only, no options.rpy, structured v1..v16 labels. */
    private fun patchStaging(
        pairs: List<Pair<String, String>> = cast,
        highestVersionLabel: Int = 16,
    ): File {
        val staging = temp.newFolder("patch-${System.nanoTime()}")
        val game = File(staging, "game").apply { mkdirs() }
        File(game, "variables.rpy").writeText(characterSource(pairs))
        File(game, "gallery_scenes.rpy").writeText(
            (1..highestVersionLabel).joinToString("\n") { "label v$it:\n    return\n" },
        )
        File(game, "script.rpy").writeText("label start:\n    call v1\n    return\n")
        File(File(game, "gui").apply { mkdirs() }, "game_menu.png").writeText("png")
        File(File(game, "gui"), "main_menu.png").writeText("png")
        return staging
    }

    private fun patchEvidence(
        staging: File,
        archiveName: String = "GH v0.16 Taboo Patch.zip",
        options: RenPyOptions = RenPyOptions.EMPTY,
    ) = PatchEvidence(
        archiveName = archiveName,
        engine = PatchEngine.RenPy,
        destination = PatchDestination.InstallRoot,
        options = options,
        optionsSource = null,
        fileNameVersionHint = "0.16",
        sourceSignatures = RenPySourceScanner.scanDirectory(staging),
    )

    private fun installedGame(
        label: String,
        pairs: List<Pair<String, String>>,
        version: String? = "0.110",
        buildName: String = "GrandmasHouse",
        saveDirectory: String = "GrandmasHouse-1629239078",
    ): ManagedGame {
        val storage = temp.newFolder("installed-$label-${System.nanoTime()}")
        File(storage, "renpy").mkdirs()
        val game = File(storage, "game").apply { mkdirs() }
        File(game, "script.rpy").writeText("label start:\n    return\n")
        File(game, "variables.rpy").writeText(characterSource(pairs))
        if (version != null) {
            File(game, "options.rpy").writeText(
                """
                define config.name = _("$label")
                define build.name = "$buildName"
                define config.version = "$version"
                define config.save_directory = "$saveDirectory"
                """.trimIndent(),
            )
        }
        return validateManagedGame(
            ManagedGame(
                id = java.util.UUID.randomUUID().toString(),
                canonicalPath = canonicalManagedGamePath(storage.absolutePath),
                storagePath = storage.absolutePath,
                label = label,
                versionName = version ?: "",
                defaultRunner = ManagedRunnerKind.JoiPlay,
                runnerBindings = listOf(ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "script.rpy")),
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun probe(game: ManagedGame) = RenPyGameProbe.probe(game, includeSourceSignatures = true)

    // ------------------------------------------------------- signature reading

    @Test
    fun characterDefinitionsAndLabelsBecomeExactSignatures() {
        val signatures = RenPySourceScanner.scanFiles(
            listOf(
                temp.newFile("sample.rpy").apply {
                    writeText(
                        """
                        define e = Character("Eileen", color="#c8ffc8")
                        define narrator = Character(None)
                        default lucy = Character(_("Lucy"))
                        label chapter_one:
                            return
                        label start:
                            return
                        define config.name = _("Sample Game")
                        define gui.text_color = "#ffffff"
                        """.trimIndent(),
                    )
                },
            ),
        )
        assertTrue(signatures.characters.contains("char:e=eileen"))
        assertTrue(signatures.characters.contains("char:lucy=lucy"))
        assertFalse("engine boilerplate is not identity", signatures.characters.contains("char:narrator="))
        assertTrue(signatures.labels.contains("label:chapter_one"))
        assertFalse("engine boilerplate is not identity", signatures.labels.contains("label:start"))
        assertTrue(signatures.declarations.contains("define:config.name=sample game"))
        assertFalse("gui theme values are not identity", signatures.declarations.any { it.startsWith("define:gui.") })
    }

    @Test
    fun scanningIsBoundedByFileCountAndBytes() {
        val root = temp.newFolder("bounded")
        repeat(RenPySourceScanner.MAX_FILES + 12) { index ->
            File(root, "file$index.rpy").writeText("define c$index = Character(\"N$index\")\n")
        }
        val many = RenPySourceScanner.scanDirectory(root)
        assertTrue(many.filesScanned <= RenPySourceScanner.MAX_FILES)
        assertTrue(many.truncated)

        val bigRoot = temp.newFolder("bounded-bytes")
        val filler = "# ".repeat(64) + "\n"
        File(bigRoot, "huge.rpy").writeText(
            buildString {
                append("define a = Character(\"Ann\")\n")
                while (length < RenPySourceScanner.MAX_FILE_BYTES + 256L * 1024L) append(filler)
            },
        )
        val big = RenPySourceScanner.scanDirectory(bigRoot)
        assertTrue(big.truncated)
        assertTrue(big.bytesScanned <= RenPySourceScanner.MAX_FILE_BYTES)
        assertTrue(big.characters.contains("char:a=ann"))
    }

    // --------------------------------------------------------------- identity

    @Test
    fun theValidatedTabooPatchIsIdentifiedAndOfferedAsAnUnprovenOverride() {
        val patch = patchEvidence(patchStaging())
        assertEquals(48, patch.sourceSignatures.characters.size)

        // Installed 0.110 declares 47 of the patch's 48 characters.
        val installed = installedGame("Grandma's House", cast.dropLast(1) + ("xx" to "Renamed"))
        val other = installedGame("Midnight Paradise", listOf("mp" to "Mika", "mp2" to "Sara"), version = "0.20")
        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed), probe(other)))

        val overridable = outcome as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.Unproven, overridable.compatibility)
        assertEquals(installed.id, overridable.target.managedGameId)
        assertEquals("v16", overridable.patchVersionMarker)
        assertEquals("0.110", overridable.installedVersion)
        assertTrue("names the identified game", overridable.reason.contains("Grandma's House"))
        assertTrue("names the marker", overridable.reason.contains("v16"))
        assertTrue("names the installed version", overridable.reason.contains("0.110"))
        assertTrue(
            "explains the missing normalisation rule",
            overridable.reason.contains("no defined mapping"),
        )
        assertTrue(overridable.reason.contains("could mean"))
        assertTrue("identity is still proved", overridable.proofs.any { it.contains("Character") })
    }

    @Test
    fun theSameSourceIdentityIsAcceptedWhenTheMarkerAndVersionAgree() {
        // v0_110 carries two numeric groups, so AGM's one defined rule maps it to 0.110.
        val staging = temp.newFolder("patch-agreeing")
        val game = File(staging, "game").apply { mkdirs() }
        File(game, "variables.rpy").writeText(characterSource(cast))
        File(game, "gallery_scenes.rpy").writeText("label v0_110:\n    return\n")
        val patch = patchEvidence(staging)
        val installed = installedGame("Grandma's House", cast)

        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed)))
        val matched = outcome as PatchMatchOutcome.Matched
        assertEquals(installed.id, matched.target.managedGameId)
        assertTrue(matched.proofs.any { it.contains("Character") })
    }

    @Test
    fun anUnrelatedGameIsNeverIdentified() {
        val patch = patchEvidence(patchStaging())
        val unrelated = installedGame(
            "Midnight Paradise",
            listOf(
                "mp" to "Mika", "mp2" to "Sara", "mp3" to "Nina", "mp4" to "Tara", "mp5" to "Lena",
                "mp6" to "Vera", "mp7" to "Dana", "mp8" to "Rita", "mp9" to "Iris", "mp10" to "June",
            ),
            version = "0.20",
        )
        val refusal = PatchTargetMatcher.match(patch, listOf(probe(unrelated))) as PatchMatchOutcome.Refused
        assertTrue(refusal.reason.contains("No managed game declares this patch's Ren'Py source identity"))
    }

    @Test
    fun twoGamesDeclaringTheSameSourceAreAmbiguousAndRefused() {
        val patch = patchEvidence(patchStaging())
        val first = installedGame("Grandma's House", cast)
        val second = installedGame("Grandmas House Copy", cast, saveDirectory = "GrandmasHouseCopy-1")
        val refusal =
            PatchTargetMatcher.match(patch, listOf(probe(first), probe(second))) as PatchMatchOutcome.Refused
        assertTrue(refusal.reason.contains("more than one managed game"))
    }

    @Test
    fun tooFewSignaturesAreNeverIdentity() {
        val staging = temp.newFolder("patch-thin")
        val game = File(staging, "game").apply { mkdirs() }
        File(game, "extra.rpy").writeText(characterSource(cast.take(3)))
        val patch = patchEvidence(staging)
        val installed = installedGame("Grandma's House", cast)

        val refusal = PatchTargetMatcher.match(patch, listOf(probe(installed))) as PatchMatchOutcome.Refused
        assertTrue(refusal.reason.contains("no concrete Ren'Py identity"))
        assertTrue(refusal.reason.contains("archive name is only a hint"))
    }

    @Test
    fun aContradictingConfigNameBlocksASourceMatch() {
        val patch = patchEvidence(
            patchStaging(highestVersionLabel = 0),
            options = RenPyOptions(configName = "Totally Different Game"),
        )
        val installed = installedGame("Grandma's House", cast)
        val refusal = PatchTargetMatcher.match(patch, listOf(probe(installed))) as PatchMatchOutcome.Refused
        assertTrue(refusal.reason.contains("contradictory evidence"))
    }

    @Test
    fun aSourceIdentifiedPatchWithoutAnyVersionMarkerIsAccepted() {
        val patch = patchEvidence(patchStaging(highestVersionLabel = 0))
        val installed = installedGame("Grandma's House", cast)
        assertTrue(
            PatchTargetMatcher.match(patch, listOf(probe(installed))) is PatchMatchOutcome.Matched,
        )
    }

    // -------------------------------------------------------- version markers

    @Test
    fun onlyMultiGroupMarkersHaveADefinedVersionMapping() {
        assertNull(PatchSourceVersionEvidence.normalizeMarker("v16"))
        assertNull(PatchSourceVersionEvidence.normalizeMarker("v3"))
        assertEquals("0.110", PatchSourceVersionEvidence.normalizeMarker("v0_110"))
        assertEquals("1.2.3", PatchSourceVersionEvidence.normalizeMarker("v1_2_3"))
        assertEquals("0.16", PatchSourceVersionEvidence.normalizeMarker("v0.16"))
    }

    @Test
    fun theHighestMarkerIsChosenNumericallyNotAlphabetically() {
        val labels = (1..16).map { "label:v$it" }.toSet() + setOf("label:intro", "label:v2a")
        assertEquals("v16", PatchSourceVersionEvidence.highestMarker(labels))
        assertEquals(17, PatchSourceVersionEvidence.markers(labels).size)
    }

    @Test
    fun aMarkerWithMoreGroupsDoesNotOutrankALargerNumber() {
        // v0_9 is written with two groups and v10 with one; the numeric value still decides.
        assertEquals("v10", PatchSourceVersionEvidence.highestMarker(setOf("label:v0_9", "label:v10")))
        assertEquals(
            listOf("v0_9", "v1_2", "v2", "v10"),
            PatchSourceVersionEvidence.markers(
                setOf("label:v10", "label:v0_9", "label:v2", "label:v1_2"),
            ),
        )
        // Mixed shapes still order by value, then by the extra group, then deterministically.
        assertEquals(
            listOf("v1", "v1_0", "v1_2", "v1_2_3", "v2", "v9", "v10", "v11_0"),
            PatchSourceVersionEvidence.markers(
                setOf(
                    "label:v2", "label:v11_0", "label:v1_2", "label:v10", "label:v1",
                    "label:v9", "label:v1_2_3", "label:v1_0",
                ),
            ),
        )
    }

    @Test
    fun aLaterSingleGroupMarkerMakesTheVersionMappingUnprovenAgainstAnInstalled0_9() {
        // The patch's own source declares both v0_9 and v10. v10 is the highest, and a one-number
        // marker has no defined mapping, so compatibility stays unproven even though "v0_9" would
        // have matched the installed 0.9 exactly. Ordering by group count first hid v10 and
        // installed the patch as if it were proven.
        val staging = temp.newFolder("marker-patch-${System.nanoTime()}")
        val game = File(staging, "game").apply { mkdirs() }
        File(game, "variables.rpy").writeText(characterSource(cast))
        File(game, "gallery_scenes.rpy").writeText("label v0_9:\n    return\n\nlabel v10:\n    return\n")

        val signatures = RenPySourceScanner.scanDirectory(staging)
        assertEquals("v10", PatchSourceVersionEvidence.highestMarker(signatures.labels))
        assertNull(PatchSourceVersionEvidence.normalizeMarker("v10"))
        assertEquals("0.9", PatchSourceVersionEvidence.normalizeMarker("v0_9"))

        val patch = PatchEvidence(
            archiveName = "GH patch.zip",
            engine = PatchEngine.RenPy,
            destination = PatchDestination.InstallRoot,
            options = RenPyOptions.EMPTY,
            optionsSource = null,
            fileNameVersionHint = null,
            sourceSignatures = signatures,
        )
        val installed = installedGame("Grandma's House", cast, version = "0.9")
        val overridable =
            PatchTargetMatcher.match(patch, listOf(probe(installed))) as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.Unproven, overridable.compatibility)
        assertTrue(overridable.reason.contains("\u201Cv10\u201D"))
        assertTrue(overridable.reason.contains("single-number marker"))
        assertFalse(
            "the reason must not claim the v0_9 mapping",
            overridable.reason.contains("resolves to 0.9"),
        )
    }

    @Test
    fun aPatchWhoseHighestMarkerIsMultiGroupStillInstallsOnTheMatchingVersion() {
        val staging = temp.newFolder("marker-ok-${System.nanoTime()}")
        val game = File(staging, "game").apply { mkdirs() }
        File(game, "variables.rpy").writeText(characterSource(cast))
        File(game, "gallery_scenes.rpy").writeText("label v9:\n    return\n\nlabel v0_110:\n    return\n")

        val signatures = RenPySourceScanner.scanDirectory(staging)
        // 9 beats 0.110 numerically, so the highest marker is v9 and the mapping stays unproven.
        assertEquals("v9", PatchSourceVersionEvidence.highestMarker(signatures.labels))

        val onlyMulti = RenPySourceScanner.scanDirectory(
            temp.newFolder("marker-ok2-${System.nanoTime()}").apply {
                File(this, "game").apply { mkdirs() }.let { dir ->
                    File(dir, "variables.rpy").writeText(characterSource(cast))
                    File(dir, "gallery_scenes.rpy").writeText(
                        "label v0_9:\n    return\n\nlabel v0_110:\n    return\n",
                    )
                }
            },
        )
        assertEquals("v0_110", PatchSourceVersionEvidence.highestMarker(onlyMulti.labels))
        val patch = PatchEvidence(
            archiveName = "GH patch.zip",
            engine = PatchEngine.RenPy,
            destination = PatchDestination.InstallRoot,
            options = RenPyOptions.EMPTY,
            optionsSource = null,
            fileNameVersionHint = null,
            sourceSignatures = onlyMulti,
        )
        val installed = installedGame("Grandma's House", cast, version = "0.110")
        assertTrue(
            PatchTargetMatcher.match(patch, listOf(probe(installed))) is PatchMatchOutcome.Matched,
        )
    }

    @Test
    fun theInstalledProbeReadsSourceBeyondOptionsRpy() {
        val installed = installedGame("Grandma's House", cast)
        val evidence = probe(installed)
        assertEquals("0.110", evidence.provenVersion)
        assertEquals("GrandmasHouse", evidence.provenBuildName)
        assertEquals(48, evidence.sourceSignatures.characters.size)
        assertTrue(evidence.sourceSignatures.filesScanned >= 2)
    }
}
