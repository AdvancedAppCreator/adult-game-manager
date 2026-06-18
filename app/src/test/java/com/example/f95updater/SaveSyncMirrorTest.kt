package com.example.f95updater

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SaveSyncMirrorTest {
    @Test
    fun findsMatchingSaveInChildSyncFolder() {
        val dir = createTempDir(prefix = "sync-target-")
        val save = File(dir, "1-1.save").apply { writeText("local") }
        val sync = File(dir, "sync").apply { mkdirs() }
        File(sync, save.name).writeText("synced")

        val target = SaveSyncMirror.findSyncTarget(save.absolutePath)

        assertNotNull(target)
        assertEquals(File(sync, save.name).absolutePath, target?.filePath)
    }

    @Test
    fun ignoresSavesAlreadyInsideSyncFolder() {
        val sync = createTempDir(prefix = "sync-source-")
        val save = File(sync, "1-1.save").apply { writeText("synced") }

        assertNull(SaveSyncMirror.findSyncTarget(save.absolutePath))
    }

    @Test
    fun overwritesSyncCopyAndKeepsBackup() = runBlocking {
        val dir = createTempDir(prefix = "sync-overwrite-")
        val save = File(dir, "1-1.save").apply { writeText("edited") }
        val sync = File(dir, "sync").apply { mkdirs() }
        val syncSave = File(sync, save.name).apply { writeText("old") }
        val target = SaveSyncMirror.findSyncTarget(save.absolutePath)!!

        val result = SaveSyncMirror.overwriteSyncTarget(save.absolutePath, target)

        assertTrue(result.message, result.ok)
        assertEquals("edited", syncSave.readText())
        assertEquals("old", sync.listFiles()!!.single { it.name.startsWith("1-1.save.agm-bak-") }.readText())
    }
}
