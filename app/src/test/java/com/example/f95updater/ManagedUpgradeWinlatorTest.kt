package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManagedUpgradeWinlatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val plan = ManagedUpgradeWinlatorPlan(
        winlatorGameId = "winlator-game-1",
        oldGamePath = "/storage/emulated/0/Games/Old/bin",
        oldExecutablePath = "/storage/emulated/0/Games/Old/bin/Game.exe",
        newGamePath = "/storage/emulated/0/Games/New/bin",
        newExecutablePath = "/storage/emulated/0/Games/New/bin/Game.exe",
    )

    @Test
    fun portableLocationExtrasUseWinlatorsNativeKeys() {
        val extras = WinlatorApi.portableLocationExtras(
            gamePath = "/storage/emulated/0/Games/New/bin",
            executablePath = "/storage/emulated/0/Games/New/bin/Game.exe",
        )

        assertEquals(listOf("game_path", "executable_path"), extras.keys.toList())
        assertEquals("/storage/emulated/0/Games/New/bin", extras["game_path"])
        assertEquals("/storage/emulated/0/Games/New/bin/Game.exe", extras["executable_path"])
    }

    @Test
    fun portableLocationExtrasRejectBlankPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            WinlatorApi.portableLocationExtras("", "/a/Game.exe")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WinlatorApi.portableLocationExtras("/a", "  ")
        }
    }

    @Test
    fun verificationRecognisesTheNewLocation() {
        assertEquals(
            ManagedUpgradeWinlatorVerification.Location.NewPath,
            ManagedUpgradeWinlatorVerification.classify(
                plan.newGamePath,
                plan.newExecutablePath,
                plan,
            ),
        )
    }

    @Test
    fun verificationRecognisesTheUnchangedOldLocation() {
        assertEquals(
            ManagedUpgradeWinlatorVerification.Location.OldPath,
            ManagedUpgradeWinlatorVerification.classify(
                "/storage/emulated/0/Games/Old/bin/",
                "/storage/emulated/0/Games/Old/./bin/Game.exe",
                plan,
            ),
        )
    }

    @Test
    fun verificationNeverGuessesWhenWinlatorPointsSomewhereElse() {
        assertEquals(
            ManagedUpgradeWinlatorVerification.Location.Unknown,
            ManagedUpgradeWinlatorVerification.classify(null, null, plan),
        )
        assertEquals(
            ManagedUpgradeWinlatorVerification.Location.Unknown,
            ManagedUpgradeWinlatorVerification.classify(
                plan.newGamePath,
                plan.oldExecutablePath,
                plan,
            ),
        )
        assertEquals(
            ManagedUpgradeWinlatorVerification.Location.Unknown,
            ManagedUpgradeWinlatorVerification.classify(
                "/storage/emulated/0/Games/Third/bin",
                "/storage/emulated/0/Games/Third/bin/Game.exe",
                plan,
            ),
        )
    }

    @Test
    fun cleanupFolderNameIsUniqueAndPrefixed() {
        val parent = temp.newFolder("root")
        val first = uniqueUpgradedFolder(parent, "My Game")
        assertEquals("upgraded_to_delete_My Game", first.name)

        File(parent, "upgraded_to_delete_My Game").mkdirs()
        val second = uniqueUpgradedFolder(parent, "My Game")
        assertEquals("upgraded_to_delete_My Game_2", second.name)

        File(parent, "upgraded_to_delete_My Game_2").mkdirs()
        assertEquals("upgraded_to_delete_My Game_3", uniqueUpgradedFolder(parent, "My Game").name)
        assertTrue(uniqueUpgradedFolder(parent, "Other").parentFile == parent)
    }
}
