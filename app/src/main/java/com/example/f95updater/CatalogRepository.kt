package com.example.f95updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.GZIPInputStream

private val Context.catalogMetaStore by preferencesDataStore("catalog_meta")
private val ETAG_KEY     = stringPreferencesKey("catalog_etag")
private val LASTMOD_KEY  = stringPreferencesKey("catalog_lastmod")
private val LABELS_ETAG_KEY    = stringPreferencesKey("labels_etag")
private val LABELS_LASTMOD_KEY = stringPreferencesKey("labels_lastmod")
private val LASTSYNC_KEY = longPreferencesKey("catalog_last_sync_ms")
private val SIZE_KEY     = longPreferencesKey("catalog_size_bytes")
private val COUNT_KEY    = longPreferencesKey("catalog_count")

@Serializable
private data class CatalogEnvelope(
    val generated_at: String = "",
    val source: String = "",
    val mode: String = "",
    val count: Long = 0L,
    val games: Map<String, CatalogGame> = emptyMap(),
)

data class CatalogTitleMatch(
    val game: CatalogGame,
    val via: String,
)

data class CatalogAmbiguousTitleMatch(
    val candidates: List<CatalogGame>,
    val via: String,
)

data class CatalogSearchResult(
    val game: CatalogGame,
    val translatedTitle: String? = null,
)

/** Metadata for the validated, unified catalog database. */
data class CatalogDatabaseInfo(
    val signature: String,
    val totalCount: Int,
    val tagLabels: List<String>,
)

private fun catalogChoicesForLog(games: List<CatalogGame>): String =
    games.take(12).joinToString(prefix = "[", postfix = "]") {
        "${it.source}:${it.sourceId ?: it.thread_id}:'${it.title}' " +
            "norm='${CatalogRepository.normalizeTitle(it.title)}'"
    } + if (games.size > 12) " +${games.size - 12} more" else ""

sealed class CatalogSyncResult {
    data class Updated(val gameCount: Int, val sizeBytes: Long) : CatalogSyncResult()
    object NotModified : CatalogSyncResult()
    data class Error(val message: String) : CatalogSyncResult()
}

/** Manages the cached source catalogs (and the labels file). Uses ETag/Last-Modified
 *  so subsequent syncs return 304 with no payload when unchanged. */
class CatalogRepository(private val context: Context) : AutoCloseable {

    companion object {
        /**
         * Serializes catalog-database preparation across EVERY [CatalogRepository] instance in
         * the process. The UI holds one instance and [CatalogTranslationWorker] creates another,
         * yet both target the same catalog_rows.db file. Without a process-global lock the two
         * instances rebuild that database concurrently and contend on catalog_rows.db.tmp,
         * inflating each source file from well under a second to 25-40s and never completing
         * within the load watchdog. With the lock, the second caller waits and then finds the
         * freshly built database valid, skipping the redundant rebuild entirely.
         */
        private val catalogDatabasePreparationMutex = Mutex()

        /** Normalize a title or app label for matching: lowercase, Unicode alphanumeric only. */
        fun normalizeTitle(s: String): String =
            s.lowercase().filter(Char::isLetterOrDigit)

        /** Tokenize: lowercase, split on non-alphanumeric, keep tokens of length >= 3. */
        fun tokenize(s: String): List<String> =
            s.lowercase().split(NON_ALNUM_SPLIT).filter { it.length >= 3 }

        private val simpleNumberWords = mapOf(
            "zero" to 0,
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14,
            "fifteen" to 15,
            "sixteen" to 16,
            "seventeen" to 17,
            "eighteen" to 18,
            "nineteen" to 19,
        )
        private val tensNumberWords = mapOf(
            "twenty" to 20,
            "thirty" to 30,
            "forty" to 40,
            "fifty" to 50,
            "sixty" to 60,
            "seventy" to 70,
            "eighty" to 80,
            "ninety" to 90,
        )
        private val embeddedNumberPhrases: List<Pair<String, Int>> =
            (simpleNumberWords + tensNumberWords + tensNumberWords.flatMap { (tenWord, tenValue) ->
                simpleNumberWords.filterValues { it in 1..9 }.map { (unitWord, unitValue) ->
                    tenWord + unitWord to tenValue + unitValue
                }
            }.toMap())
                .toList()
                .sortedByDescending { it.first.length }

        private data class ParsedNumber(val value: Int, val nextIndex: Int)

        private fun parseSeparatedNumberWords(tokens: List<String>, start: Int): ParsedNumber? {
            val first = tokens.getOrNull(start) ?: return null
            if (first in tensNumberWords) {
                val tens = tensNumberWords.getValue(first)
                val unit = simpleNumberWords[tokens.getOrNull(start + 1)]
                return if (unit != null && unit in 1..9) ParsedNumber(tens + unit, start + 2)
                else ParsedNumber(tens, start + 1)
            }
            val simple = simpleNumberWords[first] ?: return null
            if (tokens.getOrNull(start + 1) != "hundred") return ParsedNumber(simple, start + 1)
            var value = simple * 100
            var next = start + 2
            if (tokens.getOrNull(next) == "and") next++
            parseSeparatedNumberWords(tokens, next)?.let { tail ->
                if (tail.value in 1..99) {
                    value += tail.value
                    next = tail.nextIndex
                }
            }
            return ParsedNumber(value, next)
        }

        private fun replaceEmbeddedLeadingNumberWord(token: String): String {
            if (token.any { it.isDigit() }) return token
            val match = embeddedNumberPhrases.firstOrNull { (phrase, _) ->
                token.length > phrase.length && token.startsWith(phrase)
            } ?: return token
            return match.second.toString() + token.removePrefix(match.first)
        }

        internal fun numberEquivalentTokens(tokens: List<String>): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                val parsed = parseSeparatedNumberWords(tokens, i)
                if (parsed != null) {
                    out += parsed.value.toString()
                    i = parsed.nextIndex
                } else {
                    out += replaceEmbeddedLeadingNumberWord(tokens[i])
                    i++
                }
            }
            return out
        }

        internal fun numberEquivalentKey(s: String): String =
            compactTitleKey(numberEquivalentTokens(rawTitleTokens(s).dropWhile { it in articlesToDrop }))

        /** Trailing-version detector. Matches tokens that are version-like:
         *   - "v" alone (followed by version digits in a separate token)
         *   - "v123" / "v1.2.3" / "v0.04a"
         *   - bare digit runs: "123", "1.2", "0.4a"
         */
        private val VERSION_TOKEN = Regex(
            "^(?:v\\d.*|v|\\d+[a-z]?)$",
            RegexOption.IGNORE_CASE,
        )

        private val APP_VERSION_TOKEN = Regex(
            "^(?:v|o|v?\\d[\\da-z.]*|day\\d+|episode\\d+|ep\\d+|ch\\d+|chapter\\d+)$",
            RegexOption.IGNORE_CASE,
        )

        // Compiled once and reused: these run per catalog entry during index builds, where
        // recompiling Unicode-property patterns dominated build time (minutes for ~74k rows).
        private val NON_ALNUM_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val BRACKET_CONTENT = Regex("\\[[^\\]]*\\]")
        private val PAREN_CONTENT = Regex("\\([^)]*\\)")
        private val LOWER_UPPER_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        private val UPPER_UPPERLOWER_BOUNDARY = Regex("([A-Z])([A-Z][a-z])")
        private val RJ_PRODUCT_CODE = Regex("^rj\\d+$")

        /** Platform/release tokens commonly tacked onto folder names. */
        private val PLATFORM_TOKENS = setOf(
            "pc", "win", "win32", "win64", "windows", "android",
            "mac", "osx", "linux",
            "free", "full", "pro", "premium", "patreon", "public", "release",
            "setup", "installer", "build", "demo", "compressed", "pat",
        )

        private val SAFE_PREFIX_SUFFIX_TOKENS = setOf(
            "lite", "dark", "edition", "mod", "final", "extra", "bugfix",
            "beta", "patreon", "compressed", "se", "public",
        )

        /**
         * Break a title into an ordered list of meaningful "words":
         *   1. Strip [bracketed] / (parenthetical) tags
         *   2. CamelCase split so "MyGame" -> "My Game", "AngelInLA" -> "Angel In LA"
         *   3. Lowercase + split on non-alphanumeric
         *   4. Drop trailing version-like and platform tokens
         *      (e.g., "Game v1.2.3-pc" -> ["game"])
         */
        fun titleWords(s: String): List<String> {
            val tokens = rawTitleTokens(s)
            // Strip trailing version+platform tokens: once we see a version token,
            // skip any subsequent version or platform tokens until a "real" token resumes.
            val cleaned = mutableListOf<String>()
            var inVersionTail = false
            for (t in tokens) {
                val isVersion = VERSION_TOKEN.matches(t)
                val isPlatform = t in PLATFORM_TOKENS
                if (isVersion) { inVersionTail = true; continue }
                if (inVersionTail && isPlatform) continue
                inVersionTail = false
                cleaned.add(t)
            }
            // Also strip leading "the" / "a" / "an" (English articles) so we tolerate
            // small differences (some catalog titles have them, some don't).
            return numberEquivalentTokens(cleaned.dropWhile { it in articlesToDrop })
        }

        internal fun rawTitleTokens(s: String): List<String> {
            var x = s
                .replace(BRACKET_CONTENT, " ")
                .replace(PAREN_CONTENT, " ")
            x = x.replace(LOWER_UPPER_BOUNDARY, "$1 $2")
            x = x.replace(UPPER_UPPERLOWER_BOUNDARY, "$1 $2")
            return x.lowercase()
                .split(NON_ALNUM_SPLIT)
                .filter { it.isNotBlank() }
        }

        private fun compactTitleKey(words: List<String>): String {
            val out = mutableListOf<String>()
            var i = 0
            while (i < words.size) {
                if (words[i].length == 1 && words[i][0].isLetter()) {
                    val start = i
                    while (i < words.size && words[i].length == 1 && words[i][0].isLetter()) i++
                    out += words.subList(start, i).joinToString("")
                } else {
                    out += words[i]
                    i++
                }
            }
            return out.joinToString("")
        }

        fun catalogCleanKey(s: String): String =
            compactTitleKey(numberEquivalentTokens(rawTitleTokens(s).dropWhile { it in articlesToDrop }))

        fun appCleanKey(s: String): String {
            val tokens = rawTitleTokens(s)
            val cleaned = mutableListOf<String>()
            var inVersionTail = false
            for (i in tokens.indices) {
                val t = tokens[i]
                val nextLooksNumeric = tokens.getOrNull(i + 1)?.firstOrNull()?.isDigit() == true
                val isVersion = APP_VERSION_TOKEN.matches(t) && !(t == "o" && !nextLooksNumeric)
                val isProductCode = t.matches(RJ_PRODUCT_CODE)
                val isPlatform = t in PLATFORM_TOKENS || t in SAFE_PREFIX_SUFFIX_TOKENS
                if (isVersion || isProductCode) {
                    inVersionTail = true
                    continue
                }
                if (inVersionTail && (isPlatform || t.length <= 2 || APP_VERSION_TOKEN.matches(t))) continue
                inVersionTail = false
                if (!isPlatform) cleaned.add(t)
            }
            return compactTitleKey(numberEquivalentTokens(cleaned.dropWhile { it in articlesToDrop }))
        }

        private val articlesToDrop = setOf("the", "a", "an")
        /** Visible to [CatalogTextSearchIndex] so all flows use the same acronym derivation. */
        internal fun acronym(words: List<String>): String =
            words.mapNotNull { it.firstOrNull() }.joinToString("")
    }

    private val catalogUrl: String get() = AppConfigStore.current(context).catalogUrl
    private val catalogIndexUrl: String get() = AppConfigStore.current(context).catalogIndexUrl
    private val labelsUrl: String get() = AppConfigStore.current(context).labelsUrl

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val detailsRepository = CatalogDetailsRepository(context, client)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val syncMutex = Mutex()
    private val mutableContentRevision = MutableStateFlow(0L)
    val contentRevision: StateFlow<Long> = mutableContentRevision.asStateFlow()

    private val catalogFile: File get() = File(context.filesDir, "catalog.json")
    private val labelsFile:  File get() = File(context.filesDir, "labels.json")
    private val sourceIndexFile: File get() = File(context.filesDir, "source_catalog_index.json")
    private val sourceCatalogDir: File get() = File(context.filesDir, "source_catalogs")
    private val serverSearchDatabaseFile: File
        get() = File(context.filesDir, "catalog_server_search.db")
    private val serverSearchManifestFile: File
        get() = File(context.filesDir, "catalog_server_search.json")
    private val nameSearchDatabaseFile: File get() = File(context.filesDir, "catalog_name_search.db")
    private val preparedCatalogIndexFile: File
        get() = File(context.filesDir, "catalog_prepared_index.bin.gz")
    internal val catalogRowsDatabaseFile: File
        get() = File(context.filesDir, "catalog_rows.db")

    suspend fun lastSyncMs(): Long = context.catalogMetaStore.data.map { it[LASTSYNC_KEY] ?: 0L }.first()
    suspend fun cachedCount(): Long = context.catalogMetaStore.data.map { it[COUNT_KEY] ?: 0L }.first()
    suspend fun cachedSize():  Long = context.catalogMetaStore.data.map { it[SIZE_KEY] ?: 0L }.first()

    /** Downloads (or re-validates) the catalog. Honors If-None-Match / If-Modified-Since.
     *  If the server returns 304 but the local file is missing or empty (e.g. because
     *  we changed where we store it between app versions), force a fresh download. */
    suspend fun sync(): CatalogSyncResult = syncMutex.withLock { syncUnlocked() }

    private suspend fun syncUnlocked(): CatalogSyncResult = withContext(Dispatchers.IO) {
        val prefs = context.catalogMetaStore.data.first()
        val haveLocal = catalogFile.exists() && catalogFile.length() > 0L
        val builder = Request.Builder().url(catalogUrl)
        if (haveLocal) {
            prefs[ETAG_KEY]?.let { builder.header("If-None-Match", it) }
            prefs[LASTMOD_KEY]?.let { builder.header("If-Modified-Since", it) }
        }

        val result = runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                when (resp.code) {
                    304 -> {
                        context.catalogMetaStore.edit { it[LASTSYNC_KEY] = System.currentTimeMillis() }
                        mergeSourceSync(CatalogSyncResult.NotModified)
                    }
                    200 -> {
                        val bytes = resp.body?.bytes() ?: error("empty body")
                        catalogFile.writeBytes(bytes)
                        invalidateIndex()
                        val etag = resp.header("ETag")
                        val lm   = resp.header("Last-Modified")
                        val count = countGames(bytes)
                        context.catalogMetaStore.edit {
                            if (!etag.isNullOrBlank()) it[ETAG_KEY] = etag
                            if (!lm.isNullOrBlank())   it[LASTMOD_KEY] = lm
                            it[LASTSYNC_KEY] = System.currentTimeMillis()
                            it[SIZE_KEY]     = bytes.size.toLong()
                            it[COUNT_KEY]    = count.toLong()
                        }
                        mergeSourceSync(CatalogSyncResult.Updated(count, bytes.size.toLong()))
                    }
                    else -> mergeSourceSync(CatalogSyncResult.Error("HTTP ${resp.code}"))
                }
            }
        }.getOrElse { CatalogSyncResult.Error(it.message ?: "unknown") }
        if (result is CatalogSyncResult.Updated) {
            mutableContentRevision.value = mutableContentRevision.value + 1L
            CatalogTranslationWork.scheduleAfterCatalogSync(context)
        }
        result
    }

    private fun mergeSourceSync(legacyResult: CatalogSyncResult): CatalogSyncResult {
        val labelsChanged = syncLabels()
        if (labelsChanged) invalidateIndex()
        val sourceResult = syncSourceCatalogsBlocking()
        val searchResult = syncCompiledSearchDatabaseBlocking()
        if (searchResult is CatalogSyncResult.Error) {
            AppLog.e("Catalog", "Compiled search database sync failed: ${searchResult.message}")
        }
        if (sourceResult is CatalogSyncResult.Error) {
            AppLog.e("Catalog", "Source catalog sync failed: ${sourceResult.message}")
            return legacyResult
        }
        if (sourceResult is CatalogSyncResult.Updated || searchResult is CatalogSyncResult.Updated) {
            val legacyCount = (legacyResult as? CatalogSyncResult.Updated)?.gameCount ?: 0
            val legacySize = (legacyResult as? CatalogSyncResult.Updated)?.sizeBytes ?: 0L
            val sourceCount = (sourceResult as? CatalogSyncResult.Updated)?.gameCount ?: 0
            val sourceSize = (sourceResult as? CatalogSyncResult.Updated)?.sizeBytes ?: 0L
            val searchSize = (searchResult as? CatalogSyncResult.Updated)?.sizeBytes ?: 0L
            return CatalogSyncResult.Updated(
                legacyCount + sourceCount,
                legacySize + sourceSize + searchSize,
            )
        }
        if (labelsChanged && legacyResult is CatalogSyncResult.NotModified) {
            return CatalogSyncResult.Updated(0, labelsFile.length())
        }
        return legacyResult
    }

    private fun syncCompiledSearchDatabaseBlocking(): CatalogSyncResult {
        val manifestUrl = AppConfigStore.current(context).searchDbUrl.trim()
        if (manifestUrl.isBlank()) return CatalogSyncResult.NotModified
        return runCatching {
            val manifestBytes = client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { response ->
                if (!response.isSuccessful) error("manifest HTTP ${response.code}")
                response.body?.bytes() ?: error("empty search database manifest")
            }
            val manifest = json.decodeFromString(CatalogSearchDbManifest.serializer(), manifestBytes.decodeToString())
            check(manifest.searchSchemaVersion == "9") {
                "unsupported search schema ${manifest.searchSchemaVersion}"
            }
            check(manifest.synopsisSchemaVersion == "1") {
                "unsupported synopsis schema ${manifest.synopsisSchemaVersion}"
            }
            check(manifest.url.isNotBlank()) { "search database URL is blank" }
            val cachedManifest = if (serverSearchManifestFile.isFile) {
                runCatching {
                    json.decodeFromString(
                        CatalogSearchDbManifest.serializer(),
                        serverSearchManifestFile.readText(),
                    )
                }.getOrNull()
            } else {
                null
            }
            if (cachedManifest?.databaseSha256 == manifest.databaseSha256 &&
                CatalogSynopsisDatabase.isCompatible(serverSearchDatabaseFile)
            ) {
                if (!serverSearchManifestFile.readBytes().contentEquals(manifestBytes)) {
                    serverSearchManifestFile.writeBytes(manifestBytes)
                }
                return@runCatching CatalogSyncResult.NotModified
            }

            val temporary = File(serverSearchDatabaseFile.parentFile, "${serverSearchDatabaseFile.name}.tmp")
            if (temporary.exists() && !temporary.delete()) {
                error("could not replace temporary search database")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val downloadClient = client.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
            try {
                downloadClient.newCall(Request.Builder().url(manifest.url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("database HTTP ${response.code}")
                    val body = response.body ?: error("empty search database")
                    openMaybeGzipped(body.byteStream()).use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                check(manifest.databaseSha256.isBlank() ||
                    actualSha.equals(manifest.databaseSha256, ignoreCase = true)
                ) {
                    "search database SHA-256 mismatch"
                }
                check(CatalogSynopsisDatabase.isCompatible(temporary)) {
                    "downloaded search database is incompatible"
                }
                replaceStandaloneDatabase(temporary, serverSearchDatabaseFile)
                serverSearchManifestFile.writeBytes(manifestBytes)
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
            CatalogSyncResult.Updated(0, manifest.sizeBytes)
        }.getOrElse { CatalogSyncResult.Error(it.message ?: "unknown search database error") }
    }

    private fun replaceStandaloneDatabase(temporary: File, target: File) {
        val backup = File(target.parentFile, "${target.name}.bak")
        if (backup.exists()) backup.delete()
        if (target.exists() && !target.renameTo(backup)) {
            error("could not replace catalog search database")
        }
        if (!temporary.renameTo(target)) {
            backup.renameTo(target)
            error("could not finalize catalog search database")
        }
        backup.delete()
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun syncSourceCatalogsBlocking(): CatalogSyncResult {
        val url = catalogIndexUrl.trim()
        if (url.isBlank()) return CatalogSyncResult.NotModified
        return runCatching {
            val indexBytes = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("index HTTP ${resp.code}")
                resp.body?.bytes() ?: error("empty source catalog index")
            }
            var contentChanged = replaceFileIfChanged(sourceIndexFile, indexBytes)
            val registry = indexBytes.inputStream().use { json.decodeFromStream<CatalogSourceRegistry>(it) }
            SourceRegistry.update(registry.catalogs)
            sourceCatalogDir.mkdirs()
            var totalBytes = indexBytes.size.toLong()
            var totalCount = 0
            // Files we intend to keep this sync. Anything else in the dir (a source that
            // became disabled, raised its minAppVersion above ours, or dropped out of the
            // registry entirely) is stale and must be deleted, or the unified database
            // would keep rendering it from a previously-synced file.
            val keepFiles = mutableSetOf<String>()
            for (catalog in registry.catalogs) {
                if (!catalog.enabled) continue
                if (!appVersionSatisfies(catalog.minAppVersion)) {
                    AppLog.i("Catalog", "Skipping source ${catalog.id}: requires app >= ${catalog.minAppVersion}")
                    continue
                }
                val catalogUrl = catalog.url.trim()
                if (catalogUrl.isBlank()) continue
                val bytes = client.newCall(Request.Builder().url(catalogUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("${catalog.id.sourceDisplayName} HTTP ${resp.code}")
                    resp.body?.bytes() ?: error("empty ${catalog.id.sourceDisplayName} catalog")
                }
                val fileName = "${catalog.id.lowercase()}.json"
                if (replaceFileIfChanged(File(sourceCatalogDir, fileName), bytes)) {
                    contentChanged = true
                }
                keepFiles += fileName
                totalBytes += bytes.size
                totalCount += catalog.count ?: countSourceEntries(bytes)
            }
            sourceCatalogDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.filterNot { it.name in keepFiles }
                ?.forEach {
                    if (it.delete()) contentChanged = true
                }
            if (contentChanged) {
                invalidateIndex()
                CatalogSyncResult.Updated(totalCount, totalBytes)
            } else {
                CatalogSyncResult.NotModified
            }
        }.getOrElse {
            CatalogSyncResult.Error(it.message ?: "unknown source catalog error")
        }
    }

    private fun syncLabels(): Boolean {
        val haveLocal = labelsFile.exists() && labelsFile.length() > 0L
        val prefs = runBlocking { context.catalogMetaStore.data.first() }
        val builder = Request.Builder().url(labelsUrl)
        if (haveLocal) {
            prefs[LABELS_ETAG_KEY]?.let { builder.header("If-None-Match", it) }
            prefs[LABELS_LASTMOD_KEY]?.let { builder.header("If-Modified-Since", it) }
        }
        return runCatching {
            var changed = false
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 200) {
                    resp.body?.bytes()?.let {
                        changed = replaceFileIfChanged(labelsFile, it)
                    }
                    val etag = resp.header("ETag")
                    val lm = resp.header("Last-Modified")
                    runBlocking {
                        context.catalogMetaStore.edit {
                            if (!etag.isNullOrBlank()) it[LABELS_ETAG_KEY] = etag
                            if (!lm.isNullOrBlank()) it[LABELS_LASTMOD_KEY] = lm
                        }
                    }
                    if (changed) cachedLabels = null
                }
                // 304: keep existing labels.json. Other codes: leave cache untouched.
            }
            changed
        }.onFailure { AppLog.e("Catalog", "label sync failed", it) }
            .getOrDefault(false)
    }

    private fun replaceFileIfChanged(file: File, bytes: ByteArray): Boolean {
        if (file.isFile && file.length() == bytes.size.toLong() &&
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var offset = 0
                var equal = true
                while (equal && offset < bytes.size) {
                    val read = input.read(buffer, 0, minOf(buffer.size, bytes.size - offset))
                    if (read <= 0) {
                        equal = false
                    } else {
                        for (index in 0 until read) {
                            if (buffer[index] != bytes[offset + index]) {
                                equal = false
                                break
                            }
                        }
                        offset += read
                    }
                }
                equal && offset == bytes.size && input.read() < 0
            }
        ) {
            return false
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return true
    }

    internal suspend fun catalogRowsSignature(): String = withContext(Dispatchers.IO) {
        val settings = CatalogPrefs.load(context)
        val targetLanguage = settings.translationTarget.trim().lowercase()
        // NOTE: the translation alias revision is deliberately NOT part of this signature.
        // Aliases are display/search-only translated titles written continuously by the
        // background CatalogTranslationWorker; folding them in used to invalidate the whole
        // catalog index on every worker write, forcing repeated full rebuilds of identical
        // source-derived content on catalog open and refresh. New aliases are now folded by an
        // explicit forced rebuild the worker runs once per pass (foldTranslatedTitles), off the
        // UI/refresh path. translateTitles + targetLanguage stay so toggling translation or
        // switching language still rebuilds.
        catalogDataSignature(
            "catalog-unified-v2:${CatalogTextSearchDatabaseStore.signatureGeneration}:" +
                "${settings.translateTitles}:$targetLanguage",
        )
    }

    /**
     * Ensures that catalog rows, filters, tags, and name search are present in one validated
     * database. Loading and synchronization are shared between callers but ordinary reads do
     * not take this preparation lock.
     */
    suspend fun prepareCatalogDatabase(): CatalogDatabaseInfo {
        AppLog.i("Catalog", "prepareCatalogDatabase: computing signature")
        val signature = catalogRowsSignature()
        AppLog.i("Catalog", "prepareCatalogDatabase: signature ready; ensuring + awaiting loader")
        ensureCatalogDatabaseSignature(signature)
        val info = catalogDatabaseLoader.get()
        AppLog.i("Catalog", "prepareCatalogDatabase: loader returned rows=${info.totalCount}")
        return info
    }

    @Volatile private var catalogBuildFailureReported = false
    @Volatile private var forceCatalogRebuild = false
    private val catalogPrepareTimeoutMs = 300_000L

    /** 0f..1f progress of an in-flight catalog index rebuild (1f when idle/complete). */
    private val _catalogBuildProgress = MutableStateFlow(1f)
    val catalogBuildProgress: StateFlow<Float> = _catalogBuildProgress.asStateFlow()

    private suspend fun prepareCatalogDatabaseNow(): CatalogDatabaseInfo =
        catalogDatabasePreparationMutex.withLock {
            try {
                withTimeout(catalogPrepareTimeoutMs) {
                    prepareCatalogDatabaseSerially()
                }.also { catalogBuildFailureReported = false }
            } catch (timeout: TimeoutCancellationException) {
                // Should not happen now that a full rebuild completes in seconds, but if a
                // prepare ever stalls we surface a real error instead of an endless spinner.
                // We do NOT purge the destination: only the throwaway .tmp is touched by a
                // cancelled build, so any previously valid catalog_rows.db is preserved.
                AppLog.e("Catalog", "Catalog preparation timed out", timeout)
                val error = IllegalStateException(
                    "Catalog preparation timed out after ${catalogPrepareTimeoutMs} ms",
                )
                if (!catalogBuildFailureReported) {
                    catalogBuildFailureReported = true
                    runCatching { CrashReporter.logCaught(context, "CatalogBuildTimeout", error) }
                }
                throw error
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Surface catalog-index build failures (e.g. SQLite disk I/O errors) as an
                // uploadable non-fatal so device-side diagnostics reach the crash feed. Report
                // at most once per failure episode to avoid spamming on retry.
                if (!catalogBuildFailureReported) {
                    catalogBuildFailureReported = true
                    runCatching { CrashReporter.logCaught(context, "CatalogBuild", error) }
                }
                throw error
            }
        }

    private suspend fun prepareCatalogDatabaseSerially(): CatalogDatabaseInfo {
        AppLog.i("Catalog", "prepareSerially: enter; computing signature")
        val signature = catalogRowsSignature()
        val databaseFile = catalogRowsDatabaseFile
        val forced = forceCatalogRebuild
        forceCatalogRebuild = false
        AppLog.i(
            "Catalog",
            "prepareSerially: validating existing DB (exists=${databaseFile.isFile} " +
                "bytes=${databaseFile.length()} forced=$forced)",
        )
        if (!forced && CatalogRowsDatabaseStore.isValid(databaseFile, signature)) {
            AppLog.i("Catalog", "prepareSerially: existing DB is valid; reusing")
            _catalogBuildProgress.value = 1f
            deleteLegacyCatalogIndexes()
            return catalogDatabaseInfo(databaseFile, signature)
        }

        AppLog.i("Catalog", "prepareSerially: DB invalid/missing; loading source index")
        _catalogBuildProgress.value = 0f
        try {
        val registry = sourceCatalogIndex()
        val totalEstimate = registry?.catalogs?.sumOf { it.count ?: 0 } ?: 0
        var processed = 0
        val labels = labels()
        val settings = CatalogPrefs.load(context)
        val sourceFiles = sourceCatalogDir
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()
        AppLog.i("Catalog", "prepareSerially: rebuilding from ${sourceFiles.size} source file(s)")
        val buildContext = currentCoroutineContext()
        if (sourceFiles.isNotEmpty()) {
            CatalogRowsDatabaseStore.synchronizeStreamingFromSourceEntries(
                file = databaseFile,
                signature = signature,
                labels = labels,
                producer = { emit ->
                    sourceFiles.forEachIndexed { index, sourceFile ->
                        buildContext.ensureActive()
                        val fileStartedAt = System.currentTimeMillis()
                        val result = SourceCatalogEnvelopeStreamReader.read(
                            file = sourceFile,
                            onEntry = { entry ->
                                buildContext.ensureActive()
                                val key = catalogTextSearchKey(entry.source, entry.sourceId)
                                val alias = if (settings.translateTitles) {
                                    CatalogTitleTranslator.cachedTitleTranslationBlocking(
                                        context = context,
                                        entryKey = key,
                                        sourceTitle = entry.title,
                                        targetLanguageTag = settings.translationTarget,
                                    )
                                } else {
                                    null
                                }
                                emit(
                                    CatalogRowsStreamEntry(
                                        entry = entry,
                                        translatedTitleAliases = listOfNotNull(alias),
                                    ),
                                )
                                processed++
                                if (totalEstimate > 0 && processed % 1000 == 0) {
                                    _catalogBuildProgress.value =
                                        (processed.toFloat() / totalEstimate).coerceIn(0f, 0.99f)
                                }
                            },
                            checkCancelled = buildContext::ensureActive,
                        )
                        CatalogMemoryDiagnostics.log(
                            phase = "catalog_database_source_file_complete",
                            startedAtMs = fileStartedAt,
                            detail =
                                "file=${sourceFile.name} entries=${result.emittedCount} " +
                                    "fileIndex=${index + 1}/${sourceFiles.size}",
                        )
                    }
                },
                checkCancelled = buildContext::ensureActive,
            )
        } else {
            val legacyEntries = legacySourceEntries()
            check(legacyEntries.isNotEmpty()) {
                "Cannot prepare catalog database without catalog entries"
            }
            CatalogRowsDatabaseStore.synchronizeFromSourceEntries(
                file = databaseFile,
                signature = signature,
                entries = legacyEntries,
                labels = labels,
                checkCancelled = buildContext::ensureActive,
            )
        }
        check(CatalogRowsDatabaseStore.isValid(databaseFile, signature)) {
            "Catalog database validation failed after synchronization"
        }

        deleteLegacyCatalogIndexes()
        return catalogDatabaseInfo(databaseFile, signature)
        } finally {
            _catalogBuildProgress.value = 1f
        }
    }

    private fun catalogDatabaseInfo(file: File, signature: String): CatalogDatabaseInfo =
        CatalogDatabaseInfo(
            signature = signature,
            totalCount = CatalogRowsDatabaseStore.totalCount(file),
            tagLabels = CatalogRowsDatabaseStore.tagLabels(file),
        )

    private fun ensureCatalogDatabaseSignature(signature: String) {
        val changed = synchronized(catalogDatabaseSignatureLock) {
            if (catalogDatabaseRequestedSignature == signature) {
                false
            } else {
                catalogDatabaseRequestedSignature = signature
                true
            }
        }
        if (changed) {
            catalogDatabaseLoader.invalidate()
            invalidateTextSearchIndex()
        }
    }

    private fun invalidateCatalogDatabase() {
        synchronized(catalogDatabaseSignatureLock) {
            catalogDatabaseRequestedSignature = null
        }
        catalogDatabaseLoader.invalidate()
    }

    private fun deleteLegacyCatalogIndexes() {
        listOf(
            preparedCatalogIndexFile,
            nameSearchDatabaseFile,
            File("${nameSearchDatabaseFile.path}-journal"),
            File("${nameSearchDatabaseFile.path}-wal"),
            File("${nameSearchDatabaseFile.path}-shm"),
        ).forEach { file ->
            if (file.exists() && !file.delete()) {
                AppLog.w("Catalog", "Could not delete obsolete catalog index ${file.name}")
            }
        }
    }

    private fun legacySourceEntries(): List<SourceCatalogEntry> = parseLegacyGames().map { game ->
        SourceCatalogEntry(
            source = SOURCE_F95ZONE,
            sourceId = game.thread_id.toString(),
            canonicalUrl = game.canonicalUrl,
            title = game.title,
            developer = game.creator,
            versionText = game.version,
            modifiedAt = game.ts.takeIf { it > 0L }?.let(java.time.Instant::ofEpochSecond)?.toString(),
            tags = (game.prefixes + game.tags).map { it.toString() },
            rating = game.rating,
            popularity = game.views.toDouble(),
            coverUrl = game.cover,
            thumbnailUrl = game.thumbnailUrl,
        )
    }

    private fun catalogDataSignature(formatVersion: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(formatVersion.toByteArray(Charsets.UTF_8))
        val files = buildList {
            add(catalogFile)
            add(sourceIndexFile)
            add(labelsFile)
            sourceCatalogDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.let(::addAll)
        }
        files.forEach { file ->
            digest.update(
                "${file.name}:${file.isFile}:${file.length()}:${file.lastModified()}"
                    .toByteArray(Charsets.UTF_8),
            )
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    @Volatile private var cachedLabels: CatalogLabelsV2? = null
    @Volatile private var titleIndexSignature: String? = null
    @Volatile private var cachedTextSearchIndex: CatalogTextSearchIndex? = null
    @Volatile private var textSearchBuild: TextSearchBuild? = null
    private val textSearchIndexMutex = Mutex()
    private val textSearchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val textSearchGeneration = AtomicLong()
    private val textSearchIndexStateLock = Any()
    private val textSearchUseLock = ReentrantReadWriteLock()
    private val catalogDatabaseSignatureLock = Any()
    @Volatile private var catalogDatabaseRequestedSignature: String? = null
    private val catalogDatabaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogDatabaseLoader =
        SingleFlightLoader<CatalogDatabaseInfo, Unit>(
            scope = catalogDatabaseScope,
            load = { prepareCatalogDatabaseNow() },
        )

    private fun invalidateIndex() {
        titleIndexSignature = null
        invalidateCatalogDatabase()
        invalidateTextSearchIndex()
    }

    private fun invalidateTextSearchIndex() {
        val writeLock = textSearchUseLock.writeLock()
        writeLock.lock()
        try {
            synchronized(textSearchIndexStateLock) {
                cachedTextSearchIndex?.close()
                cachedTextSearchIndex = null
                textSearchBuild?.deferred?.cancel()
                textSearchBuild = null
                textSearchGeneration.incrementAndGet()
            }
        } finally {
            writeLock.unlock()
        }
    }

    override fun close() {
        catalogDatabaseLoader.close()
        catalogDatabaseScope.cancel()
        textSearchScope.cancel()
        invalidateTextSearchIndex()
    }

    suspend fun catalogGamesByF95ThreadIds(threadIds: Set<Int>): Map<Int, CatalogGame> =
        withContext(Dispatchers.IO) {
            if (threadIds.isEmpty()) return@withContext emptyMap()
            prepareCatalogDatabase()
            CatalogRowsDatabaseStore.catalogGamesByF95ThreadIds(
                catalogRowsDatabaseFile,
                threadIds,
            )
        }

    suspend fun catalogGameByF95ThreadId(threadId: Int): CatalogGame? =
        catalogGamesByF95ThreadIds(setOf(threadId))[threadId]

    internal suspend fun catalogGroupForNavigation(
        identity: CatalogNavigationIdentity,
    ): CatalogRowsGroup? =
        withContext(Dispatchers.IO) {
            prepareCatalogDatabase()
            CatalogRowsDatabaseStore.groupForNavigation(catalogRowsDatabaseFile, identity)
        }

    suspend fun searchSynopsisAgmGroupRanks(query: String): Map<String, Double> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyMap()
            CatalogSynopsisDatabase.searchGroupRanks(serverSearchDatabaseFile, query)
        }

    internal suspend fun groupSynopsis(agmGroupId: String): CatalogGroupSynopsis? =
        withContext(Dispatchers.IO) {
            CatalogSynopsisDatabase.groupSynopsis(serverSearchDatabaseFile, agmGroupId)
        }

    private suspend fun ensureTitleIndexesCurrent() {
        val settings = CatalogPrefs.load(context)
        // Only translation MODE/language changes invalidate the index here; per-alias writes
        // (revision bumps) no longer do — those are folded by foldTranslatedTitles() once per
        // worker pass. This prevents the search path from tearing down the index mid-pass.
        val signature = "${settings.translateTitles}:${settings.translationTarget}"
        synchronized(this) {
            if (signature == titleIndexSignature) return
            titleIndexSignature = signature
            invalidateCatalogDatabase()
            invalidateTextSearchIndex()
        }
    }

    /**
     * Forces one full catalog rebuild so newly translated titles are folded into the search
     * index, regardless of whether the content signature changed. Called by the translation
     * worker at the end of a pass (NOT on the UI/refresh path) so translated-title search stays
     * fresh without per-alias index churn.
     */
    suspend fun foldTranslatedTitles() {
        forceCatalogRebuild = true
        invalidateCatalogDatabase()
        invalidateTextSearchIndex()
        prepareCatalogDatabase()
    }

    suspend fun likelySameTitle(
        appLabels: List<String>,
        game: CatalogGame,
        allowAcronym: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val gameKey = game.matchIdentityKey
        val callerContext = kotlinx.coroutines.currentCoroutineContext()
        withTextSearchIndex { index ->
            appLabels.any { appTitle ->
                index.search(appTitle, callerContext::ensureActive)
                .any { match ->
                    match.document.matchIdentityKey == gameKey &&
                        (allowAcronym || match.kind != CatalogNameMatchKind.Acronym)
                }
            }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun sourceCatalogIndex(): CatalogSourceRegistry? = withContext(Dispatchers.IO) {
        val f = sourceIndexFile
        if (!f.exists() || f.length() == 0L) return@withContext null
        runCatching { f.inputStream().use { json.decodeFromStream<CatalogSourceRegistry>(it) } }
            .onSuccess { SourceRegistry.update(it.catalogs) }
            .onFailure { AppLog.e("Catalog", "source catalog index parse failed", it) }
            .getOrNull()
    }

    internal suspend fun entryDetails(entry: SourceCatalogEntry): CatalogDetailsLoadResult {
        var sourceInfo = SourceRegistry.info(entry.source)
        if (sourceInfo == null) {
            sourceCatalogIndex()
            sourceInfo = SourceRegistry.info(entry.source)
        }
        return sourceInfo?.let { detailsRepository.load(entry, it) }
            ?: CatalogDetailsLoadResult.Missing
    }

    /** True when the installed app version is >= [minAppVersion] (null/blank = no gate). */
    private fun appVersionSatisfies(minAppVersion: String?): Boolean {
        val min = minAppVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return compareSemver(BuildConfig.VERSION_NAME, min) >= 0
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = compareValues(pa.getOrElse(i) { 0 }, pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun preferCatalogGame(games: List<CatalogGame>): CatalogGame? = games
        .sortedWith(compareBy<CatalogGame>(
            { if (it.category == "games") 0 else 1 },
            { -it.views },
            { -it.ts },
        ))
        .firstOrNull()

    /** Deterministic title matching through the same name-search pipeline used by every flow. */
    suspend fun bestTitleMatch(
        appLabels: List<String>,
        allowAcronym: Boolean = true,
    ): CatalogTitleMatch? = withContext(Dispatchers.IO) {
        val labels = appLabels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (labels.isEmpty()) return@withContext null
        val callerContext = kotlinx.coroutines.currentCoroutineContext()
        val matches = withTextSearchIndex { index ->
            labels
                .flatMap { label -> index.search(label, callerContext::ensureActive) }
                .filter { allowAcronym || it.kind != CatalogNameMatchKind.Acronym }
                .groupBy { it.document.matchIdentityKey }
                .mapNotNull { (_, hits) ->
                    val best = hits.maxWithOrNull(
                        compareBy<CatalogTextSearchScoredMatch> { it.score }
                            .thenBy {
                                it.document.sourceEntry?.source?.let(SourceRegistry::priority) ?: 0
                            },
                    ) ?: return@mapNotNull null
                    val game = best.document.sourceEntry?.let(::sourceEntryToCatalogGame)
                        ?: best.document.legacyGame
                        ?: return@mapNotNull null
                    best.score to game
                }
                .sortedWith(
                    compareByDescending<Pair<Int, CatalogGame>> { it.first }
                        .thenByDescending { SourceRegistry.priority(it.second.source) }
                        .thenByDescending { it.second.views }
                        .thenByDescending { it.second.ts },
                )
        }
        val topScore = matches.firstOrNull()?.first ?: return@withContext null
        val top = matches.filter { it.first == topScore }
        val selected = if (top.size == 1) top.single().second
        else uniqueHighestPriorityCatalogGame(top.map { it.second })
        selected?.let { CatalogTitleMatch(it, "title-unified") }
    }

    suspend fun ambiguousTitleMatch(
        appLabels: List<String>,
        allowAcronym: Boolean = true,
    ): CatalogAmbiguousTitleMatch? = withContext(Dispatchers.IO) {
        val labels = appLabels.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (labels.isEmpty()) return@withContext null
        val callerContext = kotlinx.coroutines.currentCoroutineContext()
        val candidates = withTextSearchIndex { index ->
            labels
                .flatMap { label -> index.search(label, callerContext::ensureActive) }
                .filter { allowAcronym || it.kind != CatalogNameMatchKind.Acronym }
                .groupBy { it.document.matchIdentityKey }
                .mapNotNull { (_, hits) ->
                    val best = hits.maxWithOrNull(
                        compareBy<CatalogTextSearchScoredMatch> { it.score }
                            .thenBy {
                                it.document.sourceEntry?.source?.let(SourceRegistry::priority) ?: 0
                            },
                    ) ?: return@mapNotNull null
                    val game = best.document.sourceEntry?.let(::sourceEntryToCatalogGame)
                        ?: best.document.legacyGame
                        ?: return@mapNotNull null
                    best.score to game
                }
                .sortedByDescending { it.first }
                .map { it.second }
                .take(12)
        }
        candidates.takeIf { it.size > 1 }?.let {
            CatalogAmbiguousTitleMatch(it, "title-unified")
        }
    }

    suspend fun deterministicTitleMatch(appLabel: String): CatalogGame? =
        bestTitleMatch(listOf(appLabel))?.game

    /** Replaces the bestTitleMatch/ambiguousTitleMatch pair used by refreshFromCatalog with a
     *  single scored pass over the shared [textSearchIndex]: every [candidates] text is
     *  searched once (no per-name full-catalog scan), hits are aggregated by source-aware
     *  catalog identity with README/specific-executable candidates weighted higher and
     *  corroboration from independent sources rewarded, and either the highest safe score is
     *  returned or a ranked candidate list for the ambiguity review UI. */
    internal suspend fun scoredTitleAnalysis(
        candidates: List<NameCandidate>,
        identityCandidates: List<CatalogIdentityCandidate> = emptyList(),
    ): ScoredCatalogTitleResult =
        withContext(Dispatchers.IO) {
            val callerContext = kotlinx.coroutines.currentCoroutineContext()
            withTextSearchIndex { index ->
                scoredCatalogTitleAnalysis(
                    index = index,
                    candidates = candidates,
                    identityCandidates = identityCandidates,
                    checkCancelled = callerContext::ensureActive,
                )
            }
        }

    suspend fun search(query: String, limit: Int = 25): List<CatalogSearchResult> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val callerContext = kotlinx.coroutines.currentCoroutineContext()
        val results = withTextSearchIndex { index ->
            index.search(query = query, checkCancelled = callerContext::ensureActive)
                .groupBy { it.document.matchIdentityKey }
                .mapNotNull { (_, matches) ->
                    matches.maxWithOrNull(
                        compareBy<CatalogTextSearchScoredMatch> { it.score }
                            .thenBy {
                                val game = it.document.sourceEntry?.let(::sourceEntryToCatalogGame)
                                    ?: it.document.legacyGame
                                game?.let { candidate -> SourceRegistry.priority(candidate.source) } ?: 0
                            }
                            .thenBy {
                                val game = it.document.sourceEntry?.let(::sourceEntryToCatalogGame)
                                    ?: it.document.legacyGame
                                game?.views ?: 0L
                            },
                    )
                }
                .sortedWith(
                    compareByDescending<CatalogTextSearchScoredMatch> { it.score }
                        .thenByDescending {
                            val game = it.document.sourceEntry?.let(::sourceEntryToCatalogGame) ?: it.document.legacyGame
                            game?.let { candidate -> SourceRegistry.priority(candidate.source) } ?: 0
                        }
                        .thenByDescending {
                            val game = it.document.sourceEntry?.let(::sourceEntryToCatalogGame) ?: it.document.legacyGame
                            game?.views ?: 0L
                        }
                        .thenBy { it.document.displayTitle.lowercase() },
                )
                .asSequence()
                .mapNotNull { match ->
                    val game = match.document.sourceEntry?.let(::sourceEntryToCatalogGame)
                        ?: match.document.legacyGame
                        ?: return@mapNotNull null
                    CatalogSearchResult(
                        game = game,
                        translatedTitle = match.preparedTitles
                            .firstOrNull { title ->
                                CatalogRepository.normalizeTitle(title) !=
                                    CatalogRepository.normalizeTitle(game.title)
                            },
                    )
                }
                .take(limit)
                .toList()
        }
        logSlowTextSearch("title", query, results.size, startedAt)
        results
    }

    suspend fun searchCatalogEntryRanks(query: String): Map<String, Double> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val callerContext = kotlinx.coroutines.currentCoroutineContext()
        val results = withTextSearchIndex { index ->
            index.search(query = query, checkCancelled = callerContext::ensureActive)
                .associateTo(linkedMapOf()) { it.document.key to it.score.toDouble() }
        }
        logSlowTextSearch("catalog", query, results.size, startedAt)
        results
    }

    private fun logSlowTextSearch(mode: String, query: String, resultCount: Int, startedAt: Long) {
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        if (elapsedMs >= 50L) {
            AppLog.i(
                "CatalogSearch",
                "$mode query length=${query.length} results=$resultCount took ${elapsedMs}ms",
            )
        }
    }

    private data class TextSearchBuild(
        val generation: Long,
        val deferred: Deferred<CatalogTextSearchIndex>,
    )

    private suspend fun <T> withTextSearchIndex(
        block: (CatalogTextSearchIndex) -> T,
    ): T {
        while (true) {
            val index = textSearchIndex()
            val readLock = textSearchUseLock.readLock()
            readLock.lock()
            try {
                if (cachedTextSearchIndex !== index) continue
                return block(index)
            } finally {
                readLock.unlock()
            }
        }
    }

    private suspend fun textSearchIndex(): CatalogTextSearchIndex {
        val callerContext = kotlinx.coroutines.currentCoroutineContext()
        while (true) {
            ensureTitleIndexesCurrent()
            ensureCatalogDatabaseSignature(catalogRowsSignature())
            cachedTextSearchIndex?.let { return it }
            val build = textSearchIndexMutex.withLock {
                cachedTextSearchIndex?.let { return@withLock null }
                val generation = textSearchGeneration.get()
                textSearchBuild
                    ?.takeIf { it.generation == generation && it.deferred.isActive }
                    ?: textSearchScope.async(start = CoroutineStart.LAZY) {
                            buildAndPublishTextSearchIndex(generation)
                        }
                        .let { deferred -> TextSearchBuild(generation, deferred) }
                        .also {
                            textSearchBuild = it
                            it.deferred.start()
                        }
            }
            if (build == null) continue
            try {
                return build.deferred.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                callerContext.ensureActive()
                // Catalog/translation invalidation cancelled a stale shared build.
            }
        }
    }

    private suspend fun buildAndPublishTextSearchIndex(generation: Long): CatalogTextSearchIndex {
        val totalStartedAt = System.currentTimeMillis()
        CatalogMemoryDiagnostics.log(phase = "text_index_build_start")
        val database = prepareCatalogDatabase()
        val buildContext = currentCoroutineContext()
        buildContext.ensureActive()
        val built = CatalogTextSearchIndex.open(catalogRowsDatabaseFile)
        val published = synchronized(textSearchIndexStateLock) {
            if (textSearchGeneration.get() == generation && cachedTextSearchIndex == null) {
                cachedTextSearchIndex = built
                if (textSearchBuild?.generation == generation) {
                    textSearchBuild = null
                }
                true
            } else {
                false
            }
        }
        if (!published) {
            built.close()
            throw kotlinx.coroutines.CancellationException("Catalog name index became stale")
        }
        AppLog.i(
            "CatalogSearch",
            "Opened shared text index for ${database.totalCount} games in " +
                "${System.currentTimeMillis() - totalStartedAt}ms",
        )
        CatalogMemoryDiagnostics.log(
            phase = "text_index_build_complete",
            startedAtMs = totalStartedAt,
            detail = "documents=${database.totalCount}",
        )
        return built
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    suspend fun labels(): CatalogLabelsV2 = withContext(Dispatchers.IO) {
        cachedLabels ?: run {
            val f = labelsFile
            val l = if (f.exists()) runCatching {
                f.inputStream().use { json.decodeFromStream<CatalogLabelsV2>(it) }
            }.getOrDefault(CatalogLabelsV2()) else CatalogLabelsV2()
            cachedLabels = l
            l
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun parseLegacyGames(): List<CatalogGame> {
        val f = catalogFile
        if (!f.exists() || f.length() == 0L) return emptyList()
        val env = openMaybeGzipped(f.inputStream()).use { src ->
            json.decodeFromStream<CatalogEnvelope>(src)
        }
        return env.games.values.toList()
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun countGames(bytes: ByteArray): Int = runCatching {
        openMaybeGzipped(bytes.inputStream()).use { src ->
            json.decodeFromStream<CatalogEnvelope>(src)
        }.games.size
    }.getOrDefault(0)

    private fun countSourceEntries(bytes: ByteArray): Int = runCatching {
        SourceCatalogEnvelopeStreamReader.read(
            input = bytes.inputStream(),
            onEntry = {},
        ).emittedCount
    }.getOrDefault(0)

    /**
     * Returns a stream that transparently decompresses if [raw] starts with the gzip
     * magic bytes (0x1f 0x8b). Some hosts serve catalog.json.gz as a plain file
     * download (no Content-Encoding: gzip header), so OkHttp does not auto-decompress.
     */
    private fun openMaybeGzipped(raw: InputStream): InputStream {
        val buffered = raw.buffered()
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }
}
