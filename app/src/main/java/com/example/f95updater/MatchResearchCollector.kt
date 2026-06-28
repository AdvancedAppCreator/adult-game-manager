package com.example.f95updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import org.jsoup.parser.Parser
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

data class MatchResearchProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val detail: String? = null,
)

data class MatchResearchUploadResult(
    val localFile: File,
    val blobName: String,
    val uploaded: Boolean,
    val error: String? = null,
)

@Serializable
data class MatchResearchSnapshot(
    val schemaVersion: Int = 2,
    val appVersionName: String,
    val appVersionCode: Int,
    val generatedAt: String,
    val deviceSummary: MatchResearchDeviceSummary,
    val catalogMeta: MatchResearchCatalogMeta,
    val games: List<MatchResearchGame>,
)

@Serializable
data class MatchResearchDeviceSummary(
    val sdk: Int,
    val manufacturer: String,
    val model: String,
)

@Serializable
data class MatchResearchCatalogMeta(
    val lastSyncMs: Long,
    val cachedCount: Long,
    val cachedSizeBytes: Long,
    val sourceIndexGeneratedAt: String? = null,
    val sources: List<MatchResearchCatalogSourceMeta> = emptyList(),
)

@Serializable
data class MatchResearchCatalogSourceMeta(
    val source: String,
    val generatedAt: String? = null,
    val count: Int? = null,
)

@Serializable
data class MatchResearchGame(
    val localId: String,
    val source: AppSource,
    val currentMapping: MatchResearchMapping,
    val appMetadata: MatchResearchAppMetadata,
    val androidMetadata: MatchResearchAndroidMetadata? = null,
    val joiplayMetadata: MatchResearchJoiPlayMetadata? = null,
    val derivedSignals: MatchResearchSignals,
    val currentMatcher: MatchResearchMatcherResult? = null,
    val collectorWarnings: List<String> = emptyList(),
)

@Serializable
data class MatchResearchMapping(
    val status: String,
    val threadId: Int? = null,
    val f95Url: String? = null,
    val matchSource: String? = null,
    val notOnF95: Boolean = false,
    val lastSeenVersion: String? = null,
    val acknowledgedVersion: String? = null,
)

@Serializable
data class MatchResearchAppMetadata(
    val packageName: String,
    val label: String,
    val launcherLabel: String? = null,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val lastUsedTime: Long,
    val apkSize: Long,
    val dataSize: Long,
    val cacheSize: Long,
)

@Serializable
data class MatchResearchAndroidMetadata(
    val packageNameSegments: List<String>,
    val applicationClassName: String? = null,
    val taskAffinity: String? = null,
    val launcherActivityClassNames: List<String> = emptyList(),
    val launcherActivityLabels: List<String> = emptyList(),
    val installerPackageName: String? = null,
    val sourceApkBasename: String? = null,
    val splitApkBasenames: List<String> = emptyList(),
    val titleLikeManifestMetadata: Map<String, String> = emptyMap(),
    val parsedApkMetadata: List<MatchResearchParsedMetadata> = emptyList(),
)

@Serializable
data class MatchResearchJoiPlayMetadata(
    val gameId: String? = null,
    val engineType: String? = null,
    val folderBasename: String? = null,
    val parentFolderBasename: String? = null,
    val storageFolderName: String? = null,
    val execFileRelativePath: String? = null,
    val execFileBasename: String? = null,
    val execFileExtension: String? = null,
    val versionCandidates: List<MatchResearchVersionCandidate> = emptyList(),
    val parsedMetadata: List<MatchResearchParsedMetadata> = emptyList(),
)

@Serializable
data class MatchResearchVersionCandidate(
    val source: String,
    val version: String,
    val detail: String? = null,
)

@Serializable
data class MatchResearchParsedMetadata(
    val file: String,
    val fields: Map<String, String>,
)

@Serializable
data class MatchResearchSignals(
    val candidateNames: List<String>,
    val candidateEvidence: List<MatchResearchCandidateEvidence> = emptyList(),
    val normalizedNames: List<String>,
    val titleWordsByName: Map<String, List<String>>,
    val appCleanKeys: Map<String, String>,
    val acronyms: List<String>,
    val packageTokens: List<String>,
    val folderTokens: List<String>,
    val versionCandidates: List<String>,
)

@Serializable
data class MatchResearchCandidateEvidence(
    val value: String,
    val source: String,
    val key: String,
    val normalized: String,
    val accepted: Boolean,
    val reason: String? = null,
)

@Serializable
data class MatchResearchMatcherResult(
    val threadId: Int,
    val title: String,
    val source: String,
    val sourceId: String? = null,
    val via: String,
    val version: String? = null,
)

object MatchResearchCollector {
    private const val MAX_METADATA_BYTES = 256 * 1024L
    private const val MAX_BINARY_SAMPLE_BYTES = 1024 * 1024
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun collectSaveUpload(
        context: Context,
        repo: MappingRepository,
        catalog: CatalogRepository,
        progress: (MatchResearchProgress) -> Unit,
    ): MatchResearchUploadResult = withContext(Dispatchers.IO) {
        progress(MatchResearchProgress("Loading installed Android apps"))
        val android = InstalledAppsScanner.scan(context)

        progress(MatchResearchProgress("Loading JoiPlay apps"))
        val backup = JoiPlayBackupReader.asInstalledApps(context)
        val folder = JoiPlayScanner.scan(context)
        val joiplay = mergeJoiPlaySources(backup, folder)
        val apps = (android + joiplay).sortedBy { it.label.lowercase() }

        progress(MatchResearchProgress("Loading mappings"))
        val mappings = repo.get()

        progress(MatchResearchProgress("Loading catalog metadata"))
        val catalogMeta = catalogMeta(catalog)

        val games = mutableListOf<MatchResearchGame>()
        for ((index, app) in apps.withIndex()) {
            progress(
                MatchResearchProgress(
                    stage = "Collecting game metadata",
                    current = index + 1,
                    total = apps.size,
                    detail = app.label,
                )
            )
            games += collectGame(context, catalog, app, mappings[app.packageName])
        }

        progress(MatchResearchProgress("Packaging snapshot"))
        val snapshot = MatchResearchSnapshot(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            generatedAt = utcNow(),
            deviceSummary = MatchResearchDeviceSummary(
                sdk = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
            ),
            catalogMeta = catalogMeta,
            games = games,
        )
        val bytes = json.encodeToString(snapshot).encodeToByteArray()
        val gz = gzip(bytes)
        val ts = System.currentTimeMillis()
        val local = File(context.filesDir, "match_research").apply { mkdirs() }
            .resolve("match-research-$ts.json.gz")
        local.writeBytes(gz)

        progress(MatchResearchProgress("Uploading snapshot"))
        val blobName = "match-research-$ts.json.gz"
        val upload = upload(context, local, blobName)
        upload.copy(localFile = local, blobName = blobName)
    }

    private suspend fun catalogMeta(catalog: CatalogRepository): MatchResearchCatalogMeta {
        val index = catalog.sourceCatalogIndex()
        return MatchResearchCatalogMeta(
            lastSyncMs = catalog.lastSyncMs(),
            cachedCount = catalog.cachedCount(),
            cachedSizeBytes = catalog.cachedSize(),
            sourceIndexGeneratedAt = index?.generatedAt,
            sources = index?.catalogs.orEmpty().map {
                MatchResearchCatalogSourceMeta(
                    source = it.id,
                    generatedAt = it.generatedAt,
                    count = it.count,
                )
            },
        )
    }

    private suspend fun collectGame(
        context: Context,
        catalog: CatalogRepository,
        app: InstalledApp,
        mapping: AppMapping?,
    ): MatchResearchGame {
        val warnings = mutableListOf<String>()
        val androidMetadata = if (app.source == AppSource.Android) {
            runCatching { androidMetadata(context, app) }
                .onFailure { warnings += "androidMetadata:${it.message ?: it::class.simpleName}" }
                .getOrNull()
        } else null
        val versionCandidates = if (app.source == AppSource.JoiPlay) {
            runCatching { JoiPlayVersionDetector.detect(context, app) }
                .onFailure { warnings += "joiPlayVersion:${it.message ?: it::class.simpleName}" }
                .getOrDefault(emptyList())
        } else emptyList()
        val joiplayMetadata = if (app.source == AppSource.JoiPlay) {
            runCatching { joiplayMetadata(app, versionCandidates) }
                .onFailure { warnings += "joiPlayMetadata:${it.message ?: it::class.simpleName}" }
                .getOrNull()
        } else null
        val signals = derivedSignals(app, androidMetadata, joiplayMetadata)
        val candidateNames = signals.candidateNames
        val currentMatcher = runCatching {
            catalog.bestTitleMatch(candidateNames, allowAcronym = allowAcronym(candidateNames))?.let {
                MatchResearchMatcherResult(
                    threadId = it.game.thread_id,
                    title = it.game.title,
                    source = it.game.source,
                    sourceId = it.game.sourceId,
                    via = it.via,
                    version = it.game.version,
                )
            }
        }.onFailure { warnings += "currentMatcher:${it.message ?: it::class.simpleName}" }.getOrNull()
        return MatchResearchGame(
            localId = app.packageName,
            source = app.source,
            currentMapping = mapping.toResearchMapping(),
            appMetadata = MatchResearchAppMetadata(
                packageName = app.packageName,
                label = app.label,
                launcherLabel = app.launcherLabel,
                versionName = app.versionName,
                versionCode = app.versionCode,
                firstInstallTime = app.firstInstallTime,
                lastUpdateTime = app.lastUpdateTime,
                lastUsedTime = app.lastUsedTime,
                apkSize = app.apkSize,
                dataSize = app.dataSize,
                cacheSize = app.cacheSize,
            ),
            androidMetadata = androidMetadata,
            joiplayMetadata = joiplayMetadata,
            derivedSignals = signals,
            currentMatcher = currentMatcher,
            collectorWarnings = warnings,
        )
    }

    private fun AppMapping?.toResearchMapping(): MatchResearchMapping {
        val m = this
        val status = when {
            m == null || m.f95Url.isNullOrBlank() -> "unmatched"
            m.notOnF95 -> "notInCatalog"
            m.matchSource?.startsWith("manual") == true -> "manual"
            else -> "autoOrImported"
        }
        return MatchResearchMapping(
            status = status,
            threadId = m?.threadId ?: F95UrlParser.extractThreadId(m?.f95Url),
            f95Url = m?.f95Url,
            matchSource = m?.matchSource,
            notOnF95 = m?.notOnF95 ?: false,
            lastSeenVersion = m?.lastSeenVersion,
            acknowledgedVersion = m?.acknowledgedVersion,
        )
    }

    private fun androidMetadata(context: Context, app: InstalledApp): MatchResearchAndroidMetadata {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, PackageManager.GET_META_DATA)
        }
        val ai = pi.applicationInfo
        val launcherInfos = runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                setPackage(app.packageName)
            }
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            infos.mapNotNull { ri ->
                val activity = ri.activityInfo ?: return@mapNotNull null
                val cls = activity.name.takeIf { it.isNotBlank() }
                val label = runCatching { ri.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotBlank() } }.getOrNull()
                cls?.let { it to label }
            }.distinct().take(8)
        }.getOrDefault(emptyList())
        val meta = ai?.metaData
        val titleLike = buildMap {
            if (meta != null) {
                for (key in meta.keySet()) {
                    val lk = key.lowercase()
                    val allowed = lk == "title" || lk == "name" ||
                        lk.endsWith(".title") || lk.endsWith(".name") ||
                        lk.contains("game_title") || lk.contains("app_title")
                    if (!allowed) continue
                    val value = meta.get(key)?.toString()?.trim()?.takeIf { usefulTitleCandidate(it) } ?: continue
                    put(key, value.take(160))
                    if (size >= 12) break
                }
            }
        }
        return MatchResearchAndroidMetadata(
            packageNameSegments = app.packageName.split('.').filter { it.isNotBlank() },
            applicationClassName = ai?.className,
            taskAffinity = ai?.taskAffinity,
            launcherActivityClassNames = launcherInfos.map { it.first },
            launcherActivityLabels = launcherInfos.mapNotNull { it.second }.distinct().take(8),
            installerPackageName = runCatching { pm.getInstallerPackageName(app.packageName) }.getOrNull(),
            sourceApkBasename = ai?.sourceDir?.let { File(it).name },
            splitApkBasenames = ai?.splitSourceDirs?.map { File(it).name }.orEmpty().take(12),
            titleLikeManifestMetadata = titleLike,
            parsedApkMetadata = parseApkMetadataFiles(ai?.sourceDir, ai?.splitSourceDirs),
        )
    }

    private fun joiplayMetadata(
        app: InstalledApp,
        versionCandidates: List<VersionCandidate>,
    ): MatchResearchJoiPlayMetadata {
        val path = app.storagePath?.replace('\\', '/')?.trimEnd('/')
        val folderBase = path?.substringAfterLast('/')
        val parentBase = path?.substringBeforeLast('/', missingDelimiterValue = "")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val exec = app.joiPlayExecFile?.replace('\\', '/')?.trim('/')
        val execBase = exec?.substringAfterLast('/')
        return MatchResearchJoiPlayMetadata(
            gameId = app.joiPlayGameId,
            engineType = app.joiPlayType,
            folderBasename = folderBase,
            parentFolderBasename = parentBase,
            storageFolderName = app.storageFolderName,
            execFileRelativePath = exec,
            execFileBasename = execBase,
            execFileExtension = execBase?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it != execBase },
            versionCandidates = versionCandidates.map { MatchResearchVersionCandidate(it.source, it.version, it.detail) },
            parsedMetadata = parseJoiPlayMetadataFiles(app),
        )
    }

    private fun parseJoiPlayMetadataFiles(app: InstalledApp): List<MatchResearchParsedMetadata> {
        val root = app.storagePath?.let { File(it) }?.takeIf { it.isDirectory } ?: return emptyList()
        val candidates = listOf(
            "package.json",
            "www/package.json",
            "android.json",
            "game.ini",
            "game/cache/build_info.json",
            "game/script_version.txt",
            "game/options.rpy",
            "game/gui/about.rpy",
            "game/script.rpy",
            "project.godot",
            "www/project.godot",
            "www/data/System.json",
            "data/System.json",
            "data/system/Config.tjs",
            "tyrano/data/system/Config.tjs",
            "index.html",
            "www/index.html",
        )
        val parsed = candidates.mapNotNull { rel ->
            val file = File(root, rel)
            if (!file.isFile || file.length() <= 0L || file.length() > MAX_METADATA_BYTES) return@mapNotNull null
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            val fields = parseMetadataFields(rel, text)
            fields.takeIf { it.isNotEmpty() }?.let { MatchResearchParsedMetadata(rel, it) }
        }.toMutableList()
        joiplayExecMetadata(root, app.joiPlayExecFile)?.let { parsed += it }
        return parsed
    }

    private fun parseApkMetadataFiles(sourceDir: String?, splitSourceDirs: Array<String>?): List<MatchResearchParsedMetadata> {
        val apkFiles = (listOfNotNull(sourceDir) + splitSourceDirs.orEmpty())
            .map { File(it) }
            .filter { it.isFile && it.length() > 0L }
            .take(8)
        if (apkFiles.isEmpty()) return emptyList()
        val metadataEntries = listOf(
            "assets/package.json",
            "assets/www/package.json",
            "assets/android.json",
            "assets/index.html",
            "assets/www/index.html",
            "assets/www/data/System.json",
            "assets/data/System.json",
            "assets/project.godot",
            "assets/www/project.godot",
            "assets/game/cache/build_info.json",
            "assets/game/script_version.txt",
            "assets/game/options.rpy",
            "assets/game/gui/about.rpy",
            "assets/game/script.rpy",
            "assets/x-game/game/cache/build_info.json",
            "assets/x-game/game/script_version.txt",
            "assets/x-game/game/options.rpy",
            "assets/x-game/game/gui/about.rpy",
            "assets/x-game/game/script.rpy",
            "assets/data/system/Config.tjs",
            "assets/tyrano/data/system/Config.tjs",
        )
        val binaryEntries = listOf(
            "assets/bin/Data/globalgamemanagers",
            "assets/bin/Data/globalgamemanagers.assets",
            "assets/Data/globalgamemanagers",
            "assets/Data/globalgamemanagers.assets",
        )
        val out = mutableListOf<MatchResearchParsedMetadata>()
        for (apk in apkFiles) {
            runCatching {
                ZipFile(apk).use { zip ->
                    for (entryName in metadataEntries) {
                        val entry = zip.getEntry(entryName) ?: continue
                        if (entry.size <= 0L || entry.size > MAX_METADATA_BYTES) continue
                        val text = readZipEntryBytes(zip, entry, MAX_METADATA_BYTES.toInt())?.toString(Charsets.UTF_8) ?: continue
                        val fields = parseMetadataFields(entryName, text)
                        if (fields.isNotEmpty()) out += MatchResearchParsedMetadata("${apk.name}!/$entryName", fields)
                    }
                    for (entryName in binaryEntries) {
                        val entry = zip.getEntry(entryName) ?: continue
                        val bytes = readZipEntryBytes(zip, entry, MAX_BINARY_SAMPLE_BYTES) ?: continue
                        val fields = parseUnityStringFields(bytes)
                        if (fields.isNotEmpty()) out += MatchResearchParsedMetadata("${apk.name}!/$entryName", fields)
                    }
                }
            }
        }
        return out.take(24)
    }

    private fun readZipEntryBytes(zip: ZipFile, entry: java.util.zip.ZipEntry, maxBytes: Int): ByteArray? =
        runCatching {
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        }.getOrNull()

    private fun joiplayExecMetadata(root: File, execFile: String?): MatchResearchParsedMetadata? {
        val exec = execFile?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotBlank() } ?: return null
        if (!exec.endsWith(".exe", ignoreCase = true)) return null
        val file = safeResolveInside(root, exec) ?: return null
        if (!file.isFile || file.length() <= 0L) return null
        val bytes = readFileSamples(file, firstBytes = 256 * 1024, lastBytes = 768 * 1024) ?: return null
        val fields = parsePeVersionStringFields(bytes)
        return fields.takeIf { it.isNotEmpty() }?.let { MatchResearchParsedMetadata(exec, it) }
    }

    private fun safeResolveInside(root: File, relativeOrAbsolute: String): File? =
        runCatching {
            val rootCanonical = root.canonicalFile
            val candidate = File(relativeOrAbsolute).let {
                if (it.isAbsolute) it else File(rootCanonical, relativeOrAbsolute)
            }.canonicalFile
            if (candidate.path == rootCanonical.path || candidate.path.startsWith(rootCanonical.path + File.separator)) candidate else null
        }.getOrNull()

    private fun readFileSamples(file: File, firstBytes: Int, lastBytes: Int): ByteArray? =
        runCatching {
            val length = file.length()
            if (length <= 0L) return@runCatching ByteArray(0)
            if (length <= (firstBytes + lastBytes).toLong()) return@runCatching file.readBytes()
            RandomAccessFile(file, "r").use { raf ->
                val out = ByteArrayOutputStream(firstBytes + lastBytes)
                val first = ByteArray(firstBytes)
                raf.seek(0)
                raf.readFully(first)
                out.write(first)
                val last = ByteArray(lastBytes)
                raf.seek(length - lastBytes)
                raf.readFully(last)
                out.write(last)
                out.toByteArray()
            }
        }.getOrNull()

    private fun parsePeVersionStringFields(bytes: ByteArray): Map<String, String> {
        val strings = printableStrings(bytes)
        val keys = linkedMapOf(
            "ProductName" to "pe.productName",
            "FileDescription" to "pe.fileDescription",
            "OriginalFilename" to "pe.originalFilename",
            "CompanyName" to "pe.companyName",
        )
        val out = linkedMapOf<String, String>()
        for ((rawKey, fieldKey) in keys) {
            val idx = strings.indexOfFirst { it.equals(rawKey, ignoreCase = true) || it.contains(rawKey, ignoreCase = true) }
            if (idx < 0) continue
            val value = strings.drop(idx + 1)
                .take(8)
                .firstOrNull { candidate ->
                    candidate.length in 3..160 &&
                        !keys.keys.any { candidate.equals(it, ignoreCase = true) } &&
                        usefulTitleCandidate(candidate)
                }
            if (value != null) out[fieldKey] = value.take(160)
        }
        return out
    }

    private fun parseUnityStringFields(bytes: ByteArray): Map<String, String> {
        val blocked = setOf(
            "unity",
            "unityengine",
            "android",
            "defaultcompany",
            "projectsettings",
            "globalgamemanagers",
            "gamemanager",
        )
        val candidates = printableStrings(bytes)
            .asSequence()
            .map { it.trim() }
            .filter { it.length in 3..80 }
            .filter { usefulTitleCandidate(it) }
            .filter { CatalogRepository.normalizeTitle(it) !in blocked }
            .distinct()
            .take(12)
            .toList()
        return candidates.mapIndexed { idx, value -> "unity.stringHint.${idx + 1}" to value }.toMap()
    }

    private fun printableStrings(bytes: ByteArray): List<String> {
        fun asciiStrings(text: String): List<String> =
            Regex("""[\x20-\x7E]{3,160}""").findAll(text).map { it.value.trim() }.filter { it.isNotBlank() }.toList()
        val ascii = asciiStrings(bytes.toString(Charsets.ISO_8859_1))
        val utf16 = runCatching { asciiStrings(bytes.toString(Charsets.UTF_16LE)) }.getOrDefault(emptyList())
        return (ascii + utf16).distinct().take(400)
    }

    private fun parseMetadataFields(rel: String, text: String): Map<String, String> {
        fun clean(v: String?): String? = v
            ?.let { Parser.unescapeEntities(it, false) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(160)
        fun JSONObject.cleanString(key: String): String? =
            if (has(key) && !isNull(key)) clean(optString(key)) else null
        return when (rel.substringAfterLast('/').lowercase()) {
            "package.json" -> runCatching {
                val obj = JSONObject(text)
                buildMap {
                    obj.cleanString("name")?.let { put("name", it) }
                    obj.cleanString("title")?.let { put("title", it) }
                    obj.cleanString("productName")?.let { put("productName", it) }
                    obj.cleanString("displayName")?.let { put("displayName", it) }
                    obj.cleanString("version")?.let { put("version", it) }
                }
            }.getOrDefault(emptyMap())
            "android.json" -> runCatching {
                val obj = JSONObject(text)
                buildMap {
                    obj.cleanString("name")?.let { put("name", it) }
                    obj.cleanString("title")?.let { put("title", it) }
                    obj.cleanString("label")?.let { put("label", it) }
                    obj.cleanString("version")?.let { put("version", it) }
                }
            }.getOrDefault(emptyMap())
            "system.json" -> runCatching {
                val obj = JSONObject(text)
                buildMap {
                    obj.cleanString("gameTitle")?.let { put("gameTitle", it) }
                    obj.cleanString("versionId")?.let { put("versionId", it) }
                }
            }.getOrDefault(emptyMap())
            "game.ini" -> {
                val out = linkedMapOf<String, String>()
                Regex("""(?im)^\s*(title|name|version)\s*=\s*(.+?)\s*$""").findAll(text).forEach {
                    clean(it.groupValues[2])?.let { value -> out[it.groupValues[1].lowercase()] = value }
                }
                out
            }
            "build_info.json" -> runCatching {
                val obj = JSONObject(text)
                buildMap {
                    obj.cleanString("name")?.let { put("renpy.build.name", it) }
                    obj.cleanString("version")?.let { put("renpy.build.version", it) }
                }
            }.getOrDefault(emptyMap())
            "script_version.txt" -> {
                val out = linkedMapOf<String, String>()
                clean(text.lineSequence().firstOrNull { it.isNotBlank() })?.let { value ->
                    out["renpy.script.version"] = value
                }
                out
            }
            "options.rpy" -> {
                val out = linkedMapOf<String, String>()
                parseRenPyTitleFields(text, out)
                out
            }
            "about.rpy", "script.rpy" -> {
                val out = linkedMapOf<String, String>()
                parseRenPyTitleFields(text, out)
                out
            }
            "project.godot" -> {
                val out = linkedMapOf<String, String>()
                Regex("""(?im)^\s*config/name\s*=\s*["'](.+?)["']\s*$""").find(text)?.groupValues?.getOrNull(1)?.let {
                    clean(it)?.let { v -> out["application.config.name"] = v }
                }
                Regex("""(?im)^\s*config/version\s*=\s*["'](.+?)["']\s*$""").find(text)?.groupValues?.getOrNull(1)?.let {
                    clean(it)?.let { v -> out["application.config.version"] = v }
                }
                out
            }
            "config.tjs" -> {
                val out = linkedMapOf<String, String>()
                Regex("""(?i)(?:title|name|projectName)\s*[:=]\s*["']([^"']{1,120})["']""").find(text)?.groupValues?.getOrNull(1)?.let {
                    clean(it)?.let { v -> out["config.title"] = v }
                }
                Regex("""(?i)version\s*[:=]\s*["']([^"']{1,80})["']""").find(text)?.groupValues?.getOrNull(1)?.let {
                    clean(it)?.let { v -> out["config.version"] = v }
                }
                out
            }
            "index.html" -> {
                val out = linkedMapOf<String, String>()
                Regex("""(?is)<title[^>]*>(.*?)</title>""").find(text)?.groupValues?.getOrNull(1)
                    ?.replace(Regex("""\s+"""), " ")
                    ?.let { clean(it)?.let { v -> out["html.title"] = v } }
                Regex("""(?is)<meta\s+(?:property|name)=["'](?:og:title|application-name|title)["']\s+content=["']([^"']{1,160})["']""")
                    .find(text)?.groupValues?.getOrNull(1)
                    ?.let { clean(it)?.let { v -> out["html.meta.title"] = v } }
                out
            }
            else -> emptyMap()
        }
    }

    private fun parseRenPyTitleFields(text: String, out: MutableMap<String, String>) {
        fun clean(v: String?): String? = v
            ?.let { Parser.unescapeEntities(it, false) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(160)
        Regex("""(?:define\s+)?config\.name\s*=\s*(?:_\()?["'](.+?)["']""").find(text)?.groupValues?.getOrNull(1)?.let {
            clean(it)?.let { v -> out["config.name"] = v }
        }
        Regex("""(?:define\s+)?build\.name\s*=\s*["'](.+?)["']""").find(text)?.groupValues?.getOrNull(1)?.let {
            clean(it)?.let { v -> out["build.name"] = v }
        }
        Regex("""(?:define\s+)?config\.version\s*=\s*["'](.+?)["']""").find(text)?.groupValues?.getOrNull(1)?.let {
            clean(it)?.let { v -> out["config.version"] = v }
        }
    }

    private fun derivedSignals(
        app: InstalledApp,
        android: MatchResearchAndroidMetadata?,
        joiplay: MatchResearchJoiPlayMetadata?,
    ): MatchResearchSignals {
        val names = linkedSetOf<String>()
        val evidence = mutableListOf<MatchResearchCandidateEvidence>()
        fun considerName(value: String?, source: String, key: String, forceAccept: Boolean = false) {
            val cleaned = value?.trim()?.takeIf { it.isNotBlank() } ?: return
            val normalized = CatalogRepository.normalizeTitle(cleaned)
            val accepted = forceAccept || usefulTitleCandidate(cleaned)
            if (accepted) names += cleaned
            evidence += MatchResearchCandidateEvidence(
                value = cleaned.take(160),
                source = source,
                key = key,
                normalized = normalized,
                accepted = accepted,
                reason = if (accepted) null else "filtered-generic-or-non-title",
            )
        }
        considerName(app.label, "app", "label", forceAccept = true)
        considerName(app.launcherLabel, "app", "launcherLabel", forceAccept = true)
        android?.launcherActivityLabels?.forEach { considerName(it, "android", "launcherActivityLabel", forceAccept = true) }
        considerName(joiplay?.folderBasename, "joiplay", "folderBasename")
        considerName(joiplay?.parentFolderBasename, "joiplay", "parentFolderBasename")
        considerName(joiplay?.storageFolderName, "joiplay", "storageFolderName")
        considerName(
            joiplay?.execFileBasename
                ?.substringBeforeLast('.', joiplay.execFileBasename)
            ,
            "joiplay",
            "execFileBasename",
        )
        joiplay?.parsedMetadata?.forEach { parsed ->
            parsed.fields.forEach { (key, value) ->
                if (isTitleMetadataKey(key)) considerName(value, "joiplay:${parsed.file}", key)
            }
        }
        android?.titleLikeManifestMetadata?.forEach { (key, value) -> considerName(value, "android:manifest", key) }
        android?.parsedApkMetadata?.forEach { parsed ->
            parsed.fields.forEach { (key, value) ->
                if (isTitleMetadataKey(key)) considerName(value, "android-apk:${parsed.file}", key)
            }
        }
        val candidateNames = names.toList()
        val packageTokens = app.packageName.split('.').filter { it.length >= 2 }
        val folderTokens = listOfNotNull(joiplay?.folderBasename, joiplay?.parentFolderBasename, joiplay?.execFileBasename)
            .flatMap { it.split(Regex("[^A-Za-z0-9]+")) }
            .filter { it.length >= 2 }
            .distinct()
        val titleWords = candidateNames.associateWith { CatalogRepository.titleWords(it) }
        return MatchResearchSignals(
            candidateNames = candidateNames,
            candidateEvidence = evidence.distinctBy { listOf(it.source, it.key, it.value) }.take(80),
            normalizedNames = candidateNames.map { CatalogRepository.normalizeTitle(it) }.filter { it.isNotBlank() }.distinct(),
            titleWordsByName = titleWords,
            appCleanKeys = candidateNames.associateWith { CatalogRepository.appCleanKey(it) },
            acronyms = titleWords.values.map { words -> words.mapNotNull { it.firstOrNull() }.joinToString("") }
                .filter { it.length >= 2 }
                .distinct(),
            packageTokens = packageTokens,
            folderTokens = folderTokens,
            versionCandidates = joiplay?.versionCandidates?.map { it.version }.orEmpty().distinct(),
        )
    }

    private fun allowAcronym(names: List<String>): Boolean =
        names.any { it.length in 3..8 && CatalogRepository.normalizeTitle(it).length in 3..8 }

    private val genericTitleCandidates = setOf(
        "game",
        "joiplay",
        "f95",
        "www",
        "index",
        "rmmzgame",
        "rmmvgame",
        "rpgmgame",
        "nwjs",
    )

    private val titleMetadataKeys = setOf(
        "name",
        "title",
        "gametitle",
        "config.name",
        "html.title",
        "html.meta.title",
        "label",
        "productname",
        "displayname",
        "application.config.name",
        "build.name",
        "renpy.build.name",
        "config.title",
        "pe.productname",
        "pe.filedescription",
    )

    private fun isTitleMetadataKey(key: String): Boolean {
        val cleaned = key.lowercase().replace("_", "").replace("-", "")
        return cleaned in titleMetadataKeys
    }

    private fun usefulTitleCandidate(value: String): Boolean {
        val decoded = Parser.unescapeEntities(value.trim(), false)
        val normalized = CatalogRepository.normalizeTitle(decoded)
        if (normalized.length < 3) return false
        if (normalized in genericTitleCandidates) return false
        if (normalized.all { it.isDigit() }) return false
        return normalized.any { it.isLetter() }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun utcNow(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

    private fun upload(context: Context, file: File, blobName: String): MatchResearchUploadResult {
        val config = AppConfigStore.current(context)
        val base = config.crashUploadBaseUrl
        val sas = config.crashUploadAuthQuery.orEmpty()
        if (base.isNullOrBlank()) {
            return MatchResearchUploadResult(file, blobName, uploaded = false, error = "No private upload endpoint configured")
        }
        val target = if (sas.isNotBlank()) "$base/$blobName?$sas" else "$base/$blobName"
        val req = Request.Builder()
            .url(target)
            .header("x-ms-blob-type", "BlockBlob")
            .header("x-ms-version", "2021-08-06")
            .header("Content-Type", "application/gzip")
            .put(file.readBytes().toRequestBody("application/gzip".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) MatchResearchUploadResult(file, blobName, uploaded = true)
                else MatchResearchUploadResult(file, blobName, uploaded = false, error = "HTTP ${resp.code}")
            }
        }.getOrElse {
            MatchResearchUploadResult(file, blobName, uploaded = false, error = it.message ?: it::class.simpleName)
        }
    }
}
