package com.example.f95updater

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Runtime application configuration. Holds the URLs the app uses to talk to its backend
 * services (catalog, self-update, optional crash/log upload).
 *
 * Resolution order:
 *   1. <files>/app_config.json  (user-imported config — overrides everything)
 *   2. Compiled defaults  (whatever was baked into this build of the APK)
 *
 * The public release ships with defaults that point at the public asset host (e.g. GitHub
 * Releases) and crashUploadUrl = null, which disables the crash/log upload UI.
 *
 * Personal/developer builds can either be compiled with a different default, or — more
 * conveniently — install the public APK and then drop their own app_config.json via the
 * "Import config" menu item. The Import action reads the picked file and copies it into
 * <files>/app_config.json where it overrides defaults from then on.
 */
@Serializable
data class AppConfig(
    val catalogUrl: String = DEFAULT_CATALOG_URL,
    val catalogIndexUrl: String = DEFAULT_CATALOG_INDEX_URL,
    val labelsUrl: String = DEFAULT_LABELS_URL,
    /** Backwards-compatible primary version feed. Use [versionInfoUrls] to add more feeds. */
    val versionInfoUrl: String = DEFAULT_VERSION_INFO_URL,
    /**
     * Additional version.json endpoints. The updater checks every non-blank URL
     * from [versionInfoUrl] + this list and uses the response with the highest
     * versionCode, so one config can watch both public and private/dev feeds.
     */
    val versionInfoUrls: List<String> = emptyList(),
    /** Optional. If set, the in-app updater follows this URL for the APK; otherwise it
     *  trusts AppUpdateInfo.apkUrl as returned by version.json. */
    val apkUrlOverride: String? = null,
    /** Base URL for crash/log uploads. If blank/null, upload UI is hidden — the user can
     *  still save logs locally to the Documents folder. */
    val crashUploadBaseUrl: String? = null,
    /** Optional query string appended to upload URLs. */
    val crashUploadAuthQuery: String? = null,
    /** URL of the support/community page the About screen links to. */
    val supportThreadUrl: String = DEFAULT_SUPPORT_URL,
    /** URL for bug reports and feature requests. */
    val issueReportUrl: String = DEFAULT_ISSUES_URL,
    /** Optional donation URL (Buy Me a Coffee, Ko-fi, etc.). If blank, donate button is hidden. */
    val donationUrl: String = DEFAULT_DONATION_URL,
    /** Stripe Payment Link for direct card / wallet donations. Optional. If both this
     *  and [donationUrl] are set, the in-app Support dialog shows both options. */
    val stripeDonationUrl: String = DEFAULT_STRIPE_DONATION_URL,
    /** When true the Help submenu exposes Save logs / Verbose toggle / Upload UI.
     *  Default false to keep the menu lean for end users; flip this in your
     *  local `app_config.json` (auto-loaded from Documents on startup) to keep
     *  diagnostics available for development. */
    val diagnosticsEnabled: Boolean = false,
) {
    val hasCrashUpload: Boolean
        get() = !crashUploadBaseUrl.isNullOrBlank()

    val effectiveVersionInfoUrls: List<String>
        get() = (listOf(versionInfoUrl.takeIf { it.isNotBlank() } ?: DEFAULT_VERSION_INFO_URL) + versionInfoUrls)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    val effectiveSupportThreadUrl: String
        get() {
            val trimmed = supportThreadUrl.trim()
            return trimmed
                .takeUnless { it.isBlank() || F95UrlParser.extractThreadId(it) == LEGACY_F95_UPDATER_THREAD_ID }
                ?: DEFAULT_SUPPORT_URL
        }

    companion object {
        // Public release defaults.
        //
        // - APK + version.json live on the app release/update path.
        // - catalog assets live on the fixed GitHub Release tag "catalog"; the backend crawler
        //   replaces those assets on refresh while keeping stable public URLs.
        const val DEFAULT_CATALOG_URL =
            "https://github.com/AdvancedAppCreator/adult-game-manager-releases/releases/download/catalog/catalog.json.gz"
        const val DEFAULT_CATALOG_INDEX_URL =
            "https://github.com/AdvancedAppCreator/adult-game-manager-releases/releases/download/catalog/catalogs-index-v2.json"
        const val DEFAULT_LABELS_URL =
            "https://github.com/AdvancedAppCreator/adult-game-manager-releases/releases/download/catalog/labels-v2.json"
        const val DEFAULT_VERSION_INFO_URL =
            "https://github.com/AdvancedAppCreator/adult-game-manager-releases/releases/download/app/version.json"
        /** The in-app About support link. */
        private const val LEGACY_F95_UPDATER_THREAD_ID = 299985
        const val DEFAULT_SUPPORT_URL =
            "https://f95zone.to/threads/300548/"
        const val DEFAULT_RELEASES_URL =
            "https://github.com/AdvancedAppCreator/adult-game-manager-releases/releases/download/app/AdultGameManager-latest-release.apk"
        const val DEFAULT_HELP_URL =
            "https://advancedappcreator.github.io/adult-game-manager-releases/"
        const val DEFAULT_ISSUES_URL =
            "https://github.com/AdvancedAppCreator/adult-game-manager-releases/issues"
        const val DEFAULT_DONATION_URL = ""
        const val DEFAULT_STRIPE_DONATION_URL = ""
    }
}

object AppConfigStore {
    private const val FILE_NAME = "app_config.json"
    private const val BACKUP_FOLDER = "AdultGameManager"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    // Reactive state. UI collects this and recomposes on every change (import,
    // drop-in pickup, clear). Non-UI callers use `current(context)` for a snapshot.
    private val _flow = MutableStateFlow(AppConfig())
    /** Observable config. Composables: `val cfg by AppConfigStore.flow.collectAsState()` */
    val flow: StateFlow<AppConfig> = _flow.asStateFlow()

    @Volatile private var initialized: Boolean = false

    private fun privateFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * Returns the current effective config. On first call, performs the synchronous
     * load (drop-in scan, then private file). Subsequent calls return the cached
     * StateFlow value.
     *
     * Resolution:
     *   1. Drop-in: well-known public paths (app-external dir first, then
     *      Documents/AdultGameManager, Download/AdultGameManager, Download). If found AND newer
     *      than the private copy, copy it into the private location.
     *   2. Read private config from <files>/app_config.json (working copy).
     *   3. Otherwise, compiled defaults.
     *
     * The UI observes `flow` and recomposes automatically on Import / drop-in pickup,
     * so users no longer need to restart the app.
     */
    fun current(context: Context): AppConfig {
        if (initialized) return _flow.value
        synchronized(this) {
            if (initialized) return _flow.value
            val cfg = loadFromDisk(context)
            _flow.value = cfg
            initialized = true
            return cfg
        }
    }

    /** Reactive accessor for Composables. Triggers the initial load if needed. */
    fun observe(context: Context): StateFlow<AppConfig> {
        current(context)
        return flow
    }

    /** Force a re-scan from disk (drop-in + private) and push to the flow. */
    suspend fun reload(context: Context): AppConfig = withContext(Dispatchers.IO) {
        val cfg = loadFromDisk(context)
        _flow.value = cfg
        initialized = true
        cfg
    }

    private fun loadFromDisk(context: Context): AppConfig {
        val priv = privateFile(context)
        // 1. Try drop-in first
        runCatching {
            val dropIn = findDropInConfig(context)
            if (dropIn != null) {
                val privMtime = if (priv.exists()) priv.lastModified() else 0L
                val dropMtime = dropIn.lastModified()
                val text = dropIn.readText()
                val privateText = if (priv.exists()) runCatching { priv.readText() }.getOrNull() else null
                if (!priv.exists() || dropMtime > privMtime || text != privateText) {
                    val parsed = json.decodeFromString(AppConfig.serializer(), text)
                    logConfigSummary("Parsed drop-in", parsed)
                    priv.writeText(text)
                    runCatching { priv.setLastModified(dropMtime) }
                    AppLog.i("AppConfig", "Auto-imported drop-in config from ${dropIn.absolutePath}")
                }
            }
        }.onFailure { AppLog.w("AppConfig", "Drop-in scan failed (ignored)", it) }

        // 2. Private override
        if (priv.exists()) {
            val loaded = runCatching { json.decodeFromString(AppConfig.serializer(), priv.readText()) }
                .onSuccess { logConfigSummary("Loaded private", it) }
                .onFailure { AppLog.w("AppConfig", "Failed to parse private $FILE_NAME - using defaults", it) }
                .getOrNull()
            return loaded ?: AppConfig().also { logConfigSummary("Loaded defaults after private parse failure", it) }
        }
        return AppConfig().also { logConfigSummary("Loaded defaults", it) }
    }

    private fun logConfigSummary(source: String, config: AppConfig) {
        AppLog.i(
            "AppConfig",
            "$source config summary: diagnosticsEnabled=${config.diagnosticsEnabled} " +
                "hasCrashUpload=${config.hasCrashUpload} " +
                "crashUploadBaseUrlSet=${!config.crashUploadBaseUrl.isNullOrBlank()} " +
                "crashUploadAuthQuerySet=${!config.crashUploadAuthQuery.isNullOrBlank()} " +
                "versionFeeds=${config.effectiveVersionInfoUrls.size}",
        )
    }

    /** Marks state stale so the next current() call re-reads from disk. */
    fun invalidate() { initialized = false }

    /**
     * Look for a drop-in app_config.json in well-known public locations.
     * Searches in priority order, with verbose logging.
     */
    private fun findDropInConfig(context: Context): File? {
        AppLog.d("AppConfig", "Scanning for drop-in $FILE_NAME...")
        val tried = mutableListOf<String>()
        // 0. App's own external dir (always readable; works in Secure Folder).
        //    Path: /storage/emulated/<user>/Android/data/<pkg>/files/app_config.json
        runCatching {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val c = File(extDir, FILE_NAME)
                tried.add(c.absolutePath)
                logFileCandidate(c)
                if (c.exists() && c.canRead()) {
                    AppLog.i("AppConfig", "Drop-in found at ${c.absolutePath}")
                    return c
                }
            }
        }
        // 1. Direct File access (legacy + Downloads dir on most devices)
        @Suppress("DEPRECATION")
        val directCandidates = listOf(
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS), "$BACKUP_FOLDER/$FILE_NAME"),
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS), FILE_NAME),
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "$BACKUP_FOLDER/$FILE_NAME"),
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), FILE_NAME),
        )
        for (c in directCandidates) {
            tried.add(c.absolutePath)
            runCatching {
                logFileCandidate(c)
                if (c.exists() && c.canRead()) {
                    AppLog.i("AppConfig", "Drop-in found at ${c.absolutePath}")
                    return c
                }
            }
        }
        // 2. MediaStore lookup (API 29+ scoped storage)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH,
            )
            val mediaStoreCandidates = listOf(
                "${android.os.Environment.DIRECTORY_DOCUMENTS}/$BACKUP_FOLDER/",
                "${android.os.Environment.DIRECTORY_DOCUMENTS}/",
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/$BACKUP_FOLDER/",
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/",
            )
            for (relativePath in mediaStoreCandidates) {
                tried.add("MediaStore: $relativePath")
                runCatching {
                    logMediaStoreFolder(context, relativePath)
                    resolver.query(
                        collection,
                        projection,
                        "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                            "${android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
                        arrayOf(FILE_NAME, relativePath),
                        null,
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val id = c.getLong(0)
                            val displayName = c.getString(1)
                            val relPath = c.getString(2)
                            val uri = android.content.ContentUris.withAppendedId(collection, id)
                            // Copy to a temp file so callers can use File API.
                            val tmp = File(context.cacheDir, "dropin_app_config.json")
                            resolver.openInputStream(uri)?.use { input ->
                                tmp.outputStream().use { input.copyTo(it) }
                            }
                            if (tmp.exists() && tmp.length() > 0) {
                                AppLog.i("AppConfig", "Drop-in found via MediaStore: $relPath$displayName (${tmp.length()} bytes)")
                                return tmp
                            }
                        }
                    }
                }.onFailure { AppLog.w("AppConfig", "MediaStore drop-in lookup failed for $relativePath", it) }
            }
        }
        AppLog.d("AppConfig", "No drop-in config found. Checked: ${tried.joinToString(" | ")}")
        return null
    }

    private fun logFileCandidate(candidate: File) {
        runCatching {
            AppLog.i(
                "AppConfig",
                "Direct candidate ${candidate.absolutePath}: exists=${candidate.exists()} " +
                    "canRead=${candidate.canRead()} isFile=${candidate.isFile} length=${candidate.length()}",
            )
            val parent = candidate.parentFile ?: return
            val entries = parent.listFiles()
            val summary = when {
                entries == null -> "unreadable or unavailable"
                entries.isEmpty() -> "empty"
                else -> entries
                    .sortedBy { it.name.lowercase() }
                    .take(50)
                    .joinToString(", ") {
                        "${it.name}${if (it.isDirectory) "/" else ""}(${it.length()}b,read=${it.canRead()})"
                    } + if (entries.size > 50) ", ... +${entries.size - 50} more" else ""
            }
            AppLog.i("AppConfig", "Direct folder listing ${parent.absolutePath}: $summary")
        }.onFailure { AppLog.w("AppConfig", "Direct candidate logging failed for ${candidate.absolutePath}", it) }
    }

    private fun logMediaStoreFolder(context: Context, relativePath: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
            android.provider.MediaStore.Files.FileColumns.SIZE,
            android.provider.MediaStore.Files.FileColumns.MIME_TYPE,
        )
        runCatching {
            val entries = mutableListOf<String>()
            resolver.query(
                collection,
                projection,
                "${android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                "${android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && entries.size < 50) {
                    val name = cursor.getString(0)
                    val size = cursor.getLong(1)
                    val mime = cursor.getString(2)
                    entries += "$name(${size}b,${mime ?: "no-mime"})"
                }
            }
            val summary = if (entries.isEmpty()) "empty or not visible" else entries.joinToString(", ")
            AppLog.i("AppConfig", "MediaStore folder listing $relativePath: $summary")
        }.onFailure { AppLog.w("AppConfig", "MediaStore folder listing failed for $relativePath", it) }
    }

    /** Write a config to the app-private file. Pushes to the flow so UI updates immediately. */
    suspend fun savePrivate(context: Context, config: AppConfig) = withContext(Dispatchers.IO) {
        privateFile(context).writeText(json.encodeToString(config))
        _flow.value = config
        initialized = true
    }

    /** Delete the private override, restoring compiled defaults. UI updates immediately. */
    suspend fun clearPrivate(context: Context) = withContext(Dispatchers.IO) {
        runCatching { privateFile(context).delete() }
        _flow.value = AppConfig()
        initialized = true
    }

    /**
     * Export the current effective config to:
     *   1. <files>/app_config.json (private — also acts as the override)
     *   2. /Documents/AdultGameManager/app_config.json (public, survives uninstall)
     * @return human-readable summary describing where it went.
     */
    suspend fun exportToDocuments(context: Context): String = withContext(Dispatchers.IO) {
        val config = current(context)
        val text = json.encodeToString(config)

        // 1) write/refresh private copy
        runCatching { privateFile(context).writeText(text) }
            .onFailure { AppLog.w("AppConfig", "private write failed", it) }

        // 2) public copy under Documents/AdultGameManager/
        val publicResult = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, FILE_NAME, "application/json", text, BACKUP_FOLDER)
            } else {
                @Suppress("DEPRECATION")
                val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val dir = File(docs, BACKUP_FOLDER).apply { mkdirs() }
                File(dir, FILE_NAME).also { it.writeText(text) }.absolutePath
            }
        }
        publicResult.fold(
            onSuccess = { path -> "Saved to private storage and $path" },
            onFailure = { "Saved to private storage only (Documents copy failed: ${it.message})" }
        )
    }

    /** Import a config from an arbitrary content URI (e.g., SAF-picked file). Pushes to flow. */
    suspend fun importFromUri(context: Context, uri: Uri): Result<AppConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not open file")
            val parsed = json.decodeFromString(AppConfig.serializer(), text)
            privateFile(context).writeText(json.encodeToString(parsed))
            _flow.value = parsed
            initialized = true
            parsed
        }
    }

    /** Public helper for save-logs-locally to use the same MediaStore destination. */
    suspend fun writeLogToDocuments(context: Context, fileName: String, content: String): String =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, fileName, "text/plain", content, "$BACKUP_FOLDER/logs")
            } else {
                @Suppress("DEPRECATION")
                val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val dir = File(docs, "$BACKUP_FOLDER/logs").apply { mkdirs() }
                File(dir, fileName).also { it.writeText(content) }.absolutePath
            }
        }

    suspend fun writeBytesToDocuments(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        subFolder: String,
    ): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeBytesViaMediaStore(context, fileName, mimeType, bytes, subFolder)
        } else {
            @Suppress("DEPRECATION")
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = File(docs, subFolder).apply { mkdirs() }
            File(dir, fileName).also { it.writeBytes(bytes) }.absolutePath
        }
    }

    /** MediaStore writer for Q+; returns the user-facing path string. */
    private fun writeViaMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
        subFolder: String,
    ): String {
        val relPath = "${Environment.DIRECTORY_DOCUMENTS}/$subFolder"
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        // Try to find an existing entry to overwrite, so we don't accumulate duplicates.
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
            arrayOf("$relPath/", fileName),
            null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        val uri = if (existing != null) {
            android.content.ContentUris.withAppendedId(collection, existing)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relPath)
            }
            resolver.insert(collection, values) ?: error("MediaStore.insert returned null")
        }
        resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
            ?: error("openOutputStream returned null")
        return "Documents/$subFolder/$fileName"
    }

    private fun writeBytesViaMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        subFolder: String,
    ): String {
        val relPath = "${Environment.DIRECTORY_DOCUMENTS}/$subFolder"
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
            arrayOf("$relPath/", fileName),
            null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        val uri = if (existing != null) {
            android.content.ContentUris.withAppendedId(collection, existing)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relPath)
            }
            resolver.insert(collection, values) ?: error("MediaStore.insert returned null")
        }
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("openOutputStream returned null")
        return "Documents/$subFolder/$fileName"
    }
}
