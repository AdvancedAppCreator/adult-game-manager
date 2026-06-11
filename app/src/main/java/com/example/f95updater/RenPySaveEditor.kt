package com.example.f95updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class RenPyEditableVariable(
    val key: String,
    val type: String,
    val displayValue: String,
    val patchStart: Int,
    val patchEnd: Int,
)

data class RenPyEditInspection(
    val variables: List<RenPyEditableVariable>,
    val warning: String? = null,
)

data class RenPyEditResult(val ok: Boolean, val message: String)

data class SaveEditSessionBackup(
    val fileName: String,
    val filePath: String,
    val createdAt: Long,
)

private data class RenPySigningKey(
    val source: File,
    val privateDer: ByteArray,
    val publicDer: ByteArray,
)

private data class RenPySignatureResult(
    val signatures: String,
    val keyFile: File,
)

data class RenPySaveBackup(
    val fileName: String,
    val filePath: String,
    val createdAt: Long,
    val sizeBytes: Long,
)

object RenPySaveEditor {
    private const val MAX_BACKUPS = 3

    suspend fun inspect(slot: RenPySaveSlot): RenPyEditInspection = withContext(Dispatchers.IO) {
        runCatching {
            val log = readLog(File(slot.filePath)) ?: return@withContext RenPyEditInspection(emptyList(), "No Ren'Py log entry found.")
            val variables = editableVariables(log)
            RenPyEditInspection(
                variables = variables,
                warning = if (variables.isEmpty()) "No directly editable store.* scalar variables were found." else null,
            )
        }.getOrElse { t ->
            AppLog.w("RenPyEditor", "Save inspection failed for ${slot.fileName}: ${t.message}", t)
            RenPyEditInspection(
                variables = emptyList(),
                warning = "This save uses Ren'Py pickle data AGM cannot safely edit yet (${t.javaClass.simpleName}: ${t.message ?: "unknown error"}).",
            )
        }
    }

    suspend fun edit(
        slot: RenPySaveSlot,
        variableKey: String,
        newValue: String,
        sessionBackup: SaveEditSessionBackup? = null,
    ): Pair<RenPyEditResult, SaveEditSessionBackup?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val saveFile = File(slot.filePath)
                val originalLog = readLog(saveFile)                 ?: return@withContext RenPyEditResult(false, "No Ren'Py log entry found.") to sessionBackup
                val variable = editableVariables(originalLog).firstOrNull { it.key == variableKey }
                ?: return@withContext RenPyEditResult(false, "Variable is not editable.") to sessionBackup

                val encoded = encodeValue(variable, newValue)
                ?: return@withContext RenPyEditResult(false, "Invalid value for ${variable.type}.") to sessionBackup
                val patchedLog = patchBytes(originalLog, variable.patchStart, variable.patchEnd, encoded)
                val verifyValue = editableVariables(patchedLog).firstOrNull { it.key == variableKey }
                ?: return@withContext RenPyEditResult(false, "Patched variable could not be read back.") to sessionBackup
                if (verifyValue.displayValue != normalizedDisplay(variable.type, newValue)) {
                return@withContext RenPyEditResult(false, "Patch verification did not read back the new value.") to sessionBackup
                }

                val backup = sessionBackup?.takeIf { File(it.filePath).isFile }
                ?: createBackup(saveFile).toSessionBackup()
                val tmp = File(saveFile.parentFile, "${saveFile.name}.tmp-${System.currentTimeMillis()}")
                val signatureResult = signLogIfPossible(saveFile.parentFile, patchedLog)
                rebuildSaveZip(saveFile, tmp, patchedLog, signatureResult?.signatures.orEmpty())
                val replaced = runCatching {
                    tmp.copyTo(saveFile, overwrite = true)
                    tmp.delete()
                    true
                }.getOrDefault(false)
                if (!replaced) {
                    tmp.delete()
                    return@withContext RenPyEditResult(false, "Could not replace save file; backup kept at ${backup.fileName}.") to backup
                }
                pruneBackups(saveFile)
                val signedText = signatureResult?.let { " Signed with ${it.keyFile.name}." }
                    ?: " No signing key found; the game may warn that the save is unsigned."
                val backupText = if (sessionBackup == null) "Backup: ${backup.fileName}." else "Session backup: ${backup.fileName}."
                RenPyEditResult(true, "Saved. $backupText$signedText") to backup
            }.getOrElse { t ->
                AppLog.w("RenPyEditor", "Save edit failed for ${slot.fileName}: ${t.message}", t)
                RenPyEditResult(false, "Could not safely edit this save (${t.javaClass.simpleName}: ${t.message ?: "unknown error"}).") to sessionBackup
            }
        }

    suspend fun edit(slot: RenPySaveSlot, variableKey: String, newValue: String): RenPyEditResult =
        edit(slot, variableKey, newValue, null).first

    suspend fun listBackups(slot: RenPySaveSlot): List<RenPySaveBackup> = withContext(Dispatchers.IO) {
        val saveFile = File(slot.filePath)
        managedBackups(saveFile).map { file ->
            RenPySaveBackup(
                fileName = file.name,
                filePath = file.absolutePath,
                createdAt = backupTimestamp(file) ?: file.lastModified(),
                sizeBytes = file.length(),
            )
        }
    }

    suspend fun restoreBackup(slot: RenPySaveSlot, backup: RenPySaveBackup): RenPyEditResult = withContext(Dispatchers.IO) {
        runCatching {
            val saveFile = File(slot.filePath)
            val backupFile = File(backup.filePath)
            if (!backupFile.isFile) return@withContext RenPyEditResult(false, "Backup file no longer exists.")
            val preRestore = createBackup(saveFile)
            backupFile.copyTo(saveFile, overwrite = true)
            pruneBackups(saveFile)
            RenPyEditResult(true, "Restored ${backup.fileName}. Previous current save backed up as ${preRestore.name}.")
        }.getOrElse { t ->
            AppLog.w("RenPyEditor", "Restore failed for ${slot.fileName}: ${t.message}", t)
            RenPyEditResult(false, "Restore failed: ${t.message ?: "unknown error"}")
        }
    }

    suspend fun extractThumbnail(context: Context, slot: RenPySaveSlot): File? = withContext(Dispatchers.IO) {
        if (!slot.hasScreenshot) return@withContext null
        val saveFile = File(slot.filePath)
        val key = sha256("${slot.filePath}:${slot.modifiedAt}:${slot.sizeBytes}").take(24)
        val dir = File(context.cacheDir, "renpy-save-thumbs").apply { mkdirs() }
        val out = File(dir, "$key.png")
        if (out.isFile && out.length() > 0L) return@withContext out
        runCatching {
            ZipFile(saveFile).use { zip ->
                val entry = zip.getEntry("screenshot.png") ?: return@withContext null
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out
        }.getOrNull()
    }

    private fun createBackup(saveFile: File): File {
        val backup = File(saveFile.parentFile, "${saveFile.name}.agm-bak-${System.currentTimeMillis()}")
        saveFile.copyTo(backup, overwrite = false)
        return backup
    }

    private fun File.toSessionBackup(): SaveEditSessionBackup =
        SaveEditSessionBackup(
            fileName = name,
            filePath = absolutePath,
            createdAt = backupTimestamp(this) ?: lastModified(),
        )

    private fun managedBackups(saveFile: File): List<File> =
        saveFile.parentFile?.listFiles { file ->
            file.isFile && (
                file.name.startsWith("${saveFile.name}.agm-bak-") ||
                    file.name.startsWith("${saveFile.name}.bak-")
                )
        }.orEmpty()
            .sortedByDescending { backupTimestamp(it) ?: it.lastModified() }

    private fun pruneBackups(saveFile: File) {
        managedBackups(saveFile).drop(MAX_BACKUPS).forEach { runCatching { it.delete() } }
    }

    private fun backupTimestamp(file: File): Long? =
        file.name.substringAfterLast(".agm-bak-", file.name)
            .substringAfterLast(".bak-", "")
            .toLongOrNull()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun editableVariables(log: ByteArray): List<RenPyEditableVariable> {
        val byVm = runCatching {
            val parsed = PickleVm.parse(log)
            val roots = parsed.finalValue.asTupleItems().firstOrNull() as? PValue.DictValue
                ?: return@runCatching emptyList()
            roots.entries.mapNotNull { (keyValue, value) ->
                val key = (keyValue as? PValue.StringValue)?.value ?: return@mapNotNull null
                if (!key.startsWith("store.")) return@mapNotNull null
                value.toEditableVariable(key, parsed.referenceCounts)
            }
        }.getOrDefault(emptyList())
        val direct = scanDirectStoreScalars(log)
        return (byVm + direct)
            .distinctBy { it.key }
            .sortedBy { it.key.lowercase() }
    }

    private fun scanDirectStoreScalars(log: ByteArray): List<RenPyEditableVariable> {
        val found = linkedMapOf<String, RenPyEditableVariable>()
        var offset = 0
        while (offset < log.size) {
            val key = readUnicodeAt(log, offset)
            if (key != null && key.value.startsWith("store.")) {
                var valueOffset = skipMemoOpcodes(log, key.end)
                readScalarAt(log, valueOffset)?.let { variable ->
                    found.putIfAbsent(
                        key.value,
                        variable.copy(key = key.value),
                    )
                }
            }
            val len = runCatching { PickleVm.opcodeLength(log, offset) }.getOrDefault(1).coerceAtLeast(1)
            offset += len
        }
        return found.values.toList()
    }

    private data class StringRead(val value: String, val end: Int)

    private fun readUnicodeAt(bytes: ByteArray, offset: Int): StringRead? {
        if (offset >= bytes.size) return null
        val op = bytes[offset].toInt() and 0xff
        return runCatching {
            when (op) {
                'X'.code -> {
                    val len = ByteBuffer.wrap(bytes, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val start = offset + 5
                    StringRead(bytes.copyOfRange(start, start + len).toString(Charsets.UTF_8), start + len)
                }
                0x8c -> {
                    val len = bytes[offset + 1].toInt() and 0xff
                    val start = offset + 2
                    StringRead(bytes.copyOfRange(start, start + len).toString(Charsets.UTF_8), start + len)
                }
                0x8d -> {
                    val len = ByteBuffer.wrap(bytes, offset + 1, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
                    val start = offset + 9
                    StringRead(bytes.copyOfRange(start, start + len).toString(Charsets.UTF_8), start + len)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun skipMemoOpcodes(bytes: ByteArray, start: Int): Int {
        var offset = start
        while (offset < bytes.size) {
            val op = bytes[offset].toInt() and 0xff
            offset += when (op) {
                0x94 -> 1
                'q'.code -> 2
                'r'.code -> 5
                else -> return offset
            }
        }
        return offset
    }

    private fun readScalarAt(bytes: ByteArray, offset: Int): RenPyEditableVariable? {
        if (offset >= bytes.size) return null
        val op = bytes[offset].toInt() and 0xff
        return runCatching {
            when (op) {
                'K'.code -> RenPyEditableVariable("", "int", (bytes[offset + 1].toInt() and 0xff).toString(), offset, offset + 2)
                'M'.code -> {
                    val v = (bytes[offset + 1].toInt() and 0xff) or ((bytes[offset + 2].toInt() and 0xff) shl 8)
                    RenPyEditableVariable("", "int", v.toString(), offset, offset + 3)
                }
                'J'.code -> {
                    val v = ByteBuffer.wrap(bytes, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    RenPyEditableVariable("", "int", v.toString(), offset, offset + 5)
                }
                'G'.code -> {
                    val v = ByteBuffer.wrap(bytes, offset + 1, 8).order(ByteOrder.BIG_ENDIAN).double
                    RenPyEditableVariable("", "float", v.toString(), offset, offset + 9)
                }
                0x88 -> RenPyEditableVariable("", "bool", "true", offset, offset + 1)
                0x89 -> RenPyEditableVariable("", "bool", "false", offset, offset + 1)
                'X'.code, 0x8c, 0x8d -> {
                    val s = readUnicodeAt(bytes, offset) ?: return@runCatching null
                    RenPyEditableVariable("", "string", s.value, offset, s.end)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun PValue.toEditableVariable(key: String, referenceCounts: Map<Int, Int>): RenPyEditableVariable? {
        if (referenceCounts[id].orZero() > 0) return null
        return when (this) {
            is PValue.IntValue -> RenPyEditableVariable(key, "int", value.toString(), start, end)
            is PValue.FloatValue -> RenPyEditableVariable(key, "float", value.toString(), start, end)
            is PValue.BoolValue -> RenPyEditableVariable(key, "bool", value.toString(), start, end)
            is PValue.StringValue -> RenPyEditableVariable(key, "string", value, start, end)
            else -> null
        }
    }

    private fun encodeValue(variable: RenPyEditableVariable, text: String): ByteArray? =
        when (variable.type) {
            "int" -> text.trim().toLongOrNull()?.let { encodeLong(it) }
            "float" -> text.trim().toDoubleOrNull()?.let { encodeFloat(it) }
            "bool" -> when (text.trim().lowercase()) {
                "true", "1", "yes", "on" -> byteArrayOf(0x88.toByte())
                "false", "0", "no", "off" -> byteArrayOf(0x89.toByte())
                else -> null
            }
            "string" -> encodeString(text)
            else -> null
        }

    private fun normalizedDisplay(type: String, text: String): String =
        when (type) {
            "int" -> text.trim().toLong().toString()
            "float" -> text.trim().toDouble().toString()
            "bool" -> when (text.trim().lowercase()) {
                "true", "1", "yes", "on" -> "true"
                else -> "false"
            }
            else -> text
        }

    private fun encodeLong(value: Long): ByteArray {
        if (value in 0..255) return byteArrayOf('K'.code.toByte(), value.toByte())
        if (value in 0..65535) {
            val v = value.toInt()
            return byteArrayOf('M'.code.toByte(), (v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte())
        }
        if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
            val v = value.toInt()
            return ByteArrayOutputStream().use { out ->
                out.write('J'.code)
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
                out.toByteArray()
            }
        }
        val raw = BigInteger.valueOf(value).toByteArray().reversedArray()
        return ByteArrayOutputStream().use { out ->
            out.write(0x8a)
            out.write(raw.size)
            out.write(raw)
            out.toByteArray()
        }
    }

    private fun encodeFloat(value: Double): ByteArray =
        ByteArrayOutputStream().use { out ->
            out.write('G'.code)
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array())
            out.toByteArray()
        }

    private fun encodeString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= 255) {
            byteArrayOf(0x8c.toByte(), bytes.size.toByte()) + bytes
        } else {
            ByteArrayOutputStream().use { out ->
                out.write('X'.code)
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
                out.write(bytes)
                out.toByteArray()
            }
        }
    }

    private fun patchBytes(original: ByteArray, start: Int, end: Int, replacement: ByteArray): ByteArray {
        val delta = replacement.size - (end - start)
        val out = ByteArrayOutputStream()
        out.write(original, 0, start)
        out.write(replacement)
        out.write(original, end, original.size - end)
        val patched = out.toByteArray()
        if (delta != 0) updateContainingFrameLength(patched, original, start, delta)
        return patched
    }

    private fun updateContainingFrameLength(patched: ByteArray, original: ByteArray, patchStart: Int, delta: Int) {
        var offset = 0
        while (offset < original.size) {
            val op = original[offset].toInt() and 0xff
            if (op == 0x95) {
                val lenOffset = offset + 1
                val payloadStart = offset + 9
                val length = ByteBuffer.wrap(original, lenOffset, 8).order(ByteOrder.LITTLE_ENDIAN).long
                val payloadEnd = payloadStart + length.toInt()
                if (patchStart in payloadStart until payloadEnd) {
                    val newLength = length + delta
                    ByteBuffer.wrap(patched, lenOffset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(newLength)
                    return
                }
                offset = payloadEnd
            } else {
                offset += PickleVm.opcodeLength(original, offset)
            }
        }
    }

    private fun readLog(saveFile: File): ByteArray? =
        runCatching {
            ZipFile(saveFile).use { zip ->
                val entry = zip.getEntry("log") ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()

    private fun rebuildSaveZip(source: File, target: File, newLog: ByteArray, signatures: String) {
        ZipFile(source).use { zip ->
            ZipOutputStream(target.outputStream()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    val newEntry = ZipEntry(entry.name)
                    out.putNextEntry(newEntry)
                    when (entry.name) {
                        "log" -> out.write(newLog)
                        "signatures" -> out.write(signatures.toByteArray(Charsets.UTF_8))
                        else -> zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
                if (zip.getEntry("signatures") == null) {
                    out.putNextEntry(ZipEntry("signatures"))
                    out.write(signatures.toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
            }
        }
    }

    private fun signLogIfPossible(saveDir: File?, log: ByteArray): RenPySignatureResult? {
        if (saveDir == null) return null
        val candidates = signingKeyCandidates(saveDir)
        val directFiles = candidates.take(2).filter { it.isFile }
        if (directFiles.isNotEmpty()) {
            val directKeys = directFiles.flatMap { parseSigningKeys(it) }
            AppLog.i("RenPyEditor", "Direct security key files=${directFiles.map { it.absolutePath }} signingKeys=${directKeys.size}")
            if (directKeys.isEmpty()) {
                AppLog.w("RenPyEditor", "Direct security_keys.txt exists but has no usable signing-key; leaving save unsigned")
                return null
            }
            return signWithFirstWorkingKey(log, directKeys)
        }

        val fallbackKeys = candidates.drop(2).flatMap { parseSigningKeys(it) }
        AppLog.i("RenPyEditor", "Fallback security key search files=${candidates.drop(2).filter { it.isFile }.map { it.absolutePath }} signingKeys=${fallbackKeys.size}")
        return signWithFirstWorkingKey(log, fallbackKeys)
    }

    private fun signWithFirstWorkingKey(log: ByteArray, keys: List<RenPySigningKey>): RenPySignatureResult? {
        val key = keys.firstOrNull { signingKey ->
            runCatching { signWithKey(log, signingKey) != null }.getOrDefault(false)
        } ?: return null
        val rawSig = signWithKey(log, key) ?: return null
        val line = encodeLine("signature", key.publicDer, rawSig)
        AppLog.i("RenPyEditor", "Signed Ren'Py save log with ${key.source.absolutePath}; signatureBytes=${rawSig.size}")
        return RenPySignatureResult(line, key.source)
    }

    private fun signingKeyCandidates(saveDir: File): List<File> =
        buildList {
            add(File(saveDir, "security_keys.txt"))
            add(File(saveDir, "tokens/security_keys.txt"))
            saveDir.parentFile?.let { parent ->
                add(File(parent, "security_keys.txt"))
                add(File(parent, "tokens/security_keys.txt"))
            }
            saveDir.parentFile?.parentFile?.let { grand ->
                add(File(grand, "tokens/security_keys.txt"))
            }
        }.distinctBy { it.absolutePath }

    private fun parseSigningKeys(file: File): List<RenPySigningKey> {
        if (!file.isFile) return emptyList()
        val keys = file.readLines().mapNotNull { line ->
            val parts = line.trim().split(Regex("""\s+"""))
            if (parts.size < 3 || parts[0] != "signing-key") return@mapNotNull null
            runCatching {
                RenPySigningKey(
                    source = file,
                    privateDer = Base64.getDecoder().decode(parts[1]),
                    publicDer = Base64.getDecoder().decode(parts[2]),
                )
            }.getOrNull()
        }
        if (keys.isEmpty()) {
            val hasSecurityLines = runCatching { file.readLines().any { it.trim().isNotBlank() && !it.trim().startsWith("#") } }.getOrDefault(false)
            if (hasSecurityLines) AppLog.w("RenPyEditor", "No usable signing-key lines in ${file.absolutePath}")
        }
        return keys
    }

    private fun signWithKey(data: ByteArray, key: RenPySigningKey): ByteArray? =
        runCatching {
            val privateKey = parseEcPrivateKey(key.privateDer)
            val publicKey = parseEcPublicKey(key.publicDer) as ECPublicKey
            val signer = Signature.getInstance("SHA1withECDSA")
            signer.initSign(privateKey)
            signer.update(data)
            val der = signer.sign()
            val raw = derSignatureToRaw(der)
            val verifier = Signature.getInstance("SHA1withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(data)
            if (!verifier.verify(rawSignatureToDer(raw))) return null
            raw
        }.getOrElse { t ->
            AppLog.w("RenPyEditor", "Signing failed with ${key.source.absolutePath}: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }

    private fun parseEcPrivateKey(privateDer: ByteArray): PrivateKey {
        val sec1Wrapped = lazy { wrapSec1EcPrivateKeyAsPkcs8(privateDer) }
        var last: Throwable? = null
        for (factory in ecKeyFactories()) {
            runCatching { return factory.generatePrivate(PKCS8EncodedKeySpec(privateDer)) }
                .onFailure { last = it }
            runCatching { return factory.generatePrivate(PKCS8EncodedKeySpec(sec1Wrapped.value)) }
                .onFailure { last = it }
        }
        throw last ?: IllegalArgumentException("No EC KeyFactory accepted private key")
    }

    private fun parseEcPublicKey(publicDer: ByteArray): PublicKey {
        var last: Throwable? = null
        for (factory in ecKeyFactories()) {
            runCatching { return factory.generatePublic(X509EncodedKeySpec(publicDer)) }
                .onFailure { last = it }
        }
        throw last ?: IllegalArgumentException("No EC KeyFactory accepted public key")
    }

    private fun ecKeyFactories(): List<KeyFactory> {
        val factories = Security.getProviders().asSequence()
            .filterNot { it.name.equals("AndroidKeyStore", ignoreCase = true) }
            .mapNotNull { provider ->
                runCatching { KeyFactory.getInstance("EC", provider) }.getOrNull()
            }
            .toList()
        return factories.ifEmpty { listOf(KeyFactory.getInstance("EC")) }
    }

    private fun wrapSec1EcPrivateKeyAsPkcs8(sec1Der: ByteArray): ByteArray {
        val ecPublicKeyOid = derOid(1, 2, 840, 10045, 2, 1)
        val prime256v1Oid = derOid(1, 2, 840, 10045, 3, 1, 7)
        val algorithm = derSequence(ecPublicKeyOid + prime256v1Oid)
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val privateKey = derOctetString(sec1Der)
        return derSequence(version + algorithm + privateKey)
    }

    private fun derSequence(content: ByteArray): ByteArray = byteArrayOf(0x30) + derLength(content.size) + content

    private fun derOctetString(content: ByteArray): ByteArray = byteArrayOf(0x04) + derLength(content.size) + content

    private fun derLength(length: Int): ByteArray =
        when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length <= 0xff -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
        }

    private fun derOid(vararg components: Int): ByteArray {
        require(components.size >= 2)
        val body = mutableListOf<Byte>()
        body += (components[0] * 40 + components[1]).toByte()
        components.drop(2).forEach { component ->
            val stack = mutableListOf<Byte>()
            var value = component
            stack += (value and 0x7f).toByte()
            value = value ushr 7
            while (value > 0) {
                stack += ((value and 0x7f) or 0x80).toByte()
                value = value ushr 7
            }
            body += stack.asReversed()
        }
        return byteArrayOf(0x06) + derLength(body.size) + body.toByteArray()
    }

    private fun encodeLine(kind: String, first: ByteArray, second: ByteArray): String =
        kind + " " +
            Base64.getEncoder().encodeToString(first) + " " +
            Base64.getEncoder().encodeToString(second) + "\n"

    private fun derSignatureToRaw(der: ByteArray): ByteArray {
        require(der.isNotEmpty() && der[0] == 0x30.toByte()) { "Not an ECDSA DER signature" }
        var offset = 2
        if ((der[1].toInt() and 0xff) > 0x80) {
            val lenBytes = der[1].toInt() and 0x7f
            offset = 2 + lenBytes
        }
        require(der[offset++] == 0x02.toByte()) { "Missing R integer" }
        val rLen = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        require(der[offset++] == 0x02.toByte()) { "Missing S integer" }
        val sLen = der[offset++].toInt() and 0xff
        val s = der.copyOfRange(offset, offset + sLen)
        return unsignedTo32(r) + unsignedTo32(s)
    }

    private fun rawSignatureToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "Raw signature must be 64 bytes" }
        val r = derInteger(raw.copyOfRange(0, 32))
        val s = derInteger(raw.copyOfRange(32, 64))
        val bodyLen = r.size + s.size
        return byteArrayOf(0x30, bodyLen.toByte()) + r + s
    }

    private fun unsignedTo32(value: ByteArray): ByteArray {
        val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
        require(trimmed.size <= 32) { "ECDSA integer too large" }
        return ByteArray(32 - trimmed.size) + trimmed
    }

    private fun derInteger(raw32: ByteArray): ByteArray {
        val withoutLeadingZeros = raw32.dropWhile { it == 0.toByte() }.toByteArray()
        val trimmed = if (withoutLeadingZeros.isEmpty()) byteArrayOf(0) else withoutLeadingZeros
        val positive = if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
        return byteArrayOf(0x02, positive.size.toByte()) + positive
    }

    private fun Int?.orZero(): Int = this ?: 0
}

private sealed class PValue(open val id: Int, open val start: Int, open val end: Int) {
    data class StringValue(override val id: Int, val value: String, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class IntValue(override val id: Int, val value: Long, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class FloatValue(override val id: Int, val value: Double, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class BoolValue(override val id: Int, val value: Boolean, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class NoneValue(override val id: Int, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class OpaqueValue(override val id: Int, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class DictValue(override val id: Int, val entries: LinkedHashMap<PValue, PValue>, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class ListValue(override val id: Int, val items: List<PValue>, override val start: Int, override val end: Int) : PValue(id, start, end)
    data class TupleValue(override val id: Int, val items: List<PValue>, override val start: Int, override val end: Int) : PValue(id, start, end)

    fun asTupleItems(): List<PValue> = (this as? TupleValue)?.items.orEmpty()
}

private object Mark

private data class PickleParseResult(val finalValue: PValue, val referenceCounts: Map<Int, Int>)

private object PickleVm {
    private var nextId = 1

    fun parse(bytes: ByteArray): PickleParseResult {
        nextId = 1
        val stack = mutableListOf<Any>()
        val memo = mutableMapOf<Int, PValue>()
        val refCounts = mutableMapOf<Int, Int>()
        var lastMemoIndex = 0
        var offset = 0
        var finalValue: PValue? = null
        while (offset < bytes.size) {
            val start = offset
            val op = bytes[offset].toInt() and 0xff
            offset++
            when (op) {
                0x80 -> offset++
                0x95 -> offset += 8
                '.'.code -> {
                    finalValue = stack.lastOrNull() as? PValue
                    break
                }
                '('.code -> stack += Mark
                ')'.code -> stack += tuple(emptyList(), start, offset)
                ']'.code -> stack += list(emptyList(), start, offset)
                '}'.code -> stack += dict(linkedMapOf(), start, offset)
                'N'.code -> stack += none(start, offset)
                0x88 -> stack += bool(true, start, offset)
                0x89 -> stack += bool(false, start, offset)
                'K'.code -> {
                    val v = bytes[offset].toInt() and 0xff
                    offset++
                    stack += int(v.toLong(), start, offset)
                }
                'M'.code -> {
                    val v = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
                    offset += 2
                    stack += int(v.toLong(), start, offset)
                }
                'J'.code -> {
                    val v = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    offset += 4
                    stack += int(v.toLong(), start, offset)
                }
                0x8a -> {
                    val len = bytes[offset].toInt() and 0xff
                    offset++
                    val raw = bytes.copyOfRange(offset, offset + len)
                    offset += len
                    stack += int(BigInteger(raw.reversedArray()).toLong(), start, offset)
                }
                0x8b -> {
                    val len = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    offset += 4 + len
                    stack += opaque(start, offset)
                }
                'G'.code -> {
                    val v = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).double
                    offset += 8
                    stack += float(v, start, offset)
                }
                'X'.code -> {
                    val len = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    offset += 4
                    val value = bytes.copyOfRange(offset, offset + len).toString(Charsets.UTF_8)
                    offset += len
                    stack += string(value, start, offset)
                }
                0x8c -> {
                    val len = bytes[offset].toInt() and 0xff
                    offset++
                    val value = bytes.copyOfRange(offset, offset + len).toString(Charsets.UTF_8)
                    offset += len
                    stack += string(value, start, offset)
                }
                0x8d -> {
                    val len = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
                    offset += 8
                    val value = bytes.copyOfRange(offset, offset + len).toString(Charsets.UTF_8)
                    offset += len
                    stack += string(value, start, offset)
                }
                'q'.code -> memo[bytes[offset++].toInt() and 0xff] = stack.last() as PValue
                'r'.code -> {
                    memo[ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int] = stack.last() as PValue
                    offset += 4
                }
                0x94 -> memo[lastMemoIndex++] = stack.last() as PValue
                'h'.code -> {
                    val value = memo[bytes[offset++].toInt() and 0xff] ?: opaque(start, offset)
                    refCounts[value.id] = refCounts.getOrDefault(value.id, 0) + 1
                    stack += value
                }
                'j'.code -> {
                    val value = memo[ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int] ?: opaque(start, offset)
                    offset += 4
                    refCounts[value.id] = refCounts.getOrDefault(value.id, 0) + 1
                    stack += value
                }
                'u'.code -> setItems(stack)
                's'.code -> {
                    val value = stack.removeLast() as PValue
                    val key = stack.removeLast() as PValue
                    val d = stack.last() as? PValue.DictValue ?: throw IllegalArgumentException("SETITEM without dict")
                    d.entries[key] = value
                }
                't'.code -> stack += tuple(popSinceMark(stack), start, offset)
                0x85 -> stack += tuple(listOf(stack.removeLast() as PValue), start, offset)
                0x86 -> {
                    val b = stack.removeLast() as PValue
                    val a = stack.removeLast() as PValue
                    stack += tuple(listOf(a, b), start, offset)
                }
                0x87 -> {
                    val c = stack.removeLast() as PValue
                    val b = stack.removeLast() as PValue
                    val a = stack.removeLast() as PValue
                    stack += tuple(listOf(a, b, c), start, offset)
                }
                'l'.code -> stack += list(popSinceMark(stack), start, offset)
                'e'.code -> {
                    val items = popSinceMark(stack)
                    val l = stack.last() as? PValue.ListValue ?: throw IllegalArgumentException("APPENDS without list")
                    stack[stack.lastIndex] = l.copy(items = l.items + items, end = offset)
                }
                'a'.code -> {
                    val item = stack.removeLast() as PValue
                    val l = stack.last() as? PValue.ListValue ?: throw IllegalArgumentException("APPEND without list")
                    stack[stack.lastIndex] = l.copy(items = l.items + item, end = offset)
                }
                '0'.code -> stack.removeLast()
                '1'.code -> popSinceMark(stack)
                '2'.code -> stack += stack.last()
                else -> {
                    val len = opcodeLength(bytes, start)
                    offset = start + len
                    applyOpaqueOpcode(op, stack, start, offset)
                }
            }
        }
        return PickleParseResult(finalValue ?: throw IllegalArgumentException("No STOP value"), refCounts)
    }

    fun opcodeLength(bytes: ByteArray, offset: Int): Int {
        val op = bytes[offset].toInt() and 0xff
        return when (op) {
            0x80, 'K'.code, 'q'.code, 'h'.code, 0x8a, 0x82 -> 2
            0x83 -> 3
            'M'.code -> 3
            'J'.code, 'r'.code, 'j'.code, 0x84 -> 5
            'G'.code -> 9
            0x95 -> 9
            'X'.code, 'B'.code -> 5 + ByteBuffer.wrap(bytes, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            0x8c, 'C'.code -> 2 + (bytes[offset + 1].toInt() and 0xff)
            0x8d, 0x8e, 0x96 -> 9 + ByteBuffer.wrap(bytes, offset + 1, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
            0x8b -> 5 + ByteBuffer.wrap(bytes, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            'c'.code, 'i'.code, 'P'.code -> {
                var p = offset + 1
                var newlines = if (op == 'P'.code) 1 else 2
                while (p < bytes.size && newlines > 0) if (bytes[p++].toInt().toChar() == '\n') newlines--
                p - offset
            }
            'I'.code, 'L'.code, 'F'.code, 'S'.code, 'V'.code, 'p'.code, 'g'.code -> {
                var p = offset + 1
                while (p < bytes.size && bytes[p++].toInt().toChar() != '\n') Unit
                p - offset
            }
            else -> 1
        }
    }

    private fun applyOpaqueOpcode(op: Int, stack: MutableList<Any>, start: Int, end: Int) {
        when (op) {
            'c'.code, 0x82, 0x83, 0x84 -> stack += opaque(start, end)
            'C'.code, 'B'.code, 0x8e, 0x96 -> stack += opaque(start, end)
            0x93 -> {
                if (stack.size >= 2) {
                    stack.removeLast(); stack.removeLast()
                }
                stack += opaque(start, end)
            }
            'R'.code, 0x81 -> {
                if (stack.size >= 2) {
                    stack.removeLast(); stack.removeLast()
                }
                stack += opaque(start, end)
            }
            0x92 -> {
                if (stack.size >= 3) {
                    stack.removeLast(); stack.removeLast(); stack.removeLast()
                }
                stack += opaque(start, end)
            }
            'b'.code -> {
                if (stack.size >= 2) {
                    stack.removeLast()
                    val obj = stack.removeLast() as? PValue ?: opaque(start, end)
                    stack += opaque(obj.start, end)
                }
            }
            'Q'.code -> {
                if (stack.isNotEmpty()) stack.removeLast()
                stack += opaque(start, end)
            }
            0x8f -> stack += opaque(start, end)
            0x90, 0x91 -> {
                popSinceMark(stack)
                stack += opaque(start, end)
            }
            else -> {
                // Opcode has no useful stack value for direct store.* scalar editing.
            }
        }
    }

    private fun setItems(stack: MutableList<Any>) {
        val items = popSinceMark(stack)
        val d = stack.last() as? PValue.DictValue ?: throw IllegalArgumentException("SETITEMS without dict")
        var i = 0
        while (i + 1 < items.size) {
            d.entries[items[i]] = items[i + 1]
            i += 2
        }
    }

    private fun popSinceMark(stack: MutableList<Any>): List<PValue> {
        val items = mutableListOf<PValue>()
        while (stack.isNotEmpty()) {
            val item = stack.removeLast()
            if (item === Mark) break
            items += item as PValue
        }
        return items.asReversed()
    }

    private fun string(value: String, start: Int, end: Int) = PValue.StringValue(nextId++, value, start, end)
    private fun int(value: Long, start: Int, end: Int) = PValue.IntValue(nextId++, value, start, end)
    private fun float(value: Double, start: Int, end: Int) = PValue.FloatValue(nextId++, value, start, end)
    private fun bool(value: Boolean, start: Int, end: Int) = PValue.BoolValue(nextId++, value, start, end)
    private fun none(start: Int, end: Int) = PValue.NoneValue(nextId++, start, end)
    private fun opaque(start: Int, end: Int) = PValue.OpaqueValue(nextId++, start, end)
    private fun dict(entries: LinkedHashMap<PValue, PValue>, start: Int, end: Int) = PValue.DictValue(nextId++, entries, start, end)
    private fun list(items: List<PValue>, start: Int, end: Int) = PValue.ListValue(nextId++, items, start, end)
    private fun tuple(items: List<PValue>, start: Int, end: Int) = PValue.TupleValue(nextId++, items, start, end)
}
