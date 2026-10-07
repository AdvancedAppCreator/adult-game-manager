package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

class BrowserDownloadsTest {
    @Test
    fun contentDispositionPreservesGoogleDriveArchiveName() {
        assertEquals(
            "YEAAHL.rar",
            BrowserDownloads.contentDispositionFileName(
                """attachment; filename="YEAAHL.rar"""",
            ),
        )
        assertEquals(
            "[kimochi]新婚妻の秘密風俗メイドコースひかる①.zip",
            BrowserDownloads.contentDispositionFileName(
                """attachment; filename="[kimochi]新婚妻の秘密風俗メイドコースひかる①.zip"""",
            ),
        )
    }

    @Test
    fun contentDispositionPrefersUtf8ExtendedNameAndSanitizesPaths() {
        assertEquals(
            "日本語 game.zip",
            BrowserDownloads.contentDispositionFileName(
                """attachment; filename="fallback.bin"; filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E%20game.zip""",
            ),
        )
        assertEquals(
            "game.rar",
            BrowserDownloads.contentDispositionFileName(
                """attachment; filename="../../game.rar"""",
            ),
        )
    }

    @Test
    fun serverFilenameTakesPriorityOverPageTitle() {
        assertEquals(
            "YEAAHL.rar",
            BrowserDownloads.preferredFileName(
                preferredFileName = null,
                contentDisposition = """attachment; filename="YEAAHL.rar"""",
                pageTitle = "Unrelated Page.zip",
            ),
        )
    }

    @Test
    fun filenameLimitPreservesExtensionAndUtf8FilesystemLimit() {
        val limited = BrowserDownloads.limitFileName("界".repeat(200) + ".zip")

        assertTrue(limited.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(limited.endsWith(".zip"))
    }

    @Test
    fun reserveTargetCreatesCollisionProofPlaceholder() {
        val folder = Files.createTempDirectory("agm-reserve-test").toFile()
        try {
            File(folder, "game.zip").writeText("existing")
            val first = BrowserDownloads.reserveTargetFile(folder, "game.zip")
            val second = BrowserDownloads.reserveTargetFile(folder, "game.zip")

            assertEquals("game (1).zip", first.name)
            assertEquals("game (2).zip", second.name)
            assertTrue(first.isFile)
            assertTrue(second.isFile)
            assertEquals(0L, first.length())
            assertEquals(0L, second.length())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun destinationLocalFinalizationAtomicallyReplacesReservation() = runBlocking {
        val folder = Files.createTempDirectory("agm-finalize-test").toFile()
        try {
            val staged = File(folder, ".agm-download.part").apply { writeText("payload") }
            val target = BrowserDownloads.reserveTargetFile(folder, "game.zip")

            val result = BrowserDownloads.finalizeDownloadedFile(staged, target, staged.length())

            assertEquals(target, result)
            assertFalse(staged.exists())
            assertEquals("payload", target.readText())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun legacyFinalizationReusesOnlyByteIdenticalDestination() = runBlocking {
        val base = Files.createTempDirectory("agm-legacy-finalize-test").toFile()
        try {
            val staging = File(base, "staging").apply { mkdirs() }
            val targetFolder = File(base, "target").apply { mkdirs() }
            val staged = File(staging, "download.bin").apply { writeText("same payload") }
            val existing = File(targetFolder, "game.zip").apply { writeText("same payload") }

            val reservation =
                BrowserDownloads.reserveLegacyTarget(targetFolder, "game.zip", staged)
            val result = BrowserDownloads.finalizeDownloadedFile(
                staged,
                reservation.file,
                staged.length(),
                targetContentVerified = reservation.verifiedExisting,
            )

            assertEquals(existing, reservation.file)
            assertEquals(existing, result)
            assertFalse(staged.exists())
            assertEquals(1, targetFolder.listFiles()?.size)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun legacyFinalizationDoesNotReuseDifferentSameSizeDestination() = runBlocking {
        val base = Files.createTempDirectory("agm-legacy-collision-test").toFile()
        try {
            val staging = File(base, "staging").apply { mkdirs() }
            val targetFolder = File(base, "target").apply { mkdirs() }
            val staged = File(staging, "download.bin").apply { writeText("payload-a") }
            File(targetFolder, "game.zip").writeText("payload-b")

            val reservation =
                BrowserDownloads.reserveLegacyTarget(targetFolder, "game.zip", staged)

            assertEquals("game (1).zip", reservation.file.name)
            assertFalse(reservation.verifiedExisting)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun finalizationRejectsIncompleteStagingWithoutChangingFiles() = runBlocking {
        val folder = Files.createTempDirectory("agm-size-test").toFile()
        try {
            val staged = File(folder, ".agm-download.part").apply { writeText("short") }
            val target = BrowserDownloads.reserveTargetFile(folder, "game.zip")

            val error = runCatching {
                BrowserDownloads.finalizeDownloadedFile(staged, target, expectedBytes = 100)
            }.exceptionOrNull()

            assertTrue(error?.message?.contains("expected 100") == true)
            assertTrue(staged.isFile)
            assertEquals(0L, target.length())
        } finally {
            folder.deleteRecursively()
        }
    }


    @Test
    fun pageTitleProvidesArchiveFileName() {
        assertEquals(
            "Shoretown_Saga-0.6-pc-Compressed.zip",
            BrowserDownloads.pageTitleFileName("Shoretown_Saga-0.6-pc-Compressed.zip"),
        )
        assertNull(BrowserDownloads.pageTitleFileName("workupload - download file"))
    }

    @Test
    fun zipSignatureCorrectsGenericBinExtension() {
        val file = File.createTempFile("browser-download-", ".bin")
        try {
            file.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3))
            assertEquals(
                "download.zip",
                BrowserDownloads.correctGenericDownloadName(file, "download.bin"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun nonZipSignatureKeepsGenericName() {
        val file = File.createTempFile("browser-download-", ".bin")
        try {
            file.writeText("not a zip")
            assertEquals(
                "download.bin",
                BrowserDownloads.correctGenericDownloadName(file, "download.bin"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun rar5SignatureCorrectsGenericBinExtension() {
        val file = File.createTempFile("browser-download-", ".bin")
        try {
            file.writeBytes(
                byteArrayOf(
                    0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00,
                ),
            )
            assertEquals(
                "STWMAD.rar",
                BrowserDownloads.correctGenericDownloadName(file, "STWMAD.bin"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun sevenZipAndExecutableSignaturesCorrectGenericNames() {
        val sevenZip = File.createTempFile("browser-download-", ".bin")
        val executable = File.createTempFile("browser-download-", ".bin")
        try {
            sevenZip.writeBytes(
                byteArrayOf(
                    0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c,
                ),
            )
            executable.writeBytes(byteArrayOf(0x4d, 0x5a))
            assertEquals(
                "game.7z",
                BrowserDownloads.correctGenericDownloadName(sevenZip, "game.bin"),
            )
            assertEquals(
                "setup.exe",
                BrowserDownloads.correctGenericDownloadName(executable, "setup.bin"),
            )
        } finally {
            sevenZip.delete()
            executable.delete()
        }
    }

    @Test
    fun androidManifestDistinguishesApkFromGenericZip() {
        val file = File.createTempFile("browser-download-", ".bin")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            assertEquals(
                "game.apk",
                BrowserDownloads.correctGenericDownloadName(file, "game.bin"),
            )
        } finally {
            file.delete()
        }
    }
}
