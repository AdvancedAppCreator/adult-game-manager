package com.example.f95updater

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.InflaterInputStream

private val Context.rpgmSaveStore by preferencesDataStore("rpgm_save_locations")
private val RPGM_SAVE_SNAPSHOT_KEY = stringPreferencesKey("rpgm_save_snapshot_json")
private val RPGM_SAVE_MANUAL_ASSOCIATIONS_KEY = stringPreferencesKey("rpgm_save_manual_associations_json")

@Serializable
data class RpgmSaveLocation(
    val saveDirPath: String,
    val ownerId: String,
    val saveCount: Int,
    val latestModified: Long,
    val associatedPackageName: String?,
    val associatedLabel: String?,
    val confidence: Int,
    val reason: String?,
)

@Serializable
data class RpgmSaveManualAssociation(
    val saveDirPath: String,
    val packageName: String,
    val label: String,
)

data class RpgmSaveSlot(
    val fileName: String,
    val filePath: String,
    val codec: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val summary: String,
)

data class RpgmSaveScanResult(
    val locations: List<RpgmSaveLocation>,
    val associationsByPackage: Map<String, List<RpgmSaveLocation>>,
    val lastScannedAt: Long,
)

object RpgmSaveScanner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val locationListSer = ListSerializer(RpgmSaveLocation.serializer())
    private val manualListSer = ListSerializer(RpgmSaveManualAssociation.serializer())
    private val androidPackageRe = Regex("""/(?:Android)/(?:data|media)/([^/]+)/""")

    @Serializable
    data class Snapshot(
        val locations: List<RpgmSaveLocation> = emptyList(),
        val lastScannedAt: Long = 0L,
    )

    suspend fun loadSnapshot(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val text = context.rpgmSaveStore.data.first()[RPGM_SAVE_SNAPSHOT_KEY]
            ?: return@withContext Snapshot()
        runCatching { json.decodeFromString(Snapshot.serializer(), text) }.getOrDefault(Snapshot())
    }

    suspend fun saveSnapshot(context: Context, locations: List<RpgmSaveLocation>, lastScannedAt: Long) =
        withContext(Dispatchers.IO) {
            context.rpgmSaveStore.edit {
                it[RPGM_SAVE_SNAPSHOT_KEY] = json.encodeToString(
                    Snapshot.serializer(),
                    Snapshot(locations = locations, lastScannedAt = lastScannedAt),
                )
            }
        }

    suspend fun loadManualAssociations(context: Context): Map<String, RpgmSaveManualAssociation> =
        withContext(Dispatchers.IO) {
            val text = context.rpgmSaveStore.data.first()[RPGM_SAVE_MANUAL_ASSOCIATIONS_KEY]
                ?: return@withContext emptyMap()
            runCatching { json.decodeFromString(manualListSer, text) }
                .getOrDefault(emptyList())
                .associateBy { it.saveDirPath.normalizePathKey() }
        }

    suspend fun saveManualAssociations(context: Context, associations: Map<String, RpgmSaveManualAssociation>) =
        withContext(Dispatchers.IO) {
            context.rpgmSaveStore.edit {
                it[RPGM_SAVE_MANUAL_ASSOCIATIONS_KEY] = json.encodeToString(
                    manualListSer,
                    associations.values.sortedBy { assoc -> assoc.saveDirPath.lowercase() },
                )
            }
        }

    fun associationsByPackage(locations: List<RpgmSaveLocation>): Map<String, List<RpgmSaveLocation>> =
        locations.filter { it.associatedPackageName != null }
            .groupBy { it.associatedPackageName!! }
            .mapValues { (_, values) -> values.sortedWith(compareByDescending<RpgmSaveLocation> { it.confidence }.thenBy { it.saveDirPath }) }

    fun applyManualAssociations(
        locations: List<RpgmSaveLocation>,
        manual: Map<String, RpgmSaveManualAssociation>,
        apps: List<InstalledApp>,
    ): List<RpgmSaveLocation> {
        val appLabels = apps.associate { it.packageName to it.label }
        return locations.map { location ->
            val assoc = manual[location.saveDirPath.normalizePathKey()] ?: return@map location
            location.copy(
                associatedPackageName = assoc.packageName,
                associatedLabel = appLabels[assoc.packageName] ?: assoc.label,
                confidence = 100,
                reason = "Manually associated",
            )
        }
    }

    suspend fun scan(apps: List<InstalledApp>): RpgmSaveScanResult = withContext(Dispatchers.IO) {
        val appByPackage = apps.associateBy { it.packageName }
        val candidates = linkedMapOf<String, File>()
        val external = Environment.getExternalStorageDirectory()
        addAndroidCandidates(candidates, external)
        addJoiPlayCandidates(candidates, apps)
        addKnownPublicCandidates(candidates, external)
        addBroadCandidates(candidates, listOf(external))

        val locations = candidates.values.mapNotNull { dir ->
            summarizeDir(dir)?.let { summary ->
                val match = associate(dir, summary.ownerId, apps, appByPackage)
                RpgmSaveLocation(
                    saveDirPath = dir.absolutePath,
                    ownerId = summary.ownerId,
                    saveCount = summary.saveCount,
                    latestModified = summary.latestModified,
                    associatedPackageName = match?.packageName,
                    associatedLabel = match?.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.label },
                    confidence = match?.confidence ?: 0,
                    reason = match?.reason,
                )
            }
        }.sortedWith(compareByDescending<RpgmSaveLocation> { it.associatedPackageName != null }.thenBy { it.saveDirPath.lowercase() })

        AppLog.i("RpgmSaves", "Scan complete candidates=${candidates.size} locations=${locations.size}")
        RpgmSaveScanResult(
            locations = locations,
            associationsByPackage = associationsByPackage(locations),
            lastScannedAt = System.currentTimeMillis(),
        )
    }

    suspend fun verifiedLocationForFolder(folderPath: String, app: InstalledApp): RpgmSaveLocation? =
        withContext(Dispatchers.IO) {
            val dir = File(folderPath)
            val summary = summarizeDir(dir) ?: return@withContext null
            RpgmSaveLocation(
                saveDirPath = dir.absolutePath,
                ownerId = summary.ownerId,
                saveCount = summary.saveCount,
                latestModified = summary.latestModified,
                associatedPackageName = app.packageName,
                associatedLabel = app.label,
                confidence = 100,
                reason = "Manually added save folder",
            )
        }

    suspend fun listSaveSlots(location: RpgmSaveLocation): List<RpgmSaveSlot> = withContext(Dispatchers.IO) {
        File(location.saveDirPath).safeListFiles()
            .filter { it.isFile && isSaveExtension(it) && !isNonSlotSave(it) }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                decodeSave(file)?.let { decoded ->
                    RpgmSaveSlot(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        codec = decoded.codec,
                        modifiedAt = file.lastModified(),
                        sizeBytes = file.length(),
                        summary = decoded.summary,
                    )
                }
            }
    }

    private data class DirSummary(val ownerId: String, val saveCount: Int, val latestModified: Long)
    private data class DecodedSave(val codec: String, val summary: String)
    private data class AssociationMatch(val packageName: String, val confidence: Int, val reason: String)

    private fun summarizeDir(dir: File): DirSummary? {
        val saves = dir.safeListFiles()
            .filter { it.isFile && isSaveExtension(it) && !isNonSlotSave(it) }
            .mapNotNull { file -> decodeSave(file)?.let { file } }
        if (saves.isEmpty()) return null
        return DirSummary(
            ownerId = ownerIdFromDir(dir),
            saveCount = saves.size,
            latestModified = saves.maxOf { it.lastModified() },
        )
    }

    private fun decodeSave(file: File): DecodedSave? {
        val text = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull() ?: return null
        val attempts = sequenceOf(
            "json" to text,
            "lz-string-base64" to decompressLzStringBase64(text),
            "zlib-base64" to inflateBase64(text),
        )
        for ((codec, candidate) in attempts) {
            val decoded = candidate?.trim() ?: continue
            val obj = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull() ?: continue
            return DecodedSave(codec, summarizeJson(obj))
        }
        return null
    }

    private fun summarizeJson(obj: JsonObject): String =
        buildList {
            (obj["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { add(it) }
            (obj["mapId"] as? JsonPrimitive)?.content?.let { add("map $it") }
            (obj["gold"] as? JsonPrimitive)?.content?.let { add("gold $it") }
            if (isEmpty()) add(obj.keys.take(5).joinToString(", "))
        }.joinToString(" • ")

    private fun addAndroidCandidates(out: MutableMap<String, File>, external: File) {
        listOf(File(external, "Android/data"), File(external, "Android/media")).forEach { root ->
            root.safeListFiles().filter { it.isDirectory }.forEach { pkg ->
                listOf(
                    File(pkg, "files/save"),
                    File(pkg, "files/saves"),
                    File(pkg, "files/www/save"),
                    File(pkg, "files/game/save"),
                ).forEach { addIfReadable(out, it) }
            }
        }
    }

    private fun addJoiPlayCandidates(out: MutableMap<String, File>, apps: List<InstalledApp>) {
        apps.asSequence()
            .filter { it.source == AppSource.JoiPlay }
            .mapNotNull { it.storagePath?.takeIf { path -> path.isNotBlank() } }
            .map { File(it.replace('\\', '/').trimEnd('/')) }
            .forEach { gameDir ->
                val root = if (gameDir.name.lowercase() == "www") gameDir.parentFile ?: gameDir else gameDir
                listOf(
                    File(root, "www/save"),
                    File(root, "save"),
                    File(root, "saves"),
                    File(root, "game/save"),
                ).forEach { addIfReadable(out, it) }
            }
    }

    private fun addKnownPublicCandidates(out: MutableMap<String, File>, external: File) {
        listOf("RPGMaker", "RPG Maker", "Documents/RPGMaker", "Documents/RPG Maker").forEach { rel ->
            val root = File(external, rel)
            addIfReadable(out, root)
            root.safeListFiles().filter { it.isDirectory }.forEach { addIfReadable(out, it) }
        }
    }

    private fun addBroadCandidates(out: MutableMap<String, File>, roots: List<File>) {
        var visited = 0
        val stack = ArrayDeque<Pair<File, Int>>()
        roots.filter { it.isDirectory && it.canRead() }.forEach { stack += it to 0 }
        while (stack.isNotEmpty() && visited < 15000) {
            val (dir, depth) = stack.removeLast()
            if (!dir.isDirectory || !dir.canRead()) continue
            visited++
            val children = dir.safeListFiles()
            if (children.any { it.isFile && isSaveExtension(it) }) addIfReadable(out, dir)
            if (depth >= 8 || shouldSkipDescend(dir)) continue
            children.filter { it.isDirectory && it.canRead() }.forEach { stack += it to depth + 1 }
        }
    }

    private fun associate(dir: File, ownerId: String, apps: List<InstalledApp>, appByPackage: Map<String, InstalledApp>): AssociationMatch? {
        val path = dir.absolutePath.replace('\\', '/')
        androidPackageRe.find(path)?.groupValues?.getOrNull(1)?.let { pkg ->
            if (appByPackage.containsKey(pkg)) return AssociationMatch(pkg, 100, "Android save path belongs to package $pkg")
        }
        apps.filter { it.source == AppSource.JoiPlay }.firstOrNull { app ->
            val storage = app.storagePath?.replace('\\', '/')?.trimEnd('/') ?: return@firstOrNull false
            val root = if (storage.substringAfterLast('/').lowercase() == "www") storage.substringBeforeLast('/') else storage
            path == root || path.startsWith("$root/")
        }?.let { app -> return AssociationMatch(app.packageName, 100, "Save folder is inside this JoiPlay game folder") }

        val ownerNorm = normalize(ownerId)
        val best = apps.mapNotNull { app ->
            val score = catalogMatchLabels(app).maxOfOrNull { label ->
                val labelNorm = normalize(label)
                when {
                    labelNorm.isBlank() -> 0
                    labelNorm == ownerNorm -> 85
                    ownerNorm.contains(labelNorm) || labelNorm.contains(ownerNorm) -> 70
                    else -> 0
                }
            } ?: 0
            if (score > 0) app to score else null
        }.maxByOrNull { it.second }
        return best?.let { (app, score) -> AssociationMatch(app.packageName, score, "RPGM save-folder owner '$ownerId' matched game title/folder") }
    }

    private fun decompressLzStringBase64(input: String): String? {
        if (input.isBlank()) return null
        val keyStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        fun getValue(index: Int): Int = keyStr.indexOf(input[index]).takeIf { it >= 0 } ?: 0
        return lzDecompress(input.length, 32) { index -> getValue(index) }
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
            var maxpower = 1 shl bits
            var resb: Int
            var result = 0
            while (power != maxpower) {
                resb = dataVal and dataPosition
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
        dictionary[0] = ""
        dictionary[1] = ""
        dictionary[2] = ""
        dictionary[3] = first
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
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
            result.append(entry)
            dictionary[dictSize++] = w + entry[0]
            enlargeIn--
            w = entry
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
        }
    }

    private fun inflateBase64(input: String): String? =
        runCatching {
            val bytes = Base64.getDecoder().decode(input)
            InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()

    private fun addIfReadable(out: MutableMap<String, File>, dir: File) {
        if (dir.isDirectory && dir.canRead()) out[dir.absolutePath.replace('\\', '/')] = dir
    }

    private fun isSaveExtension(file: File): Boolean =
        file.extension.equals("rpgsave", true) || file.extension.equals("rmmzsave", true)

    private fun isNonSlotSave(file: File): Boolean {
        val name = file.nameWithoutExtension.lowercase()
        return name == "global" || name == "config" || name == "settings"
    }

    private fun ownerIdFromDir(dir: File): String =
        if (dir.name.equals("save", true) || dir.name.equals("saves", true)) dir.parentFile?.name ?: dir.name else dir.name

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ").replace(Regex("""[^a-z0-9]+"""), "")

    private fun shouldSkipDescend(dir: File): Boolean {
        val name = dir.name.lowercase()
        val path = dir.absolutePath.replace('\\', '/').lowercase()
        return name in setOf("cache", "code_cache", "tmp", "temp", ".thumbnails", ".trash", "lost.dir") ||
            path.endsWith("/android/obb")
    }

    private fun File.safeListFiles(): List<File> =
        runCatching { listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())

    private fun String.normalizePathKey(): String = replace('\\', '/')
}
