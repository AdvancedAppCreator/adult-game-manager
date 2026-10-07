package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedUpgradeIdentityTest {

    private var nextId = 0

    private fun managed(label: String, folder: String = label) = InstalledApp(
        packageName = "managed:$label",
        label = label,
        versionName = "",
        versionCode = 0L,
        source = AppSource.Managed,
        managedGameId = "0000000${nextId++}-1111-1111-1111-111111111111",
        managedDefaultRunner = ManagedRunnerKind.JoiPlay,
        storagePath = "/storage/emulated/0/Games/$folder",
    )

    private fun hint(
        value: String,
        evidence: ManagedNameEvidence = ManagedNameEvidence.ArchiveName,
        source: String = "test",
    ) = ManagedNameHint(value, evidence, source)

    // ------------------------------------------------------------- identity

    @Test
    fun genericReleaseWordsAreNotIdentity() {
        assertTrue(ManagedUpgradeIdentity.identity("Release").isBlank)
        assertTrue(ManagedUpgradeIdentity.identity("PC Compressed Public Patch").isBlank)
        assertTrue(ManagedUpgradeIdentity.identity("v0.5-pc").isBlank)
        assertEquals(
            listOf("eternum"),
            ManagedUpgradeIdentity.identity("Eternum-0.8-pc-compressed").words,
        )
    }

    @Test
    fun possessivesAndSeparatorsNormaliseToTheSameKey() {
        assertEquals(
            ManagedUpgradeIdentity.identity("Grandma's House").key,
            ManagedUpgradeIdentity.identity("GrandmasHouse").key,
        )
        assertEquals(
            ManagedUpgradeIdentity.identity("Midnight Paradise").key,
            ManagedUpgradeIdentity.identity("Midnight_Paradise-v0.20-pc").key,
        )
    }

    @Test
    fun oneSharedWordIsNeverIdentity() {
        val a = ManagedUpgradeIdentity.identity("Summer Heat")
        val b = ManagedUpgradeIdentity.identity("Winter Heat")
        assertNull(ManagedUpgradeIdentity.relate(a, b))

        val c = ManagedUpgradeIdentity.identity("Grandma's House")
        val d = ManagedUpgradeIdentity.identity("House Party")
        assertNull(ManagedUpgradeIdentity.relate(c, d))
    }

    @Test
    fun aLongerTitleThatMerelyExtendsAWordIsNotContainment() {
        assertNull(
            ManagedUpgradeIdentity.relate(
                ManagedUpgradeIdentity.identity("Summer Heat"),
                ManagedUpgradeIdentity.identity("Summer Heatwave"),
            ),
        )
    }

    @Test
    fun aWholeTitleInsideAnotherIsContainment() {
        assertEquals(
            ManagedUpgradeIdentity.Relation.Contains,
            ManagedUpgradeIdentity.relate(
                ManagedUpgradeIdentity.identity("Long Live The Princess"),
                ManagedUpgradeIdentity.identity("Long Live The Princess Chapter Five"),
            ),
        )
    }

    @Test
    fun aShortSingleWordTitleIsNotDistinctiveEnough() {
        assertNull(
            ManagedUpgradeIdentity.relate(
                ManagedUpgradeIdentity.identity("Ash"),
                ManagedUpgradeIdentity.identity("Ash"),
            ),
        )
        assertEquals(
            ManagedUpgradeIdentity.Relation.Exact,
            ManagedUpgradeIdentity.relate(
                ManagedUpgradeIdentity.identity("Eternum"),
                ManagedUpgradeIdentity.identity("Eternum"),
            ),
        )
    }

    // ------------------------------------------------------------ candidates

    @Test
    fun unrelatedArchivesSharingAGenericWordProduceNoCandidate() {
        val apps = listOf(managed("Midnight Paradise"), managed("Grandma's House"))
        listOf(
            "Public Release v3",
            "Game Patch 2024",
            "Paradise",
            "House",
            "Compressed Android Port",
        ).forEach { name ->
            assertEquals(
                name,
                emptyList<ManagedUpgradeCandidate>(),
                ManagedUpgradeIdentity.candidates(listOf(hint(name)), apps),
            )
        }
    }

    @Test
    fun aVersionSuffixedArchiveNameStillMatchesTheSameTitle() {
        val apps = listOf(managed("Midnight Paradise"), managed("Grandma's House"))
        val candidates = ManagedUpgradeIdentity.candidates(
            listOf(hint("Midnight Paradise 0 20 pc", source = "MidnightParadise-0.20-pc.zip")),
            apps,
        )
        assertEquals(1, candidates.size)
        assertEquals("Midnight Paradise", candidates.single().app.label)
        assertTrue(candidates.single().exact)
        assertTrue(candidates.single().reason.contains("Midnight Paradise"))
    }

    @Test
    fun metadataOutranksAndOverridesAContradictingFileName() {
        val apps = listOf(managed("Midnight Paradise"), managed("Grandma's House"))
        val candidates = ManagedUpgradeIdentity.candidates(
            listOf(
                hint("Grandma's House", ManagedNameEvidence.Metadata, "game/options.rpy"),
                hint("Midnight Paradise", ManagedNameEvidence.ArchiveName, "MidnightParadise.zip"),
            ),
            apps,
        )
        assertEquals(1, candidates.size)
        assertEquals("Grandma's House", candidates.single().app.label)
        assertEquals(ManagedNameEvidence.Metadata, candidates.single().evidence)
        assertTrue(candidates.single().reason.contains("game/options.rpy"))
    }

    @Test
    fun twoEquallyMatchingGamesResolveToNoCandidate() {
        val apps = listOf(
            managed("Midnight Paradise", folder = "MidnightParadise-A"),
            managed("Midnight Paradise", folder = "MidnightParadise-B"),
        )
        assertEquals(
            emptyList<ManagedUpgradeCandidate>(),
            ManagedUpgradeIdentity.candidates(listOf(hint("Midnight Paradise")), apps),
        )
    }

    @Test
    fun onlyManagedRowsWithAnIdAndFolderCanBeUpgraded() {
        val android = InstalledApp(
            packageName = "com.example.midnight",
            label = "Midnight Paradise",
            versionName = "1",
            versionCode = 1L,
            source = AppSource.Android,
        )
        val noPath = managed("Midnight Paradise").copy(storagePath = null)
        val noId = managed("Midnight Paradise").copy(managedGameId = null)
        assertEquals(
            emptyList<ManagedUpgradeCandidate>(),
            ManagedUpgradeIdentity.candidates(listOf(hint("Midnight Paradise")), listOf(android, noPath, noId)),
        )
    }

    @Test
    fun aStructuralFolderNameMatchesWhenNoMetadataExists() {
        val apps = listOf(managed("Grandma's House"))
        val candidates = ManagedUpgradeIdentity.candidates(
            listOf(
                hint("GrandmasHouse 0 110", ManagedNameEvidence.Structure, "folder \u201CGrandmasHouse-0.110\u201D"),
                hint("Unrelated Upload", ManagedNameEvidence.ArchiveName, "upload.zip"),
            ),
            apps,
        )
        assertEquals(1, candidates.size)
        assertEquals(ManagedNameEvidence.Structure, candidates.single().evidence)
    }
}
