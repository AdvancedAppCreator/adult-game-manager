package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KirikiroidLauncherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolveStartupFileResolvesRelativeEntryPoint() {
        val game = temporaryFolder.newFolder("game")
        val executable = game.resolve("game.exe").apply { writeText("exe") }

        assertEquals(
            executable.absolutePath,
            KirikiroidLauncher.resolveStartupFile(game.absolutePath, "game.exe"),
        )
    }

    @Test
    fun resolveStartupFileRejectsMissingEntryPoint() {
        val game = temporaryFolder.newFolder("game")

        assertNull(
            KirikiroidLauncher.resolveStartupFile(game.absolutePath, "missing.exe"),
        )
    }

    @Test
    fun resolveStartupFileReplacesAssetArchiveWithDataArchive() {
        val game = temporaryFolder.newFolder("game")
        game.resolve("bgm.xp3").writeText("bgm")
        val data = game.resolve("data.xp3").apply { writeText("data") }

        assertEquals(
            data.absolutePath,
            KirikiroidLauncher.resolveStartupFile(game.absolutePath, "bgm.xp3"),
        )
    }
}
