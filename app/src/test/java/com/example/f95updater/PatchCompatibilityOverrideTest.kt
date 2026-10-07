package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * The explicit compatibility override, end to end but without Android: which match outcomes may
 * offer it, which must never offer it, and the pure acknowledgement gate that stands between a
 * preview and [PatchInstallTransaction].
 *
 * The primary fixture reproduces the production case behind this feature: "GH V0.16 Taboo Patch.zip"
 * carries no options.rpy, is identified purely by its own Ren'Py source against one installed game,
 * and its highest structured marker is `v002`, which has no defined mapping to a config.version.
 */
class PatchCompatibilityOverrideTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cast = (1..48).map { "c$it" to "Name$it" }

    private fun characterSource(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("\n") { (variable, name) ->
            "define $variable = Character(\"$name\", color=\"#c8ffc8\")"
        }

    /** A GH-shaped patch: `game/` tree, source-only identity, structured version labels. */
    private fun patchStaging(
        pairs: List<Pair<String, String>> = cast,
        versionLabels: List<String> = listOf("v001", "v002"),
    ): File {
        val staging = temp.newFolder("patch-${System.nanoTime()}")
        val game = File(staging, "game").apply { mkdirs() }
        File(game, "variables.rpy").writeText(characterSource(pairs))
        File(game, "gallery_scenes.rpy").writeText(
            versionLabels.joinToString("\n") { "label $it:\n    return\n" },
        )
        return staging
    }

    private fun patchEvidence(
        staging: File,
        archiveName: String = "GH V0.16 Taboo Patch.zip",
        options: RenPyOptions = RenPyOptions.EMPTY,
        engine: PatchEngine = PatchEngine.RenPy,
    ) = PatchEvidence(
        archiveName = archiveName,
        engine = engine,
        destination = PatchDestination.InstallRoot,
        options = options,
        optionsSource = null,
        fileNameVersionHint = "0.16",
        sourceSignatures = RenPySourceScanner.scanDirectory(staging),
    )

    private fun installedGame(
        label: String,
        pairs: List<Pair<String, String>> = cast,
        version: String? = "V0.16",
        buildName: String = "GrandmasHouse",
        saveDirectory: String = "GrandmasHouse-1629239078",
        configName: String = label,
    ): ManagedGame {
        val storage = temp.newFolder("installed-${System.nanoTime()}")
        File(storage, "renpy").mkdirs()
        val game = File(storage, "game").apply { mkdirs() }
        File(game, "script.rpy").writeText("label start:\n    return\n")
        File(game, "variables.rpy").writeText(characterSource(pairs))
        File(game, "options.rpy").writeText(
            buildString {
                appendLine("define config.name = _(\"$configName\")")
                appendLine("define build.name = \"$buildName\"")
                if (version != null) appendLine("define config.version = \"$version\"")
                appendLine("define config.save_directory = \"$saveDirectory\"")
            },
        )
        return validateManagedGame(
            ManagedGame(
                id = UUID.randomUUID().toString(),
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

    // ------------------------------------------------------------ pure matcher

    @Test
    fun aProvenIdentityAndProvenVersionNeedsNoOverride() {
        val staging = patchStaging(versionLabels = listOf("v0_16"))
        val patch = patchEvidence(staging)
        val installed = installedGame("Grandma's House", version = "0.16")

        val matched = PatchTargetMatcher.match(patch, listOf(probe(installed))) as PatchMatchOutcome.Matched
        assertEquals(installed.id, matched.target.managedGameId)
    }

    @Test
    fun theGrandmasHouseV016CaseIsAUniqueTargetWithAnUnprovenVersion() {
        val patch = patchEvidence(patchStaging())
        val installed = installedGame("GrandmasHouse-V0.16-pc")
        val unrelated = installedGame(
            "Midnight Paradise",
            pairs = (1..12).map { "m$it" to "Other$it" },
            version = "0.20",
            buildName = "MidnightParadise",
            saveDirectory = "MidnightParadise-42",
        )

        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed), probe(unrelated)))
        val overridable = outcome as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.Unproven, overridable.compatibility)
        assertEquals(installed.id, overridable.target.managedGameId)
        assertEquals("GrandmasHouse-V0.16-pc", overridable.target.label)
        assertEquals("v002", overridable.patchVersionMarker)
        assertEquals("V0.16", overridable.installedVersion)
        assertTrue(overridable.reason.contains("no defined mapping"))
        assertTrue("identity itself is still proven", overridable.proofs.any { it.contains("Character") })
    }

    @Test
    fun theFileNameVersionHintNeverApprovesAPatchOnItsOwn() {
        // The archive says V0.16 and the installed game says V0.16, but only the archive *name*
        // says so, and a name is never evidence. The outcome must still be an override.
        val patch = patchEvidence(patchStaging(), archiveName = "GH V0.16 Taboo Patch.zip")
        val installed = installedGame("GrandmasHouse-V0.16-pc", version = "V0.16")
        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed)))
        assertTrue(outcome is PatchMatchOutcome.Overridable)
        assertEquals(
            PatchCompatibility.Unproven,
            (outcome as PatchMatchOutcome.Overridable).compatibility,
        )
    }

    @Test
    fun aProvenVersionMismatchIsOverridableAndLabelledAsAMismatch() {
        val staging = patchStaging(versionLabels = listOf("v0_16"))
        val patch = patchEvidence(staging)
        val installed = installedGame("Grandma's House", version = "0.14")

        val overridable =
            PatchTargetMatcher.match(patch, listOf(probe(installed))) as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.ExplicitMismatch, overridable.compatibility)
        assertEquals("0.14", overridable.installedVersion)
        assertTrue(overridable.reason.contains("resolves to 0.16"))
    }

    // -------------------------------------------- declared identity, marker-only version evidence

    /** Options.rpy identity (build.name + config.save_directory) but no config.version at all. */
    private fun declaredIdentityOptions(version: String? = null) = RenPyOptions(
        buildName = "GrandmasHouse",
        version = version,
        saveDirectory = "GrandmasHouse-1629239078",
    )

    @Test
    fun aDeclaredIdentityWithNoConfigVersionAndAnAmbiguousMarkerIsAnUnprovenOverride() {
        // Regression: identity proven from options.rpy used to skip the marker rules entirely and
        // report Proven, silently claiming a fit AGM never established.
        val patch = patchEvidence(patchStaging(), options = declaredIdentityOptions())
        val installed = installedGame("GrandmasHouse-V0.16-pc", version = "V0.16")

        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed)))
        val overridable = outcome as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.Unproven, overridable.compatibility)
        assertEquals(installed.id, overridable.target.managedGameId)
        assertEquals("v002", overridable.patchVersionMarker)
        assertEquals("V0.16", overridable.installedVersion)
        assertTrue(overridable.reason.contains("no defined mapping"))
        assertTrue("identity is still proven by options.rpy", overridable.proofs.any { it.contains("build.name") })
    }

    @Test
    fun aDeclaredIdentityWithNoConfigVersionAndACleanMarkerCanStillMismatch() {
        val patch = patchEvidence(
            patchStaging(versionLabels = listOf("v0_16")),
            options = declaredIdentityOptions(),
        )
        val installed = installedGame("Grandma's House", version = "0.14")

        val overridable =
            PatchTargetMatcher.match(patch, listOf(probe(installed))) as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.ExplicitMismatch, overridable.compatibility)
        assertEquals("0.14", overridable.installedVersion)
        assertEquals("v0_16", overridable.patchVersionMarker)
        assertTrue(overridable.reason.contains("resolves to 0.16"))
    }

    @Test
    fun aDeclaredConfigVersionIsAuthoritativeOverAContradictoryMarker() {
        // Defined behaviour: a config.version AGM can read is the patch's own statement of what it
        // targets, so when it agrees with the installed build the match is Proven and needs no
        // override — a stale gallery label such as `v0_99` never downgrades it, and equally never
        // upgrades a config.version that disagrees.
        val patch = patchEvidence(
            patchStaging(versionLabels = listOf("v0_99")),
            options = declaredIdentityOptions(version = "0.16"),
        )
        val installed = installedGame("Grandma's House", version = "V0.16")

        val matched =
            PatchTargetMatcher.match(patch, listOf(probe(installed))) as PatchMatchOutcome.Matched
        assertEquals(installed.id, matched.target.managedGameId)

        // The mirror case: the declared version alone decides the mismatch, and its wording — not
        // the marker's — is what the user is shown.
        val mismatching = patchEvidence(
            patchStaging(versionLabels = listOf("v0_14")),
            options = declaredIdentityOptions(version = "0.16"),
        )
        val other = installedGame("Grandma's House Older", version = "0.14")
        val overridable = PatchTargetMatcher.match(mismatching, listOf(probe(other)))
            as PatchMatchOutcome.Overridable
        assertEquals(PatchCompatibility.ExplicitMismatch, overridable.compatibility)
        assertTrue(overridable.reason.contains("targets version 0.16"))
        assertFalse("the marker never speaks for a declared version", overridable.reason.contains("resolves to"))
    }

    @Test
    fun aDeclaredIdentityWithNoVersionEvidenceAtAllStillMatches() {
        // Neither side makes a version claim AGM knows how to read, so there is nothing to prove
        // wrong and nothing to override.
        val patch = patchEvidence(
            patchStaging(versionLabels = emptyList()),
            options = declaredIdentityOptions(),
        )
        val installed = installedGame("Grandma's House", version = null)
        assertTrue(
            PatchTargetMatcher.match(patch, listOf(probe(installed))) is PatchMatchOutcome.Matched,
        )
    }

    @Test
    fun thePreviewHeadlineNeverClaimsTheVersionFitsForAnUnresolvedMarker() {
        val patch = patchEvidence(patchStaging(), options = declaredIdentityOptions())
        val installed = installedGame("GrandmasHouse-V0.16-pc", version = "V0.16")
        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed)))

        val shown = previewFor(outcome)
        assertEquals(PatchCompatibility.Unproven, shown.compatibility)
        assertTrue(shown.requiresOverrideAcknowledgement)
        val headline = PatchOverrideGate.headline(shown)
        assertFalse(
            "an unresolved marker may never be headlined as a proven fit",
            headline.contains("belongs to this game and fits"),
        )
        assertTrue(headline.contains("could not prove the patch fits"))
        assertFalse(PatchOverrideGate.mayInstall(shown, acknowledged = false))
        assertEquals("Install anyway", PatchOverrideGate.confirmLabel(shown))
    }

    /** The same outcome -> preview mapping [PatchInstallFlow] performs, without Android. */
    private fun previewFor(outcome: PatchMatchOutcome): PatchInstallPreview {
        val compatibility: PatchCompatibility
        val reason: String?
        when (outcome) {
            is PatchMatchOutcome.Matched -> {
                compatibility = PatchCompatibility.Proven
                reason = null
            }
            is PatchMatchOutcome.Overridable -> {
                compatibility = outcome.compatibility
                reason = outcome.reason
            }
            is PatchMatchOutcome.Refused -> throw AssertionError("expected a target: ${outcome.reason}")
        }
        return preview(compatibility, reason = reason)
    }

    @Test
    fun aPatchWithNoIdentityIsRefusedWithNoOverride() {
        val patch = patchEvidence(patchStaging(pairs = cast.take(3)))
        val installed = installedGame("Grandma's House")
        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed)))
        assertTrue(outcome is PatchMatchOutcome.Refused)
        assertTrue((outcome as PatchMatchOutcome.Refused).reason.contains("no concrete Ren'Py identity"))
    }

    @Test
    fun anAmbiguousIdentityIsRefusedWithNoOverride() {
        val patch = patchEvidence(patchStaging())
        val first = installedGame("Grandma's House")
        val second = installedGame("Grandmas House Copy", saveDirectory = "GrandmasHouseCopy-1")
        val outcome = PatchTargetMatcher.match(patch, listOf(probe(first), probe(second)))
        assertTrue(outcome is PatchMatchOutcome.Refused)
        assertTrue((outcome as PatchMatchOutcome.Refused).reason.contains("more than one managed game"))
    }

    @Test
    fun contradictoryIdentityIsRefusedWithNoOverride() {
        val patch = patchEvidence(
            patchStaging(versionLabels = emptyList()),
            options = RenPyOptions(configName = "Totally Different Game"),
        )
        val installed = installedGame("Grandma's House", configName = "Grandma's House")
        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed)))
        assertTrue(outcome is PatchMatchOutcome.Refused)
        assertTrue((outcome as PatchMatchOutcome.Refused).reason.contains("contradictory evidence"))
    }

    @Test
    fun aNonRenPyArchiveIsRefusedWithNoOverride() {
        val patch = patchEvidence(patchStaging(), engine = PatchEngine.Unknown)
        val installed = installedGame("Grandma's House")
        val outcome = PatchTargetMatcher.match(patch, listOf(probe(installed)))
        assertTrue(outcome is PatchMatchOutcome.Refused)
        assertTrue((outcome as PatchMatchOutcome.Refused).reason.contains("only installs Ren'Py"))
    }

    @Test
    fun anUnsafeArchiveIsRejectedBeforeIdentityIsEvenConsidered() {
        // Archive safety is not overridable, so it never produces a match outcome at all.
        val traversal = PatchArchiveScanner.scan(
            listOf(PatchArchiveEntry("../evil.rpy", 12L, PatchEntryKind.File)),
        )
        assertTrue(traversal is PatchArchiveScan.Rejected)
        val reserved = PatchArchiveScanner.scan(
            listOf(PatchArchiveEntry(".agm-owner/script.rpy", 12L, PatchEntryKind.File)),
        )
        assertTrue(reserved is PatchArchiveScan.Rejected)
        val link = PatchArchiveScanner.scan(
            listOf(PatchArchiveEntry("script.rpy", 0L, PatchEntryKind.Link)),
        )
        assertTrue(link is PatchArchiveScan.Rejected)
    }

    // --------------------------------------------------------------- the gate

    private fun preview(
        compatibility: PatchCompatibility,
        targetId: String = "game-1",
        reason: String? = "AGM cannot prove the versions match.",
    ) = PatchInstallPreview(
        archiveName = "GH V0.16 Taboo Patch.zip",
        engineLabel = "Ren'Py",
        patchIdentityLines = listOf("Engine: Ren'Py"),
        proofs = listOf("47 of 48 Character definitions match"),
        targetManagedGameId = targetId,
        targetLabel = "GrandmasHouse-V0.16-pc",
        targetStoragePath = "/storage/games/GrandmasHouse-V0.16-pc",
        targetIdentityLines = listOf("Game config.version: V0.16"),
        destinationRoot = "/storage/games/GrandmasHouse-V0.16-pc",
        additions = listOf("game/gallery_scenes.rpy"),
        replacements = listOf("game/variables.rpy"),
        totalBytes = 2048L,
        compatibility = compatibility,
        compatibilityReason = if (compatibility == PatchCompatibility.Proven) null else reason,
        installedVersion = "V0.16",
        patchVersionMarker = "v002",
        patchVersionHint = "0.16",
    )

    @Test
    fun aProvenPreviewInstallsWithoutAnyAcknowledgement() {
        val proven = preview(PatchCompatibility.Proven)
        assertFalse(proven.requiresOverrideAcknowledgement)
        assertNull(PatchOverrideGate.blockReason(proven, acknowledged = false))
        assertTrue(PatchOverrideGate.mayInstall(proven, acknowledged = false))
        assertEquals("Install patch", PatchOverrideGate.confirmLabel(proven))
        assertEquals("Install patch?", PatchOverrideGate.title(proven))
        assertNull(PatchOverrideGate.overrideReason(proven, acknowledged = true))
    }

    @Test
    fun anUnprovenPreviewIsBlockedUntilTheAcknowledgementIsTicked() {
        val unproven = preview(PatchCompatibility.Unproven)
        assertTrue(unproven.requiresOverrideAcknowledgement)
        assertNotNull(PatchOverrideGate.blockReason(unproven, acknowledged = false))
        assertFalse(PatchOverrideGate.mayInstall(unproven, acknowledged = false))
        assertTrue(PatchOverrideGate.mayInstall(unproven, acknowledged = true))
        assertEquals("Compatibility not verified", PatchOverrideGate.title(unproven))
        assertEquals("Install anyway", PatchOverrideGate.confirmLabel(unproven))
    }

    @Test
    fun aMismatchPreviewIsLabelledMoreStronglyButOverridableTheSameWay() {
        val mismatch = preview(PatchCompatibility.ExplicitMismatch, reason = "Patch is for 0.16, game is 0.14.")
        assertEquals("Incompatible version", PatchOverrideGate.title(mismatch))
        assertEquals("Install anyway", PatchOverrideGate.confirmLabel(mismatch))
        assertFalse(PatchOverrideGate.mayInstall(mismatch, acknowledged = false))
        assertTrue(PatchOverrideGate.mayInstall(mismatch, acknowledged = true))
        assertEquals(
            "Patch is for 0.16, game is 0.14.",
            PatchOverrideGate.overrideReason(mismatch, acknowledged = true),
        )
    }

    @Test
    fun theAcknowledgementNamesTheExactTargetAndPromisesRollback() {
        val unproven = preview(PatchCompatibility.Unproven)
        val label = PatchOverrideGate.acknowledgementLabel(unproven)
        assertTrue(label.contains("may break GrandmasHouse-V0.16-pc"))
        assertTrue(label.contains("/storage/games/GrandmasHouse-V0.16-pc"))
        assertTrue(PatchOverrideGate.rollbackStatement(unproven).contains("Roll back"))
    }

    @Test
    fun anOverrideReasonIsOnlyProducedForAnAcknowledgedOverride() {
        val unproven = preview(PatchCompatibility.Unproven)
        assertNull(PatchOverrideGate.overrideReason(unproven, acknowledged = false))
        assertEquals(
            "AGM cannot prove the versions match.",
            PatchOverrideGate.overrideReason(unproven, acknowledged = true),
        )
    }

    @Test
    fun thereIsNothingToConfirmWithoutAPreview() {
        assertNotNull(PatchOverrideGate.blockReason(null, acknowledged = true))
        assertFalse(PatchOverrideGate.mayInstall(null, acknowledged = true))
    }

    @Test
    fun theOverrideLogLineNamesTheDecisionTargetAndReasonOnly() {
        val line = PatchOverrideGate.logLine(preview(PatchCompatibility.Unproven))
        assertTrue(line.contains("User-confirmed compatibility override"))
        assertTrue(line.contains("compatibility=Unproven"))
        assertTrue(line.contains("managedGameId=game-1"))
        assertTrue(line.contains("label=GrandmasHouse-V0.16-pc"))
        assertTrue(line.contains("installedVersion=V0.16"))
        assertTrue(line.contains("patchVersionMarker=v002"))
        assertTrue(line.contains("reason=AGM cannot prove the versions match."))
        assertFalse("no source content is ever logged", line.contains("define "))
    }

    // ------------------------------------------------------------- persistence

    private fun record(
        overridden: Boolean,
        reason: String?,
    ) = PatchTransactionRecord(
        transactionId = "tx-1",
        phase = PatchTransactionPhase.Committed,
        managedGameId = "game-1",
        label = "GrandmasHouse-V0.16-pc",
        storagePath = "/storage/games/GrandmasHouse-V0.16-pc",
        destinationRoot = "/storage/games/GrandmasHouse-V0.16-pc",
        workDir = "/storage/games/GrandmasHouse-V0.16-pc/.agm-patch-tx-1",
        backupDir = "/storage/games/GrandmasHouse-V0.16-pc/.agm-patch-tx-1/backup",
        archiveName = "GH V0.16 Taboo Patch.zip",
        added = emptyList(),
        replaced = emptyList(),
        createdDirectories = emptyList(),
        compatibilityOverridden = overridden,
        compatibilityOverrideReason = reason,
    )

    @Test
    fun theOverrideAndItsReasonSurviveAJournalRoundTrip() {
        val stored = record(overridden = true, reason = "Marker v002 has no defined mapping.")
        val decoded = PatchTransactionStore.decode(PatchTransactionStore.encode(listOf(stored))).single()
        assertTrue(decoded.compatibilityOverridden)
        assertEquals("Marker v002 has no defined mapping.", decoded.compatibilityOverrideReason)
        assertTrue(
            PatchOverrideGate.installedNote(decoded.compatibilityOverrideReason)
                .contains("explicit compatibility override"),
        )
        // An overridden record is a completely ordinary rollback point.
        assertTrue(decoded.isUsableRollbackPoint)
        assertFalse(decoded.isUnresolved)
        assertEquals(
            listOf(PatchRecordAction.RollBack, PatchRecordAction.DiscardBackup),
            patchRecordActions(decoded),
        )
    }

    @Test
    fun aJournalRecordWrittenBeforeOverridesExistedStillReads() {
        val legacy = PatchTransactionStore.encode(listOf(record(overridden = false, reason = null)))
            .replace(",\"compatibilityOverridden\":false", "")
        val decoded = PatchTransactionStore.decode(legacy).single()
        assertFalse(decoded.compatibilityOverridden)
        assertNull(decoded.compatibilityOverrideReason)
    }

    @Test
    fun anInstallResultReportsTheOverrideItRanUnder() {
        val plain = PatchInstallResult(
            transactionId = "tx-1",
            managedGameId = "game-1",
            label = "GrandmasHouse-V0.16-pc",
            storagePath = "/storage/games/GrandmasHouse-V0.16-pc",
            destinationRoot = "/storage/games/GrandmasHouse-V0.16-pc",
            archiveName = "GH V0.16 Taboo Patch.zip",
            addedCount = 1,
            replacedCount = 1,
        )
        assertFalse(plain.compatibilityOverridden)
        val overridden = plain.copy(
            compatibilityOverridden = true,
            compatibilityOverrideReason = "Marker v002 has no defined mapping.",
        )
        assertTrue(overridden.compatibilityOverridden)
        assertTrue(
            PatchOverrideGate.installedNote(overridden.compatibilityOverrideReason)
                .contains("Marker v002"),
        )
    }
}
