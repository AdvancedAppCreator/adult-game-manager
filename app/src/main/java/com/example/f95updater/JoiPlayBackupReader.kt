package com.example.f95updater

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.lingala.zip4j.ZipFile
import java.io.File
import java.util.zip.ZipInputStream

private val Context.joiplayBackupStore by preferencesDataStore("joiplay_backup")
private val BACKUP_GAMES_JSON = stringPreferencesKey("backup_games_json")
private val BACKUP_IMPORTED_AT = stringPreferencesKey("backup_imported_at")
private val BACKUP_METADATA_JSON = stringPreferencesKey("backup_metadata_json")
private val BACKUP_SETTINGS_JSON = stringPreferencesKey("backup_settings_json")
/** Newline-joined JoiPlay game IDs the user has deleted. We hide these from the UI even
 *  if the original .joiback snapshot still lists them — otherwise the row reappears on
 *  every launch because cachedGames() is the source of truth. */
private val BACKUP_DELETED_IDS = stringPreferencesKey("backup_deleted_game_ids")

@Serializable
data class JoiPlayBackupGame(
    val id: String = "",
    val title: String = "",
    val folder: String = "",
    val execFile: String = "",
    val icon: String = "",
    val type: String = "",          // engine: renpy / rpgmmz / unity / etc.
    val date: Long = 0L,            // epoch ms
    val playCount: Int = 0,
    /** User-set version in JoiPlay's game properties dialog (often blank). */
    val version: String = "",
)

@Serializable
private data class GamesMapEnvelope(val map: Map<String, JoiPlayBackupGame> = emptyMap())

private data class ParsedJoiPlayBackup(
    val gamesJson: String,
    val settingsJson: String?,
    val metadataJson: String?,
    val games: List<JoiPlayBackupGame>,
)

object JoiPlayBackupReader {
    private const val BACKUP_PASSWORD = "joiplaybackupfile"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun import(context: Context, file: File): Int = import(context, Uri.fromFile(file))

    /** Reads a .joiback file without changing the app's cached imported backup. */
    suspend fun readGames(context: Context, file: File): List<JoiPlayBackupGame> =
        readGames(context, Uri.fromFile(file))

    /** Reads a .joiback file without changing the app's cached imported backup. */
    suspend fun readGames(context: Context, uri: Uri): List<JoiPlayBackupGame> = withContext(Dispatchers.IO) {
        parseBackup(context, uri).games
    }

    /** Imports a .joiback file. Returns the count of games found. */
    suspend fun import(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val parsed = parseBackup(context, uri)

        // Persist
        context.joiplayBackupStore.edit {
            it[BACKUP_GAMES_JSON] = parsed.gamesJson
            it[BACKUP_IMPORTED_AT] = System.currentTimeMillis().toString()
            if (parsed.metadataJson != null) it[BACKUP_METADATA_JSON] = parsed.metadataJson
            if (!parsed.settingsJson.isNullOrBlank()) it[BACKUP_SETTINGS_JSON] = parsed.settingsJson
        }
        AppLog.i(
            "JoiPlayImport",
            "Imported ${parsed.games.size} games, settings=${parsed.settingsJson?.length ?: 0} chars, " +
                "metadata=${parsed.metadataJson?.length ?: 0} chars"
        )
        AppLog.i("JoiPlayImport", "Backup settings: ${settingsSummary(parsed.settingsJson)}")
        parsed.games.sortedBy { it.title.lowercase() }.forEach { game ->
            AppLog.i(
                "JoiPlayImportGame",
                "id='${game.id}' title='${game.title}' folder='${game.folder}' execFile='${game.execFile}' " +
                    "type='${game.type}' version='${game.version}' date=${game.date} playCount=${game.playCount} " +
                    "icon='${game.icon}'"
            )
        }
        parsed.games.size
    }

    private fun parseBackup(context: Context, uri: Uri): ParsedJoiPlayBackup {
        // Copy the SAF stream into our cache so zip4j can open it via File API.
        val outerFile = File(context.cacheDir, "joiback_in.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            outerFile.outputStream().use { input.copyTo(it) }
        } ?: error("cannot open backup uri")

        // Decrypt ff.joiback (contains configuration/games.json)
        val outerZip = ZipFile(outerFile, BACKUP_PASSWORD.toCharArray())
        val ffFile = File(context.cacheDir, "ff.joiback")
        if (ffFile.exists()) ffFile.delete()
        outerZip.extractFile("ff.joiback", context.cacheDir.absolutePath, "ff.joiback")

        // ff.joiback is a plain ZIP — pluck games, global settings, and metadata from it.
        var gamesJsonBytes: ByteArray? = null
        var settingsJsonBytes: ByteArray? = null
        var metadataZipBytes: ByteArray? = null
        ZipInputStream(ffFile.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                when (entry.name) {
                    "configuration/games.json" -> gamesJsonBytes = zin.readBytes()
                    "configuration/settings.json" -> settingsJsonBytes = zin.readBytes()
                    "metadata.zip" -> metadataZipBytes = zin.readBytes()
                }
                if (gamesJsonBytes != null && settingsJsonBytes != null && metadataZipBytes != null) break
            }
        }
        val raw = gamesJsonBytes ?: error("games.json not in backup")
        val text = raw.toString(Charsets.UTF_8)
        // Validate parse
        val parsed = json.decodeFromString(GamesMapEnvelope.serializer(), text)
        val settingsText = settingsJsonBytes?.toString(Charsets.UTF_8)?.also {
            json.parseToJsonElement(it)
        }

        // Extract metadata.json from the nested metadata.zip (JoiPlay's own catalog).
        val metadataText: String? = metadataZipBytes?.let { mzBytes ->
            runCatching {
                ZipInputStream(mzBytes.inputStream()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: return@use null
                        if (e.name == "metadata.json") return@use zin.readBytes().toString(Charsets.UTF_8)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
            }.onFailure { AppLog.w("JoiPlayImport", "metadata.zip parse failed", it) }.getOrNull()
        }

        // Cleanup
        runCatching { outerFile.delete() }
        runCatching { ffFile.delete() }
        return ParsedJoiPlayBackup(
            gamesJson = text,
            settingsJson = settingsText,
            metadataJson = metadataText,
            games = parsed.map.values.toList(),
        )
    }

    private fun settingsSummary(settingsJson: String?): String {
        if (settingsJson.isNullOrBlank()) return "missing"
        return runCatching {
            val root = json.parseToJsonElement(settingsJson) as? JsonObject ?: return@runCatching "non-object"
            val topKeys = root.keys.sorted().joinToString(",")
            val renpy = root["renpy"] as? JsonObject
            val renpySummary = renpy?.entries
                ?.sortedBy { it.key }
                ?.joinToString(",") { (key, value) -> "$key=$value" }
                ?: "missing"
            "length=${settingsJson.length} topKeys=[$topKeys] renpy={$renpySummary}"
        }.getOrElse { "invalid: ${it.message}" }
    }

    suspend fun cachedGames(context: Context): List<JoiPlayBackupGame> = withContext(Dispatchers.IO) {
        val text = context.joiplayBackupStore.data.map { it[BACKUP_GAMES_JSON] }.first()
            ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString(GamesMapEnvelope.serializer(), text).map.values.toList()
        }.getOrDefault(emptyList())
    }

    suspend fun cachedMetadataJson(context: Context): String? = withContext(Dispatchers.IO) {
        context.joiplayBackupStore.data.map { it[BACKUP_METADATA_JSON] }.first()
    }

    suspend fun cachedSettingsJson(context: Context): String? = withContext(Dispatchers.IO) {
        context.joiplayBackupStore.data.map { it[BACKUP_SETTINGS_JSON] }.first()
    }

    /** Raw games.json string (for backup export). null if no .joiback has been imported. */
    suspend fun cachedGamesJson(context: Context): String? = withContext(Dispatchers.IO) {
        context.joiplayBackupStore.data.map { it[BACKUP_GAMES_JSON] }.first()
    }

    /** Restore a previously-exported backup snapshot. */
    suspend fun restoreFromBackup(
        context: Context,
        gamesJson: String?,
        metadataJson: String?,
        settingsJson: String?,
    ): Int = withContext(Dispatchers.IO) {
        if (gamesJson.isNullOrBlank()) return@withContext 0
        // Validate before persisting
        val parsed = json.decodeFromString(GamesMapEnvelope.serializer(), gamesJson)
        if (!settingsJson.isNullOrBlank()) json.parseToJsonElement(settingsJson)
        context.joiplayBackupStore.edit {
            it[BACKUP_GAMES_JSON] = gamesJson
            it[BACKUP_IMPORTED_AT] = System.currentTimeMillis().toString()
            if (!metadataJson.isNullOrBlank()) it[BACKUP_METADATA_JSON] = metadataJson
            if (!settingsJson.isNullOrBlank()) it[BACKUP_SETTINGS_JSON] = settingsJson
        }
        parsed.map.size
    }

    suspend fun importedAtMs(context: Context): Long =
        context.joiplayBackupStore.data.map { it[BACKUP_IMPORTED_AT] }.first()?.toLongOrNull() ?: 0L

    /** Persisted set of JoiPlay game IDs the user has deleted via our app. */
    suspend fun deletedIds(context: Context): Set<String> = withContext(Dispatchers.IO) {
        context.joiplayBackupStore.data.map { it[BACKUP_DELETED_IDS] }.first()
            ?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun markDeleted(context: Context, id: String) = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext
        val cur = deletedIds(context).toMutableSet()
        if (cur.add(id)) {
            context.joiplayBackupStore.edit {
                it[BACKUP_DELETED_IDS] = cur.joinToString("\n")
            }
        }
    }

    /**
     * Walks all backup-cached games and marks-deleted those whose folder no longer exists
     * on disk (per [JoiPlayScanner.folderExists]). Returns the count of newly-pruned games.
     * Conservative: only marks deletion when existence check returns *false* (definitely
     * missing); games with unknown existence (outside the granted SAF root and unreachable
     * via File API) are left alone so we don't accidentally hide games the user can still
     * access through JoiPlay itself.
     */
    suspend fun pruneMissingFolders(context: Context): Int = withContext(Dispatchers.IO) {
        val games = cachedGames(context)
        if (games.isEmpty()) return@withContext 0
        val alreadyDeleted = deletedIds(context)
        val newlyDeleted = mutableSetOf<String>()
        for (g in games) {
            if (g.id in alreadyDeleted) continue
            val folderBase = g.folder.replace('\\', '/').trimEnd('/').substringAfterLast('/')
            val exists = JoiPlayScanner.folderExists(
                context,
                storagePath = g.folder.ifBlank { null },
                folderName = folderBase.ifBlank { null },
            )
            if (exists == false) {
                AppLog.i("JoiPlayPrune", "Folder missing: '${g.title}' id=${g.id} path=${g.folder}")
                newlyDeleted.add(g.id)
            }
        }
        if (newlyDeleted.isNotEmpty()) {
            val merged = alreadyDeleted + newlyDeleted
            context.joiplayBackupStore.edit {
                it[BACKUP_DELETED_IDS] = merged.joinToString("\n")
            }
        }
        newlyDeleted.size
    }

    /** Convert backup games into the unified InstalledApp model. */
    suspend fun asInstalledApps(context: Context): List<InstalledApp> {
        val games = cachedGames(context)
        val settingsJson = cachedSettingsJson(context)?.trim()?.ifBlank { null }
        val overrides = JoiPlayVersionOverrides.loadAll(context)
        val deleted = deletedIds(context)
        return games.filter { it.id !in deleted }.map { g ->
            // Try to extract a version from title, folder basename, and parent of basename
            // (some games point at e.g. "<gameRoot>/www" — the gameRoot has the version).
            val folderPathClean = g.folder.replace('\\', '/').trimEnd('/')
            val folderBasename = folderPathClean.substringAfterLast('/').ifBlank { g.folder }
            val parentBasename = folderPathClean.substringBeforeLast('/', missingDelimiterValue = "")
                .substringAfterLast('/')
                .ifBlank { "" }

            val (_, versionFromTitle) = extractVersion(g.title)
            val (_, versionFromFolder) = extractVersion(folderBasename)
            val tryParent = folderBasename.lowercase() in setOf("www", "game", "app", "src", "resources")
            val (_, versionFromParent) = if (tryParent) extractVersion(parentBasename) else "" to null

            // Priority: 1) explicit user override (from the conflict dialog), 2) JoiPlay's version field,
            //           3) title regex, 4) folder regex, 5) parent dir regex.
            val override = overrides[g.id]?.trim()?.ifBlank { null }
            val joiPlayVersion = g.version.trim().ifBlank { null }
            val version = override ?: joiPlayVersion ?: versionFromTitle ?: versionFromFolder ?: versionFromParent
            if (version == null) {
                AppLog.i(
                    "JoiPlayVer",
                    "No version: title='${g.title}' folder='$folderBasename' parent='$parentBasename' type=${g.type}"
                )
            }
            InstalledApp(
                packageName = "joiplay:${g.id}",
                label = g.title.ifBlank { folderBasename },
                versionName = version ?: "",
                versionCode = 0L,
                firstInstallTime = g.date,
                lastUpdateTime = g.date,
                lastUsedTime = 0L,
                apkSize = 0L,
                dataSize = 0L,
                cacheSize = 0L,
                source = AppSource.JoiPlay,
                storagePath = g.folder.ifBlank { null },
                storageFolderName = folderBasename.ifBlank { null },
                joiPlayGameId = g.id.ifBlank { null },
                joiPlayType = g.type.ifBlank { null },
                joiPlayExecFile = g.execFile.ifBlank { null },
                joiPlaySettingsJson = settingsJson,
            )
        }
    }

    // Match a version. Order of patterns matters: more specific first.
    private val versionPatterns = listOf(
        // Strong "v.X.X.X" / "v-X.X.X" / "vX.X.X" / "verX.X.X" — explicit version marker.
        // Mark group 2 = "has explicit v prefix" so we can be more permissive on numbers below.
        Regex("""(?:^|[^A-Za-z0-9])(v(?:er(?:sion)?)?)[._\- ]?(\d+(?:\.\d+)*[a-zA-Z0-9._\-]{0,16})""", RegexOption.IGNORE_CASE),
        // Letter "O" misread as zero in version context: "O.6.5"
        Regex("""(?:^|[^A-Za-z0-9])()[Oo](\.\d+(?:\.\d+)*[a-zA-Z0-9._\-]{0,8})"""),
        // Bracketed: [0.04], (1.2.3)
        Regex("""[\[(]()v?(\d+(?:\.\d+)+[a-zA-Z0-9._\-]*)[\])]""", RegexOption.IGNORE_CASE),
        // Multi-dot bare semver anywhere (1.2 / 1.2.3 / 0.04a)
        Regex("""(?:^|[^A-Za-z0-9.])()(\d+\.\d+(?:\.\d+)*[a-zA-Z]?)"""),
        // Same as above but after a lowercase letter (catches "Beginning0.99.7")
        Regex("""[a-z]()(\d+\.\d+(?:\.\d+)+[a-zA-Z]?)"""),
        // Dash-separated version like "0-20-16" (Summertime Saga). Requires 3+ segments to avoid false positives.
        Regex("""(?:^|[^A-Za-z0-9])()(\d+-\d+-\d+(?:-\d+)*[a-zA-Z]?)"""),
    )

    // Trailing junk to strip from extracted versions: platform/build suffixes.
    private val trailingJunk = Regex(
        """[-_.](pc|win|win32|win64|windows|android|mac|osx|linux|free|full|pro|premium|patreon|public|release|build|setup|installer)\b.*$""",
        RegexOption.IGNORE_CASE
    )

    /** Reject obvious false positives (year-like numbers without explicit "v" prefix). */
    private fun looksLikeVersion(v: String, hasVPrefix: Boolean): Boolean {
        if (v.isBlank() || v.none { it.isDigit() }) return false
        // With "v" prefix, accept any digit run (even "v54013").
        if (hasVPrefix) return true
        // Without prefix, reject single-segment 4+ digit numbers (years/timestamps/ids).
        if (!v.contains('.') && !v.contains('-')) {
            val firstSeg = v.takeWhile { it.isDigit() }
            if (firstSeg.length >= 4) return false
        }
        return true
    }

    private fun extractVersion(s: String): Pair<String, String?> {
        for (re in versionPatterns) {
            val m = re.find(s) ?: continue
            val hasV = m.groupValues[1].isNotBlank()
            var ver = m.groupValues[2].trimEnd('.', '-', '_', ' ').trimStart('-', '_', ' ')
            // If capture starts with a leading dot (from "O.X.X" → ".X.X"), prepend "0".
            if (ver.startsWith('.')) ver = "0$ver"
            // Strip platform suffix (e.g. "1.0-pc" -> "1.0").
            ver = trailingJunk.replace(ver, "").trimEnd('.', '-', '_', ' ')
            if (!looksLikeVersion(ver, hasV)) continue
            val label = s.substring(0, m.range.first).trim().trim('-', '_', ' ', '[', '(')
            return (label.ifBlank { s }) to ver
        }
        return s to null
    }
}
