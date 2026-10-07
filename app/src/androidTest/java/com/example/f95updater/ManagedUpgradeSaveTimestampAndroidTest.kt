package com.example.f95updater

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

@RunWith(AndroidJUnit4::class)
class ManagedUpgradeSaveTimestampAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun sharedStorageSaveCopyRetainsCreationAndModifiedTimes() {
        val externalRoot = requireNotNull(context.getExternalFilesDir(null))
        val root = File(externalRoot, "save-timestamp-test-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val old = File(root, "old").apply { mkdirs() }
            val new = File(root, "new").apply { mkdirs() }
            val source = File(old, "saves/slot1.sav").apply {
                parentFile?.mkdirs()
                writeText("timestamped")
            }
            val modified = FileTime.fromMillis(System.currentTimeMillis() - 43_200_000L)
            Files.getFileAttributeView(source.toPath(), BasicFileAttributeView::class.java)
                .setTimes(modified, null, null)
            val sourceTimes = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java)
            Thread.sleep(3_000L)

            ManagedUpgradeSaveMigration.migrate(old, new)

            val destinationTimes = Files.readAttributes(
                File(new, "saves/slot1.sav").toPath(),
                BasicFileAttributes::class.java,
            )
            assertTrue(
                sourceTimes.lastModifiedTime().toMillis() ==
                    destinationTimes.lastModifiedTime().toMillis(),
            )
            assertTrue(
                sourceTimes.creationTime().toMillis() ==
                    destinationTimes.creationTime().toMillis(),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
