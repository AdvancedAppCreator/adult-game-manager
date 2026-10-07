package com.example.f95updater

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Validates the fast [File]-based JoiPlay sizer: correct total plus the game/save/backup
 * category folding that mirrors the SAF walker's semantics (a file's category is its immediate
 * parent folder's category, inherited downward).
 */
class JoiPlayFastSizerTest {

    private fun writeFile(dir: File, name: String, bytes: Int) {
        dir.mkdirs()
        File(dir, name).writeBytes(ByteArray(bytes))
    }

    @Test
    fun sizesAndCategorizesTreeInParallel() = runBlocking {
        val root = Files.createTempDirectory("agm-fast-size").toFile()
        try {
            // Game (root + non-keyword wrapper folder)
            writeFile(root, "game.exe", 100)
            writeFile(File(root, "www"), "app.js", 50)
            // Save (direct + nested under a non-keyword folder)
            writeFile(File(root, "save"), "s1.sav", 30)
            writeFile(File(root, "saves"), "s2.sav", 20)
            writeFile(File(File(root, "data"), "save"), "s3.sav", 10)
            // Backup
            writeFile(File(root, "bak-20240101"), "b1.zip", 40)

            val info = JoiPlayScanner.computeFolderSizeForTest(root)

            assertEquals(150L, info.gameBytes)
            assertEquals(60L, info.saveBytes)
            assertEquals(40L, info.backupBytes)
            assertEquals(0L, info.otherBytes)
            assertEquals(250L, info.totalBytes)
            assertEquals(root.lastModified(), info.dirMtime)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyFolderSizesToZero() = runBlocking {
        val root = Files.createTempDirectory("agm-fast-size-empty").toFile()
        try {
            val info = JoiPlayScanner.computeFolderSizeForTest(root)
            assertEquals(0L, info.totalBytes)
            assertEquals(0L, info.gameBytes)
        } finally {
            root.deleteRecursively()
        }
    }
}
