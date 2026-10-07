package com.example.f95updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ManagedGameModelsTest {
    private val joiPlay = ManagedRunnerBinding.JoiPlay(
        type = "renpy",
        execFile = "game.exe",
        importId = "jp-1",
    )
    private val winlator = ManagedRunnerBinding.Winlator(
        managedId = "win-1",
        executablePath = "/games/Test/game.exe",
    )

    @Test
    fun canonicalPathDeduplicatesEquivalentPaths() {
        val ids = ArrayDeque(listOf("11111111-1111-1111-1111-111111111111"))
        val games = upsertManagedGames(
            current = emptyList(),
            drafts = listOf(
                draft("/games/Test/./content", joiPlay),
                draft("\\games\\Test\\content\\", winlator),
            ),
            idFactory = { ids.removeFirst() },
            now = 100L,
        )

        assertEquals(1, games.size)
        assertEquals("/games/Test/content", games.single().canonicalPath)
        assertEquals(setOf(ManagedRunnerKind.JoiPlay, ManagedRunnerKind.Winlator),
            games.single().runnerBindings.mapTo(hashSetOf()) { it.kind })
    }

    @Test
    fun validationRejectsDuplicateAndUnavailableDefaultRunners() {
        val duplicate = game(
            bindings = listOf(joiPlay, joiPlay.copy(importId = "jp-2")),
            defaultRunner = ManagedRunnerKind.JoiPlay,
        )
        assertThrows(ManagedGameValidationException.DuplicateRunner::class.java) {
            validateManagedGame(duplicate)
        }

        val disabled = game(
            bindings = listOf(joiPlay.copy(enabled = false)),
            defaultRunner = ManagedRunnerKind.JoiPlay,
        )
        assertThrows(ManagedGameValidationException.InvalidDefaultRunner::class.java) {
            validateManagedGame(disabled)
        }
    }

    @Test
    fun runnerMergeReplacesSameKindAndRetainsOtherKinds() {
        val replacement = joiPlay.copy(execFile = "new.exe")
        val merged = mergeManagedRunnerBindings(
            existing = listOf(joiPlay, winlator),
            incoming = listOf(replacement),
        )

        assertEquals(listOf(ManagedRunnerKind.JoiPlay, ManagedRunnerKind.Winlator), merged.map { it.kind })
        assertSame(replacement, merged.first())
        assertSame(winlator, merged.last())
    }

    @Test
    fun repeatUpsertPreservesStableIdentity() {
        var generated = 0
        val first = upsertManagedGames(
            current = emptyList(),
            drafts = listOf(draft("/games/Test", joiPlay)),
            idFactory = {
                generated++
                "11111111-1111-1111-1111-111111111111"
            },
            now = 100L,
        )
        val second = upsertManagedGames(
            current = first,
            drafts = listOf(draft("/games/Test/.", winlator)),
            idFactory = {
                generated++
                "22222222-2222-2222-2222-222222222222"
            },
            now = 200L,
        )

        assertEquals(1, generated)
        assertEquals(first.single().id, second.single().id)
        assertEquals(first.single().createdAt, second.single().createdAt)
        assertEquals(200L, second.single().updatedAt)
    }

    @Test
    fun bindingsSerializeWithNonConflictingRunnerDiscriminator() {
        val app = game(
            bindings = listOf(joiPlay, winlator),
            defaultRunner = ManagedRunnerKind.JoiPlay,
        ).toInstalledApp()

        val encoded = Json.encodeToString(InstalledApp.serializer(), app)
        val decoded = Json.decodeFromString(InstalledApp.serializer(), encoded)

        assertEquals(AppSource.Managed, decoded.source)
        assertEquals("11111111-1111-1111-1111-111111111111", decoded.managedGameId)
        assertEquals(app.managedRunnerBindings, decoded.managedRunnerBindings)
        assertTrue(encoded.contains("\"runner\":\"joiplay\""))
    }

    @Test
    fun recentWinlatorBindingSurvivesProviderPropagationDelay() {
        val binding = winlator.copy(
            metadata = mapOf("agmBoundAtMs" to "1000"),
        )
        assertTrue(shouldRetainRecentlyBoundWinlator(binding, now = 120_999L))
        assertTrue(!shouldRetainRecentlyBoundWinlator(binding, now = 121_001L))
    }

    @Test
    fun legacyMigrationRejectsManagedRows() {
        val managed = game(
            bindings = listOf(joiPlay),
            defaultRunner = ManagedRunnerKind.JoiPlay,
        ).toInstalledApp()

        assertThrows(IllegalArgumentException::class.java) {
            legacyInstalledAppToManagedDraft(managed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            legacyInstalledAppToManagedDraft(managed.copy(source = AppSource.Android, managedGameId = null))
        }
    }

    @Test
    fun legacyMigrationAcceptsOnlyThreeLegacyRunnerSources() {
        val base = InstalledApp(
            packageName = "legacy",
            label = "Test",
            versionName = "1",
            versionCode = 1L,
            storagePath = "/games/Test",
        )
        val drafts = listOf(
            base.copy(
                source = AppSource.JoiPlay,
                joiPlayType = "renpy",
                joiPlayExecFile = "game.exe",
            ),
            base.copy(source = AppSource.Winlator, winlatorGameId = "win-1"),
            base.copy(
                source = AppSource.Kirikiroid,
                kirikiroidStartupPath = "data.xp3",
            ),
        ).map(::legacyInstalledAppToManagedDraft)

        assertEquals(
            listOf(ManagedRunnerKind.JoiPlay, ManagedRunnerKind.Winlator, ManagedRunnerKind.Kirikiroid),
            drafts.map { it.defaultRunner },
        )
    }

    private fun draft(path: String, binding: ManagedRunnerBinding) = ManagedGameDraft(
        storagePath = path,
        label = "Test",
        defaultRunner = binding.kind,
        runnerBindings = listOf(binding),
    )

    private fun game(
        bindings: List<ManagedRunnerBinding>,
        defaultRunner: ManagedRunnerKind,
    ) = ManagedGame(
        id = "11111111-1111-1111-1111-111111111111",
        canonicalPath = "/games/Test",
        storagePath = "/games/Test",
        label = "Test",
        defaultRunner = defaultRunner,
        runnerBindings = bindings,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
