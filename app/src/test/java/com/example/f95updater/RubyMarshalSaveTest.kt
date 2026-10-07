package com.example.f95updater

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RubyMarshalSaveTest {
    @Test
    fun inspectsAndSurgicallyPatchesVxAceScalars() {
        val bytes = fixture()
        val inspection = RubyMarshalSave.inspect(bytes, 5000)

        assertEquals("Hero • 01:23:45", inspection.summary)
        assertEquals("447", inspection.values.first { it.path == "party._gold" }.displayValue)
        assertEquals("Switch #1", inspection.values.first { it.path == "switches._data[1]" }.name)
        assertEquals("Actor #1 · Level", inspection.values.first { it.path == "actors._data[1]._level" }.name)

        val patchedGold = RubyMarshalSave.patch(bytes, "party._gold", "999")
        assertTrue(patchedGold.error.orEmpty(), patchedGold.bytes != null)
        val patchedSwitch = RubyMarshalSave.patch(patchedGold.bytes!!, "switches._data[2]", "true")
        assertTrue(patchedSwitch.error.orEmpty(), patchedSwitch.bytes != null)

        val after = RubyMarshalSave.inspect(patchedSwitch.bytes!!, 5000)
        assertEquals("999", after.values.first { it.path == "party._gold" }.displayValue)
        assertEquals("true", after.values.first { it.path == "switches._data[2]" }.displayValue)
        assertEquals("5270", after.values.first { it.path == "party._steps" }.displayValue)
    }

    @Test
    fun scannerAndEditorHandleVxAceSaveWithBackup() = runBlocking {
        val dir = Files.createTempDirectory("vxace-save-").toFile()
        val save = File(dir, "Save04.rvdata2").apply { writeBytes(fixture()) }
        val location = RpgmSaveLocation(
            saveDirPath = dir.absolutePath,
            ownerId = dir.name,
            saveCount = 1,
            latestModified = save.lastModified(),
            associatedPackageName = null,
            associatedLabel = null,
            confidence = 0,
            reason = null,
        )

        val slot = RpgmSaveScanner.listSaveSlots(location).single()
        assertEquals(RubyMarshalSave.CODEC, slot.codec)
        assertTrue(slot.summary.contains("01:23:45"))

        val result = RpgmSaveEditor.edit(slot, "party._gold", "1234")

        assertTrue(result.message, result.ok)
        assertEquals(
            "1234",
            RpgmSaveEditor.inspect(slot).values.first { it.path == "party._gold" }.displayValue,
        )
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("${save.name}.agm-bak-") })
    }

    @Test
    fun rejectsInvalidTypedEditWithoutChangingBytes() {
        val bytes = fixture()

        val result = RubyMarshalSave.patch(bytes, "party._gold", "not-a-number")

        assertTrue(result.bytes == null)
        assertTrue(result.error.orEmpty().contains("Invalid int"))
    }

    private fun fixture(): ByteArray = Base64.getDecoder().decode(
        "BAh7BzoPY2hhcmFjdGVyc1sGWwdJIglIZXJvBjoGRVRpADoPcGxheXRpbWVfc0kiDTAxOjIzOjQ1BjsGVAQIews6DXN3aXRjaGVzbzoSR2FtZV9Td2l0Y2hlcwY6CkBkYXRhWwgwVEY6DnZhcmlhYmxlc286E0dhbWVfVmFyaWFibGVzBjsHWwcwaQw6C2FjdG9yc286EEdhbWVfQWN0b3JzBjsHWwcwbzoPR2FtZV9BY3Rvcgk6CkBuYW1lSSIJSGVybwY6BkVUOgtAbGV2ZWxpCDoIQGhwaVA6CEBtcGkZOgpwYXJ0eW86D0dhbWVfUGFydHkIOgpAZ29sZGkCvwE6C0BzdGVwc2kClhQ6C0BpdGVtc3sGaQZpBzoIbWFwbzoNR2FtZV9NYXAGOgxAbWFwX2lkaS06C3BsYXllcm86EEdhbWVfUGxheWVyBzoHQHhpDzoHQHlpCg==",
    )
}
