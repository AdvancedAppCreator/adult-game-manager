package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtractedContentInspectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun resolvesOnlyFilesInsideExtractedRoot() {
        val root = temp.newFolder("wrapper")
        val nested = File(root, "payload/game.zip").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val outside = temp.newFile("outside.zip")
        val extractRoot = ArchiveExtractor.ExtractRoot.FileRoot(root)

        assertEquals(
            nested.canonicalFile,
            ExtractedContentInspector.resolveFile(extractRoot, "payload/game.zip"),
        )
        assertNull(
            ExtractedContentInspector.resolveFile(
                extractRoot,
                "../${outside.name}",
            ),
        )
    }
}
