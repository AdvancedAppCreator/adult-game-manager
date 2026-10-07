package com.example.f95updater

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JoiPlayBackupManagerTest {

    private fun tempRoot(): File =
        File.createTempFile("agm-backup-test", "").let {
            it.delete(); it.mkdirs(); it
        }

    private fun writeFile(dir: File, rel: String, content: String) {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun joiPlayApp(gameFolder: File) = InstalledApp(
        packageName = "joiplay:test",
        label = "Test Game",
        versionName = "1.0",
        versionCode = 0L,
        source = AppSource.JoiPlay,
        storagePath = gameFolder.absolutePath,
        storageFolderName = gameFolder.name,
        joiPlayExecFile = "game.exe",
    )

    @Test
    fun findBackup_returnsNull_whenNoBackupFolders() {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        writeFile(game, "game.exe", "x")
        assertNull(JoiPlayBackupManager.findBackupForFolder(game))
    }

    @Test
    fun findBackup_findsSingleBakFolder() = runBlocking {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        val bak = File(root, "MyGame.bak-20260101-120000").apply { mkdirs() }
        writeFile(bak, "old.txt", "12345")

        val info = JoiPlayBackupManager.findBackupForFolder(game)
        assertNotNull(info)
        assertEquals(1, info!!.count)
        assertEquals(bak.name, info.newest.name)
        assertEquals(5L, JoiPlayBackupManager.totalSize(info))
    }

    @Test
    fun findBackup_picksNewest_amongMultiple() {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        val older = File(root, "MyGame.bak-20260101-120000").apply { mkdirs() }
        val newer = File(root, "MyGame.bak-20260202-120000").apply { mkdirs() }
        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)

        val info = JoiPlayBackupManager.findBackupForFolder(game)!!
        assertEquals(2, info.count)
        assertEquals(newer.name, info.newest.name)
    }

    @Test
    fun findBackup_ignoresUnrelatedFolders() {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        File(root, "OtherGame.bak-20260101-120000").mkdirs()
        File(root, "MyGame.saves").mkdirs()
        File(root, "MyGame-backup").mkdirs()
        assertNull(JoiPlayBackupManager.findBackupForFolder(game))
    }

    @Test
    fun deleteBackups_removesAllBakFolders() = runBlocking {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        writeFile(game, "game.exe", "new")
        File(root, "MyGame.bak-20260101-120000").apply { mkdirs(); writeFile(this, "a.txt", "aa") }
        File(root, "MyGame.bak-20260202-120000").apply { mkdirs(); writeFile(this, "b.txt", "bbb") }

        val result = JoiPlayBackupManager.deleteBackups(joiPlayApp(game))
        assertTrue(result is JoiPlayBackupManager.ActionResult.Success)
        assertTrue(File(root, "MyGame").exists())
        assertFalse(File(root, "MyGame.bak-20260101-120000").exists())
        assertFalse(File(root, "MyGame.bak-20260202-120000").exists())
    }

    @Test
    fun deleteBackups_noBackup_returnsNoBackup() = runBlocking {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        val result = JoiPlayBackupManager.deleteBackups(joiPlayApp(game))
        assertTrue(result is JoiPlayBackupManager.ActionResult.NoBackup)
    }

    @Test
    fun revert_restoresNewestBackup_andDropsCurrent() = runBlocking {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        writeFile(game, "marker.txt", "NEW-BROKEN")
        val older = File(root, "MyGame.bak-20260101-120000").apply { mkdirs() }
        writeFile(older, "marker.txt", "OLD-1")
        val newer = File(root, "MyGame.bak-20260202-120000").apply { mkdirs() }
        writeFile(newer, "marker.txt", "OLD-2-GOOD")
        // Set mtimes AFTER writing children (writing a child bumps the dir mtime).
        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)

        val result = JoiPlayBackupManager.revertToBackup(joiPlayApp(game))
        assertTrue(result is JoiPlayBackupManager.ActionResult.Success)

        // Game folder now holds the newest backup's content.
        assertEquals("OLD-2-GOOD", File(game, "marker.txt").readText())
        // Newest backup was consumed (renamed into place); older remains.
        assertFalse(File(root, "MyGame.bak-20260202-120000").exists())
        assertTrue(File(root, "MyGame.bak-20260101-120000").exists())
    }

    @Test
    fun revert_noBackup_returnsNoBackup() = runBlocking {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        val result = JoiPlayBackupManager.revertToBackup(joiPlayApp(game))
        assertTrue(result is JoiPlayBackupManager.ActionResult.NoBackup)
    }

    @Test
    fun findBackup_nonJoiPlaySource_returnsNull() = runBlocking {
        val root = tempRoot()
        val game = File(root, "MyGame").apply { mkdirs() }
        File(root, "MyGame.bak-20260101-120000").mkdirs()
        val androidApp = InstalledApp(
            packageName = "com.example",
            label = "X",
            versionName = "1",
            versionCode = 1L,
            source = AppSource.Android,
            storagePath = game.absolutePath,
        )
        assertNull(JoiPlayBackupManager.findBackup(androidApp))
    }
}
