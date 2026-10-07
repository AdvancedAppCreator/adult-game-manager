package com.example.f95updater

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ArchiveExtractorAndroidTest {
    @Test
    fun extractsLegitimateHighlyCompressibleFile() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val work = File(context.cacheDir, "archive-ratio-test").apply {
                deleteRecursively()
                mkdirs()
            }
            val archive = File(work, "game.zip")
            val payloadSize = 4 * 1024 * 1024
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("Game/Game_Data/sharedassets12.assets.resS"))
                val block = ByteArray(64 * 1024)
                repeat(payloadSize / block.size) { zip.write(block) }
                zip.closeEntry()
            }
            val destination = File(work, "out").apply { mkdirs() }

            val outcome = ArchiveExtractor.extract(
                context = context,
                archive = archive,
                format = ArchiveExtractor.Format.ZIP,
                password = null,
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination),
                suggestedName = "game",
            ) {}

            assertTrue(outcome is ArchiveExtractor.Outcome.Ok)
            val result = outcome as ArchiveExtractor.Outcome.Ok
            val root = result.rootFolder as ArchiveExtractor.ExtractRoot.FileRoot
            assertEquals(payloadSize.toLong(), result.bytesWritten)
            assertEquals(
                payloadSize.toLong(),
                File(root.file, "Game_Data/sharedassets12.assets.resS").length(),
            )
            work.deleteRecursively()
        }
    }

    @Test
    fun repeatedExtractionsNeverReuseAnExistingFolder() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val work = File(context.cacheDir, "archive-unique-test").apply {
                deleteRecursively()
                mkdirs()
            }
            val archive = File(work, "My Game.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("My Game/Game.exe"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            val destination = File(work, "root").apply { mkdirs() }

            val folders = (1..3).map {
                val outcome = ArchiveExtractor.extract(
                    context = context,
                    archive = archive,
                    format = ArchiveExtractor.Format.ZIP,
                    password = null,
                    destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination),
                    suggestedName = "My Game",
                ) {}
                assertTrue(outcome is ArchiveExtractor.Outcome.Ok)
                ((outcome as ArchiveExtractor.Outcome.Ok).rootFolder as ArchiveExtractor.ExtractRoot.FileRoot).file
            }

            assertEquals(listOf("My Game", "My Game-2", "My Game-3"), folders.map { it.name })
            folders.forEach { assertTrue(File(it, "Game.exe").isFile) }
            assertEquals(3, destination.listFiles()!!.size)
            work.deleteRecursively()
        }
    }

    @Test
    fun passwordRetriesDoNotLeaveExtraFolders() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val work = File(context.cacheDir, "archive-password-test").apply {
                deleteRecursively()
                mkdirs()
            }
            val payloadDir = File(work, "payload").apply { mkdirs() }
            File(payloadDir, "Game.exe").writeText("payload")
            val archive = File(work, "Secret Game.zip")
            val parameters = net.lingala.zip4j.model.ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD
            }
            net.lingala.zip4j.ZipFile(archive, "right".toCharArray())
                .addFolder(payloadDir, parameters)
            val destination = File(work, "root").apply { mkdirs() }

            val noPassword = ArchiveExtractor.extract(
                context = context,
                archive = archive,
                format = ArchiveExtractor.Format.ZIP,
                password = null,
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination),
                suggestedName = "Secret Game",
            ) {}
            assertTrue(noPassword is ArchiveExtractor.Outcome.NeedsPassword)

            val wrongPassword = ArchiveExtractor.extract(
                context = context,
                archive = archive,
                format = ArchiveExtractor.Format.ZIP,
                password = "wrong".toCharArray(),
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination),
                suggestedName = "Secret Game",
            ) {}
            assertTrue(wrongPassword is ArchiveExtractor.Outcome.NeedsPassword)

            val ok = ArchiveExtractor.extract(
                context = context,
                archive = archive,
                format = ArchiveExtractor.Format.ZIP,
                password = "right".toCharArray(),
                destRoot = ArchiveExtractor.ExtractRoot.FileRoot(destination),
                suggestedName = "Secret Game",
            ) {}
            assertTrue(ok is ArchiveExtractor.Outcome.Ok)

            assertEquals(
                "Failed password attempts must not leave folders behind",
                1,
                destination.listFiles()!!.size,
            )
            work.deleteRecursively()
        }
    }
}
