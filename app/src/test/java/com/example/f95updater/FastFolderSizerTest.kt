package com.example.f95updater

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Validates the shared [FastFolderSizer]: plain total and category-bucketed sizing. */
class FastFolderSizerTest {

    private fun writeFile(dir: File, name: String, bytes: Int) {
        dir.mkdirs()
        File(dir, name).writeBytes(ByteArray(bytes))
    }

    @Test
    fun sizeTotalSumsAllFilesRecursively() = runBlocking {
        val root = Files.createTempDirectory("agm-fast-total").toFile()
        try {
            writeFile(root, "a.bin", 100)
            writeFile(File(root, "sub"), "b.bin", 250)
            writeFile(File(File(root, "sub"), "deep"), "c.bin", 40)
            assertEquals(390L, FastFolderSizer.sizeTotal(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sizeByCategoryBucketsByParentFolder() = runBlocking {
        val root = Files.createTempDirectory("agm-fast-cat").toFile()
        try {
            writeFile(root, "root.bin", 10)                       // bucket "g"
            writeFile(File(root, "keep"), "k.bin", 20)            // bucket "g" (non-special)
            writeFile(File(root, "special"), "s.bin", 33)        // bucket "s"
            writeFile(File(File(root, "special"), "child"), "c.bin", 7) // inherits "s"

            val totals = FastFolderSizer.sizeByCategory(
                root = root,
                rootCategory = "g",
                childCategoryOf = { name, parent -> if (name == "special") "s" else parent },
            )
            assertEquals(30L, totals["g"])
            assertEquals(40L, totals["s"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyFolderSizesToZero() = runBlocking {
        val root = Files.createTempDirectory("agm-fast-empty2").toFile()
        try {
            assertEquals(0L, FastFolderSizer.sizeTotal(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
