package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ManagedGameDiscoveryTest {
    @Test
    fun renpyFolderOffersJoiPlayAndWinlator() {
        val root = Files.createTempDirectory("managed-renpy").toFile()
        try {
            root.resolve("renpy").mkdir()
            root.resolve("game").mkdir()
            root.resolve("game/script.rpy").writeText("label start:")
            root.resolve("Game.exe").writeText("exe")

            val inspection = ManagedGameDiscovery.inspect(
                root = root,
                joiPlayAvailable = true,
                winlatorAvailable = true,
                kirikiroidAvailable = true,
            )

            assertEquals(InstallRecommendation.GameEngine.RENPY, inspection.advice.engine)
            assertTrue(inspection.candidate(ManagedRunnerKind.JoiPlay).compatible)
            assertTrue(inspection.candidate(ManagedRunnerKind.Winlator).compatible)
            assertFalse(inspection.candidate(ManagedRunnerKind.Kirikiroid).compatible)
            val joi = inspection.candidate(ManagedRunnerKind.JoiPlay).binding
                as ManagedRunnerBinding.JoiPlay
            assertEquals("renpy", joi.type)
            assertEquals("Game.exe", joi.execFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun kirikiriFolderOffersKirikiroidAndWinlatorWhenExeExists() {
        val root = Files.createTempDirectory("managed-kirikiri").toFile()
        try {
            root.resolve("data.xp3").writeText("xp3")
            root.resolve("game.exe").writeText("exe")

            val inspection = ManagedGameDiscovery.inspect(
                root = root,
                joiPlayAvailable = true,
                winlatorAvailable = true,
                kirikiroidAvailable = true,
            )

            assertEquals(InstallRecommendation.GameEngine.KIRIKIRI, inspection.advice.engine)
            assertTrue(inspection.candidate(ManagedRunnerKind.Kirikiroid).compatible)
            assertTrue(inspection.candidate(ManagedRunnerKind.Winlator).compatible)
            assertFalse(inspection.candidate(ManagedRunnerKind.JoiPlay).compatible)
            val binding = inspection.candidate(ManagedRunnerKind.Kirikiroid).binding
                as ManagedRunnerBinding.Kirikiroid
            assertEquals("data.xp3", binding.startupPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun kirikiroidPrefersDataArchiveOverAlphabeticallyEarlierAssetArchive() {
        val root = Files.createTempDirectory("managed-kirikiroid-data").toFile()
        try {
            root.resolve("bgm.xp3").writeText("bgm")
            root.resolve("data.xp3").writeText("data")

            val inspection = ManagedGameDiscovery.inspect(
                root = root,
                joiPlayAvailable = false,
                winlatorAvailable = false,
                kirikiroidAvailable = true,
            )

            val binding = inspection.candidate(ManagedRunnerKind.Kirikiroid).binding
                as ManagedRunnerBinding.Kirikiroid
            assertEquals("data.xp3", binding.startupPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun htmlWithoutExeCannotEnableWinlator() {
        val root = Files.createTempDirectory("managed-html").toFile()
        try {
            root.resolve("index.html").writeText("<html></html>")
            val inspection = ManagedGameDiscovery.inspect(
                root = root,
                joiPlayAvailable = true,
                winlatorAvailable = true,
                kirikiroidAvailable = true,
            )

            assertTrue(inspection.candidate(ManagedRunnerKind.JoiPlay).compatible)
            assertFalse(inspection.candidate(ManagedRunnerKind.Winlator).compatible)
            assertNotNull(inspection.candidate(ManagedRunnerKind.JoiPlay).binding)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bulkDiscoveryAddsMissingEnginesWithoutReplacingExistingMetadata() {
        val root = Files.createTempDirectory("managed-merge").toFile()
        try {
            root.resolve("renpy").mkdir()
            root.resolve("game").mkdir()
            root.resolve("game/script.rpy").writeText("label start:")
            root.resolve("Game.exe").writeText("exe")
            val inspection = ManagedGameDiscovery.inspect(
                root = root,
                joiPlayAvailable = true,
                winlatorAvailable = true,
                kirikiroidAvailable = true,
            )
            val existing = ManagedRunnerBinding.JoiPlay(
                type = "renpy",
                execFile = "custom.py",
                importId = "legacy-id",
            )

            val merged = ManagedGameDiscovery.mergeDiscoveredBindings(
                existing = listOf(existing),
                inspection = inspection,
            )

            assertEquals(existing, merged.single { it.kind == ManagedRunnerKind.JoiPlay })
            assertFalse(merged.single { it.kind == ManagedRunnerKind.Winlator }.enabled)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ManagedGameInspection.candidate(kind: ManagedRunnerKind): ManagedRunnerCandidate =
        candidates.single { it.kind == kind }
}
