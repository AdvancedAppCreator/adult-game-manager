package com.example.f95updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RenPySaveScannerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun detectsModernRenPyZipSaveMetadata() {
        val save = File.createTempFile("renpy-", ".save")
        save.deleteOnExit()
        ZipOutputStream(save.outputStream()).use { zip ->
            zip.putText("log", "pickle-bytes")
            zip.putText("json", """{"_save_name":"Day 4 bedroom"}""")
            zip.putText("renpy_version", "Ren'Py 8.2.3")
        }

        val metadata = RenPySaveScanner.readSaveMetadataForTest(save)

        assertEquals("Day 4 bedroom", metadata?.saveName)
        assertEquals("Ren'Py 8.2.3", metadata?.renpyVersion)
    }

    @Test
    fun ignoresZipWithoutRenPyLogEntry() {
        val save = File.createTempFile("not-renpy-", ".save")
        save.deleteOnExit()
        ZipOutputStream(save.outputStream()).use { zip ->
            zip.putText("json", """{"_save_name":"Not a slot"}""")
        }

        assertNull(RenPySaveScanner.readSaveMetadataForTest(save))
    }

    @Test
    fun acceptsRenPyZipSaveWithMalformedJsonMetadata() {
        val save = File.createTempFile("renpy-bad-json-", ".save")
        save.deleteOnExit()
        ZipOutputStream(save.outputStream()).use { zip ->
            zip.putText("log", "pickle-bytes")
            zip.putText("json", "{not-json")
            zip.putText("renpy_version", "Ren'Py 8.3.0")
        }

        val metadata = RenPySaveScanner.readSaveMetadataForTest(save)

        assertEquals(null, metadata?.saveName)
        assertEquals("Ren'Py 8.3.0", metadata?.renpyVersion)
    }

    @Test
    fun saveSnapshotRoundTripsLocationsAndScanTime() {
        val snapshot = RenPySaveScanner.Snapshot(
            lastScannedAt = 123456789L,
            locations = listOf(
                RenPySaveLocation(
                    saveDirPath = "/storage/emulated/0/RenPy/TestGame",
                    ownerId = "TestGame",
                    saveCount = 2,
                    sampleSaveNames = listOf("Slot 1"),
                    renpyVersion = "Ren'Py 8.3.0",
                    latestModified = 123456000L,
                    associatedPackageName = "org.renpy.testgame",
                    associatedLabel = "Test Game",
                    confidence = 85,
                    reason = "Ren'Py save-folder owner 'TestGame' matched game title/folder",
                )
            ),
        )

        val decoded = json.decodeFromString(
            RenPySaveScanner.Snapshot.serializer(),
            json.encodeToString(RenPySaveScanner.Snapshot.serializer(), snapshot),
        )

        assertEquals(123456789L, decoded.lastScannedAt)
        assertEquals("/storage/emulated/0/RenPy/TestGame", decoded.locations.single().saveDirPath)
        assertEquals("org.renpy.testgame", decoded.locations.single().associatedPackageName)
    }

    @Test
    fun manualAssociationOverridesLocationAssociation() {
        val location = RenPySaveLocation(
            saveDirPath = "/storage/emulated/0/RenPy/TestGame",
            ownerId = "TestGame",
            saveCount = 1,
            sampleSaveNames = emptyList(),
            renpyVersion = null,
            latestModified = 1L,
            associatedPackageName = null,
            associatedLabel = null,
            confidence = 0,
            reason = null,
        )
        val app = InstalledApp(
            packageName = "org.renpy.testgame",
            label = "Test Game",
            versionName = "",
            versionCode = 0L,
        )

        val updated = RenPySaveScanner.applyManualAssociations(
            locations = listOf(location),
            manualAssociations = mapOf(
                "/storage/emulated/0/RenPy/TestGame" to RenPySaveManualAssociation(
                    saveDirPath = "/storage/emulated/0/RenPy/TestGame",
                    packageName = app.packageName,
                    label = app.label,
                )
            ),
            apps = listOf(app),
        ).single()

        assertEquals(app.packageName, updated.associatedPackageName)
        assertEquals(app.label, updated.associatedLabel)
        assertEquals(100, updated.confidence)
        assertEquals("Manually associated", updated.reason)
    }

    @Test
    fun editorInspectsAndPatchesDirectStoreScalars() = runBlocking {
        val save = File.createTempFile("renpy-edit-", ".save")
        save.deleteOnExit()
        writeSaveZip(save, renPyLogPickle(money = 10, name = "Alex", flag = true))
        val slot = RenPySaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            saveName = "Slot",
            renpyVersion = "Ren'Py 8.3.0",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            hasScreenshot = false,
            entries = listOf("log"),
        )

        val before = RenPySaveEditor.inspect(slot)
        assertEquals("10", before.variables.first { it.key == "store.money" }.displayValue)
        assertEquals("Alex", before.variables.first { it.key == "store.name" }.displayValue)

        val result = RenPySaveEditor.edit(slot, "store.money", "300")
        assertTrue(result.message, result.ok)

        val after = RenPySaveEditor.inspect(slot)
        assertEquals("300", after.variables.first { it.key == "store.money" }.displayValue)
    }

    @Test
    fun editorSignsRenPySaveWhenSecurityKeysExist() = runBlocking {
        val dir = createTempDir(prefix = "renpy-sign-")
        val save = File(dir, "slot1.save")
        writeSaveZip(save, renPyLogPickle(money = 10, name = "Alex", flag = true))
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        File(dir, "security_keys.txt").writeText(
            "signing-key ${Base64.getEncoder().encodeToString(pair.private.encoded)} ${Base64.getEncoder().encodeToString(pair.public.encoded)}\n"
        )
        val slot = RenPySaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            saveName = "Slot",
            renpyVersion = "Ren'Py 8.3.0",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            hasScreenshot = false,
            entries = listOf("log", "signatures"),
        )

        val result = RenPySaveEditor.edit(slot, "store.money", "77")

        assertTrue(result.message, result.ok)
        ZipFile(save).use { zip ->
            val log = zip.getInputStream(zip.getEntry("log")).use { it.readBytes() }
            val signatures = zip.getInputStream(zip.getEntry("signatures")).use { it.readBytes().toString(Charsets.UTF_8) }
            val parts = signatures.trim().split(Regex("""\s+"""))
            assertEquals("signature", parts[0])
            assertEquals(Base64.getEncoder().encodeToString(pair.public.encoded), parts[1])
            val raw = Base64.getDecoder().decode(parts[2])
            val verifier = Signature.getInstance("SHA1withECDSA")
            verifier.initVerify(pair.public)
            verifier.update(log)
            assertTrue(verifier.verify(rawToDer(raw)))
        }
    }

    @Test
    fun editorCreatesOnlyOneBackupForSessionEdits() = runBlocking {
        val dir = createTempDir(prefix = "renpy-session-edit-")
        val save = File(dir, "slot1.save")
        writeSaveZip(save, renPyLogPickle(money = 10, name = "Alex", flag = true))
        val slot = RenPySaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            saveName = "Slot",
            renpyVersion = "Ren'Py 8.3.0",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            hasScreenshot = false,
            entries = listOf("log", "signatures"),
        )

        val (first, backup) = RenPySaveEditor.edit(slot, "store.money", "77", null)
        assertTrue(first.message, first.ok)
        val (second, sameBackup) = RenPySaveEditor.edit(slot, "store.name", "Robin", backup)
        assertTrue(second.message, second.ok)

        assertEquals(backup?.filePath, sameBackup?.filePath)
        assertEquals(1, dir.listFiles().orEmpty().count { it.name.startsWith("${save.name}.agm-bak-") })
        val after = RenPySaveEditor.inspect(slot)
        assertEquals("77", after.variables.first { it.key == "store.money" }.displayValue)
        assertEquals("Robin", after.variables.first { it.key == "store.name" }.displayValue)
    }

    @Test
    fun editorDoesNotUseParentKeyWhenDirectSecurityFileHasNoSigningKey() = runBlocking {
        val parent = createTempDir(prefix = "renpy-parent-sign-")
        val dir = File(parent, "saves").apply { mkdirs() }
        val save = File(dir, "slot1.save")
        writeSaveZip(save, renPyLogPickle(money = 10, name = "Alex", flag = true))
        File(dir, "security_keys.txt").writeText("verifying-key ${Base64.getEncoder().encodeToString(ByteArray(8) { it.toByte() })}\n")
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        File(parent, "security_keys.txt").writeText(
            "signing-key ${Base64.getEncoder().encodeToString(pair.private.encoded)} ${Base64.getEncoder().encodeToString(pair.public.encoded)}\n"
        )
        val slot = RenPySaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            saveName = "Slot",
            renpyVersion = "Ren'Py 8.3.0",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            hasScreenshot = false,
            entries = listOf("log", "signatures"),
        )

        val result = RenPySaveEditor.edit(slot, "store.money", "77")

        assertTrue(result.message, result.ok)
        ZipFile(save).use { zip ->
            val signatures = zip.getInputStream(zip.getEntry("signatures")).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("", signatures)
        }
    }

    @Test
    fun editorInspectionFailsClosedForUnsupportedPickleData() = runBlocking {
        val save = File.createTempFile("renpy-unsupported-", ".save")
        save.deleteOnExit()
        writeSaveZip(save, byteArrayOf(0x80.toByte(), 0x05, 'X'.code.toByte()))
        val slot = RenPySaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            saveName = "Slot",
            renpyVersion = "Ren'Py 8.3.0",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            hasScreenshot = false,
            entries = listOf("log"),
        )

        val inspection = RenPySaveEditor.inspect(slot)

        assertTrue(inspection.variables.isEmpty())
        assertTrue(inspection.warning?.isNotBlank() == true)
    }

    @Test
    fun editorFindsDirectStoreScalarsEvenWhenFullPickleHasOpaqueData() = runBlocking {
        val save = File.createTempFile("renpy-direct-", ".save")
        save.deleteOnExit()
        val log = ByteArrayOutputStream().use { out ->
            out.write(byteArrayOf(0x80.toByte(), 0x05))
            out.writeBinUnicode("store.money")
            out.write(0x94)
            out.write('K'.code)
            out.write(12)
            out.write('c'.code)
            out.write("renpy.python\nRevertableDict\n".toByteArray(Charsets.UTF_8))
            out.write('.'.code)
            out.toByteArray()
        }
        writeSaveZip(save, log)
        val slot = RenPySaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            saveName = "Slot",
            renpyVersion = "Ren'Py 8.3.0",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            hasScreenshot = false,
            entries = listOf("log"),
        )

        val inspection = RenPySaveEditor.inspect(slot)

        assertEquals("12", inspection.variables.first { it.key == "store.money" }.displayValue)
    }

    @Test
    fun editorPatchesLargeRenPyInteger() = runBlocking {
        val save = File.createTempFile("renpy-large-int-", ".save")
        save.deleteOnExit()
        writeSaveZip(save, renPyLongPickle("store.money", 5_000_000_000L))
        val slot = RenPySaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            saveName = "Slot",
            renpyVersion = "Ren'Py 8.3.0",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            hasScreenshot = false,
            entries = listOf("log"),
        )

        val before = RenPySaveEditor.inspect(slot)
        assertEquals("5000000000", before.variables.first { it.key == "store.money" }.displayValue)
        val result = RenPySaveEditor.edit(slot, "store.money", "6000000000")
        assertTrue(result.message, result.ok)

        val after = RenPySaveEditor.inspect(slot)
        assertEquals("6000000000", after.variables.first { it.key == "store.money" }.displayValue)
    }

    @Test
    fun rpgmScannerListsRawJsonSaveSlot() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-save-")
        val save = File(dir, "file1.rpgsave")
        save.writeText("""{"gold":123,"mapId":4,"actors":[]}""")
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

        val slots = RpgmSaveScanner.listSaveSlots(location)

        assertEquals(1, slots.size)
        assertEquals("json", slots.single().codec)
        assertTrue(slots.single().summary.contains("map 4"))
    }

    @Test
    fun rpgmEditorPatchesRawJsonScalar() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-edit-")
        val save = File(dir, "file1.rpgsave")
        save.writeText("""{"gold":123,"mapId":4,"actor":{"name":"Alex","alive":true},"actors":[{"level":1}]}""")
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "json",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )

        val before = RpgmSaveEditor.inspect(slot)
        assertEquals("123", before.values.first { it.path == "gold" }.displayValue)
        assertEquals("Alex", before.values.first { it.path == "actor.name" }.displayValue)

        val result = RpgmSaveEditor.edit(slot, "gold", "999")
        assertTrue(result.message, result.ok)
        val nestedResult = RpgmSaveEditor.edit(slot, "actors[0].level", "7")
        assertTrue(nestedResult.message, nestedResult.ok)

        val after = RpgmSaveEditor.inspect(slot)
        assertEquals("999", after.values.first { it.path == "gold" }.displayValue)
        assertEquals("Alex", after.values.first { it.path == "actor.name" }.displayValue)
        assertEquals("7", after.values.first { it.path == "actors[0].level" }.displayValue)
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("${save.name}.agm-bak-") })
    }

    @Test
    fun rpgmEditorCreatesOnlyOneBackupForSessionEdits() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-session-edit-")
        val save = File(dir, "file1.rpgsave")
        save.writeText("""{"gold":123,"actor":{"name":"Alex"}}""")
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "json",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )

        val (first, backup) = RpgmSaveEditor.edit(slot, "gold", "999", null)
        assertTrue(first.message, first.ok)
        val (second, sameBackup) = RpgmSaveEditor.edit(slot, "actor.name", "Robin", backup)
        assertTrue(second.message, second.ok)

        assertEquals(backup?.filePath, sameBackup?.filePath)
        assertEquals(1, dir.listFiles().orEmpty().count { it.name.startsWith("${save.name}.agm-bak-") })
        val after = RpgmSaveEditor.inspect(slot)
        assertEquals("999", after.values.first { it.path == "gold" }.displayValue)
        assertEquals("Robin", after.values.first { it.path == "actor.name" }.displayValue)
    }

    @Test
    fun rpgmEditorEditsTargetPathInLargeSave() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-large-edit-")
        val save = File(dir, "file1.rpgsave")
        val entries = (0 until 3000).joinToString(",") { index ->
            """{"id":$index,"name":"Actor $index","hp":$index}"""
        }
        save.writeText("""{"gold":123,"actors":[$entries],"target":{"value":1}}""")
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "json",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )

        val result = RpgmSaveEditor.edit(slot, "target.value", "42")

        assertTrue(result.message, result.ok)
        val after = RpgmSaveEditor.inspect(slot)
        val saved = json.parseToJsonElement(save.readText()).jsonObject
        assertEquals("42", saved["target"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(after.warning.orEmpty().contains("Large save"))
    }

    @Test
    fun rpgmInspectionCapsLargeValueListsWithWarning() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-cap-")
        val save = File(dir, "file1.rpgsave")
        val entries = (0 until RpgmSaveEditor.MAX_INSPECT_VALUES + 50).joinToString(",") { index ->
            "\"v$index\":$index"
        }
        save.writeText("{$entries}")
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "json",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )

        val inspection = RpgmSaveEditor.inspect(slot)

        assertEquals(RpgmSaveEditor.MAX_INSPECT_VALUES, inspection.values.size)
        assertTrue(inspection.warning.orEmpty().contains("Large save"))
    }

    @Test
    fun rpgmEditorWritesThroughTempAndCleansTempFile() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-temp-edit-")
        val save = File(dir, "file1.rpgsave")
        save.writeText("""{"gold":123}""")
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "json",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )

        val result = RpgmSaveEditor.edit(slot, "gold", "456")

        assertTrue(result.message, result.ok)
        assertTrue(dir.listFiles().orEmpty().none { it.name.contains(".tmp-") })
        assertEquals("456", RpgmSaveEditor.inspect(slot).values.first { it.path == "gold" }.displayValue)
    }

    @Test
    fun rpgmEditorListsAndRestoresBackups() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-restore-")
        val save = File(dir, "file1.rpgsave")
        save.writeText("""{"gold":123}""")
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "json",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )
        val edit = RpgmSaveEditor.edit(slot, "gold", "999")
        assertTrue(edit.message, edit.ok)
        val backups = RpgmSaveEditor.listBackups(slot)
        assertEquals(1, backups.size)

        val restored = RpgmSaveEditor.restoreBackup(slot, backups.single())

        assertTrue(restored.message, restored.ok)
        assertEquals("123", RpgmSaveEditor.inspect(slot).values.first { it.path == "gold" }.displayValue)
        assertTrue(RpgmSaveEditor.listBackups(slot).isNotEmpty())
    }

    @Test
    fun rpgmEditorPatchesZlibBase64SaveAndKeepsCodec() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-zlib-edit-")
        val save = File(dir, "file2.rpgsave")
        save.writeText(deflateBase64("""{"gold":123,"actor":{"name":"Alex"}}"""))
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "zlib-base64",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )

        val result = RpgmSaveEditor.edit(slot, "actor.name", "Robin")

        assertTrue(result.message, result.ok)
        assertTrue("encoded save should remain base64-ish, not raw JSON", !save.readText().trim().startsWith("{"))
        val after = RpgmSaveEditor.inspect(slot)
        assertEquals("Robin", after.values.first { it.path == "actor.name" }.displayValue)
    }

    @Test
    fun rpgmScannerAndEditorHandleLzStringBase64Save() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-lz-edit-")
        val save = File(dir, "file3.rmmzsave")
        save.writeText("N4Ig5g9gNgJiBcBWRAaEBDAxgFwgJwVHSgEsA3AUwWzwFcKBfBoA")
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
        assertEquals("lz-string-base64", slot.codec)
        val result = RpgmSaveEditor.edit(slot, "actor.alive", "false")

        assertTrue(result.message, result.ok)
        val after = RpgmSaveEditor.inspect(slot)
        assertEquals("false", after.values.first { it.path == "actor.alive" }.displayValue)
    }

    @Test
    fun rpgmEditorRejectsInvalidTypedValueWithoutWriting() = runBlocking {
        val dir = createTempDir(prefix = "rpgm-invalid-edit-")
        val save = File(dir, "file1.rpgsave")
        save.writeText("""{"gold":123}""")
        val before = save.readText()
        val slot = RpgmSaveSlot(
            fileName = save.name,
            filePath = save.absolutePath,
            codec = "json",
            modifiedAt = save.lastModified(),
            sizeBytes = save.length(),
            summary = "",
        )

        val result = RpgmSaveEditor.edit(slot, "gold", "not-a-number")

        assertTrue(!result.ok)
        assertEquals(before, save.readText())
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("${save.name}.agm-bak-") })
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun writeSaveZip(save: File, log: ByteArray) {
        ZipOutputStream(save.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("log"))
            zip.write(log)
            zip.closeEntry()
            zip.putText("json", """{"_save_name":"Slot"}""")
            zip.putText("renpy_version", "Ren'Py 8.3.0")
            zip.putText("signatures", "signature")
        }
    }

    private fun deflateBase64(input: String): String {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(input.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun renPyLogPickle(money: Int, name: String, flag: Boolean): ByteArray =
        ByteArrayOutputStream().use { out ->
            out.write(byteArrayOf(0x80.toByte(), 0x02))
            out.write('}'.code)
            out.write('('.code)
            out.writeBinUnicode("store.money")
            out.write('K'.code)
            out.write(money)
            out.writeBinUnicode("store.name")
            out.writeBinUnicode(name)
            out.writeBinUnicode("store.flag")
            out.write(if (flag) 0x88 else 0x89)
            out.write('u'.code)
            out.write(']'.code)
            out.write(0x86)
            out.write('.'.code)
            out.toByteArray()
        }

    private fun renPyLongPickle(key: String, value: Long): ByteArray =
        ByteArrayOutputStream().use { out ->
            out.write(byteArrayOf(0x80.toByte(), 0x02))
            out.write('}'.code)
            out.write('('.code)
            out.writeBinUnicode(key)
            out.writePickleLong(value)
            out.write('u'.code)
            out.write(']'.code)
            out.write(0x86)
            out.write('.'.code)
            out.toByteArray()
        }

    private fun ByteArrayOutputStream.writePickleLong(value: Long) {
        val raw = BigInteger.valueOf(value).toByteArray().reversedArray()
        write(0x8a)
        write(raw.size)
        write(raw)
    }

    private fun ByteArrayOutputStream.writeBinUnicode(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write('X'.code)
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
        write(bytes)
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun intPart(bytes: ByteArray): ByteArray {
            val withoutLeadingZeros = bytes.dropWhile { it == 0.toByte() }.toByteArray()
            val trimmed = if (withoutLeadingZeros.isEmpty()) byteArrayOf(0) else withoutLeadingZeros
            val positive = if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(0x02, positive.size.toByte()) + positive
        }
        val r = intPart(raw.copyOfRange(0, 32))
        val s = intPart(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }
}
