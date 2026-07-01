package com.example.f95updater

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JoiPlayUnusedFolderReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun canon(file: File): String =
        file.canonicalPath.replace('\\', '/').trimEnd('/')

    private fun mkGame(root: File, rel: String): File =
        File(root, rel).apply { mkdirs(); File(this, "game.rpa").writeText("x") }

    private fun mkEmpty(root: File, rel: String): File =
        File(root, rel).apply { mkdirs() }

    private fun backup(title: String, folder: File) =
        JoiPlayBackupGame(id = title, title = title, folder = canon(folder))

    @Test
    fun reportsMaximalUnusedSubtreesAtAnyDepth() = runBlocking {
        // /f1
        //   f2a
        //     f2b
        //       f2c1  (game)
        //       f2c2  (game)
        //     f2c     (no game)   <- expected unused
        //   f3a       (game)
        //   f4a       (no game)   <- expected unused
        val root = tmp.newFolder("f1")
        val g1 = mkGame(root, "f2a/f2b/f2c1")
        val g2 = mkGame(root, "f2a/f2b/f2c2")
        val g3 = mkGame(root, "f3a")
        val u1 = mkEmpty(root, "f2a/f2c")
        val u2 = mkEmpty(root, "f4a")

        val report = JoiPlayUnusedFolderReporter.buildReport(
            rootFolder = root,
            backupGames = listOf(
                backup("game1", g1),
                backup("game2", g2),
                backup("game3", g3),
            ),
        )

        val unusedPaths = report.probablyUnusedFolders.map { it.path }.toSet()
        val inUsePaths = report.inUseFolders.map { it.path }.toSet()

        assertEquals(setOf(canon(u1), canon(u2)), unusedPaths)
        assertEquals(setOf(canon(g1), canon(g2), canon(g3)), inUsePaths)
        assertTrue("no missing expected", report.missingReferencedFolders.isEmpty())
        assertTrue("no inaccessible expected", report.inaccessibleParentPaths.isEmpty())

        // Used containers (f2a, f2b) must not be listed as either unused or in-use.
        val container1 = canon(File(root, "f2a"))
        val container2 = canon(File(root, "f2a/f2b"))
        assertTrue(container1 !in unusedPaths && container1 !in inUsePaths)
        assertTrue(container2 !in unusedPaths && container2 !in inUsePaths)
    }

    @Test
    fun gameFolderIsUsedLeafSoInternalSubfoldersAreNotReported() = runBlocking {
        // A game folder that itself contains an empty subfolder must not surface that
        // subfolder as unused; we stop descending at the game.
        val root = tmp.newFolder("games")
        val game = mkGame(root, "MyGame")
        mkEmpty(root, "MyGame/saves") // internal empty subfolder inside the game

        val report = JoiPlayUnusedFolderReporter.buildReport(
            rootFolder = root,
            backupGames = listOf(backup("MyGame", game)),
        )

        assertTrue(report.probablyUnusedFolders.isEmpty())
        assertEquals(setOf(canon(game)), report.inUseFolders.map { it.path }.toSet())
    }

    @Test
    fun rootChildWithNoGamesIsReportedEvenWhenSiblingHasGames() = runBlocking {
        val root = tmp.newFolder("Games")
        val game = mkGame(root, "2/HS-1.0-pc")
        val swf = mkEmpty(root, "Swf")

        val report = JoiPlayUnusedFolderReporter.buildReport(
            rootFolder = root,
            backupGames = listOf(backup("HS", game)),
        )

        assertEquals(setOf(canon(swf)), report.probablyUnusedFolders.map { it.path }.toSet())
        assertEquals(setOf(canon(game)), report.inUseFolders.map { it.path }.toSet())
    }
}
