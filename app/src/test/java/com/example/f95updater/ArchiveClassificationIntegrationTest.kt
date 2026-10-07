package com.example.f95updater

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveClassificationIntegrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun inspectedZipEntriesDriveUnifiedRouting() = runBlocking {
        val archive = temp.newFile("windows-game.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Test Game/Game.exe"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Test Game/index.html"))
            zip.write("<html/>".toByteArray())
            zip.closeEntry()
        }

        val analysis = ManagedArchiveInspector.analyze(archive)

        assertEquals(2, analysis.entryCount)
        assertEquals(
            InstallRouting.ArchiveRoute.ChooseRunner,
            InstallRouting.routeArchive(analysis.entryNames),
        )
    }

    @Test
    fun inspectedSplitBundleIsRejected() = runBlocking {
        val archive = temp.newFile("bundle.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("base.apk", "split_config.arm64_v8a.apk").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }

        val analysis = ManagedArchiveInspector.analyze(archive)

        assertEquals(
            InstallRouting.ArchiveRoute.Unsupported(InstallRouting.splitBundleMessage),
            InstallRouting.routeArchive(analysis.entryNames),
        )
    }

    @Test
    fun inspectedWrapperArchiveRoutesToItsSingleNestedArchive() = runBlocking {
        val inner = temp.newFile("payload.zip")
        ZipOutputStream(inner.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Game/Game.exe"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val outer = temp.newFile("wrapper.zip")
        ZipOutputStream(outer.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("wrapper/OTOMI-GAMES.COM.url"))
            zip.write("https://otomi-games.com".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wrapper/payload.zip"))
            zip.write(inner.readBytes())
            zip.closeEntry()
        }

        val analysis = ManagedArchiveInspector.analyze(outer)

        assertEquals(
            InstallRouting.ArchiveRoute.ExtractNested("wrapper/payload.zip"),
            InstallRouting.routeArchive(analysis.entryNames),
        )
    }

    // ------------------------------------------------- upgrade identity evidence

    private fun managedApp(label: String, id: String) = InstalledApp(
        packageName = "managed:$id",
        label = label,
        versionName = "",
        versionCode = 0L,
        source = AppSource.Managed,
        managedGameId = id,
        managedDefaultRunner = ManagedRunnerKind.JoiPlay,
        storagePath = "/storage/emulated/0/Games/$label",
    )

    @Test
    fun zipMetadataOutranksAMisleadingArchiveName() = runBlocking {
        val archive = temp.newFile("Midnight Paradise-0.20-pc.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("GrandmasHouse-0.110-pc/game/options.rpy"))
            zip.write(
                """
                define config.name = _("Grandma's House")
                define build.name = "GrandmasHouse"
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("GrandmasHouse-0.110-pc/GrandmasHouse.exe"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        val analysis = ManagedArchiveInspector.analyze(archive)
        assertTrue(analysis.nameHints.any { it.evidence == ManagedNameEvidence.Metadata })

        val decision = ManagedArchiveUpgradeCoordinator.decide(
            route = InstallRouting.routeArchive(analysis.entryNames),
            analysis = analysis,
            apps = listOf(managedApp("Grandma's House", "id-gh"), managedApp("Midnight Paradise", "id-mp")),
        )
        val matches = (decision as ManagedArchiveUpgradeCoordinator.Decision.Upgrade).matches
        assertEquals(listOf("Grandma's House"), matches.map { it.app.label })
        assertEquals(ManagedNameEvidence.Metadata, matches.single().evidence)
    }

    @Test
    fun sevenZipMetadataIsReadTheSameWayAsZip() = runBlocking {
        val archive = temp.newFile("upload-final.7z")
        SevenZOutputFile(archive).use { out ->
            val payload = """{"title":"Midnight Paradise","name":"midnight-paradise"}""".toByteArray()
            val entry = out.createArchiveEntry(temp.newFile("package.json").apply { writeBytes(payload) }, "MP/package.json")
            out.putArchiveEntry(entry)
            out.write(payload)
            out.closeArchiveEntry()
            val exe = byteArrayOf(1, 2)
            val exeEntry = out.createArchiveEntry(temp.newFile("Game.exe").apply { writeBytes(exe) }, "MP/Game.exe")
            out.putArchiveEntry(exeEntry)
            out.write(exe)
            out.closeArchiveEntry()
        }

        val analysis = ManagedArchiveInspector.analyze(archive)
        val metadata = analysis.nameHints.filter { it.evidence == ManagedNameEvidence.Metadata }
        assertTrue(metadata.any { it.value == "Midnight Paradise" })

        val decision = ManagedArchiveUpgradeCoordinator.decide(
            route = InstallRouting.routeArchive(analysis.entryNames),
            analysis = analysis,
            apps = listOf(managedApp("Midnight Paradise", "id-mp")),
        )
        assertEquals(
            listOf("Midnight Paradise"),
            (decision as ManagedArchiveUpgradeCoordinator.Decision.Upgrade).matches.map { it.app.label },
        )
    }

    @Test
    fun anUnrelatedArchiveNeverOffersAnUpgrade() = runBlocking {
        val archive = temp.newFile("Some Public Release v3.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Some Public Release v3/Game.exe"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        val analysis = ManagedArchiveInspector.analyze(archive)
        assertEquals(
            ManagedArchiveUpgradeCoordinator.Decision.InstallAsNew,
            ManagedArchiveUpgradeCoordinator.decide(
                route = InstallRouting.routeArchive(analysis.entryNames),
                analysis = analysis,
                apps = listOf(managedApp("Grandma's House", "id-gh"), managedApp("Midnight Paradise", "id-mp")),
            ),
        )
    }
}
