package com.example.f95updater

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

private val Context.renPySaveStore by preferencesDataStore("renpy_save_locations")
private val RENPY_SAVE_SNAPSHOT_KEY = stringPreferencesKey("renpy_save_snapshot_json")
private val RENPY_SAVE_MANUAL_ASSOCIATIONS_KEY = stringPreferencesKey("renpy_save_manual_associations_json")

@Serializable
data class RenPySaveAssociation(
    val appPackageName: String,
    val saveDirPath: String,
    val ownerId: String,
    val saveCount: Int,
    val sampleSaveNames: List<String>,
    val renpyVersion: String?,
    val latestModified: Long,
    val confidence: Int,
    val reason: String,
)

@Serializable
data class RenPySaveLocation(
    val saveDirPath: String,
    val ownerId: String,
    val saveCount: Int,
    val sampleSaveNames: List<String>,
    val renpyVersion: String?,
    val latestModified: Long,
    val associatedPackageName: String?,
    val associatedLabel: String?,
    val confidence: Int,
    val reason: String?,
)

@Serializable
data class RenPySaveManualAssociation(
    val saveDirPath: String,
    val packageName: String,
    val label: String,
)

data class RenPySaveScanResult(
    val locations: List<RenPySaveLocation>,
    val associationsByPackage: Map<String, List<RenPySaveAssociation>>,
    val lastScannedAt: Long,
)

data class RenPySaveFileMetadata(val saveName: String?, val renpyVersion: String?)

data class RenPySaveSlot(
    val fileName: String,
    val filePath: String,
    val saveName: String?,
    val renpyVersion: String?,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val hasScreenshot: Boolean,
    val entries: List<String>,
    val error: String? = null,
)

object RenPySaveScanner {
    private val json = Json { ignoreUnknownKeys = true }
    private val locationListSer = ListSerializer(RenPySaveLocation.serializer())
    private val manualAssociationListSer = ListSerializer(RenPySaveManualAssociation.serializer())
    private val androidPackageRe = Regex("""/(?:Android)/(?:data|media)/([^/]+)/""")
    private val trailingIdRe = Regex("""[-_ ]+\d{4,}$""")

    @Serializable
    data class Snapshot(
        val locations: List<RenPySaveLocation> = emptyList(),
        val lastScannedAt: Long = 0L,
    )

    suspend fun loadSnapshot(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val text = context.renPySaveStore.data.first()[RENPY_SAVE_SNAPSHOT_KEY]
            ?: return@withContext Snapshot()
        runCatching {
            json.decodeFromString(Snapshot.serializer(), text)
        }.getOrElse {
            runCatching {
                Snapshot(locations = json.decodeFromString(locationListSer, text), lastScannedAt = 0L)
            }.getOrDefault(Snapshot())
        }
    }

    suspend fun saveSnapshot(context: Context, locations: List<RenPySaveLocation>, lastScannedAt: Long) =
        withContext(Dispatchers.IO) {
            val snapshot = Snapshot(locations = locations, lastScannedAt = lastScannedAt)
            context.renPySaveStore.edit {
                it[RENPY_SAVE_SNAPSHOT_KEY] = json.encodeToString(Snapshot.serializer(), snapshot)
            }
        }

    suspend fun loadManualAssociations(context: Context): Map<String, RenPySaveManualAssociation> =
        withContext(Dispatchers.IO) {
            val text = context.renPySaveStore.data.first()[RENPY_SAVE_MANUAL_ASSOCIATIONS_KEY]
                ?: return@withContext emptyMap()
            runCatching { json.decodeFromString(manualAssociationListSer, text) }
                .getOrDefault(emptyList())
                .associateBy { it.saveDirPath.normalizePathKey() }
        }

    suspend fun saveManualAssociations(context: Context, associations: Map<String, RenPySaveManualAssociation>) =
        withContext(Dispatchers.IO) {
            val sorted = associations.values.sortedBy { it.saveDirPath.lowercase() }
            context.renPySaveStore.edit {
                it[RENPY_SAVE_MANUAL_ASSOCIATIONS_KEY] = json.encodeToString(manualAssociationListSer, sorted)
            }
        }

    fun applyManualAssociations(
        locations: List<RenPySaveLocation>,
        manualAssociations: Map<String, RenPySaveManualAssociation>,
        apps: List<InstalledApp>,
    ): List<RenPySaveLocation> {
        val appLabels = apps.associate { it.packageName to it.label }
        return locations.map { location ->
            val manual = manualAssociations[location.saveDirPath.normalizePathKey()] ?: return@map location
            location.copy(
                associatedPackageName = manual.packageName,
                associatedLabel = appLabels[manual.packageName] ?: manual.label,
                confidence = 100,
                reason = "Manually associated",
            )
        }
    }

    fun associationsByPackage(locations: List<RenPySaveLocation>): Map<String, List<RenPySaveAssociation>> =
        locations.mapNotNull { it.toAssociationOrNull() }
            .groupBy { it.appPackageName }
            .mapValues { (_, values) ->
                values.sortedWith(compareByDescending<RenPySaveAssociation> { it.confidence }.thenBy { it.saveDirPath })
            }

    suspend fun scan(apps: List<InstalledApp>): RenPySaveScanResult =
        withContext(Dispatchers.IO) {
            val appByPackage = apps.associateBy { it.packageName }
            val candidates = linkedMapOf<String, File>()
            val external = Environment.getExternalStorageDirectory()
            addAndroidPackageCandidates(candidates, external)
            addRenPyRootCandidates(candidates, File(external, "RenPy"))
            addRenPyRootCandidates(candidates, File(external, "renpy"))
            addRenPyRootCandidates(candidates, File(external, "Documents/RenPy"))
            addRenPyRootCandidates(candidates, File(external, "Documents/renpy"))
            addJoiPlayCandidates(candidates, apps)
            addBroadAccessibleCandidates(candidates, accessibleStorageRoots(external))

            val locations = mutableListOf<RenPySaveLocation>()
            var saveFiles = 0
            for (dir in candidates.values) {
                if (!currentCoroutineContext().isActive) break
                val summary = summarizeSaveDir(dir) ?: continue
                saveFiles += summary.saveCount
                val match = associate(summary, apps, appByPackage)
                locations += summary.toLocation(match, apps)
            }
            val lastScannedAt = System.currentTimeMillis()
            val sortedLocations = locations.sortedWith(
                compareByDescending<RenPySaveLocation> { it.associatedPackageName != null }
                    .thenByDescending { it.confidence }
                    .thenBy { it.saveDirPath.lowercase() }
            )
            val associations = sortedLocations.mapNotNull { it.toAssociationOrNull() }
            AppLog.i(
                "RenPySaves",
                "Scan complete candidates=${candidates.size} saveFiles=$saveFiles locations=${locations.size} associated=${associations.size}"
            )
            RenPySaveScanResult(
                locations = sortedLocations,
                associationsByPackage = associationsByPackage(sortedLocations),
                lastScannedAt = lastScannedAt,
            )
        }

    suspend fun listSaveSlots(location: RenPySaveLocation): List<RenPySaveSlot> =
        withContext(Dispatchers.IO) {
            val dir = File(location.saveDirPath)
            dir.safeListFiles()
                .filter { it.isFile && it.extension.equals("save", ignoreCase = true) }
                .sortedByDescending { it.lastModified() }
                .mapNotNull { file -> readSaveSlot(file) }
        }

    suspend fun verifiedLocationForFolder(folderPath: String, app: InstalledApp): RenPySaveLocation? =
        withContext(Dispatchers.IO) {
            val dir = File(folderPath)
            val summary = summarizeSaveDir(dir) ?: return@withContext null
            RenPySaveLocation(
                saveDirPath = dir.absolutePath,
                ownerId = summary.ownerId,
                saveCount = summary.saveCount,
                sampleSaveNames = summary.sampleSaveNames,
                renpyVersion = summary.renpyVersion,
                latestModified = summary.latestModified,
                associatedPackageName = app.packageName,
                associatedLabel = app.label,
                confidence = 100,
                reason = "Manually added save folder",
            )
        }

    private fun addAndroidPackageCandidates(out: MutableMap<String, File>, external: File) {
        listOf(File(external, "Android/data"), File(external, "Android/media")).forEach { androidRoot ->
            androidRoot.safeListFiles()
                .filter { it.isDirectory }
                .forEach { pkg ->
                    listOf(
                        File(pkg, "files/saves"),
                        File(pkg, "files/game/saves"),
                        File(pkg, "files/.renpy"),
                        File(pkg, "files/RenPy"),
                    ).forEach { addIfReadable(out, it) }
                }
        }
    }

    private fun addRenPyRootCandidates(out: MutableMap<String, File>, root: File) {
        addIfReadable(out, root)
        root.safeListFiles()
            .filter { it.isDirectory }
            .forEach { child -> addIfReadable(out, child) }
    }

    private fun addJoiPlayCandidates(out: MutableMap<String, File>, apps: List<InstalledApp>) {
        apps.asSequence()
            .filter { it.source == AppSource.JoiPlay }
            .mapNotNull { it.storagePath?.takeIf { path -> path.isNotBlank() } }
            .map { File(it.replace('\\', '/').trimEnd('/')) }
            .forEach { gameDir ->
                val scanRoot = if (gameDir.name.lowercase() in setOf("www", "game", "app", "src", "resources")) {
                    gameDir.parentFile ?: gameDir
                } else {
                    gameDir
                }
                listOf(
                    File(scanRoot, "game/saves"),
                    File(scanRoot, "saves"),
                    File(scanRoot, "renpy/saves"),
                    File(scanRoot, ".renpy"),
                ).forEach { addIfReadable(out, it) }
            }
    }

    private fun accessibleStorageRoots(primaryExternal: File): List<File> {
        val roots = linkedMapOf<String, File>()
        fun add(root: File) {
            if (root.isDirectory && root.canRead()) {
                roots[root.absolutePath.replace('\\', '/')] = root
            }
        }
        add(primaryExternal)
        File("/storage").safeListFiles()
            .filter { it.isDirectory && it.canRead() }
            .forEach { storage ->
                if (storage.name == "self") return@forEach
                if (storage.absolutePath.replace('\\', '/') == primaryExternal.absolutePath.replace('\\', '/')) return@forEach
                add(storage)
            }
        return roots.values.toList()
    }

    private suspend fun addBroadAccessibleCandidates(out: MutableMap<String, File>, roots: List<File>) {
        val seen = mutableSetOf<String>()
        var visited = 0
        var limited = false
        roots.forEach { root ->
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(root to 0)
            while (stack.isNotEmpty()) {
                if (!currentCoroutineContext().isActive) return
                if (visited >= BROAD_SCAN_MAX_DIRS) {
                    limited = true
                    break
                }
                val (dir, depth) = stack.removeLast()
                val key = dir.safeCanonicalPath() ?: dir.absolutePath.replace('\\', '/')
                if (!seen.add(key)) continue
                if (!dir.isDirectory || !dir.canRead()) continue
                visited++
                val children = dir.safeListFiles()
                if (children.any { it.isFile && it.extension.equals("save", ignoreCase = true) }) {
                    addIfReadable(out, dir)
                }
                if (depth >= BROAD_SCAN_MAX_DEPTH || shouldSkipDescend(dir)) continue
                children.asSequence()
                    .filter { it.isDirectory && it.canRead() }
                    .forEach { child -> stack.add(child to depth + 1) }
            }
        }
        if (limited) AppLog.w("RenPySaves", "Broad scan stopped after $BROAD_SCAN_MAX_DIRS directories")
    }

    private fun addIfReadable(out: MutableMap<String, File>, dir: File) {
        if (dir.isDirectory && dir.canRead()) {
            out[dir.absolutePath.replace('\\', '/')] = dir
        }
    }

    private data class SaveDirSummary(
        val dir: File,
        val ownerId: String,
        val saveCount: Int,
        val sampleSaveNames: List<String>,
        val renpyVersion: String?,
        val latestModified: Long,
    )

    private fun summarizeSaveDir(dir: File): SaveDirSummary? {
        val saves = dir.safeListFiles()
            .filter { it.isFile && it.extension.equals("save", ignoreCase = true) }
            .mapNotNull { file -> readSaveMetadata(file)?.let { file to it } }
            .sortedByDescending { it.first.lastModified() }
        if (saves.isEmpty()) return null
        val version = saves.firstNotNullOfOrNull { (_, meta) -> meta.renpyVersion }
        return SaveDirSummary(
            dir = dir,
            ownerId = ownerIdFromDir(dir),
            saveCount = saves.size,
            sampleSaveNames = saves.take(3).mapNotNull { (_, meta) -> meta.saveName }.filter { it.isNotBlank() },
            renpyVersion = version,
            latestModified = saves.maxOf { it.first.lastModified() },
        )
    }

    internal fun readSaveMetadataForTest(file: File): RenPySaveFileMetadata? = readSaveMetadata(file)

    private fun readSaveMetadata(file: File): RenPySaveFileMetadata? {
        return runCatching {
            ZipFile(file).use { zip ->
                val log = zip.getEntry("log") ?: return@use null
                val jsonEntry = zip.getEntry("json")
                val extraInfo = zip.getEntry("extra_info")?.let { entry ->
                    zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8).trim() }
                }

                val renpyVersion = zip.getEntry("renpy_version")?.let { entry ->
                    zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8).trim() }
                }
                val jsonName = jsonEntry?.let { entry ->
                    runCatching {
                        zip.getInputStream(entry).use { stream ->
                            val text = stream.readBytes().toString(Charsets.UTF_8)
                            val obj = json.parseToJsonElement(text) as? JsonObject
                            obj?.get("_save_name")?.jsonPrimitive?.content
                                ?: obj?.get("_savename")?.jsonPrimitive?.content
                        }
                    }.getOrNull()
                }
                if (log.size == 0L) null else RenPySaveFileMetadata(jsonName ?: extraInfo, renpyVersion)
            }
        }.getOrElse {
            val header = runCatching {
                file.inputStream().use { input ->
                    ByteArray(32).also { input.read(it) }.toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (header?.startsWith("Ren'Py Save Game") == true) RenPySaveFileMetadata(null, header.lineSequence().firstOrNull())
            else null
        }
    }

    private fun readSaveSlot(file: File): RenPySaveSlot? {
        val metadata = readSaveMetadata(file) ?: return null
        val zipDetails = runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.sorted().toList()
                (zip.getEntry("screenshot.png") != null) to entries
            }
        }.getOrDefault(false to emptyList())
        return RenPySaveSlot(
            fileName = file.name,
            filePath = file.absolutePath,
            saveName = metadata.saveName,
            renpyVersion = metadata.renpyVersion,
            modifiedAt = file.lastModified(),
            sizeBytes = file.length(),
            hasScreenshot = zipDetails.first,
            entries = zipDetails.second,
        )
    }

    private data class AssociationMatch(
        val packageName: String,
        val confidence: Int,
        val reason: String,
    )

    private fun associate(
        summary: SaveDirSummary,
        apps: List<InstalledApp>,
        appByPackage: Map<String, InstalledApp>,
    ): AssociationMatch? {
        val path = summary.dir.absolutePath.replace('\\', '/')
        androidPackageRe.find(path)?.groupValues?.getOrNull(1)?.let { pkg ->
            if (appByPackage.containsKey(pkg)) {
                return AssociationMatch(pkg, confidence = 100, reason = "Android save path belongs to package $pkg")
            }
        }

        apps.filter { it.source == AppSource.JoiPlay }
            .firstOrNull { app ->
                val storage = app.storagePath?.replace('\\', '/')?.trimEnd('/') ?: return@firstOrNull false
                val root = if (storage.substringAfterLast('/').lowercase() in setOf("www", "game", "app", "src", "resources")) {
                    storage.substringBeforeLast('/', missingDelimiterValue = storage)
                } else {
                    storage
                }
                path == root || path.startsWith("$root/")
            }
            ?.let { app ->
                return AssociationMatch(app.packageName, confidence = 100, reason = "Save folder is inside this JoiPlay game folder")
            }

        val ownerNorm = normalizeOwner(summary.ownerId)
        if (ownerNorm.isBlank()) return null
        val best = apps.mapNotNull { app ->
            val score = catalogMatchLabels(app).maxOfOrNull { label ->
                val labelNorm = normalizeOwner(label)
                when {
                    labelNorm.isBlank() -> 0
                    labelNorm == ownerNorm -> 85
                    ownerNorm.contains(labelNorm) || labelNorm.contains(ownerNorm) -> 70
                    else -> 0
                }
            } ?: 0
            if (score > 0) app to score else null
        }.maxByOrNull { it.second }
        return best?.let { (app, score) ->
            AssociationMatch(app.packageName, confidence = score, reason = "Ren'Py save-folder owner '${summary.ownerId}' matched game title/folder")
        }
    }

    private fun SaveDirSummary.toLocation(match: AssociationMatch?, apps: List<InstalledApp>): RenPySaveLocation =
        RenPySaveLocation(
            saveDirPath = dir.absolutePath,
            ownerId = ownerId,
            saveCount = saveCount,
            sampleSaveNames = sampleSaveNames,
            renpyVersion = renpyVersion,
            latestModified = latestModified,
            associatedPackageName = match?.packageName,
            associatedLabel = match?.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.label },
            confidence = match?.confidence ?: 0,
            reason = match?.reason,
        )

    private fun RenPySaveLocation.toAssociationOrNull(): RenPySaveAssociation? {
        val pkg = associatedPackageName ?: return null
        return RenPySaveAssociation(
            appPackageName = pkg,
            saveDirPath = saveDirPath,
            ownerId = ownerId,
            saveCount = saveCount,
            sampleSaveNames = sampleSaveNames,
            renpyVersion = renpyVersion,
            latestModified = latestModified,
            confidence = confidence,
            reason = reason ?: "Associated",
        )
    }

    private fun ownerIdFromDir(dir: File): String {
        val name = dir.name
        return if (name.equals("saves", ignoreCase = true) || name.equals(".renpy", ignoreCase = true)) {
            dir.parentFile?.name ?: name
        } else {
            name
        }
    }

    private fun normalizeOwner(value: String): String =
        trailingIdRe.replace(value, "")
            .lowercase()
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), "")

    private fun File.safeListFiles(): List<File> =
        runCatching { listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())

    private fun File.safeCanonicalPath(): String? =
        runCatching { canonicalPath.replace('\\', '/') }.getOrNull()

    private fun shouldSkipDescend(dir: File): Boolean {
        val name = dir.name.lowercase()
        val path = dir.absolutePath.replace('\\', '/').lowercase()
        return name in setOf("cache", "code_cache", "tmp", "temp", ".thumbnails", ".trash", "lost.dir") ||
            path.endsWith("/android/obb")
    }

    private const val BROAD_SCAN_MAX_DIRS = 30000
    private const val BROAD_SCAN_MAX_DEPTH = 10

    private fun String.normalizePathKey(): String = replace('\\', '/')
}
