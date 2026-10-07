package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedUpgradePendingStateTest {
    private val id = "55555555-5555-5555-5555-555555555555"

    private fun game(path: String) = validateManagedGame(
        ManagedGame(
            id = id,
            canonicalPath = canonicalManagedGamePath(path),
            storagePath = path,
            storageFolderName = path.substringAfterLast('/'),
            label = "Journalled Game",
            versionName = "1.2",
            versionCode = 4L,
            firstInstallTime = 1L,
            lastUpdateTime = 2L,
            readmeTitle = "Readme",
            defaultRunner = ManagedRunnerKind.Winlator,
            runnerBindings = listOf(
                ManagedRunnerBinding.JoiPlay(
                    enabled = false,
                    type = "renpy",
                    execFile = "Game.sh",
                    importId = "import-1",
                ),
                ManagedRunnerBinding.Winlator(
                    managedId = "winlator-game-1",
                    executablePath = "$path/bin/Game.exe",
                    containerId = 9,
                    configJson = """{"screenSize":"1280x720"}""",
                ),
                ManagedRunnerBinding.Kirikiroid(
                    startupPath = "data.xp3",
                    metadata = mapOf("locale" to "ja"),
                ),
            ),
            createdAt = 10L,
            updatedAt = 11L,
        ),
    )

    @Test
    fun journalRoundTripsEveryRunnerBindingAndPhase() {
        val state = ManagedUpgradePendingState(
            phase = ManagedUpgradePhase.WinlatorRequested,
            managedGameId = id,
            label = "Journalled Game",
            oldRootPath = "/storage/emulated/0/Games/Old",
            oldCanonicalPath = "/storage/emulated/0/Games/Old",
            newRootPath = "/storage/emulated/0/Games/New",
            newCanonicalPath = "/storage/emulated/0/Games/New",
            sourceArchivePath = "/storage/emulated/0/Download/game-1.2.zip",
            saveItemsCopied = 3,
            originalGame = game("/storage/emulated/0/Games/Old"),
            replacementGame = game("/storage/emulated/0/Games/New"),
            winlator = ManagedUpgradeWinlatorPlan(
                winlatorGameId = "winlator-game-1",
                oldGamePath = "/storage/emulated/0/Games/Old/bin",
                oldExecutablePath = "/storage/emulated/0/Games/Old/bin/Game.exe",
                newGamePath = "/storage/emulated/0/Games/New/bin",
                newExecutablePath = "/storage/emulated/0/Games/New/bin/Game.exe",
            ),
            savedAt = 1234L,
        )

        val restored = ManagedUpgradePendingStore.decode(ManagedUpgradePendingStore.encode(state))

        assertEquals(state, restored)
        assertEquals(ManagedUpgradePhase.WinlatorRequested, restored.phase)
        assertEquals(3, restored.saveItemsCopied)
        assertEquals(id, restored.replacementGame.id)
        assertEquals(
            listOf(ManagedRunnerKind.JoiPlay, ManagedRunnerKind.Winlator, ManagedRunnerKind.Kirikiroid),
            restored.replacementGame.runnerBindings.map { it.kind },
        )
        assertEquals(
            "import-1",
            restored.replacementGame.runnerBindings
                .filterIsInstance<ManagedRunnerBinding.JoiPlay>().single().importId,
        )
        assertEquals(
            9,
            restored.replacementGame.runnerBindings
                .filterIsInstance<ManagedRunnerBinding.Winlator>().single().containerId,
        )
        assertNotNull(restored.winlator)
    }

    @Test
    fun theArchiveDeletionChoiceRoundTripsAndDefaultsToKeepingIt() {
        val state = ManagedUpgradePendingState(
            phase = ManagedUpgradePhase.StoreRelocationPending,
            managedGameId = id,
            label = "Journalled Game",
            oldRootPath = "/storage/emulated/0/Games/Old",
            oldCanonicalPath = "/storage/emulated/0/Games/Old",
            newRootPath = "/storage/emulated/0/Games/New",
            newCanonicalPath = "/storage/emulated/0/Games/New",
            sourceArchivePath = "/storage/emulated/0/Download/game-1.2.zip",
            originalGame = game("/storage/emulated/0/Games/Old"),
            replacementGame = game("/storage/emulated/0/Games/New"),
        )

        assertFalse("keeping the archive is the default", state.deleteSourceArchive)
        assertTrue(
            ManagedUpgradePendingStore.decode(
                ManagedUpgradePendingStore.encode(state.copy(deleteSourceArchive = true)),
            ).deleteSourceArchive,
        )
        assertFalse(
            ManagedUpgradePendingStore.decode(ManagedUpgradePendingStore.encode(state)).deleteSourceArchive,
        )
    }

    @Test
    fun aJournalWrittenBeforeTheChoiceExistedKeepsTheArchive() {
        // Exactly what an older build persisted: no deleteSourceArchive key at all.
        val legacy = ManagedUpgradePendingStore.encode(
            ManagedUpgradePendingState(
                phase = ManagedUpgradePhase.WinlatorRequested,
                managedGameId = id,
                label = "Journalled Game",
                oldRootPath = "/storage/emulated/0/Games/Old",
                oldCanonicalPath = "/storage/emulated/0/Games/Old",
                newRootPath = "/storage/emulated/0/Games/New",
                newCanonicalPath = "/storage/emulated/0/Games/New",
                sourceArchivePath = "/storage/emulated/0/Download/game-1.2.zip",
                deleteSourceArchive = true,
                originalGame = game("/storage/emulated/0/Games/Old"),
                replacementGame = game("/storage/emulated/0/Games/New"),
            ),
        ).replace(Regex(""","deleteSourceArchive":true"""), "")

        assertFalse("the key is really gone", legacy.contains("deleteSourceArchive"))
        val restored = ManagedUpgradePendingStore.decode(legacy)
        assertFalse("a record without the choice must never delete the user's archive", restored.deleteSourceArchive)
        assertEquals("/storage/emulated/0/Download/game-1.2.zip", restored.sourceArchivePath)
        assertEquals(ManagedUpgradePhase.WinlatorRequested, restored.phase)
    }

    @Test
    fun everyPhaseSurvivesTheJournal() {
        ManagedUpgradePhase.entries.forEach { phase ->
            val state = ManagedUpgradePendingState(
                phase = phase,
                managedGameId = id,
                label = "Journalled Game",
                oldRootPath = "/storage/emulated/0/Games/Old",
                oldCanonicalPath = "/storage/emulated/0/Games/Old",
                newRootPath = "/storage/emulated/0/Games/New",
                newCanonicalPath = "/storage/emulated/0/Games/New",
                originalGame = game("/storage/emulated/0/Games/Old"),
                replacementGame = game("/storage/emulated/0/Games/New"),
            )
            val restored = ManagedUpgradePendingStore.decode(ManagedUpgradePendingStore.encode(state))
            assertEquals(phase, restored.phase)
        }
        assertTrue(ManagedUpgradePhase.entries.size >= 4)
    }
}
