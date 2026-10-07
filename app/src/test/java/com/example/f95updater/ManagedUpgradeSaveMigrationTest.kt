package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class ManagedUpgradeSaveMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(root: File, relative: String, content: String): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun renPySavesAreCopiedToTheSameRelativePath() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "game/saves/1-1-LT1.save", "renpy-slot-1")
        write(old, "game/saves/persistent", "renpy-persistent")

        val copied = ManagedUpgradeSaveMigration.migrate(old, new)

        assertEquals(1, copied)
        assertEquals("renpy-slot-1", File(new, "game/saves/1-1-LT1.save").readText())
        assertEquals("renpy-persistent", File(new, "game/saves/persistent").readText())
    }

    @Test
    fun rpgMakerFolderAndRootSaveFilesAreCopied() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "www/save/file1.rpgsave", "mv-slot")
        write(old, "Save01.rvdata2", "vxace-slot")
        write(old, "SAVE02.RXDATA", "xp-slot")
        write(old, "Save3.lsd", "2k3-slot")
        write(old, "notes.txt", "ignored")

        val copied = ManagedUpgradeSaveMigration.migrate(old, new)

        assertEquals(4, copied)
        assertEquals("mv-slot", File(new, "www/save/file1.rpgsave").readText())
        assertEquals("vxace-slot", File(new, "Save01.rvdata2").readText())
        assertEquals("xp-slot", File(new, "SAVE02.RXDATA").readText())
        assertEquals("2k3-slot", File(new, "Save3.lsd").readText())
        assertFalse(File(new, "notes.txt").exists())
    }

    @Test
    fun kirikiroidSaveDataIsCopied() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "savedata/quick.ksd", "krkr-slot")

        assertEquals(1, ManagedUpgradeSaveMigration.migrate(old, new))
        assertEquals("krkr-slot", File(new, "savedata/quick.ksd").readText())
    }

    @Test
    fun shippedDestinationSaveFolderIsReplacedByTheOldOne() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "saves/slot1.sav", "mine")
        write(new, "saves/demo.sav", "shipped-demo")

        ManagedUpgradeSaveMigration.migrate(old, new)

        assertEquals("mine", File(new, "saves/slot1.sav").readText())
        assertFalse(
            "The shipped demo save must not survive the migration",
            File(new, "saves/demo.sav").exists(),
        )
    }

    @Test
    fun noRecognisedSavesIsASuccessfulZeroCopy() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "game/script.rpy", "code")

        assertEquals(0, ManagedUpgradeSaveMigration.migrate(old, new))
        assertTrue(ManagedUpgradeSaveMigration.detect(old).isEmpty)
    }

    @Test
    fun detectListsEveryRecognisedArtifact() {
        val old = temp.newFolder("old")
        write(old, "saves/a.sav", "a")
        write(old, "userdata/b.dat", "b")
        write(old, "Save09.rvdata", "c")

        val artifacts = ManagedUpgradeSaveMigration.detect(old)

        assertEquals(listOf("saves", "userdata"), artifacts.directories)
        assertEquals(listOf("Save09.rvdata"), artifacts.files)
        assertEquals(3, artifacts.total)
    }

    @Test
    fun aDetectedArtifactThatCannotBeCopiedAbortsTheMigration() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "game/saves/slot.save", "mine")
        // A plain file where the destination folder has to live makes the copy impossible.
        write(new, "game", "not-a-folder")

        val error = assertThrows(IOException::class.java) {
            ManagedUpgradeSaveMigration.migrate(old, new)
        }
        assertTrue(error.message!!.contains("game/saves"))
    }

    @Test
    fun copiedSaveRetainsModifiedTime() {
        val old = temp.newFolder("old-times")
        val new = temp.newFolder("new-times")
        val source = write(old, "saves/slot1.sav", "timestamped")
        val sourceView = Files.getFileAttributeView(
            source.toPath(),
            BasicFileAttributeView::class.java,
        )
        val modified = FileTime.fromMillis(System.currentTimeMillis() - 43_200_000L)
        sourceView.setTimes(modified, null, null)
        val sourceTimes = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java)

        ManagedUpgradeSaveMigration.migrate(old, new)

        val destinationTimes = Files.readAttributes(
            File(new, "saves/slot1.sav").toPath(),
            BasicFileAttributes::class.java,
        )
        assertEquals(sourceTimes.lastModifiedTime().toMillis(), destinationTimes.lastModifiedTime().toMillis())
    }
}
