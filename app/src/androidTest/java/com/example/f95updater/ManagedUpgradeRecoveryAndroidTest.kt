package com.example.f95updater

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ManagedUpgradeRecoveryAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val json = Json { ignoreUnknownKeys = true }
    private val createdIds = mutableListOf<String>()
    private lateinit var root: File

    @Before
    fun setUp() {
        ManagedUpgradePendingStore.clear(context)
        root = File(context.cacheDir, "managed-upgrade-recovery-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            val store = ManagedGameStore(context)
            createdIds.forEach { store.delete(it) }
            ManagedUpgradePendingStore.clear(context)
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryDiscardsAnUpgradeInterruptedBeforeSaveCopy() = runBlocking {
        val oldRoot = gameFolder("old")
        val newRoot = gameFolder("new")
        val game = createGame(oldRoot)
        val state = pendingState(
            phase = ManagedUpgradePhase.SavesPending,
            game = game,
            newRoot = newRoot,
            replacement = game,
        )
        ManagedUpgradePendingStore.save(context, state)

        val outcome = recoverPendingManagedUpgrade(context)

        assertTrue(outcome is ManagedUpgradeRecoveryOutcome.Discarded)
        assertFalse(newRoot.exists())
        assertEquals(oldRoot.absolutePath, ManagedGameStore(context).find(game.id)?.storagePath)
        assertNull(ManagedUpgradePendingStore.load(context))
    }

    @Test
    fun recoveryCommitsRelocationAndPerformsBestEffortCleanup() = runBlocking {
        val oldRoot = gameFolder("old-commit")
        val newRoot = gameFolder("new-commit")
        val archive = File(root, "RecoveryGame-2.0.zip").apply { writeText("archive") }
        val game = createGame(oldRoot)
        val replacement = game.copy(
            canonicalPath = canonicalManagedGamePath(newRoot.absolutePath),
            storagePath = newRoot.absolutePath,
            storageFolderName = newRoot.name,
            versionName = "2.0",
            lastUpdateTime = 200L,
        )
        val state = pendingState(
            phase = ManagedUpgradePhase.StoreRelocationPending,
            game = game,
            newRoot = newRoot,
            replacement = replacement,
            sourceArchive = archive,
            deleteSourceArchive = true,
            saveItemsCopied = 2,
        )
        ManagedUpgradePendingStore.save(context, state)

        val outcome = recoverPendingManagedUpgrade(context)

        assertTrue(outcome is ManagedUpgradeRecoveryOutcome.Completed)
        val completed = outcome as ManagedUpgradeRecoveryOutcome.Completed
        val stored = requireNotNull(ManagedGameStore(context).find(game.id))
        assertEquals(game.id, stored.id)
        assertEquals(newRoot.absolutePath, stored.storagePath)
        assertEquals("2.0", stored.versionName)
        assertEquals(2, completed.result.saveItemsCopied)
        assertFalse(archive.exists())
        assertTrue(
            "the recorded choice was to delete it",
            completed.result.sourceArchiveOutcome.deletionRequested,
        )
        assertEquals(ManagedUpgradeSourceArchiveOutcome.Deleted, completed.result.sourceArchiveOutcome)
        assertFalse(oldRoot.exists())
        val renamedOld = File(completed.result.oldFolder)
        assertTrue(renamedOld.isDirectory)
        assertTrue(renamedOld.name.startsWith("upgraded_to_delete_"))
        assertFalse(File(renamedOld, MANAGED_GAME_OWNERSHIP_FILE).exists())
        val marker = json.decodeFromString(
            ManagedGameOwnership.serializer(),
            File(newRoot, MANAGED_GAME_OWNERSHIP_FILE).readText(),
        )
        assertEquals(game.id, marker.id)
        assertEquals(canonicalManagedGamePath(newRoot.absolutePath), marker.canonicalPath)
        assertNull(ManagedUpgradePendingStore.load(context))
    }

    @Test
    fun concurrentRecoveryCommitsTheJournalOnlyOnce() = runBlocking {
        val oldRoot = gameFolder("old-concurrent")
        val newRoot = gameFolder("new-concurrent")
        val game = createGame(oldRoot)
        val replacement = game.copy(
            canonicalPath = canonicalManagedGamePath(newRoot.absolutePath),
            storagePath = newRoot.absolutePath,
            storageFolderName = newRoot.name,
            versionName = "3.0",
        )
        val state = pendingState(
            phase = ManagedUpgradePhase.StoreRelocationPending,
            game = game,
            newRoot = newRoot,
            replacement = replacement,
        )
        ManagedUpgradePendingStore.save(context, state)

        val outcomes = coroutineScope {
            listOf(
                async(Dispatchers.Default) { recoverPendingManagedUpgrade(context) },
                async(Dispatchers.Default) { recoverPendingManagedUpgrade(context) },
            ).awaitAll()
        }

        assertEquals(1, outcomes.count { it is ManagedUpgradeRecoveryOutcome.Completed })
        assertEquals(1, outcomes.count { it is ManagedUpgradeRecoveryOutcome.None })
        val completed = outcomes.filterIsInstance<ManagedUpgradeRecoveryOutcome.Completed>().single()
        assertTrue(File(completed.result.oldFolder).isDirectory)
        assertTrue(File(completed.result.oldFolder).name.startsWith("upgraded_to_delete_"))
        assertEquals(newRoot.absolutePath, ManagedGameStore(context).find(game.id)?.storagePath)
        assertNull(ManagedUpgradePendingStore.load(context))
    }

    @Test
    fun unknownWinlatorLocationKeepsBothFoldersAndJournal() = runBlocking {
        val oldRoot = gameFolder("old-winlator")
        val newRoot = gameFolder("new-winlator")
        val game = createGame(oldRoot)
        val newExecutable = File(newRoot, "Game.exe").apply { writeText("new") }
        val oldExecutable = File(oldRoot, "Game.exe").apply { writeText("old") }
        val replacement = game.copy(
            canonicalPath = canonicalManagedGamePath(newRoot.absolutePath),
            storagePath = newRoot.absolutePath,
            storageFolderName = newRoot.name,
            runnerBindings = listOf(
                ManagedRunnerBinding.Winlator(
                    managedId = "missing-winlator-game",
                    executablePath = newExecutable.absolutePath,
                ),
            ),
            defaultRunner = ManagedRunnerKind.Winlator,
        )
        val state = pendingState(
            phase = ManagedUpgradePhase.WinlatorRequested,
            game = game,
            newRoot = newRoot,
            replacement = replacement,
        ).copy(
            winlator = ManagedUpgradeWinlatorPlan(
                winlatorGameId = "missing-winlator-game",
                oldGamePath = oldRoot.absolutePath,
                oldExecutablePath = oldExecutable.absolutePath,
                newGamePath = newRoot.absolutePath,
                newExecutablePath = newExecutable.absolutePath,
            ),
        )
        ManagedUpgradePendingStore.save(context, state)

        val outcome = recoverPendingManagedUpgrade(context)

        assertTrue(outcome is ManagedUpgradeRecoveryOutcome.Failed)
        assertTrue(oldRoot.isDirectory)
        assertTrue(newRoot.isDirectory)
        assertEquals(oldRoot.absolutePath, ManagedGameStore(context).find(game.id)?.storagePath)
        assertEquals(state, ManagedUpgradePendingStore.load(context))
    }

    private fun gameFolder(name: String): File =
        File(root, name).apply {
            mkdirs()
            File(this, "Game.sh").writeText("#!/bin/sh")
        }

    private suspend fun createGame(folder: File): ManagedGame {
        val game = ManagedGameStore(context).create(
            ManagedGameDraft(
                storagePath = folder.absolutePath,
                storageFolderName = folder.name,
                label = "Recovery Game",
                versionName = "1.0",
                defaultRunner = ManagedRunnerKind.JoiPlay,
                runnerBindings = listOf(
                    ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh"),
                ),
            ),
        )
        createdIds += game.id
        return game
    }

    private fun pendingState(
        phase: ManagedUpgradePhase,
        game: ManagedGame,
        newRoot: File,
        replacement: ManagedGame,
        sourceArchive: File? = null,
        deleteSourceArchive: Boolean = false,
        saveItemsCopied: Int = 0,
    ) = ManagedUpgradePendingState(
        phase = phase,
        managedGameId = game.id,
        label = game.label,
        oldRootPath = game.storagePath,
        oldCanonicalPath = game.canonicalPath,
        newRootPath = newRoot.absolutePath,
        newCanonicalPath = canonicalManagedGamePath(newRoot.absolutePath),
        sourceArchivePath = sourceArchive?.absolutePath,
        deleteSourceArchive = deleteSourceArchive,
        saveItemsCopied = saveItemsCopied,
        originalGame = game,
        replacementGame = replacement,
    )
}
