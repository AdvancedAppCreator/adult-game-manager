package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManagedUpgradeBindingMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val gameId = "22222222-2222-2222-2222-222222222222"

    private fun touch(root: File, relative: String): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText("x")
        return file
    }

    private fun managedGame(
        root: File,
        bindings: List<ManagedRunnerBinding>,
        default: ManagedRunnerKind,
    ) = validateManagedGame(
        ManagedGame(
            id = gameId,
            canonicalPath = canonicalManagedGamePath(root.absolutePath),
            storagePath = root.absolutePath,
            storageFolderName = root.name,
            label = "Test Game",
            versionName = "0.9",
            versionCode = 3L,
            firstInstallTime = 111L,
            lastUpdateTime = 222L,
            readmeTitle = "Readme Title",
            defaultRunner = default,
            runnerBindings = bindings,
            createdAt = 10L,
            updatedAt = 20L,
        ),
    )

    private fun ready(outcome: ManagedUpgradeBindingMigration.Outcome) =
        outcome as ManagedUpgradeBindingMigration.Outcome.Ready

    @Test
    fun joiPlayBindingKeepsUserStateAndTakesTheNewEngineAndLaunchFile() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        touch(new, "Game.exe")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.JoiPlay(
                    type = "rpgmmv",
                    execFile = "www/index.html",
                    settingsJson = """{"volume":50}""",
                    importId = "joiplay-import-1",
                ),
            ),
            ManagedRunnerKind.JoiPlay,
        )
        val discovered = listOf(
            ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.exe"),
        )

        val outcome = ready(ManagedUpgradeBindingMigration.plan(game, new, discovered, now = 900L))
        val binding = outcome.replacement.runnerBindings
            .filterIsInstance<ManagedRunnerBinding.JoiPlay>()
            .single()

        assertEquals(gameId, outcome.replacement.id)
        assertEquals(canonicalManagedGamePath(new.absolutePath), outcome.replacement.canonicalPath)
        assertEquals(new.absolutePath, outcome.replacement.storagePath)
        assertEquals(new.name, outcome.replacement.storageFolderName)
        assertEquals(10L, outcome.replacement.createdAt)
        assertEquals("Test Game", outcome.replacement.label)
        assertEquals("renpy", binding.type)
        assertEquals("Game.exe", binding.execFile)
        assertEquals("""{"volume":50}""", binding.settingsJson)
        assertEquals("joiplay-import-1", binding.importId)
        assertNull(outcome.winlator)
    }

    @Test
    fun kirikiroidStartupPathIsRefreshedAndStaleEntryPointDropped() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        touch(new, "data/data.xp3")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.Kirikiroid(
                    startupPath = "startup.tjs",
                    entryPoint = "old/startup.tjs",
                    launchArguments = listOf("-debug"),
                    metadata = mapOf("locale" to "ja"),
                ),
            ),
            ManagedRunnerKind.Kirikiroid,
        )
        val discovered = listOf(ManagedRunnerBinding.Kirikiroid(startupPath = "data/data.xp3"))

        val binding = ready(ManagedUpgradeBindingMigration.plan(game, new, discovered))
            .replacement.runnerBindings.filterIsInstance<ManagedRunnerBinding.Kirikiroid>().single()

        assertEquals("data/data.xp3", binding.startupPath)
        assertNull(binding.entryPoint)
        assertEquals(listOf("-debug"), binding.launchArguments)
        assertEquals(mapOf("locale" to "ja"), binding.metadata)
    }

    @Test
    fun winlatorRepathKeepsGameIdAndContainerWhileMovingTheExecutable() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val oldExecutable = touch(old, "bin/Game.exe")
        val newExecutable = touch(new, "bin/Game.exe")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh", enabled = false),
                ManagedRunnerBinding.Winlator(
                    managedId = "winlator-game-1",
                    executablePath = oldExecutable.absolutePath,
                    executableDosPath = "Z:\\old\\bin\\Game.exe",
                    containerId = 7,
                    state = "ready",
                    containerPolicy = "shared_default",
                    containerShared = true,
                    containerKey = "agm.default",
                    configJson = """{"screenSize":"1280x720"}""",
                    configSha256 = "abc",
                ),
            ),
            ManagedRunnerKind.Winlator,
        )
        val discovered = listOf(
            ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh"),
            ManagedRunnerBinding.Winlator(executablePath = newExecutable.absolutePath, state = "setup_required"),
        )

        val outcome = ready(ManagedUpgradeBindingMigration.plan(game, new, discovered))
        val binding = outcome.replacement.runnerBindings
            .filterIsInstance<ManagedRunnerBinding.Winlator>()
            .single()

        assertEquals("winlator-game-1", binding.managedId)
        assertEquals(7, binding.containerId)
        assertEquals("shared_default", binding.containerPolicy)
        assertEquals("""{"screenSize":"1280x720"}""", binding.configJson)
        assertEquals(newExecutable.absolutePath, binding.executablePath)
        assertNull("The stale DOS path must not survive the move", binding.executableDosPath)

        val repath = requireNotNull(outcome.winlator)
        assertEquals("winlator-game-1", repath.winlatorGameId)
        assertEquals(oldExecutable.absolutePath, repath.oldExecutablePath)
        assertEquals(requireNotNull(oldExecutable.parentFile).absolutePath, repath.oldGamePath)
        assertEquals(newExecutable.absolutePath, repath.newExecutablePath)
        assertEquals(requireNotNull(newExecutable.parentFile).absolutePath, repath.newGamePath)
    }

    @Test
    fun winlatorFallsBackToTheDiscoveredExecutableWhenTheLayoutChanged() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val oldExecutable = touch(old, "bin/Game.exe")
        val newExecutable = touch(new, "Game64.exe")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.Winlator(
                    managedId = "winlator-game-1",
                    executablePath = oldExecutable.absolutePath,
                ),
            ),
            ManagedRunnerKind.Winlator,
        )
        val discovered = listOf(
            ManagedRunnerBinding.Winlator(executablePath = newExecutable.absolutePath),
        )

        val outcome = ready(ManagedUpgradeBindingMigration.plan(game, new, discovered))

        assertEquals(
            newExecutable.absolutePath,
            requireNotNull(outcome.winlator).newExecutablePath,
        )
    }

    @Test
    fun dosOnlyWinlatorGamesAreRejected() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        touch(new, "Game.exe")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.Winlator(
                    managedId = "winlator-game-1",
                    executableDosPath = "C:\\Program Files\\Game\\Game.exe",
                ),
            ),
            ManagedRunnerKind.Winlator,
        )
        val discovered = listOf(ManagedRunnerBinding.Winlator(executablePath = File(new, "Game.exe").absolutePath))

        val outcome = ManagedUpgradeBindingMigration.plan(game, new, discovered)

        assertTrue(outcome is ManagedUpgradeBindingMigration.Outcome.Rejected)
        assertTrue(
            (outcome as ManagedUpgradeBindingMigration.Outcome.Rejected).message.contains("container"),
        )
    }

    @Test
    fun anEnabledRunnerWithoutANewBindingIsRejected() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        touch(new, "readme.txt")
        val game = managedGame(
            old,
            listOf(ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh")),
            ManagedRunnerKind.JoiPlay,
        )

        val outcome = ManagedUpgradeBindingMigration.plan(game, new, discovered = emptyList())

        assertTrue(outcome is ManagedUpgradeBindingMigration.Outcome.Rejected)
    }

    @Test
    fun disabledRunnersThatCannotMoveAreDroppedInsteadOfKeepingStalePaths() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val oldExecutable = touch(old, "Game.exe")
        touch(new, "Game.sh")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh"),
                ManagedRunnerBinding.Winlator(
                    enabled = false,
                    managedId = "winlator-game-1",
                    executablePath = oldExecutable.absolutePath,
                ),
            ),
            ManagedRunnerKind.JoiPlay,
        )
        val discovered = listOf(ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.sh"))

        val outcome = ready(ManagedUpgradeBindingMigration.plan(game, new, discovered))

        assertEquals(listOf(ManagedRunnerKind.Winlator), outcome.droppedRunners)
        assertEquals(
            listOf(ManagedRunnerKind.JoiPlay),
            outcome.replacement.runnerBindings.map { it.kind },
        )
        assertNull(outcome.winlator)
    }

    @Test
    fun everyEnabledRunnerOfACombinationIsMoved() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val oldExecutable = touch(old, "Game.exe")
        val newExecutable = touch(new, "Game.exe")
        touch(new, "data.xp3")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.exe"),
                ManagedRunnerBinding.Winlator(
                    managedId = "winlator-game-1",
                    executablePath = oldExecutable.absolutePath,
                ),
                ManagedRunnerBinding.Kirikiroid(startupPath = "data.xp3"),
            ),
            ManagedRunnerKind.JoiPlay,
        )
        val discovered = listOf(
            ManagedRunnerBinding.JoiPlay(type = "renpy", execFile = "Game.exe"),
            ManagedRunnerBinding.Winlator(executablePath = newExecutable.absolutePath),
            ManagedRunnerBinding.Kirikiroid(startupPath = "data.xp3"),
        )

        val outcome = ready(ManagedUpgradeBindingMigration.plan(game, new, discovered))

        assertEquals(
            listOf(ManagedRunnerKind.JoiPlay, ManagedRunnerKind.Winlator, ManagedRunnerKind.Kirikiroid),
            outcome.replacement.runnerBindings.map { it.kind },
        )
        assertEquals(ManagedRunnerKind.JoiPlay, outcome.replacement.defaultRunner)
        assertEquals("winlator-game-1", requireNotNull(outcome.winlator).winlatorGameId)
    }

    @Test
    fun aDefaultRunnerThatCannotMoveIsRejectedEvenWhenAnotherRunnerCould() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val oldExecutable = touch(old, "Game.exe")
        touch(new, "data.xp3")
        val game = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.Winlator(
                    enabled = false,
                    compatible = true,
                    managedId = "winlator-game-1",
                    executablePath = oldExecutable.absolutePath,
                ),
                ManagedRunnerBinding.Kirikiroid(startupPath = "data.xp3"),
            ),
            ManagedRunnerKind.Kirikiroid,
        )
        val discovered = listOf(ManagedRunnerBinding.Kirikiroid(startupPath = "data.xp3"))
        // Sanity: with the Kirikiroid default this plan succeeds.
        assertTrue(
            ManagedUpgradeBindingMigration.plan(game, new, discovered)
                is ManagedUpgradeBindingMigration.Outcome.Ready,
        )

        val winlatorDefault = managedGame(
            old,
            listOf(
                ManagedRunnerBinding.Winlator(
                    managedId = "winlator-game-1",
                    executablePath = oldExecutable.absolutePath,
                ),
                ManagedRunnerBinding.Kirikiroid(startupPath = "data.xp3"),
            ),
            ManagedRunnerKind.Winlator,
        )

        assertTrue(
            ManagedUpgradeBindingMigration.plan(winlatorDefault, new, discovered)
                is ManagedUpgradeBindingMigration.Outcome.Rejected,
        )
    }
}
