package com.example.f95updater

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ManagedGameStoreRelocationAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun workspace(name: String): File =
        File(context.cacheDir, "managed-relocation-test/$name").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun marker(directory: File): ManagedGameOwnership? {
        val file = File(directory, MANAGED_GAME_OWNERSHIP_FILE)
        if (!file.isFile) return null
        return json.decodeFromString(ManagedGameOwnership.serializer(), file.readText())
    }

    private fun draft(root: File) = ManagedGameDraft(
        storagePath = root.absolutePath,
        storageFolderName = root.name,
        label = "Relocation Test ${root.name}",
        defaultRunner = ManagedRunnerKind.JoiPlay,
        runnerBindings = listOf(
            ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh", importId = "import-1"),
        ),
    )

    @Test
    fun relocationKeepsTheUuidAndRewritesTheOwnershipMarker() = runBlocking {
        val store = ManagedGameStore(context)
        val old = workspace("old")
        val new = workspace("new")
        File(old, "Game.sh").writeText("#!/bin/sh")
        File(new, "Game.sh").writeText("#!/bin/sh")
        val created = store.create(draft(old))
        try {
            assertEquals(created.id, marker(old)?.id)
            assertEquals(created.canonicalPath, marker(old)?.canonicalPath)

            val replacement = created.copy(
                canonicalPath = canonicalManagedGamePath(new.absolutePath),
                storagePath = new.absolutePath,
                storageFolderName = new.name,
            )
            val relocated = store.relocate(created.canonicalPath, replacement)

            assertEquals(created.id, relocated.id)
            assertEquals(created.createdAt, relocated.createdAt)
            assertEquals(created.label, relocated.label)
            assertEquals(new.absolutePath, relocated.storagePath)
            assertEquals(canonicalManagedGamePath(new.absolutePath), relocated.canonicalPath)
            assertEquals(created.id, marker(new)?.id)
            assertEquals(relocated.canonicalPath, marker(new)?.canonicalPath)
            assertEquals(
                "The stored record must be the relocated one",
                new.absolutePath,
                store.find(created.id)?.storagePath,
            )

            // Idempotent re-run (crash recovery replays the same relocation).
            val again = store.relocate(created.canonicalPath, replacement)
            assertEquals(created.id, again.id)
            assertEquals(relocated.canonicalPath, again.canonicalPath)

            // Deleting still works after the move, using the regenerated marker.
            File(new, "data").mkdirs()
            File(new, "data/asset.bin").writeText("asset")
            val progress = mutableListOf<ManagedGameDeleteProgress>()
            assertTrue(
                deleteManagedGameFilesAndRecord(context, again.toInstalledApp()) {
                    progress += it
                },
            )
            assertTrue(progress.last().removedEntries >= 4L)
            assertFalse(new.exists())
            assertNull(store.find(created.id))
        } finally {
            store.delete(created.id)
            File(context.cacheDir, "managed-relocation-test").deleteRecursively()
        }
    }

    @Test
    fun relocationRejectsAPathOwnedByAnotherManagedGame() = runBlocking {
        val store = ManagedGameStore(context)
        val first = workspace("collide-a")
        val second = workspace("collide-b")
        File(first, "Game.sh").writeText("#!/bin/sh")
        File(second, "Game.sh").writeText("#!/bin/sh")
        val a = store.create(draft(first))
        val b = store.create(draft(second))
        try {
            val clash = a.copy(
                canonicalPath = b.canonicalPath,
                storagePath = b.storagePath,
                storageFolderName = second.name,
            )
            val failure = runCatching { store.relocate(a.canonicalPath, clash) }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure is ManagedGameRelocationException)
            assertEquals(first.absolutePath, store.find(a.id)?.storagePath)
            assertEquals(a.id, marker(first)?.id)
            assertEquals(b.id, marker(second)?.id)
        } finally {
            store.delete(a.id)
            store.delete(b.id)
            File(context.cacheDir, "managed-relocation-test").deleteRecursively()
        }
    }
}
