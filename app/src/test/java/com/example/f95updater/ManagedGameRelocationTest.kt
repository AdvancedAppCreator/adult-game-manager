package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManagedGameRelocationTest {
    private val id = "33333333-3333-3333-3333-333333333333"
    private val otherId = "44444444-4444-4444-4444-444444444444"

    private fun game(
        gameId: String,
        path: String,
        label: String = "Test Game",
        createdAt: Long = 100L,
    ) = validateManagedGame(
        ManagedGame(
            id = gameId,
            canonicalPath = canonicalManagedGamePath(path),
            storagePath = path,
            storageFolderName = path.substringAfterLast('/'),
            label = label,
            versionName = "1.0",
            versionCode = 2L,
            firstInstallTime = 5L,
            lastUpdateTime = 6L,
            readmeTitle = "Readme",
            defaultRunner = ManagedRunnerKind.JoiPlay,
            runnerBindings = listOf(
                ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh", importId = "import-1"),
            ),
            createdAt = createdAt,
            updatedAt = createdAt,
        ),
    )

    @Test
    fun relocationKeepsIdentityAndUserStateWhileMovingThePath() {
        val current = listOf(game(id, "/storage/emulated/0/Games/Old"))
        val replacement = game(id, "/storage/emulated/0/Games/New", createdAt = 999L)

        val relocated = planManagedGameRelocation(
            current,
            "/storage/emulated/0/Games/Old",
            replacement,
            now = 4242L,
        )

        assertEquals(id, relocated.id)
        assertEquals("managed:$id", relocated.packageName)
        assertEquals("/storage/emulated/0/Games/New", relocated.storagePath)
        assertEquals("/storage/emulated/0/Games/New", relocated.canonicalPath)
        assertEquals("Test Game", relocated.label)
        assertEquals("import-1", relocated.runnerBindings.filterIsInstance<ManagedRunnerBinding.JoiPlay>().single().importId)
        assertEquals("The original creation time must survive", 100L, relocated.createdAt)
        assertEquals(4242L, relocated.updatedAt)
        assertNotEquals(current.single().canonicalPath, relocated.canonicalPath)
    }

    @Test
    fun relocationIsIdempotentSoRecoveryCanRerunIt() {
        val alreadyMoved = listOf(game(id, "/storage/emulated/0/Games/New"))
        val replacement = game(id, "/storage/emulated/0/Games/New")

        val relocated = planManagedGameRelocation(
            alreadyMoved,
            "/storage/emulated/0/Games/Old",
            replacement,
            now = 7L,
        )

        assertEquals("/storage/emulated/0/Games/New", relocated.canonicalPath)
        assertEquals(id, relocated.id)
    }

    @Test
    fun relocationRejectsAnUnexpectedCurrentPath() {
        val current = listOf(game(id, "/storage/emulated/0/Games/Somewhere Else"))

        assertThrows(ManagedGameRelocationException::class.java) {
            planManagedGameRelocation(
                current,
                "/storage/emulated/0/Games/Old",
                game(id, "/storage/emulated/0/Games/New"),
            )
        }
    }

    @Test
    fun relocationRejectsAnUnknownId() {
        assertThrows(ManagedGameRelocationException::class.java) {
            planManagedGameRelocation(
                listOf(game(otherId, "/storage/emulated/0/Games/Old")),
                "/storage/emulated/0/Games/Old",
                game(id, "/storage/emulated/0/Games/New"),
            )
        }
    }

    @Test
    fun relocationRejectsAPathAlreadyOwnedByAnotherManagedGame() {
        val current = listOf(
            game(id, "/storage/emulated/0/Games/Old"),
            game(otherId, "/storage/emulated/0/Games/New", label = "Another"),
        )

        assertThrows(ManagedGameRelocationException::class.java) {
            planManagedGameRelocation(
                current,
                "/storage/emulated/0/Games/Old",
                game(id, "/storage/emulated/0/Games/New"),
            )
        }
    }
}
