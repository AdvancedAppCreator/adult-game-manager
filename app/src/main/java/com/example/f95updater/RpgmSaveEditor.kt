package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class RpgmEditableValue(
    val path: String,
    val type: String,
    val displayValue: String,
    /** Human-readable RPG Maker name for this value (from the game's data JSON files), when known. */
    val name: String? = null,
    /** Semantic bucket used to group/highlight meaningful properties in the editor UI. */
    val category: String = RpgmCategory.OTHER,
)

/** Semantic groups the editor uses to surface meaningful RPG Maker save properties. */
object RpgmCategory {
    const val PARTY = "Party"
    const val INVENTORY = "Inventory"
    const val ACTORS = "Actors"
    const val VARIABLES = "Variables"
    const val SWITCHES = "Switches"
    const val OTHER = "Other"

    /** Display order for section grouping; meaningful categories first, raw JSON last. */
    val order = listOf(PARTY, ACTORS, INVENTORY, VARIABLES, SWITCHES, OTHER)
}

data class RpgmEditInspection(
    val values: List<RpgmEditableValue>,
    val warning: String? = null,
)

object RpgmSaveEditor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
    private const val MAX_BACKUPS = 3
    internal const val MAX_INSPECT_VALUES = 5000

    suspend fun inspect(slot: RpgmSaveSlot): RpgmEditInspection = withContext(Dispatchers.IO) {
        runCatching {
            if (slot.codec == RubyMarshalSave.CODEC) {
                val ruby = RubyMarshalSave.inspect(File(slot.filePath).readBytes(), MAX_INSPECT_VALUES)
                val values = ruby.values.sortedWith(
                    compareBy<RpgmEditableValue> { RpgmCategory.order.indexOf(it.category).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        .thenBy { it.name?.lowercase() ?: "\uFFFF" }
                        .thenBy { it.path.lowercase() },
                )
                val warning = when {
                    values.isEmpty() -> "No editable scalar Ruby Marshal values found."
                    ruby.capped -> "Large save: showing the first $MAX_INSPECT_VALUES editable values. Use the filter to narrow the list before editing."
                    else -> null
                }
                return@withContext RpgmEditInspection(values, warning)
            }
            val decoded = decode(File(slot.filePath), slot.codec)
                ?: return@withContext RpgmEditInspection(emptyList(), "Could not decode ${slot.codec} save data.")
            val out = mutableListOf<RpgmEditableValue>()
            val capped = collectEditableValues(decoded.element, out = out, limit = MAX_INSPECT_VALUES)
            val named = annotate(File(slot.filePath), out)
            val values = named.sortedWith(
                compareBy<RpgmEditableValue> { RpgmCategory.order.indexOf(it.category).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.name?.lowercase() ?: "\uFFFF" }
                    .thenBy { it.path.lowercase() },
            )
            val warning = when {
                values.isEmpty() -> "No editable scalar JSON values found."
                capped -> "Large save: showing the first $MAX_INSPECT_VALUES editable values. Use the filter to narrow the list before editing."
                else -> null
            }
            RpgmEditInspection(values, warning)
        }.getOrElse { t ->
            AppLog.w("RpgmEditor", "Inspect failed for ${slot.fileName}: ${t.message}", t)
            RpgmEditInspection(emptyList(), "Could not safely inspect this RPGM save (${t.message ?: "unknown error"}).")
        }
    }

    suspend fun edit(
        slot: RpgmSaveSlot,
        path: String,
        newValue: String,
        sessionBackup: SaveEditSessionBackup? = null,
    ): Pair<RenPyEditResult, SaveEditSessionBackup?> = withContext(Dispatchers.IO) {
        runCatching {
            val saveFile = File(slot.filePath)
            if (slot.codec == RubyMarshalSave.CODEC) {
                val patch = RubyMarshalSave.patch(saveFile.readBytes(), path, newValue)
                val encoded = patch.bytes
                    ?: return@withContext RenPyEditResult(false, patch.error ?: "Could not edit Ruby Marshal save data.") to sessionBackup
                val backup = sessionBackup?.takeIf { File(it.filePath).isFile }
                    ?: createBackup(saveFile).toSessionBackup()
                val tmp = File(saveFile.parentFile, "${saveFile.name}.tmp-${System.currentTimeMillis()}")
                tmp.writeBytes(encoded)
                if (!RubyMarshalSave.canParse(tmp.readBytes())) {
                    tmp.delete()
                    return@withContext RenPyEditResult(false, "Temp write verification failed; backup kept at ${backup.fileName}.") to backup
                }
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
                val backupText = if (sessionBackup == null) "Backup: ${backup.fileName}." else "Session backup: ${backup.fileName}."
                return@withContext RenPyEditResult(true, "Saved. $backupText") to backup
            }
            val decoded = decode(saveFile, slot.codec)
                ?: return@withContext RenPyEditResult(false, "Could not decode ${slot.codec} save data.") to sessionBackup
            val pathParts = parsePath(path)
            val currentElement = getAtPath(decoded.element, pathParts)
            val current = (currentElement as? JsonPrimitive)?.let { primitive ->
                val type = primitiveType(primitive)
                if (type == "null") null else RpgmEditableValue(path, type, displayValue(primitive))
            }
                ?: return@withContext RenPyEditResult(false, "Value no longer exists.") to sessionBackup
            val replacement = parseReplacement(current.type, newValue)
                ?: return@withContext RenPyEditResult(false, "Invalid ${current.type} value.") to sessionBackup
            val patched = setAtPath(decoded.element, pathParts, replacement)
            val encoded = encode(patched, decoded.codec)
                ?: return@withContext RenPyEditResult(false, "Could not encode ${decoded.codec} save data.") to sessionBackup
            val verify = decodeBytes(encoded, decoded.codec)
                ?: return@withContext RenPyEditResult(false, "Re-encode verification failed.") to sessionBackup
            val verifyValue = getAtPath(verify, pathParts)
                ?: return@withContext RenPyEditResult(false, "Edited value could not be read back.") to sessionBackup
            if (displayValue(verifyValue) != displayValue(replacement)) {
                return@withContext RenPyEditResult(false, "Edited value did not verify after re-encode.") to sessionBackup
            }

            val backup = sessionBackup?.takeIf { File(it.filePath).isFile }
                ?: createBackup(saveFile).toSessionBackup()
            val tmp = File(saveFile.parentFile, "${saveFile.name}.tmp-${System.currentTimeMillis()}")
            tmp.writeBytes(encoded)
            val tmpVerify = decode(tmp, decoded.codec)?.element
                ?: run {
                    tmp.delete()
                    return@withContext RenPyEditResult(false, "Temp write verification failed; backup kept at ${backup.fileName}.") to backup
                }
            val tmpVerifyValue = getAtPath(tmpVerify, pathParts)
                ?: run {
                    tmp.delete()
                    return@withContext RenPyEditResult(false, "Edited value could not be read back from temp file; backup kept at ${backup.fileName}.") to backup
                }
            if (displayValue(tmpVerifyValue) != displayValue(replacement)) {
                tmp.delete()
                return@withContext RenPyEditResult(false, "Temp file verification did not match edited value; backup kept at ${backup.fileName}.") to backup
            }
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
            val backupText = if (sessionBackup == null) "Backup: ${backup.fileName}." else "Session backup: ${backup.fileName}."
            RenPyEditResult(true, "Saved. $backupText") to backup
        }.getOrElse { t ->
            AppLog.w("RpgmEditor", "Edit failed for ${slot.fileName}: ${t.message}", t)
            RenPyEditResult(false, "Could not edit this RPGM save (${t.message ?: "unknown error"}).") to sessionBackup
        }
    }

    suspend fun edit(slot: RpgmSaveSlot, path: String, newValue: String): RenPyEditResult =
        edit(slot, path, newValue, null).first

    suspend fun listBackups(slot: RpgmSaveSlot): List<RenPySaveBackup> = withContext(Dispatchers.IO) {
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

    suspend fun restoreBackup(slot: RpgmSaveSlot, backup: RenPySaveBackup): RenPyEditResult = withContext(Dispatchers.IO) {
        runCatching {
            val saveFile = File(slot.filePath)
            val backupFile = File(backup.filePath)
            if (!backupFile.isFile) return@withContext RenPyEditResult(false, "Backup file no longer exists.")
            val preRestore = createBackup(saveFile)
            val tmp = File(saveFile.parentFile, "${saveFile.name}.restore-${System.currentTimeMillis()}")
            backupFile.copyTo(tmp, overwrite = true)
            val restoredDecodes = if (slot.codec == RubyMarshalSave.CODEC) {
                RubyMarshalSave.canParse(tmp.readBytes())
            } else {
                decode(tmp, slot.codec) != null || decode(tmp, "json") != null
            }
            if (!restoredDecodes) {
                tmp.delete()
                return@withContext RenPyEditResult(false, "Backup could not be decoded; current save was not changed.")
            }
            val replaced = runCatching {
                tmp.copyTo(saveFile, overwrite = true)
                tmp.delete()
                true
            }.getOrDefault(false)
            if (!replaced) {
                tmp.delete()
                return@withContext RenPyEditResult(false, "Restore failed; current save backed up as ${preRestore.name}.")
            }
            pruneBackups(saveFile)
            RenPyEditResult(true, "Restored ${backup.fileName}. Previous current save backed up as ${preRestore.name}.")
        }.getOrElse { t ->
            AppLog.w("RpgmEditor", "Restore failed for ${slot.fileName}: ${t.message}", t)
            RenPyEditResult(false, "Restore failed: ${t.message ?: "unknown error"}")
        }
    }

    private data class Decoded(val codec: String, val element: JsonElement)

    private fun decode(file: File, codecHint: String): Decoded? {
        val bytes = file.readBytes()
        return decodeBytes(bytes, codecHint)?.let { Decoded(codecHint, it) }
            ?: sequenceOf("json", "lz-string-base64", "zlib-base64")
                .firstNotNullOfOrNull { codec -> decodeBytes(bytes, codec)?.let { Decoded(codec, it) } }
    }

    private fun decodeBytes(bytes: ByteArray, codec: String): JsonElement? {
        val text = when (codec) {
            "json" -> bytes.toString(Charsets.UTF_8)
            "lz-string-base64" -> decompressLzStringBase64(bytes.toString(Charsets.UTF_8).trim())
            "zlib-base64" -> inflateBase64(bytes.toString(Charsets.UTF_8).trim())
            else -> null
        } ?: return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun encode(element: JsonElement, codec: String): ByteArray? {
        val text = json.encodeToString(JsonElement.serializer(), element)
        return when (codec) {
            "json" -> text.toByteArray(Charsets.UTF_8)
            "lz-string-base64" -> compressLzStringBase64(text)?.toByteArray(Charsets.UTF_8)
            "zlib-base64" -> deflateBase64(text).toByteArray(Charsets.UTF_8)
            else -> null
        }
    }

    // RPG Maker MV/MZ store the NAMES of variables/switches/items/weapons/armors/actors/classes in the
    // game's data/*.json (1-indexed arrays), while the save stores only their VALUES (by numeric index or
    // id). Mapping the two lets the editor label each entry with its authored name and group it into a
    // meaningful category, so users edit "Gold" or "Hero · Level" instead of raw JSON paths.
    private val variableIdRe = Regex("""(?:^|\.)variables(?:\._data)?(?:\.@a)?[.\[](\d+)]?$""")
    private val switchIdRe = Regex("""(?:^|\.)switches(?:\._data)?(?:\.@a)?[.\[](\d+)]?$""")
    private val partyStackRe = Regex("""(?:^|\.)party\._(items|weapons|armors)[.\[](\d+)]?$""")
    private val actorRe = Regex("""(?:^|\.)actors(?:\._data)?(?:\.@a)?[.\[](\d+)]?\.(.+)$""")

    internal data class RpgmDataNames(
        val variables: List<String> = emptyList(),
        val switches: List<String> = emptyList(),
        val items: List<String> = emptyList(),
        val weapons: List<String> = emptyList(),
        val armors: List<String> = emptyList(),
        val actors: List<String> = emptyList(),
        val classes: List<String> = emptyList(),
    ) {
        val isEmpty get() = variables.isEmpty() && switches.isEmpty() && items.isEmpty() &&
            weapons.isEmpty() && armors.isEmpty() && actors.isEmpty()
    }

    internal data class RpgmDescriptor(val label: String?, val category: String)

    private fun annotate(saveFile: File, values: List<RpgmEditableValue>): List<RpgmEditableValue> {
        val names = loadDataNames(saveFile)
        return values.map { value ->
            val d = describePath(value.path, names)
            value.copy(name = d.label ?: value.name, category = d.category)
        }
    }

    /** Resolves the RPG Maker variable/switch name for a save path, or null if it isn't one. */
    internal fun resolveName(path: String, variables: List<String>, switches: List<String>): String? =
        describePath(path, RpgmDataNames(variables = variables, switches = switches))
            .takeIf { it.category == RpgmCategory.VARIABLES || it.category == RpgmCategory.SWITCHES }
            ?.label

    /** Maps a save-file JSON path to a human label + category. Pure so it can be unit-tested. */
    internal fun describePath(path: String, names: RpgmDataNames): RpgmDescriptor {
        variableIdRe.find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { id ->
            return RpgmDescriptor(names.variables.getOrNull(id)?.takeIf { it.isNotBlank() }, RpgmCategory.VARIABLES)
        }
        switchIdRe.find(path)?.groupValues?.get(1)?.toIntOrNull()?.let { id ->
            return RpgmDescriptor(names.switches.getOrNull(id)?.takeIf { it.isNotBlank() }, RpgmCategory.SWITCHES)
        }
        when {
            path.endsWith("party._gold") || path == "party._gold" -> return RpgmDescriptor("Gold", RpgmCategory.PARTY)
            path.endsWith("party._steps") -> return RpgmDescriptor("Steps", RpgmCategory.PARTY)
        }
        partyStackRe.find(path)?.let { m ->
            val kind = m.groupValues[1]
            val id = m.groupValues[2].toIntOrNull()
            val list = when (kind) { "items" -> names.items; "weapons" -> names.weapons; else -> names.armors }
            val prefix = when (kind) { "items" -> "Item"; "weapons" -> "Weapon"; else -> "Armor" }
            val named = id?.let { list.getOrNull(it)?.takeIf { n -> n.isNotBlank() } }
            return RpgmDescriptor(named?.let { "$prefix · $it (qty)" } ?: "$prefix #$id (qty)", RpgmCategory.INVENTORY)
        }
        actorRe.find(path)?.let { m ->
            val id = m.groupValues[1].toIntOrNull()
            val sub = m.groupValues[2]
            val actorName = id?.let { names.actors.getOrNull(it)?.takeIf { n -> n.isNotBlank() } } ?: "Actor #$id"
            val field = actorFieldLabel(sub) ?: return RpgmDescriptor("$actorName · ${sub.trimStart('_')}", RpgmCategory.ACTORS)
            return RpgmDescriptor("$actorName · $field", RpgmCategory.ACTORS)
        }
        return RpgmDescriptor(null, RpgmCategory.OTHER)
    }

    private fun actorFieldLabel(sub: String): String? = when {
        sub == "_name" -> "Name"
        sub == "_nickname" -> "Nickname"
        sub == "_level" -> "Level"
        sub == "_hp" -> "HP"
        sub == "_mp" -> "MP"
        sub == "_tp" -> "TP"
        sub == "_classId" || sub == "_class" -> "Class"
        sub.startsWith("_exp") -> "EXP"
        else -> null
    }

    private fun loadDataNames(saveFile: File): RpgmDataNames {
        val dataDir = findDataDir(saveFile) ?: return RpgmDataNames()
        fun names(fileName: String, key: String): List<String> {
            val file = File(dataDir, fileName).takeIf { runCatching { it.isFile }.getOrDefault(false) }
                ?: return emptyList()
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return emptyList()
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
            // System.json is an object with named arrays; database files are arrays of {id,name} objects.
            return when (root) {
                is JsonObject -> root[key]?.jsonArray.orEmpty().map { el ->
                    runCatching { el.jsonPrimitive.contentOrNull }.getOrNull().orEmpty()
                }
                is JsonArray -> root.map { el ->
                    runCatching { (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
                }
                else -> emptyList()
            }
        }
        return RpgmDataNames(
            variables = names("System.json", "variables"),
            switches = names("System.json", "switches"),
            items = names("Items.json", "name"),
            weapons = names("Weapons.json", "name"),
            armors = names("Armors.json", "name"),
            actors = names("Actors.json", "name"),
            classes = names("Classes.json", "name"),
        )
    }

    /** Locates the game's data/ directory relative to a save file, covering MV (www/) and MZ layouts. */
    private fun findDataDir(saveFile: File): File? {
        val saveDir = saveFile.parentFile ?: return null
        val gameRoot = saveDir.parentFile ?: return null
        val candidates = listOfNotNull(
            File(gameRoot, "data"),               // MZ: <root>/data, MV: www/data (gameRoot=www)
            File(gameRoot, "www/data"),           // save alongside www/
            gameRoot.parentFile?.let { File(it, "data") },
            gameRoot.parentFile?.let { File(it, "www/data") },
        )
        return candidates.firstOrNull { runCatching { it.isDirectory }.getOrDefault(false) }
    }

    private fun flatten(element: JsonElement, prefix: String = ""): List<RpgmEditableValue> =        when (element) {
            is JsonObject -> element.flatMap { (key, value) ->
                flatten(value, if (prefix.isBlank()) key.escapePathPart() else "$prefix.${key.escapePathPart()}")
            }
            is JsonArray -> element.flatMapIndexed { index, value -> flatten(value, "$prefix[$index]") }
            is JsonPrimitive -> {
                val type = when {
                    element.booleanOrNull != null -> "bool"
                    element.longOrNull != null -> "int"
                    element.doubleOrNull != null -> "float"
                    element.isString -> "string"
                    element.contentOrNull == null -> "null"
                    else -> "string"
                }
                if (type == "null") emptyList() else listOf(RpgmEditableValue(prefix, type, displayValue(element)))
            }
            else -> emptyList()
        }

    private fun collectEditableValues(
        element: JsonElement,
        prefix: String = "",
        out: MutableList<RpgmEditableValue>,
        limit: Int,
    ): Boolean {
        if (out.size >= limit) return true
        return when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    val capped = collectEditableValues(
                        value,
                        if (prefix.isBlank()) key.escapePathPart() else "$prefix.${key.escapePathPart()}",
                        out,
                        limit,
                    )
                    if (capped) return true
                }
                false
            }
            is JsonArray -> {
                for (index in element.indices) {
                    val capped = collectEditableValues(element[index], "$prefix[$index]", out, limit)
                    if (capped) return true
                }
                false
            }
            is JsonPrimitive -> {
                val type = primitiveType(element)
                if (type != "null") out += RpgmEditableValue(prefix, type, displayValue(element))
                out.size >= limit
            }
            else -> false
        }
    }

    private fun primitiveType(element: JsonPrimitive): String =
        when {
            element.booleanOrNull != null -> "bool"
            element.longOrNull != null -> "int"
            element.doubleOrNull != null -> "float"
            element.isString -> "string"
            element.contentOrNull == null -> "null"
            else -> "string"
        }

    private fun parseReplacement(type: String, value: String): JsonElement? =
        when (type) {
            "bool" -> when (value.trim().lowercase()) {
                "true", "1", "yes", "on" -> JsonPrimitive(true)
                "false", "0", "no", "off" -> JsonPrimitive(false)
                else -> null
            }
            "int" -> value.trim().toLongOrNull()?.let { JsonPrimitive(it) }
            "float" -> value.trim().toDoubleOrNull()?.let { JsonPrimitive(it) }
            "string" -> JsonPrimitive(value)
            else -> null
        }

    private sealed class PathPart {
        data class Key(val value: String) : PathPart()
        data class Index(val value: Int) : PathPart()
    }

    private fun parsePath(path: String): List<PathPart> {
        val parts = mutableListOf<PathPart>()
        val token = StringBuilder()
        var i = 0
        fun flushKey() {
            if (token.isNotEmpty()) {
                parts += PathPart.Key(token.toString().unescapePathPart())
                token.clear()
            }
        }
        while (i < path.length) {
            when (val ch = path[i]) {
                '.' -> flushKey()
                '[' -> {
                    flushKey()
                    val end = path.indexOf(']', startIndex = i)
                    parts += PathPart.Index(path.substring(i + 1, end).toInt())
                    i = end
                }
                '\\' -> {
                    i++
                    if (i < path.length) token.append(path[i])
                }
                else -> token.append(ch)
            }
            i++
        }
        flushKey()
        return parts
    }

    private fun setAtPath(element: JsonElement, path: List<PathPart>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        return when (val head = path.first()) {
            is PathPart.Key -> {
                val obj = element as JsonObject
                JsonObject(obj.mapValues { (k, v) -> if (k == head.value) setAtPath(v, path.drop(1), value) else v })
            }
            is PathPart.Index -> {
                val arr = element as JsonArray
                JsonArray(arr.mapIndexed { index, item -> if (index == head.value) setAtPath(item, path.drop(1), value) else item })
            }
        }
    }

    private fun getAtPath(element: JsonElement, path: List<PathPart>): JsonElement? {
        var cursor: JsonElement = element
        for (part in path) {
            cursor = when (part) {
                is PathPart.Key -> (cursor as? JsonObject)?.get(part.value) ?: return null
                is PathPart.Index -> (cursor as? JsonArray)?.getOrNull(part.value) ?: return null
            }
        }
        return cursor
    }

    private fun displayValue(element: JsonElement): String =
        (element as? JsonPrimitive)?.contentOrNull ?: element.toString()

    internal fun createBackup(
        saveFile: File,
        clock: () -> Long = System::currentTimeMillis,
    ): File {
        var timestamp = clock()
        while (true) {
            val backup = File(saveFile.parentFile, "${saveFile.name}.agm-bak-$timestamp")
            try {
                Files.copy(saveFile.toPath(), backup.toPath())
                return backup
            } catch (_: FileAlreadyExistsException) {
                timestamp = maxOf(timestamp + 1L, clock())
            }
        }
    }

    private fun File.toSessionBackup(): SaveEditSessionBackup =
        SaveEditSessionBackup(
            fileName = name,
            filePath = absolutePath,
            createdAt = backupTimestamp(this) ?: lastModified(),
        )

    private fun managedBackups(saveFile: File): List<File> =
        saveFile.parentFile?.listFiles { file -> file.isFile && file.name.startsWith("${saveFile.name}.agm-bak-") }.orEmpty()
            .sortedByDescending { backupTimestamp(it) ?: it.lastModified() }

    private fun pruneBackups(saveFile: File) {
        managedBackups(saveFile).drop(MAX_BACKUPS).forEach { runCatching { it.delete() } }
    }

    private fun backupTimestamp(file: File): Long? =
        file.name.substringAfterLast(".agm-bak-", "").toLongOrNull()

    private fun inflateBase64(input: String): String? =
        runCatching {
            val bytes = Base64.getDecoder().decode(input)
            InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()

    private fun deflateBase64(input: String): String {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(input.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun decompressLzStringBase64(input: String): String? {
        if (input.isBlank()) return null
        val keyStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        fun getValue(index: Int): Int = keyStr.indexOf(input[index]).takeIf { it >= 0 } ?: 0
        return lzDecompress(input.length, 32) { index -> getValue(index) }
    }

    private fun compressLzStringBase64(input: String): String? {
        val keyStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val res = lzCompress(input, 6) { a -> keyStr[a] } ?: return null
        return res + "=".repeat((4 - res.length % 4) % 4)
    }

    private fun lzDecompress(length: Int, resetValue: Int, getNextValue: (Int) -> Int): String? {
        if (length == 0) return ""
        val dictionary = mutableMapOf<Int, String>()
        var enlargeIn = 4
        var dictSize = 4
        var numBits = 3
        var dataVal = getNextValue(0)
        var dataPosition = resetValue
        var dataIndex = 1
        fun readBits(bits: Int): Int {
            var power = 1
            val maxpower = 1 shl bits
            var result = 0
            while (power != maxpower) {
                val resb = dataVal and dataPosition
                dataPosition = dataPosition shr 1
                if (dataPosition == 0) {
                    dataPosition = resetValue
                    dataVal = if (dataIndex < length) getNextValue(dataIndex++) else 0
                }
                if (resb > 0) result = result or power
                power = power shl 1
            }
            return result
        }
        val next = readBits(2)
        val first = when (next) {
            0 -> readBits(8).toChar().toString()
            1 -> readBits(16).toChar().toString()
            2 -> return ""
            else -> return null
        }
        dictionary[0] = ""; dictionary[1] = ""; dictionary[2] = ""; dictionary[3] = first
        var w = first
        val result = StringBuilder(first)
        while (true) {
            val c = readBits(numBits)
            val entry = when (c) {
                0 -> readBits(8).toChar().toString().also { dictionary[dictSize++] = it; enlargeIn-- }
                1 -> readBits(16).toChar().toString().also { dictionary[dictSize++] = it; enlargeIn-- }
                2 -> return result.toString()
                else -> dictionary[c] ?: if (c == dictSize) w + w[0] else return null
            }
            if (enlargeIn == 0) { enlargeIn = 1 shl numBits; numBits++ }
            result.append(entry)
            dictionary[dictSize++] = w + entry[0]
            enlargeIn--
            w = entry
            if (enlargeIn == 0) { enlargeIn = 1 shl numBits; numBits++ }
        }
    }

    private fun lzCompress(uncompressed: String, bitsPerChar: Int, getCharFromInt: (Int) -> Char): String? {
        if (uncompressed.isEmpty()) return ""
        val contextDictionary = mutableMapOf<String, Int>()
        val contextDictionaryToCreate = mutableSetOf<String>()
        var contextW = ""
        var contextEnlargeIn = 2
        var contextDictSize = 3
        var contextNumBits = 2
        val contextData = StringBuilder()
        var contextDataVal = 0
        var contextDataPosition = 0
        fun writeBit(value: Int) {
            contextDataVal = (contextDataVal shl 1) or value
            if (contextDataPosition == bitsPerChar - 1) {
                contextDataPosition = 0
                contextData.append(getCharFromInt(contextDataVal))
                contextDataVal = 0
            } else contextDataPosition++
        }
        fun writeBits(numBits: Int, valueIn: Int) {
            var value = valueIn
            repeat(numBits) {
                writeBit(value and 1)
                value = value shr 1
            }
        }
        for (ch in uncompressed) {
            val contextC = ch.toString()
            if (contextC !in contextDictionary) {
                contextDictionary[contextC] = contextDictSize++
                contextDictionaryToCreate += contextC
            }
            val wc = contextW + contextC
            if (wc in contextDictionary) {
                contextW = wc
            } else {
                if (contextW in contextDictionaryToCreate) {
                    val charCode = contextW[0].code
                    if (charCode < 256) {
                        writeBits(contextNumBits, 0)
                        writeBits(8, charCode)
                    } else {
                        writeBits(contextNumBits, 1)
                        writeBits(16, charCode)
                    }
                    contextEnlargeIn--
                    if (contextEnlargeIn == 0) {
                        contextEnlargeIn = 1 shl contextNumBits
                        contextNumBits++
                    }
                    contextDictionaryToCreate -= contextW
                } else {
                    writeBits(contextNumBits, contextDictionary[contextW] ?: return null)
                }
                contextEnlargeIn--
                if (contextEnlargeIn == 0) {
                    contextEnlargeIn = 1 shl contextNumBits
                    contextNumBits++
                }
                contextDictionary[wc] = contextDictSize++
                contextW = contextC
            }
        }
        if (contextW.isNotEmpty()) {
            if (contextW in contextDictionaryToCreate) {
                val charCode = contextW[0].code
                if (charCode < 256) {
                    writeBits(contextNumBits, 0)
                    writeBits(8, charCode)
                } else {
                    writeBits(contextNumBits, 1)
                    writeBits(16, charCode)
                }
                contextEnlargeIn--
                if (contextEnlargeIn == 0) {
                    contextEnlargeIn = 1 shl contextNumBits
                    contextNumBits++
                }
                contextDictionaryToCreate -= contextW
            } else {
                writeBits(contextNumBits, contextDictionary[contextW] ?: return null)
            }
            contextEnlargeIn--
            if (contextEnlargeIn == 0) {
                contextEnlargeIn = 1 shl contextNumBits
                contextNumBits++
            }
        }
        writeBits(contextNumBits, 2)
        while (true) {
            contextDataVal = contextDataVal shl 1
            if (contextDataPosition == bitsPerChar - 1) {
                contextData.append(getCharFromInt(contextDataVal))
                break
            } else contextDataPosition++
        }
        return contextData.toString()
    }

    private fun String.escapePathPart(): String = replace("\\", "\\\\").replace(".", "\\.").replace("[", "\\[")
    private fun String.unescapePathPart(): String {
        val out = StringBuilder()
        var escape = false
        for (ch in this) {
            if (escape) { out.append(ch); escape = false }
            else if (ch == '\\') escape = true
            else out.append(ch)
        }
        return out.toString()
    }
}
